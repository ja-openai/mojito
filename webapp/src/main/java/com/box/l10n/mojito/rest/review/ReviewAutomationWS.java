package com.box.l10n.mojito.rest.review;

import com.box.l10n.mojito.service.review.ReviewAutomationCronSchedulerService;
import com.box.l10n.mojito.service.review.ReviewAutomationRunService;
import com.box.l10n.mojito.service.review.ReviewAutomationSchedulerService;
import com.box.l10n.mojito.service.review.ReviewAutomationService;
import com.box.l10n.mojito.service.review.ReviewAutomationTriggerStatusView;
import com.box.l10n.mojito.service.review.SearchReviewAutomationsView;
import com.box.l10n.mojito.service.team.TeamService;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/review-automations")
public class ReviewAutomationWS {

  private final ReviewAutomationService reviewAutomationService;
  private final ReviewAutomationSchedulerService reviewAutomationSchedulerService;
  private final ReviewAutomationRunService reviewAutomationRunService;
  private final TeamService teamService;

  public ReviewAutomationWS(
      ReviewAutomationService reviewAutomationService,
      ReviewAutomationSchedulerService reviewAutomationSchedulerService,
      ReviewAutomationRunService reviewAutomationRunService,
      TeamService teamService) {
    this.reviewAutomationService = reviewAutomationService;
    this.reviewAutomationSchedulerService = reviewAutomationSchedulerService;
    this.reviewAutomationRunService = reviewAutomationRunService;
    this.teamService = teamService;
  }

  public record SearchReviewAutomationsResponse(
      List<ReviewAutomationSummary> reviewAutomations, long totalCount) {
    public record ReviewAutomationSummary(
        Long id,
        ZonedDateTime createdDate,
        ZonedDateTime lastModifiedDate,
        String name,
        boolean enabled,
        String cronExpression,
        String timeZone,
        TeamRef team,
        int dueDateOffsetDays,
        int maxWordCountPerProject,
        TriggerStatusRef trigger,
        long featureCount,
        List<FeatureRef> features) {}
  }

  public record ReviewAutomationResponse(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      boolean enabled,
      String cronExpression,
      String timeZone,
      TeamRef team,
      int dueDateOffsetDays,
      int maxWordCountPerProject,
      TriggerStatusRef trigger,
      List<FeatureRef> features) {}

  public record FeatureRef(Long id, String name) {}

  public record TeamRef(Long id, String name) {}

  public record TriggerStatusRef(
      String status,
      String quartzState,
      String nextRunAt,
      String previousRunAt,
      String lastRunAt,
      String lastSuccessfulRunAt,
      boolean repairRecommended) {}

  public record UpsertReviewAutomationRequest(
      String name,
      Boolean enabled,
      String cronExpression,
      String timeZone,
      Long teamId,
      Integer dueDateOffsetDays,
      Integer maxWordCountPerProject,
      List<Long> featureIds) {}

  public record BatchUpsertReviewAutomationsRequest(List<BatchRow> rows) {
    public record BatchRow(
        Long id,
        String name,
        Boolean enabled,
        String cronExpression,
        String timeZone,
        Long teamId,
        Integer dueDateOffsetDays,
        Integer maxWordCountPerProject,
        List<Long> featureIds) {}
  }

  public record ReviewAutomationOptionResponse(Long id, String name, boolean enabled) {}

  public record ReviewAutomationBatchExportResponse(
      Long id,
      String name,
      boolean enabled,
      String cronExpression,
      String timeZone,
      String teamName,
      int dueDateOffsetDays,
      int maxWordCountPerProject,
      List<String> featureNames) {}

  public record RunAutomationResponse(
      Long runId,
      Long automationId,
      String automationName,
      int featureCount,
      int createdProjectRequestCount,
      int createdProjectCount,
      int createdLocaleCount,
      int skippedLocaleCount,
      int erroredLocaleCount) {}

  public record ReviewAutomationRunResponse(
      Long id,
      Long automationId,
      String automationName,
      String triggerSource,
      Long requestedByUserId,
      String requestedByUsername,
      String status,
      String createdAt,
      String startedAt,
      String finishedAt,
      int featureCount,
      int createdProjectRequestCount,
      int createdProjectCount,
      int createdLocaleCount,
      int skippedLocaleCount,
      int erroredLocaleCount,
      String errorMessage) {}

  public record ReviewAutomationSchedulePreviewResponse(String timeZone, List<String> nextRuns) {}

  public record RepairReviewAutomationTriggerResponse(
      Long automationId, TriggerStatusRef trigger) {}

  public record BatchUpsertReviewAutomationsResponse(int createdCount, int updatedCount) {}

