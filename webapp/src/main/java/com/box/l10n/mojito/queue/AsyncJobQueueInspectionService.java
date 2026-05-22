package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Bounded operator inspection and replay operations for async job queue rows. */
@Service
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueInspectionService {

  static Logger logger = LoggerFactory.getLogger(AsyncJobQueueInspectionService.class);

  static final int DEFAULT_LIMIT = 100;
  static final int JOB_DATA_PREVIEW_MAX_LENGTH = 512;

  private final AsyncJobStore asyncJobStore;
  private final MeterRegistry meterRegistry;

  public AsyncJobQueueInspectionService(AsyncJobStore asyncJobStore, MeterRegistry meterRegistry) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  public List<AsyncJobSummary> findJobs(String queueName, String status, Integer limit) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobStatus parsedStatus = parseStatus(status == null ? "failed" : status);
    int boundedLimit =
        limit == null
            ? DEFAULT_LIMIT
            : AsyncJobQueueValidation.validateStoreQueryLimit("limit", limit);
    return asyncJobStore.findByStatus(validatedQueueName, parsedStatus, boundedLimit).stream()
        .map(this::toSummary)
        .toList();
  }

  public AsyncJobDetails getJob(String queueName, String jobId) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobId asyncJobId = new AsyncJobId(jobId);
    return asyncJobStore.getByIds(List.of(asyncJobId)).stream()
        .filter(record -> validatedQueueName.equals(record.queueName()))
        .findFirst()
        .map(this::toDetails)
        .orElseThrow(
            () ->
                new AsyncJobNotFoundException(
                    "Async job not found for queue "
                        + validatedQueueName
                        + ": "
                        + asyncJobId.value()));
  }

  public AsyncJobDetails requeueFailedJob(String queueName, String jobId, String jobData) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobId asyncJobId = new AsyncJobId(jobId);

    try {
      if (asyncJobStore.requeueFailedNow(validatedQueueName, asyncJobId, jobData)) {
        incrementRequeueCounter(validatedQueueName, "succeeded");
        logger.info(
            "Requeued failed async job for queue {}, job {}, replacementPayload={}",
            validatedQueueName,
            asyncJobId.value(),
            jobData != null);
        return getJob(validatedQueueName, asyncJobId.value());
      }

      boolean jobExistsInQueue =
          asyncJobStore.getByIds(List.of(asyncJobId)).stream()
              .anyMatch(record -> validatedQueueName.equals(record.queueName()));
      if (!jobExistsInQueue) {
        incrementRequeueCounter(validatedQueueName, "notFound");
        logger.warn(
            "Failed to requeue async job for queue {}; job {} was not found",
            validatedQueueName,
            asyncJobId.value());
        throw new AsyncJobNotFoundException(
            "Async job not found for queue " + validatedQueueName + ": " + asyncJobId.value());
      }
      incrementRequeueCounter(validatedQueueName, "notFailed");
      logger.warn(
          "Failed to requeue async job for queue {}; job {} is not failed",
          validatedQueueName,
          asyncJobId.value());
      throw new AsyncJobNotFailedException(
          "Async job is not in failed state: " + asyncJobId.value());
    } catch (AsyncJobNotFoundException | AsyncJobNotFailedException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      incrementRequeueCounter(validatedQueueName, "failed");
      logger.warn(
          "Failed to requeue async job for queue {}, job {}",
          validatedQueueName,
          asyncJobId.value(),
          exception);
      throw exception;
    }
  }

  private AsyncJobStatus parseStatus(String status) {
    Objects.requireNonNull(status);
    if (status.isBlank()) {
      throw new IllegalArgumentException("status must not be blank");
    }

    String normalizedStatus = status.trim().toLowerCase(Locale.ROOT);
    try {
      return AsyncJobStatus.fromDatabaseValue(normalizedStatus);
    } catch (IllegalArgumentException ignored) {
      try {
        return AsyncJobStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("status must be one of: queued, running, done, failed");
      }
    }
  }

  private AsyncJobSummary toSummary(AsyncJobRecord record) {
    String jobData = record.jobData();
    return new AsyncJobSummary(
        record.id().value(),
        record.queueName(),
        record.status().getDatabaseValue(),
        record.availableAt(),
        record.leaseUntil(),
        record.workerId(),
        record.attemptCount(),
        record.lastError(),
        jobData.length(),
        preview(jobData),
        record.createdDate(),
        record.updatedDate());
  }

  private AsyncJobDetails toDetails(AsyncJobRecord record) {
    return new AsyncJobDetails(
        record.id().value(),
        record.queueName(),
        record.status().getDatabaseValue(),
        record.availableAt(),
        record.leaseUntil(),
        record.workerId(),
        record.attemptCount(),
        record.lastError(),
        record.jobData(),
        record.createdDate(),
        record.updatedDate());
  }

  private String preview(String jobData) {
    return jobData.length() <= JOB_DATA_PREVIEW_MAX_LENGTH
        ? jobData
        : jobData.substring(0, JOB_DATA_PREVIEW_MAX_LENGTH);
  }

  private void incrementRequeueCounter(String queueName, String result) {
    meterRegistry
        .counter("asyncJobQueue.inspection.requeue", "queueName", queueName, "result", result)
        .increment();
  }

  public record AsyncJobSummary(
      String id,
      String queueName,
      String status,
      Instant availableAt,
      Instant leaseUntil,
      String workerId,
      int attemptCount,
      String lastError,
      int jobDataLength,
      String jobDataPreview,
      Instant createdDate,
      Instant updatedDate) {}

  public record AsyncJobDetails(
      String id,
      String queueName,
      String status,
      Instant availableAt,
      Instant leaseUntil,
      String workerId,
      int attemptCount,
      String lastError,
      String jobData,
      Instant createdDate,
      Instant updatedDate) {}

  public static class AsyncJobNotFoundException extends RuntimeException {
    public AsyncJobNotFoundException(String message) {
      super(message);
    }
  }

  public static class AsyncJobNotFailedException extends RuntimeException {
    public AsyncJobNotFailedException(String message) {
      super(message);
    }
  }
}
