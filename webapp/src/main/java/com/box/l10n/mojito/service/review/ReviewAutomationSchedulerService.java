package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewAutomationRun;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class ReviewAutomationSchedulerService {

  private static final Logger logger =
      LoggerFactory.getLogger(ReviewAutomationSchedulerService.class);

  private static final String METRIC_PREFIX = "ReviewAutomation";
  private static final DateTimeFormatter REQUEST_NAME_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final ReviewAutomationRepository reviewAutomationRepository;
  private final ReviewProjectService reviewProjectService;
  private final ReviewAutomationRunService reviewAutomationRunService;
  private final UserService userService;
  private final MeterRegistry meterRegistry;

  public ReviewAutomationSchedulerService(
      ReviewAutomationRepository reviewAutomationRepository,
      ReviewProjectService reviewProjectService,
      ReviewAutomationRunService reviewAutomationRunService,
      UserService userService,
      MeterRegistry meterRegistry) {
    this.reviewAutomationRepository = reviewAutomationRepository;
    this.reviewProjectService = reviewProjectService;
    this.reviewAutomationRunService = reviewAutomationRunService;
    this.userService = userService;
    this.meterRegistry = meterRegistry;
  }

  public RunResult runAutomationFromCron(Long automationId) {
    Long requestedByUserId = getSystemUserId();
    return runAutomation(
        automationId, ReviewAutomationRun.TriggerSource.CRON, requestedByUserId, true);
  }

  public RunResult runAutomationNow(Long automationId, Long requestedByUserId) {
    if (requestedByUserId == null) {
      throw new AccessDeniedException("No authenticated user");
    }
    return runAutomation(
        automationId, ReviewAutomationRun.TriggerSource.MANUAL, requestedByUserId, false);
  }

  public RunResult runAutomation(
      Long automationId,
      ReviewAutomationRun.TriggerSource triggerSource,
      Long requestedByUserId,
      boolean requireEnabled) {
    logger.info(
        "Review automation run starting: automationId={}, source={}", automationId, triggerSource);
    incrementCounter("runs", Tags.of("result", "started"));

    ReviewAutomation automation =
        reviewAutomationRepository
            .findByIdWithFeatures(automationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review automation not found: " + automationId));

    if (requireEnabled && !Boolean.TRUE.equals(automation.getEnabled())) {
      incrementCounter("runs", Tags.of("result", "skipped_disabled"));
      logger.info(
          "Review automation run skipped: automationId={}, source={}, reason=disabled",
          automationId,
          triggerSource);
      return new RunResult(automation.getId(), automation.getName(), 0, 0, 0, 0, 0, 0, null);
    }

    ZonedDateTime startedAt = ZonedDateTime.now(resolveZoneId(automation));
    List<ReviewFeature> features =
        automation.getFeatures().stream()
            .sorted(
                Comparator.comparing(ReviewFeature::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ReviewFeature::getId))
            .toList();

    ReviewAutomationRun run =
        reviewAutomationRunService.createRunningRun(
            automation, triggerSource, requestedByUserId, features.size(), startedAt);

    int createdProjectRequestCount = 0;
    int createdProjectCount = 0;
    int createdLocaleCount = 0;
    int skippedLocaleCount = 0;
    int erroredLocaleCount = 0;
    try {
      for (ReviewFeature feature : features) {
        CreateReviewProjectRequestResult result =
            reviewProjectService.createAutomatedReviewProjectRequest(
                new CreateAutomatedReviewProjectRequestCommand(
                    feature.getId(),
                    buildRequestName(feature, startedAt),
                    buildRequestNotes(automation, triggerSource),
                    startedAt.plusDays(automation.getDueDateOffsetDays()),
                    automation.getTeam().getId(),
                    automation.getMaxWordCountPerProject(),
                    automation.getAssignTranslator(),
                    requestedByUserId));

        if (result.requestId() != null) {
          createdProjectRequestCount++;
        }
        createdProjectCount += result.projectIds().size();
        createdLocaleCount += result.createdLocaleCount();
        skippedLocaleCount += result.skippedLocaleCount();
        erroredLocaleCount += result.erroredLocaleCount();
      }

      reviewAutomationRunService.markCompleted(
          run.getId(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount);
      logger.info(
          "Review automation run completed: automationId={}, source={}, runId={}, requests={}, projects={}, createdLocales={}, skippedLocales={}, erroredLocales={}",
          automation.getId(),
          triggerSource,
          run.getId(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount);
      incrementCounter(
          "runs",
          Tags.of("result", erroredLocaleCount > 0 ? "completed_with_errors" : "completed"));
      return new RunResult(
          automation.getId(),
          automation.getName(),
          features.size(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount,
          run.getId());
    } catch (RuntimeException e) {
      logger.error(
          "Review automation run failed: automationId={}, source={}, error={}",
          automationId,
          triggerSource,
          e.getMessage(),
          e);
      reviewAutomationRunService.markFailed(
          run.getId(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount,
          e.getMessage());
      incrementCounter("runs", Tags.of("result", "failed"));
      throw e;
    }
  }

  private Long getSystemUserId() {
    User systemUser = userService.findSystemUser();
    if (systemUser == null || systemUser.getId() == null) {
      throw new IllegalStateException("System user not found");
    }
    return systemUser.getId();
  }

  private ZoneId resolveZoneId(ReviewAutomation automation) {
    return ZoneId.of(automation.getTimeZone());
  }

  private String buildRequestName(ReviewFeature feature, ZonedDateTime startedAt) {
    return feature.getName().trim() + " review - " + REQUEST_NAME_DATE_FORMAT.format(startedAt);
  }

  private String buildRequestNotes(
      ReviewAutomation automation, ReviewAutomationRun.TriggerSource triggerSource) {
    return "Created by review automation "
        + automation.getName()
        + " ("
        + triggerSource.name().toLowerCase()
        + ")";
  }

  private void incrementCounter(String metricSuffix, Tags tags) {
    meterRegistry.counter("%s.%s".formatted(METRIC_PREFIX, metricSuffix), tags).increment();
  }

  public record RunResult(
      Long automationId,
      String automationName,
      int featureCount,
      int createdProjectRequestCount,
      int createdProjectCount,
      int createdLocaleCount,
      int skippedLocaleCount,
      int erroredLocaleCount,
      Long runId) {}
}
