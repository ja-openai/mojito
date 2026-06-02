package com.box.l10n.mojito.rest.admin;

import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentStat;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.review.ReviewProjectTimeSpentStatRepository;
import com.box.l10n.mojito.service.review.ReviewProjectTimeSpentStatService;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/linguist-time-spent")
public class LinguistTimeSpentReportWS {

  private final ReviewProjectTimeSpentStatService timeSpentStatService;
  private final StructuredBlobStorage structuredBlobStorage;
  private final ObjectMapper objectMapper;
  private final LinguistTimeSpentHybridProperties hybridProperties;
  private final AsyncTaskExecutor hybridExecutor;

  public LinguistTimeSpentReportWS(
      ReviewProjectTimeSpentStatService timeSpentStatService,
      StructuredBlobStorage structuredBlobStorage,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      LinguistTimeSpentHybridProperties hybridProperties,
      @Qualifier("linguistTimeSpentHybridExecutor") AsyncTaskExecutor hybridExecutor) {
    this.timeSpentStatService = timeSpentStatService;
    this.structuredBlobStorage = structuredBlobStorage;
    this.objectMapper = objectMapper;
    this.hybridProperties = hybridProperties;
    this.hybridExecutor = hybridExecutor;
  }

  @GetMapping
  public ResponseEntity<ReportHybridResponse> getReport(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          ZonedDateTime activityAfter,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          ZonedDateTime activityBefore,
      @RequestParam(required = false, defaultValue = "CLOSED") ReviewProjectStatus status,
      @RequestParam(required = false) Long translatorUserId,
      @RequestParam(required = false) String localeBcp47Tag,
      @RequestParam(required = false, defaultValue = "100") int summaryLimit,
      @RequestParam(required = false, defaultValue = "100") int detailLimit) {
    ReviewProjectTimeSpentStatService.TimeSpentReportCriteria criteria =
        new ReviewProjectTimeSpentStatService.TimeSpentReportCriteria(
            activityAfter,
            activityBefore,
            status,
            translatorUserId,
            localeBcp47Tag,
            summaryLimit,
            detailLimit);
    UUID requestId = UUID.randomUUID();
    AtomicBoolean forceAsyncPersistence = new AtomicBoolean(false);
    long startedAtNanos = System.nanoTime();
    Future<ReportResponse> future =
        hybridExecutor.submit(
            () -> {
              try {
                ReportResponse response =
                    ReportResponse.from(timeSpentStatService.getReport(criteria));
                persistReportHybridResponseIfNeeded(
                    requestId, response, null, forceAsyncPersistence.get(), startedAtNanos);
                return response;
              } catch (Exception e) {
                if (forceAsyncPersistence.get()) {
                  persistReportHybridResponse(
                      requestId, new ReportHybridResponse(null, null, HybridError.from(e)));
                }
                throw e;
              }
            });
    try {
      return ResponseEntity.ok(
          new ReportHybridResponse(
              future.get(hybridProperties.convertToAsyncAfter().toNanos(), TimeUnit.NANOSECONDS),
              null,
              null));
    } catch (TimeoutException e) {
      forceAsyncPersistence.set(true);
      return ResponseEntity.accepted()
          .body(new ReportHybridResponse(null, buildPollingToken(requestId), null));
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ResponseStatusException responseStatusException) {
        throw responseStatusException;
      }
      throw new UncheckedExecutionException(e);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @PostMapping("/recompute")
  public ResponseEntity<RecomputeHybridResponse> recompute(
      @RequestBody(required = false) RecomputeRequest request) {
    RecomputeRequest safeRequest = request == null ? RecomputeRequest.defaults() : request;
    ReviewProjectTimeSpentStatService.TimeSpentRecomputeRequest recomputeRequest =
        new ReviewProjectTimeSpentStatService.TimeSpentRecomputeRequest(
            safeRequest.projectCreatedAfter(),
            safeRequest.projectCreatedBefore(),
            safeRequest.status(),
            safeRequest.translatorUserId(),
            safeRequest.localeBcp47Tag(),
            safeRequest.limit());
    UUID requestId = UUID.randomUUID();
    AtomicBoolean forceAsyncPersistence = new AtomicBoolean(false);
    long startedAtNanos = System.nanoTime();
    Future<RecomputeResponse> future =
        hybridExecutor.submit(
            () -> {
              try {
                RecomputeResponse response =
                    RecomputeResponse.from(
                        timeSpentStatService.recomputeProjectStats(recomputeRequest));
                persistRecomputeHybridResponseIfNeeded(
                    requestId, response, null, forceAsyncPersistence.get(), startedAtNanos);
                return response;
              } catch (Exception e) {
                if (forceAsyncPersistence.get()) {
                  persistRecomputeHybridResponse(
                      requestId, new RecomputeHybridResponse(null, null, HybridError.from(e)));
                }
                throw e;
              }
            });
    try {
      return ResponseEntity.ok(
          new RecomputeHybridResponse(
              future.get(hybridProperties.convertToAsyncAfter().toNanos(), TimeUnit.NANOSECONDS),
              null,
              null));
    } catch (TimeoutException e) {
      forceAsyncPersistence.set(true);
      return ResponseEntity.accepted()
          .body(new RecomputeHybridResponse(null, buildPollingToken(requestId), null));
    } catch (ExecutionException e) {
      if (e.getCause() instanceof ResponseStatusException responseStatusException) {
        throw responseStatusException;
      }
      throw new UncheckedExecutionException(e);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/report/results/{requestId}")
  public ResponseEntity<ReportHybridResponse> getReportResults(@PathVariable UUID requestId) {
    Optional<String> storedResult =
        structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.LINGUIST_TIME_SPENT_REPORT_ASYNC, requestId.toString());
    if (storedResult.isEmpty()) {
      return ResponseEntity.accepted()
          .body(new ReportHybridResponse(null, buildPollingToken(requestId), null));
    }
    ReportHybridResponse response =
        objectMapper.readValueUnchecked(storedResult.get(), ReportHybridResponse.class);
    return response.error() == null
        ? ResponseEntity.ok(response)
        : ResponseEntity.internalServerError().body(response);
  }

  @GetMapping("/recompute/results/{requestId}")
  public ResponseEntity<RecomputeHybridResponse> getRecomputeResults(@PathVariable UUID requestId) {
    Optional<String> storedResult =
        structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.LINGUIST_TIME_SPENT_RECOMPUTE_ASYNC, requestId.toString());
    if (storedResult.isEmpty()) {
      return ResponseEntity.accepted()
          .body(new RecomputeHybridResponse(null, buildPollingToken(requestId), null));
    }
    RecomputeHybridResponse response =
        objectMapper.readValueUnchecked(storedResult.get(), RecomputeHybridResponse.class);
    return response.error() == null
        ? ResponseEntity.ok(response)
        : ResponseEntity.internalServerError().body(response);
  }

  public record RecomputeRequest(
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime projectCreatedAfter,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) ZonedDateTime projectCreatedBefore,
      ReviewProjectStatus status,
      Long translatorUserId,
      String localeBcp47Tag,
      int limit) {

    public static RecomputeRequest defaults() {
      return new RecomputeRequest(null, null, ReviewProjectStatus.CLOSED, null, null, 100);
    }
  }