  @GetMapping
  public SearchReviewAutomationsResponse searchReviewAutomations(
      @RequestParam(name = "search", required = false) String searchQuery,
      @RequestParam(name = "enabled", required = false) Boolean enabled,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      SearchReviewAutomationsView view =
          reviewAutomationService.searchReviewAutomations(searchQuery, enabled, limit);
      return new SearchReviewAutomationsResponse(
          view.reviewAutomations().stream().map(this::toSummaryResponse).toList(),
          view.totalCount());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @GetMapping("/{automationId}")
  public ReviewAutomationResponse getReviewAutomation(@PathVariable Long automationId) {
    try {
      return toDetailResponse(reviewAutomationService.getReviewAutomation(automationId));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
  }

  @GetMapping("/options")
  public List<ReviewAutomationOptionResponse> getReviewAutomationOptions() {
    return reviewAutomationService.getReviewAutomationOptions().stream()
        .map(
            option ->
                new ReviewAutomationOptionResponse(option.id(), option.name(), option.enabled()))
        .toList();
  }

  @GetMapping("/batch-export")
  public List<ReviewAutomationBatchExportResponse> getReviewAutomationBatchExport() {
    return reviewAutomationService.getReviewAutomationBatchExportRows().stream()
        .map(
            row ->
                new ReviewAutomationBatchExportResponse(
                    row.id(),
                    row.name(),
                    row.enabled(),
                    row.cronExpression(),
                    row.timeZone(),
                    row.teamName(),
                    row.dueDateOffsetDays(),
                    row.maxWordCountPerProject(),
                    row.featureNames()))
        .toList();
  }

  @PostMapping("/{automationId}/run")
  public RunAutomationResponse runReviewAutomationNow(@PathVariable Long automationId) {
    requireAdmin();
    try {
      ReviewAutomationSchedulerService.RunResult result =
          reviewAutomationSchedulerService.runAutomationNow(
              automationId, teamService.getCurrentUserIdOrThrow());
      return new RunAutomationResponse(
          result.runId(),
          result.automationId(),
          result.automationName(),
          result.featureCount(),
          result.createdProjectRequestCount(),
          result.createdProjectCount(),
          result.createdLocaleCount(),
          result.skippedLocaleCount(),
          result.erroredLocaleCount());
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Review automation not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{automationId}/repair-trigger")
  public RepairReviewAutomationTriggerResponse repairReviewAutomationTrigger(
      @PathVariable Long automationId) {
    requireAdmin();
    try {
      ReviewAutomationCronSchedulerService.TriggerHealth triggerHealth =
          reviewAutomationService.repairTrigger(automationId);
      return new RepairReviewAutomationTriggerResponse(
          automationId, toTriggerStatusRef(toTriggerStatusView(triggerHealth, null, null)));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Review automation not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @GetMapping("/runs")
  public List<ReviewAutomationRunResponse> getReviewAutomationRuns(
      @RequestParam(name = "automationIds", required = false) List<Long> automationIds,
      @RequestParam(name = "limit", required = false) Integer limit) {
    requireAdmin();
    int resolvedLimit = limit == null ? ReviewAutomationRunService.DEFAULT_RECENT_RUN_LIMIT : limit;
    if (resolvedLimit < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be at least 1");
    }

    return reviewAutomationRunService.getRecentRuns(automationIds, resolvedLimit).stream()
        .map(
            run ->
                new ReviewAutomationRunResponse(
                    run.id(),
                    run.automationId(),
                    run.automationName(),
                    run.triggerSource(),
                    run.requestedByUserId(),
                    run.requestedByUsername(),
                    run.status(),
                    run.createdAt() == null ? null : run.createdAt().toString(),
                    run.startedAt() == null ? null : run.startedAt().toString(),
                    run.finishedAt() == null ? null : run.finishedAt().toString(),
                    run.featureCount(),
                    run.createdProjectRequestCount(),
                    run.createdProjectCount(),
                    run.createdLocaleCount(),
                    run.skippedLocaleCount(),
                    run.erroredLocaleCount(),
                    run.errorMessage()))
        .toList();
  }

  @GetMapping("/schedule-preview")
  public ReviewAutomationSchedulePreviewResponse getSchedulePreview(
      @RequestParam(name = "cronExpression") String cronExpression,
      @RequestParam(name = "timeZone", required = false) String timeZone,
      @RequestParam(name = "count", required = false) Integer count) {
    requireAdmin();
    try {
      String resolvedTimeZone =
          timeZone == null || timeZone.trim().isEmpty()
              ? ReviewAutomationService.DEFAULT_TIME_ZONE
              : timeZone.trim();
      return new ReviewAutomationSchedulePreviewResponse(
          resolvedTimeZone,
          reviewAutomationService.previewSchedule(cronExpression, resolvedTimeZone, count).stream()
              .map(nextRun -> nextRun.toOffsetDateTime().toString())
              .toList());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ReviewAutomationResponse createReviewAutomation(
      @RequestBody UpsertReviewAutomationRequest request) {
    try {
      return toDetailResponse(
          reviewAutomationService.createReviewAutomation(
              request != null ? request.name() : null,
              request != null ? request.enabled() : null,
              request != null ? request.cronExpression() : null,
              request != null ? request.timeZone() : null,
              request != null ? request.teamId() : null,
              request != null ? request.dueDateOffsetDays() : null,
              request != null ? request.maxWordCountPerProject() : null,
              request != null ? request.featureIds() : List.of()));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @PutMapping("/{automationId}")
  public ReviewAutomationResponse updateReviewAutomation(
      @PathVariable Long automationId, @RequestBody UpsertReviewAutomationRequest request) {
    try {
      return toDetailResponse(
          reviewAutomationService.updateReviewAutomation(
              automationId,
              request != null ? request.name() : null,
              request != null ? request.enabled() : null,
              request != null ? request.cronExpression() : null,
              request != null ? request.timeZone() : null,
              request != null ? request.teamId() : null,
              request != null ? request.dueDateOffsetDays() : null,
              request != null ? request.maxWordCountPerProject() : null,
              request != null ? request.featureIds() : List.of()));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Review automation not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @DeleteMapping("/{automationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteReviewAutomation(@PathVariable Long automationId) {
    try {
      reviewAutomationService.deleteReviewAutomation(automationId);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
  }

  @PostMapping("/batch-upsert")
  public BatchUpsertReviewAutomationsResponse batchUpsertReviewAutomations(
      @RequestBody BatchUpsertReviewAutomationsRequest request) {
    try {
      ReviewAutomationService.BatchUpsertResult result =
          reviewAutomationService.batchUpsert(
              request == null || request.rows() == null
                  ? List.of()
                  : request.rows().stream()
                      .map(
                          row ->
                              new ReviewAutomationService.BatchUpsertRow(
                                  row.id(),
                                  row.name(),
                                  row.enabled(),
                                  row.cronExpression(),
                                  row.timeZone(),
                                  row.teamId(),
                                  row.dueDateOffsetDays(),
                                  row.maxWordCountPerProject(),
                                  row.featureIds()))
                      .toList());
      return new BatchUpsertReviewAutomationsResponse(result.createdCount(), result.updatedCount());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  private SearchReviewAutomationsResponse.ReviewAutomationSummary toSummaryResponse(
      SearchReviewAutomationsView.ReviewAutomationSummary view) {
    return new SearchReviewAutomationsResponse.ReviewAutomationSummary(
        view.id(),
        view.createdDate(),
        view.lastModifiedDate(),
        view.name(),
        Boolean.TRUE.equals(view.enabled()),
        view.cronExpression(),
        view.timeZone(),
        view.team() == null ? null : new TeamRef(view.team().id(), view.team().name()),
        view.dueDateOffsetDays(),
        view.maxWordCountPerProject(),
        toTriggerStatusRef(view.trigger()),
        view.featureCount(),
        view.features().stream()
            .map(feature -> new FeatureRef(feature.id(), feature.name()))
            .toList());
  }

  private ReviewAutomationResponse toDetailResponse(
      ReviewAutomationService.ReviewAutomationDetail detail) {
    return new ReviewAutomationResponse(
        detail.id(),
        detail.createdDate(),
        detail.lastModifiedDate(),
        detail.name(),
        detail.enabled(),
        detail.cronExpression(),
        detail.timeZone(),
        detail.team() == null ? null : new TeamRef(detail.team().id(), detail.team().name()),
        detail.dueDateOffsetDays(),
        detail.maxWordCountPerProject(),
        toTriggerStatusRef(detail.trigger()),
        detail.features().stream()
            .map(feature -> new FeatureRef(feature.id(), feature.name()))
            .toList());
  }

  private TriggerStatusRef toTriggerStatusRef(ReviewAutomationTriggerStatusView trigger) {
    if (trigger == null) {
      return null;
    }
    return new TriggerStatusRef(
        trigger.status(),
        trigger.quartzState(),
        trigger.nextRunAt() == null ? null : trigger.nextRunAt().toOffsetDateTime().toString(),
        trigger.previousRunAt() == null
            ? null
            : trigger.previousRunAt().toOffsetDateTime().toString(),
        trigger.lastRunAt() == null ? null : trigger.lastRunAt().toOffsetDateTime().toString(),
        trigger.lastSuccessfulRunAt() == null
            ? null
            : trigger.lastSuccessfulRunAt().toOffsetDateTime().toString(),
        trigger.repairRecommended());
  }

  private ReviewAutomationTriggerStatusView toTriggerStatusView(
      ReviewAutomationCronSchedulerService.TriggerHealth triggerHealth,
      ZonedDateTime lastRunAt,
      ZonedDateTime lastSuccessfulRunAt) {
    return new ReviewAutomationTriggerStatusView(
        triggerHealth.status().name(),
        triggerHealth.quartzState(),
        triggerHealth.nextRunAt(),
        triggerHealth.previousRunAt(),
        lastRunAt,
        lastSuccessfulRunAt,
        triggerHealth.repairRecommended());
  }

  private void requireAdmin() {
    if (!teamService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }
}
