package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.AuditResult;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.AuditWindow;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.BroaderReviewSummary;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.CarryoverDecision;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.CarryoverRun;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.CarryoverSummary;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.Coverage;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.Preview;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.ProjectRef;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.ReviewerRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class ReviewProjectDecisionIntegrityCanaryServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

  private ReviewProjectDecisionIntegrityAuditService auditService;
  private SimpleMeterRegistry meterRegistry;
  private ReviewProjectDecisionIntegrityCanaryService service;

  @Before
  public void setUp() {
    auditService = mock(ReviewProjectDecisionIntegrityAuditService.class);
    meterRegistry = new SimpleMeterRegistry();
    service =
        new ReviewProjectDecisionIntegrityCanaryService(auditService, meterRegistry, 1_000, 1_000);
  }

  @Test
  public void delegatesToBoundedAuditAndPublishesMetrics() {
    Instant from = NOW.minusSeconds(48 * 60 * 60L);
    AuditResult audit = auditResult();
    when(auditService.audit(from, NOW, 50, 0)).thenReturn(audit);

    ReviewProjectDecisionIntegrityCanaryService.CanaryResult result = service.run(NOW);

    verify(auditService).audit(from, NOW, 50, 0);
    assertThat(result.fromInclusive()).isEqualTo(from);
    assertThat(result.toExclusive()).isEqualTo(NOW);
    assertThat(result.audit()).isSameAs(audit);
    assertGauge("review_project.decision_integrity_canary.candidates", 2);
    assertGauge("review_project.decision_integrity_canary.runs", 1);
    assertGauge("review_project.decision_integrity_canary.deterministic_findings", 4);
    assertGauge("review_project.decision_integrity_canary.review_needed_findings", 5);
    assertThat(
            meterRegistry
                .get("review_project.decision_integrity_canary.last_completed_epoch_seconds")
                .gauge()
                .value())
        .isPositive();
    assertCounter("review_project.decision_integrity_canary.successful_executions", 1);
    assertCounter("review_project.decision_integrity_canary.failed_executions", 0);
    assertThat(
            meterRegistry
                .get("review_project.decision_integrity_canary.execution_duration")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void publishesFailureHealthWithoutReplacingLastSuccessfulCounts() {
    Instant from = NOW.minusSeconds(48 * 60 * 60L);
    RuntimeException failure = new RuntimeException("audit failed");
    when(auditService.audit(from, NOW, 50, 0)).thenReturn(auditResult()).thenThrow(failure);

    service.run(NOW);
    double lastCompleted =
        meterRegistry
            .get("review_project.decision_integrity_canary.last_completed_epoch_seconds")
            .gauge()
            .value();

    assertThatThrownBy(() -> service.run(NOW)).isSameAs(failure);

    assertGauge("review_project.decision_integrity_canary.candidates", 2);
    assertGauge("review_project.decision_integrity_canary.runs", 1);
    assertThat(
            meterRegistry
                .get("review_project.decision_integrity_canary.last_completed_epoch_seconds")
                .gauge()
                .value())
        .isEqualTo(lastCompleted);
    assertCounter("review_project.decision_integrity_canary.successful_executions", 1);
    assertCounter("review_project.decision_integrity_canary.failed_executions", 1);
    assertThat(
            meterRegistry
                .get("review_project.decision_integrity_canary.execution_duration")
                .timer()
                .count())
        .isEqualTo(2);
  }

  private AuditResult auditResult() {
    CarryoverDecision decision =
        new CarryoverDecision(
            true,
            11L,
            21L,
            31L,
            "2026-08-20T11:59:50Z",
            2.0,
            new Preview("Source", false, false),
            new Preview("[REDACTED_SENSITIVE_PREVIEW]", false, true),
            "https://mojito.example/review-projects/41?tu=31");
    CarryoverRun run =
        new CarryoverRun(
            2,
            3,
            1,
            true,
            new ProjectRef(41L, 51L, "Project", "NORMAL", null),
            new ReviewerRef(61L, "reviewer", "reviewer@example.com", "Reviewer"),
            "fr-FR",
            new Preview("[REDACTED_SENSITIVE_PREVIEW]", false, true),
            null,
            "Human review required",
            List.of(decision));
    return new AuditResult(
        "CANDIDATES_FOUND",
        NOW.toString(),
        new AuditWindow(NOW.minusSeconds(48 * 60 * 60L).toString(), NOW.toString(), 172_800),
        new Coverage(20, 2, 3, 20, 0, 0, "Mutable rows"),
        new CarryoverSummary(2, 1, 1, false, List.of(run)),
        new BroaderReviewSummary(
            4, 3, 5, 4, Map.of("PLACEHOLDER_MISMATCH", 4L), 0, true, List.of()),
        "Human review required");
  }

  private void assertGauge(String name, long expected) {
    assertThat(meterRegistry.get(name).gauge().value()).isEqualTo(expected);
  }

  private void assertCounter(String name, long expected) {
    assertThat(meterRegistry.get(name).counter().count()).isEqualTo(expected);
  }
}
