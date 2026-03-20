package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewAutomationRun;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserRepository;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewAutomationRunService {

  public static final int DEFAULT_RECENT_RUN_LIMIT = 20;
  public static final int MAX_RECENT_RUN_LIMIT = 200;

  private final ReviewAutomationRunRepository reviewAutomationRunRepository;
  private final UserRepository userRepository;

  public ReviewAutomationRunService(
      ReviewAutomationRunRepository reviewAutomationRunRepository, UserRepository userRepository) {
    this.reviewAutomationRunRepository = reviewAutomationRunRepository;
    this.userRepository = userRepository;
  }

  @Transactional
  public ReviewAutomationRun createRunningRun(
      ReviewAutomation automation,
      ReviewAutomationRun.TriggerSource triggerSource,
      Long requestedByUserId,
      int featureCount,
      ZonedDateTime startedAt) {
    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setReviewAutomation(automation);
    run.setTriggerSource(triggerSource);
    run.setRequestedByUser(requestedByUserId == null ? null : getUserReference(requestedByUserId));
    run.setStatus(ReviewAutomationRun.Status.RUNNING);
    run.setStartedAt(startedAt);
    run.setFeatureCount(featureCount);
    run.setCreatedProjectRequestCount(0);
    run.setCreatedProjectCount(0);
    run.setCreatedLocaleCount(0);
    run.setSkippedLocaleCount(0);
    run.setErroredLocaleCount(0);
    return reviewAutomationRunRepository.save(run);
  }

  @Transactional
  public void markCompleted(
      Long runId,
      int createdProjectRequestCount,
      int createdProjectCount,
      int createdLocaleCount,
      int skippedLocaleCount,
      int erroredLocaleCount) {
    reviewAutomationRunRepository
        .findById(runId)
        .ifPresent(
            run -> {
              run.setStatus(
                  erroredLocaleCount > 0
                      ? ReviewAutomationRun.Status.COMPLETED_WITH_ERRORS
                      : ReviewAutomationRun.Status.COMPLETED);
              if (run.getStartedAt() == null) {
                run.setStartedAt(ZonedDateTime.now());
              }
              run.setFinishedAt(ZonedDateTime.now());
              run.setCreatedProjectRequestCount(createdProjectRequestCount);
              run.setCreatedProjectCount(createdProjectCount);
              run.setCreatedLocaleCount(createdLocaleCount);
              run.setSkippedLocaleCount(skippedLocaleCount);
              run.setErroredLocaleCount(erroredLocaleCount);
              run.setErrorMessage(null);
            });
  }

  @Transactional
  public void markFailed(
      Long runId,
      int createdProjectRequestCount,
      int createdProjectCount,
      int createdLocaleCount,
      int skippedLocaleCount,
      int erroredLocaleCount,
      String errorMessage) {
    reviewAutomationRunRepository
        .findById(runId)
        .ifPresent(
            run -> {
              run.setStatus(ReviewAutomationRun.Status.FAILED);
              if (run.getStartedAt() == null) {
                run.setStartedAt(ZonedDateTime.now());
              }
              run.setFinishedAt(ZonedDateTime.now());
              run.setCreatedProjectRequestCount(createdProjectRequestCount);
              run.setCreatedProjectCount(createdProjectCount);
              run.setCreatedLocaleCount(createdLocaleCount);
              run.setSkippedLocaleCount(skippedLocaleCount);
              run.setErroredLocaleCount(erroredLocaleCount);
              run.setErrorMessage(truncate(errorMessage));
            });
  }

  @Transactional(readOnly = true)
  public List<RunSummary> getRecentRuns(List<Long> automationIds, int limit) {
    int resolvedLimit = Math.max(1, Math.min(MAX_RECENT_RUN_LIMIT, limit));
    List<ReviewAutomationRunSummaryRow> rows =
        automationIds == null || automationIds.isEmpty()
            ? reviewAutomationRunRepository.findRecentRunRows(PageRequest.of(0, resolvedLimit))
            : reviewAutomationRunRepository.findRecentRunRowsByAutomationIds(
                automationIds, PageRequest.of(0, resolvedLimit));
    return rows.stream()
        .map(
            row ->
                new RunSummary(
                    row.id(),
                    row.automationId(),
                    row.automationName(),
                    row.triggerSource().name(),
                    row.requestedByUserId(),
                    row.requestedByUsername(),
                    row.status().name(),
                    row.createdDate(),
                    row.startedAt(),
                    row.finishedAt(),
                    row.featureCount(),
                    row.createdProjectRequestCount(),
                    row.createdProjectCount(),
                    row.createdLocaleCount(),
                    row.skippedLocaleCount(),
                    row.erroredLocaleCount(),
                    row.errorMessage()))
        .toList();
  }

  private User getUserReference(Long requestedByUserId) {
    return userRepository.getReferenceById(requestedByUserId);
  }

  private String truncate(String errorMessage) {
    if (errorMessage == null) {
      return null;
    }
    return errorMessage.length() <= 4000 ? errorMessage : errorMessage.substring(0, 4000);
  }

  public record RunSummary(
      Long id,
      Long automationId,
      String automationName,
      String triggerSource,
      Long requestedByUserId,
      String requestedByUsername,
      String status,
      ZonedDateTime createdAt,
      ZonedDateTime startedAt,
      ZonedDateTime finishedAt,
      int featureCount,
      int createdProjectRequestCount,
      int createdProjectCount,
      int createdLocaleCount,
      int skippedLocaleCount,
      int erroredLocaleCount,
      String errorMessage) {}
}