  public record RecomputeResponse(
      int matchedProjectCount, int computedWindowCount, int backfilledWindowCount) {

    public static RecomputeResponse from(
        ReviewProjectTimeSpentStatService.TimeSpentRecomputeResult result) {
      return new RecomputeResponse(
          result.matchedProjectCount(),
          result.computedWindowCount(),
          result.backfilledWindowCount());
    }
  }

  public record PollingToken(UUID requestId, long recommendedPollingDurationMillis) {}

  public record HybridError(String type, String message, String stackTrace) {
    public static HybridError from(Exception exception) {
      return new HybridError(
          exception.getClass().getName(),
          exception.getMessage(),
          Throwables.getStackTraceAsString(exception));
    }
  }

  public record ReportHybridResponse(
      ReportResponse results, PollingToken pollingToken, HybridError error) {}

  public record RecomputeHybridResponse(
      RecomputeResponse results, PollingToken pollingToken, HybridError error) {}

  public record ReportResponse(
      SummaryResponse summary,
      List<TranslatorScorecardResponse> translatorScorecards,
      List<LinguistSummaryResponse> linguists,
      List<WindowResponse> windows) {

    public static ReportResponse from(ReviewProjectTimeSpentStatService.TimeSpentReport report) {
      return new ReportResponse(
          SummaryResponse.from(report.summary()),
          report.translatorScorecards().stream().map(TranslatorScorecardResponse::from).toList(),
          report.linguistSummaries().stream().map(LinguistSummaryResponse::from).toList(),
          report.windows().stream().map(WindowResponse::from).toList());
    }
  }

