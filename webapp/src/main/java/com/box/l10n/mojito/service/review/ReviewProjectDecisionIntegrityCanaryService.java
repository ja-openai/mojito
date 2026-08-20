package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.AuditResult;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.CarryoverDecision;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.CarryoverRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Runs the shared decision-integrity audit on a schedule and publishes bounded health signals. */
@Profile("!disablescheduling")
@Service
@ConditionalOnProperty(
    value = "l10n.review-project.decision-integrity-canary.enabled",
    havingValue = "true")
public class ReviewProjectDecisionIntegrityCanaryService {

  private static final Logger logger =
      LoggerFactory.getLogger(ReviewProjectDecisionIntegrityCanaryService.class);
  private static final int MAX_LOOKBACK_HOURS = 48;
  private static final int MAX_REPORTED_RUNS = 50;

  private final ReviewProjectDecisionIntegrityAuditService auditService;
  private final MeterRegistry meterRegistry;
  private final Duration lookback;
  private final int maximumReportedRuns;
  private final AtomicLong lastCandidatePairCount;
  private final AtomicLong lastRunCount;
  private final AtomicLong lastDeterministicFindingCount;
  private final AtomicLong lastReviewNeededFindingCount;
  private final AtomicLong lastCompletedEpochSeconds;
  private final Counter successfulExecutions;
  private final Counter failedExecutions;

  public ReviewProjectDecisionIntegrityCanaryService(
      ReviewProjectDecisionIntegrityAuditService auditService,
      MeterRegistry meterRegistry,
      @Value("${l10n.review-project.decision-integrity-canary.lookback-hours:26}")
          int lookbackHours,
      @Value("${l10n.review-project.decision-integrity-canary.maximum-reported-runs:50}")
          int maximumReportedRuns) {
    this.auditService = Objects.requireNonNull(auditService);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.lookback = Duration.ofHours(clamp(lookbackHours, 1, MAX_LOOKBACK_HOURS));
    this.maximumReportedRuns = clamp(maximumReportedRuns, 0, MAX_REPORTED_RUNS);
    this.lastCandidatePairCount =
        meterRegistry.gauge(
            "review_project.decision_integrity_canary.candidates", new AtomicLong());
    this.lastRunCount =
        meterRegistry.gauge("review_project.decision_integrity_canary.runs", new AtomicLong());
    this.lastDeterministicFindingCount =
        meterRegistry.gauge(
            "review_project.decision_integrity_canary.deterministic_findings", new AtomicLong());
    this.lastReviewNeededFindingCount =
        meterRegistry.gauge(
            "review_project.decision_integrity_canary.review_needed_findings", new AtomicLong());
    this.lastCompletedEpochSeconds =
        meterRegistry.gauge(
            "review_project.decision_integrity_canary.last_completed_epoch_seconds",
            new AtomicLong());
    this.successfulExecutions =
        meterRegistry.counter("review_project.decision_integrity_canary.successful_executions");
    this.failedExecutions =
        meterRegistry.counter("review_project.decision_integrity_canary.failed_executions");
  }

  public CanaryResult run() {
    return run(Instant.now());
  }

  CanaryResult run(Instant toExclusive) {
    Instant fromInclusive = toExclusive.minus(lookback);
    Timer.Sample timer = Timer.start(meterRegistry);
    try {
      AuditResult audit = auditService.audit(fromInclusive, toExclusive, maximumReportedRuns, 0);
      long candidatePairCount = audit.carryover().candidatePairCount();
      long runCount = audit.carryover().runCount();
      long deterministicFindingCount = audit.broaderReview().deterministicIntegrityFindingCount();
      long reviewNeededFindingCount = audit.broaderReview().reviewNeededFindingCount();

      lastCandidatePairCount.set(candidatePairCount);
      lastRunCount.set(runCount);
      lastDeterministicFindingCount.set(deterministicFindingCount);
      lastReviewNeededFindingCount.set(reviewNeededFindingCount);
      lastCompletedEpochSeconds.set(Instant.now().getEpochSecond());
      successfulExecutions.increment();

      boolean hasActionableFindings = candidatePairCount > 0 || deterministicFindingCount > 0;
      if (hasActionableFindings) {
        logger.warn(
            "Review Project decision-integrity canary found candidates: from={}, to={}, decisions={}, candidatePairs={}, candidateRuns={}, deterministicFindings={}, reviewNeededFindings={}, reportedRuns={}, truncated={}",
            fromInclusive,
            toExclusive,
            audit.coverage().totalDecisions(),
            candidatePairCount,
            runCount,
            deterministicFindingCount,
            reviewNeededFindingCount,
            audit.carryover().detailedRunCount(),
            audit.carryover().detailsTruncated());
        audit.carryover().runs().forEach(this::logCarryoverRun);
      } else if (reviewNeededFindingCount > 0) {
        logger.info(
            "Review Project decision-integrity canary completed with review-needed findings: from={}, to={}, decisions={}, candidatePairs=0, candidateRuns=0, deterministicFindings=0, reviewNeededFindings={}",
            fromInclusive,
            toExclusive,
            audit.coverage().totalDecisions(),
            reviewNeededFindingCount);
      } else {
        logger.info(
            "Review Project decision-integrity canary completed: from={}, to={}, decisions={}, candidatePairs=0, candidateRuns=0, deterministicFindings=0, reviewNeededFindings=0",
            fromInclusive,
            toExclusive,
            audit.coverage().totalDecisions());
      }

      return new CanaryResult(fromInclusive, toExclusive, audit);
    } catch (RuntimeException exception) {
      failedExecutions.increment();
      logger.error(
          "Review Project decision-integrity canary failed: from={}, to={}",
          fromInclusive,
          toExclusive,
          exception);
      throw exception;
    } finally {
      timer.stop(
          meterRegistry.timer("review_project.decision_integrity_canary.execution_duration"));
    }
  }

  private void logCarryoverRun(CarryoverRun run) {
    logger.warn(
        "Review Project decision-integrity carryover run: reviewProjectId={}, reviewerId={}, locale={}, candidatePairs={}, decisionCount={}, detailedDecisionCount={}, decisionsTruncated={}, decisionIds={}, reviewProjectTextUnitIds={}, tmTextUnitIds={}, links={}, repeatedTargetPreview={}, previewRedacted={}",
        run.project() == null ? null : run.project().id(),
        run.reviewer() == null ? null : run.reviewer().id(),
        run.locale(),
        run.candidatePairCount(),
        run.decisionCount(),
        run.detailedDecisionCount(),
        run.decisionsTruncated(),
        run.decisions().stream().map(CarryoverDecision::decisionId).toList(),
        run.decisions().stream().map(CarryoverDecision::reviewProjectTextUnitId).toList(),
        run.decisions().stream().map(CarryoverDecision::tmTextUnitId).toList(),
        run.decisions().stream()
            .map(CarryoverDecision::mojitoLink)
            .filter(Objects::nonNull)
            .toList(),
        run.repeatedTargetPreview() == null ? null : run.repeatedTargetPreview().text(),
        run.repeatedTargetPreview() != null && run.repeatedTargetPreview().redacted());
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.min(Math.max(value, minimum), maximum);
  }

  public record CanaryResult(Instant fromInclusive, Instant toExclusive, AuditResult audit) {}
}
