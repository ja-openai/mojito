package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  private final AsyncJobQueueCoordinator asyncJobQueueCoordinator;
  private final MeterRegistry meterRegistry;

  public AsyncJobQueueInspectionService(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.asyncJobQueueCoordinator = Objects.requireNonNull(asyncJobQueueCoordinator);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  public List<AsyncJobSummary> findJobs(String queueName, String status, Integer limit) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobStatus parsedStatus;
    try {
      parsedStatus = parseStatus(status == null ? "failed" : status);
    } catch (RuntimeException exception) {
      incrementFindCounter(validatedQueueName, "invalid", "invalidStatus");
      throw exception;
    }

    int boundedLimit;
    try {
      boundedLimit =
          limit == null
              ? DEFAULT_LIMIT
              : AsyncJobQueueValidation.validateStoreQueryLimit("limit", limit);
    } catch (RuntimeException exception) {
      incrementFindCounter(validatedQueueName, parsedStatus.getDatabaseValue(), "invalidLimit");
      throw exception;
    }

    try {
      List<AsyncJobSummary> jobs =
          asyncJobStore.findByStatus(validatedQueueName, parsedStatus, boundedLimit).stream()
              .map(this::toSummary)
              .toList();
      incrementFindCounter(validatedQueueName, parsedStatus.getDatabaseValue(), "succeeded");
      return jobs;
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementFindCounter(validatedQueueName, parsedStatus.getDatabaseValue(), "failed");
      logger.warn(
          "Failed to inspect async jobs for queue {}, status {}",
          validatedQueueName,
          parsedStatus.getDatabaseValue(),
          exception);
      throw unchecked(exception);
    }
  }

  public List<AsyncJobStatusCountSummary> countJobsByStatus(String queueName) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    try {
      Map<AsyncJobStatus, Long> countsByStatus = zeroCountsByStatus();
      for (AsyncJobStatusCount statusCount : asyncJobStore.countByStatus(validatedQueueName)) {
        countsByStatus.put(statusCount.status(), statusCount.count());
      }
      incrementCountCounter(validatedQueueName, "succeeded");
      return countsByStatus.entrySet().stream()
          .map(
              entry ->
                  new AsyncJobStatusCountSummary(
                      entry.getKey().getDatabaseValue(), Math.max(0L, entry.getValue())))
          .toList();
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementCountCounter(validatedQueueName, "failed");
      logger.warn(
          "Failed to count async jobs by status for queue {}", validatedQueueName, exception);
      throw unchecked(exception);
    }
  }

  public AsyncJobReadyStatusSummary readyStatus(String queueName) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    try {
      AsyncJobReadyStatus readyStatus = asyncJobStore.readyStatus(validatedQueueName);
      incrementReadyStatusCounter(validatedQueueName, "succeeded");
      return new AsyncJobReadyStatusSummary(
          validatedQueueName,
          readyStatus.count(),
          readyStatus.oldestAvailableAt(),
          readyStatus.observedAt(),
          readyOldestAgeMs(readyStatus));
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementReadyStatusCounter(validatedQueueName, "failed");
      logger.warn(
          "Failed to inspect async ready status for queue {}", validatedQueueName, exception);
      throw unchecked(exception);
    }
  }

  public AsyncJobExpiredLeaseStatusSummary expiredLeaseStatus(String queueName) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    try {
      AsyncJobExpiredLeaseStatus expiredLeaseStatus =
          asyncJobStore.expiredLeaseStatus(validatedQueueName);
      incrementExpiredLeaseStatusCounter(validatedQueueName, "succeeded");
      return new AsyncJobExpiredLeaseStatusSummary(
          validatedQueueName,
          expiredLeaseStatus.count(),
          expiredLeaseStatus.oldestLeaseUntil(),
          expiredLeaseStatus.observedAt(),
          expiredLeaseOldestAgeMs(expiredLeaseStatus));
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementExpiredLeaseStatusCounter(validatedQueueName, "failed");
      logger.warn(
          "Failed to inspect async expired lease status for queue {}",
          validatedQueueName,
          exception);
      throw unchecked(exception);
    }
  }

  public AsyncJobDetails getJob(String queueName, String jobId) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobId asyncJobId;
    try {
      asyncJobId = new AsyncJobId(jobId);
    } catch (RuntimeException exception) {
      incrementGetCounter(validatedQueueName, "invalidId");
      throw exception;
    }
    try {
      AsyncJobDetails job = findJobDetails(validatedQueueName, asyncJobId);
      if (job != null) {
        incrementGetCounter(validatedQueueName, "succeeded");
        return job;
      }

      incrementGetCounter(validatedQueueName, "notFound");
      throw new AsyncJobNotFoundException(
          "Async job not found for queue " + validatedQueueName + ": " + asyncJobId.value());
    } catch (AsyncJobNotFoundException exception) {
      throw exception;
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementGetCounter(validatedQueueName, "failed");
      logger.warn(
          "Failed to inspect async job for queue {}, job {}",
          validatedQueueName,
          asyncJobId.value(),
          exception);
      throw unchecked(exception);
    }
  }

  public AsyncJobDetails requeueFailedJob(String queueName, String jobId, String jobData) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobId asyncJobId;
    try {
      asyncJobId = new AsyncJobId(jobId);
    } catch (RuntimeException exception) {
      incrementRequeueCounter(validatedQueueName, "invalidId");
      throw exception;
    }

    boolean requeueSucceeded = false;
    try {
      if (asyncJobStore.requeueFailedNow(validatedQueueName, asyncJobId, jobData)) {
        requeueSucceeded = true;
        triggerReplayWakeup(validatedQueueName, asyncJobId);
        AsyncJobDetails job = findJobDetails(validatedQueueName, asyncJobId);
        if (job == null) {
          throw new AsyncJobNotFoundException(
              "Async job not found for queue " + validatedQueueName + ": " + asyncJobId.value());
        }
        incrementRequeueCounter(validatedQueueName, "succeeded");
        logger.info(
            "Requeued failed async job for queue {}, job {}, replacementPayload={}",
            validatedQueueName,
            asyncJobId.value(),
            jobData != null);
        return job;
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
      if (requeueSucceeded) {
        incrementRequeueCounter(validatedQueueName, "failed");
        logger.warn(
            "Requeued failed async job for queue {}, job {}, but failed to inspect the replayed job",
            validatedQueueName,
            asyncJobId.value(),
            exception);
      }
      throw exception;
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementRequeueCounter(validatedQueueName, "failed");
      logger.warn(
          "Failed to requeue async job for queue {}, job {}",
          validatedQueueName,
          asyncJobId.value(),
          exception);
      throw unchecked(exception);
    }
  }

  private void triggerReplayWakeup(String queueName, AsyncJobId asyncJobId) {
    try {
      asyncJobQueueCoordinator.triggerPollNow(queueName);
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      meterRegistry
          .counter("asyncJobQueue.inspection.requeueWakeup.failed", "queueName", queueName)
          .increment();
      logger.warn(
          "Failed to trigger async job queue wakeup after replay for queue {}, job {}",
          queueName,
          asyncJobId.value(),
          exception);
    }
  }

  private AsyncJobDetails findJobDetails(String validatedQueueName, AsyncJobId asyncJobId) {
    return asyncJobStore.getByIds(List.of(asyncJobId)).stream()
        .filter(record -> validatedQueueName.equals(record.queueName()))
        .findFirst()
        .map(this::toDetails)
        .orElse(null);
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
        AsyncJobQueueValidation.truncateLastError(record.lastError()),
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
        AsyncJobQueueValidation.truncateLastError(record.lastError()),
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

  private void incrementFindCounter(String queueName, String status, String result) {
    meterRegistry
        .counter(
            "asyncJobQueue.inspection.find",
            "queueName",
            queueName,
            "status",
            status,
            "result",
            result)
        .increment();
  }

  private void incrementGetCounter(String queueName, String result) {
    meterRegistry
        .counter("asyncJobQueue.inspection.get", "queueName", queueName, "result", result)
        .increment();
  }

  private void incrementCountCounter(String queueName, String result) {
    meterRegistry
        .counter("asyncJobQueue.inspection.count", "queueName", queueName, "result", result)
        .increment();
  }

  private void incrementReadyStatusCounter(String queueName, String result) {
    meterRegistry
        .counter("asyncJobQueue.inspection.readyStatus", "queueName", queueName, "result", result)
        .increment();
  }

  private void incrementExpiredLeaseStatusCounter(String queueName, String result) {
    meterRegistry
        .counter(
            "asyncJobQueue.inspection.expiredLeaseStatus", "queueName", queueName, "result", result)
        .increment();
  }

  private long readyOldestAgeMs(AsyncJobReadyStatus readyStatus) {
    if (readyStatus.count() == 0) {
      return 0L;
    }
    return Math.max(
        0L, Duration.between(readyStatus.oldestAvailableAt(), readyStatus.observedAt()).toMillis());
  }

  private long expiredLeaseOldestAgeMs(AsyncJobExpiredLeaseStatus expiredLeaseStatus) {
    if (expiredLeaseStatus.count() == 0) {
      return 0L;
    }
    return Math.max(
        0L,
        Duration.between(expiredLeaseStatus.oldestLeaseUntil(), expiredLeaseStatus.observedAt())
            .toMillis());
  }

  private Map<AsyncJobStatus, Long> zeroCountsByStatus() {
    Map<AsyncJobStatus, Long> countsByStatus = new EnumMap<>(AsyncJobStatus.class);
    for (AsyncJobStatus status : AsyncJobStatus.values()) {
      countsByStatus.put(status, 0L);
    }
    return countsByStatus;
  }

  private boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError
        || "java.lang.ThreadDeath".equals(throwable.getClass().getName());
  }

  private RuntimeException unchecked(Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (throwable instanceof Error error) {
      throw error;
    }
    return new IllegalStateException(throwable);
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

  public record AsyncJobStatusCountSummary(String status, long count) {}

  public record AsyncJobReadyStatusSummary(
      String queueName,
      long count,
      Instant oldestAvailableAt,
      Instant observedAt,
      long oldestAgeMs) {}

  public record AsyncJobExpiredLeaseStatusSummary(
      String queueName,
      long count,
      Instant oldestLeaseUntil,
      Instant observedAt,
      long oldestAgeMs) {}

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