  public record SummaryResponse(
      long windowCount,
      long projectCount,
      long decidedWordCount,
      Long selfReportedSeconds,
      long estimatedActiveSeconds,
      long rawDecisionSpanSeconds,
      long pauseSeconds,
      long pauseCount,
      long reportedMissingCount,
      long reviewFlagCount,
      ZonedDateTime lastComputedAt) {

    public static SummaryResponse from(
        ReviewProjectTimeSpentStatRepository.SummaryProjection projection) {
      if (projection == null) {
        return empty();
      }
      return new SummaryResponse(
          safeLong(projection.getWindowCount()),
          safeLong(projection.getProjectCount()),
          safeLong(projection.getDecidedWordCount()),
          projection.getSelfReportedSeconds(),
          safeLong(projection.getEstimatedActiveSeconds()),
          safeLong(projection.getRawDecisionSpanSeconds()),
          safeLong(projection.getPauseSeconds()),
          safeLong(projection.getPauseCount()),
          safeLong(projection.getReportedMissingCount()),
          safeLong(projection.getReviewFlagCount()),
          projection.getLastComputedAt());
    }

    private static SummaryResponse empty() {
      return new SummaryResponse(0, 0, 0, null, 0, 0, 0, 0, 0, 0, null);
    }
  }

  public record LinguistSummaryResponse(
      Long assignedTranslatorUserId,
      String assignedTranslatorUsername,
      String localeBcp47Tag,
      SummaryResponse metrics) {

    public static LinguistSummaryResponse from(
        ReviewProjectTimeSpentStatRepository.LinguistSummaryProjection projection) {
      return new LinguistSummaryResponse(
          projection.getAssignedTranslatorUserId(),
          projection.getAssignedTranslatorUsername(),
          projection.getLocaleBcp47Tag(),
          SummaryResponse.from(projection));
    }
  }

  public record TranslatorScorecardResponse(
      Long assignedTranslatorUserId,
      String assignedTranslatorUsername,
      long windowCount,
      long projectCount,
      long decidedWordCount,
      Long averageAssignedToAcceptedSeconds,
      long notAcceptedCount,
      double notAcceptedPercent,
      long missedDeadlineCount,
      double missedDeadlinePercent,
      long reportedMissingCount,
      double reportedMissingPercent,
      long reviewFlagCount,
      double reviewFlagPercent,
      Long selfReportedSeconds,
      long estimatedActiveSeconds,
      Double reportedComputedRatio,
      ZonedDateTime lastComputedAt) {

    public static TranslatorScorecardResponse from(
        ReviewProjectTimeSpentStatRepository.TranslatorScorecardProjection projection) {
      long windowCount = safeLong(projection.getWindowCount());
      Long selfReportedSeconds = projection.getSelfReportedSeconds();
      long estimatedActiveSeconds = safeLong(projection.getEstimatedActiveSeconds());
      return new TranslatorScorecardResponse(
          projection.getAssignedTranslatorUserId(),
          projection.getAssignedTranslatorUsername(),
          windowCount,
          safeLong(projection.getProjectCount()),
          safeLong(projection.getDecidedWordCount()),
          roundSeconds(projection.getAverageAssignedToAcceptedSeconds()),
          safeLong(projection.getNotAcceptedCount()),
          percent(projection.getNotAcceptedCount(), windowCount),
          safeLong(projection.getMissedDeadlineCount()),
          percent(projection.getMissedDeadlineCount(), windowCount),
          safeLong(projection.getReportedMissingCount()),
          percent(projection.getReportedMissingCount(), windowCount),
          safeLong(projection.getReviewFlagCount()),
          percent(projection.getReviewFlagCount(), windowCount),
          selfReportedSeconds,
          estimatedActiveSeconds,
          selfReportedSeconds == null || estimatedActiveSeconds <= 0
              ? null
              : selfReportedSeconds / (double) estimatedActiveSeconds,
          projection.getLastComputedAt());
    }
  }

  public record WindowResponse(
      long id,
      long assignmentWindowId,
      long reviewProjectId,
      Long reviewProjectRequestId,
      String reviewProjectRequestName,
      String reviewProjectStatus,
      String localeBcp47Tag,
      Long assignedTranslatorUserId,
      String assignedTranslatorUsername,
      ZonedDateTime assignmentWindowStartedAt,
      ZonedDateTime assignmentAcceptedAt,
      ZonedDateTime assignmentWindowEndedAt,
      String assignmentWindowEndReason,
      ZonedDateTime projectCreatedDate,
      ZonedDateTime projectDueDate,
      ZonedDateTime firstDecisionAt,
      ZonedDateTime lastDecisionAt,
      Long assignedToAcceptedSeconds,
      Long acceptedToFirstDecisionSeconds,
      long textUnitCount,
      long wordCount,
      long decidedCount,
      long decidedWordCount,
      long selfReportedSeconds,
      Long reportedComputedDeltaSeconds,
      Double reportedComputedRatio,
      long estimatedActiveSeconds,
      long rawDecisionSpanSeconds,
      long projectSpanSeconds,
      long pauseSeconds,
      long pauseCount,
      String reviewFlag,
      boolean reportedMissing,
      String attributionConfidence,
      ZonedDateTime finalizedAt,
      ZonedDateTime computedAt) {

    public static WindowResponse from(ReviewProjectTimeSpentStat stat) {
      return new WindowResponse(
          stat.getId(),
          stat.getAssignmentWindow().getId(),
          stat.getReviewProject().getId(),
          stat.getReviewProjectRequestId(),
          stat.getReviewProjectRequestName(),
          stat.getReviewProjectStatus(),
          stat.getLocaleBcp47Tag(),
          stat.getAssignedTranslatorUser() == null
              ? null
              : stat.getAssignedTranslatorUser().getId(),
          stat.getAssignedTranslatorUsername(),
          stat.getAssignmentWindowStartedAt(),
          stat.getAssignmentAcceptedAt(),
          stat.getAssignmentWindowEndedAt(),
          stat.getAssignmentWindowEndReason(),
          stat.getProjectCreatedDate(),
          stat.getProjectDueDate(),
          stat.getFirstDecisionAt(),
          stat.getLastDecisionAt(),
          stat.getAssignedToAcceptedSeconds(),
          stat.getAcceptedToFirstDecisionSeconds(),
          stat.getTextUnitCount(),
          stat.getWordCount(),
          stat.getDecidedCount(),
          stat.getDecidedWordCount(),
          safeLong(stat.getSelfReportedSeconds()),
          stat.getReportedComputedDeltaSeconds(),
          stat.getReportedComputedRatio(),
          stat.getEstimatedActiveSeconds(),
          stat.getRawDecisionSpanSeconds(),
          stat.getProjectSpanSeconds(),
          stat.getPauseSeconds(),
          stat.getPauseCount(),
          stat.getReviewFlag().name(),
          stat.isReportedMissing(),
          stat.getAttributionConfidence().name(),
          stat.getFinalizedAt(),
          stat.getComputedAt());
    }
  }

  private static long safeLong(Number value) {
    return value == null ? 0L : value.longValue();
  }

  private static Long roundSeconds(Double value) {
    return value == null ? null : Math.round(value);
  }

  private static double percent(Number value, long total) {
    if (value == null || total <= 0) {
      return 0;
    }
    return value.doubleValue() * 100.0d / total;
  }

  private void persistReportHybridResponseIfNeeded(
      UUID requestId,
      ReportResponse response,
      HybridError error,
      boolean forceAsyncPersistence,
      long startedAtNanos) {
    if (forceAsyncPersistence || isSlowEnoughForAsyncPersistence(startedAtNanos)) {
      persistReportHybridResponse(requestId, new ReportHybridResponse(response, null, error));
    }
  }

  private void persistRecomputeHybridResponseIfNeeded(
      UUID requestId,
      RecomputeResponse response,
      HybridError error,
      boolean forceAsyncPersistence,
      long startedAtNanos) {
    if (forceAsyncPersistence || isSlowEnoughForAsyncPersistence(startedAtNanos)) {
      persistRecomputeHybridResponse(requestId, new RecomputeHybridResponse(response, null, error));
    }
  }

  private boolean isSlowEnoughForAsyncPersistence(long startedAtNanos) {
    return System.nanoTime() - startedAtNanos >= hybridProperties.convertToAsyncAfter().toNanos();
  }

  private void persistReportHybridResponse(UUID requestId, ReportHybridResponse response) {
    structuredBlobStorage.put(
        StructuredBlobStorage.Prefix.LINGUIST_TIME_SPENT_REPORT_ASYNC,
        requestId.toString(),
        objectMapper.writeValueAsStringUnchecked(response),
        Retention.MIN_1_DAY);
  }

  private void persistRecomputeHybridResponse(UUID requestId, RecomputeHybridResponse response) {
    structuredBlobStorage.put(
        StructuredBlobStorage.Prefix.LINGUIST_TIME_SPENT_RECOMPUTE_ASYNC,
        requestId.toString(),
        objectMapper.writeValueAsStringUnchecked(response),
        Retention.MIN_1_DAY);
  }

  private PollingToken buildPollingToken(UUID requestId) {
    return new PollingToken(requestId, hybridProperties.recommendedPollingDuration().toMillis());
  }
}
