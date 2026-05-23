package com.box.l10n.mojito.queue;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class AsyncJobQueueValidation {

  static final int QUEUE_NAME_MAX_LENGTH = 64;
  static final Pattern QUEUE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
  static final int WORKER_ID_MAX_LENGTH = 128;
  static final int LAST_ERROR_MAX_LENGTH = 4_000;
  static final int CLAIM_BATCH_SIZE_MAX = 1_000;
  static final int MAX_CONCURRENCY_MAX = 256;
  static final int MAX_ATTEMPTS_MAX = 100;
  static final int STORE_QUERY_LIMIT_MAX = 1_000;
  static final long STATUS_METRICS_INTERVAL_MS_MAX = Duration.ofHours(1).toMillis();
  static final long POLL_INTERVAL_MS_MAX = Duration.ofHours(1).toMillis();
  static final long MAX_POLL_INTERVAL_MS_MAX = Duration.ofHours(1).toMillis();
  static final long LEASE_DURATION_MS_MAX = Duration.ofDays(1).toMillis();
  static final long HEARTBEAT_INTERVAL_MS_MAX = Duration.ofHours(1).toMillis();
  static final long MAX_RETRY_DELAY_MS_MAX = Duration.ofHours(1).toMillis();
  static final long SHUTDOWN_AWAIT_TERMINATION_MS_MAX = Duration.ofMinutes(5).toMillis();
  static final long RETENTION_INTERVAL_MS_MAX = Duration.ofDays(1).toMillis();
  static final long RETENTION_AGE_MS_MAX = Duration.ofDays(365).toMillis();
  static final int RETENTION_BATCH_SIZE_MAX = STORE_QUERY_LIMIT_MAX;
  // Conservative portable bounds for MySQL DATETIME after JDBC/default-timezone conversion.
  static final Instant DATABASE_TIMESTAMP_MIN = Instant.parse("1000-01-02T00:00:00Z");
  static final Instant DATABASE_TIMESTAMP_MAX = Instant.parse("9999-12-31T00:00:00Z");
  static final String STORE_IN_MEMORY = "in-memory";
  static final String STORE_JDBC = "jdbc";

  private AsyncJobQueueValidation() {}

  static AsyncJobQueueProperties validateProperties(
      AsyncJobQueueProperties asyncJobQueueProperties) {
    Objects.requireNonNull(asyncJobQueueProperties);
    String store = validateStore(asyncJobQueueProperties.getStore());
    validateStatusMetricsIntervalMs(asyncJobQueueProperties.getStatusMetricsIntervalMs());
    validateRetentionSettings(asyncJobQueueProperties.getRetention());
    if (STORE_JDBC.equals(store)) {
      AsyncJobQueueJdbcDialect.fromConfig(asyncJobQueueProperties.getJdbcDialect());
    }
    for (Map.Entry<String, AsyncJobQueueProperties.QueueSettings> queueEntry :
        asyncJobQueueProperties.getQueues().entrySet()) {
      validateQueueName(queueEntry.getKey());
      if (queueEntry.getValue() != null) {
        validateQueueSettings(queueEntry.getValue());
      }
    }
    return asyncJobQueueProperties;
  }

  static String validateStore(String store) {
    Objects.requireNonNull(store);
    if (store.isBlank()) {
      throw new IllegalArgumentException("store must not be blank");
    }
    if (!STORE_IN_MEMORY.equals(store) && !STORE_JDBC.equals(store)) {
      throw new IllegalArgumentException(
          "store must be one of: " + STORE_IN_MEMORY + ", " + STORE_JDBC);
    }
    return store;
  }

  static long validateStatusMetricsIntervalMs(long statusMetricsIntervalMs) {
    if (statusMetricsIntervalMs <= 0) {
      throw new IllegalArgumentException("statusMetricsIntervalMs must be > 0");
    }
    if (statusMetricsIntervalMs > STATUS_METRICS_INTERVAL_MS_MAX) {
      throw new IllegalArgumentException(
          "statusMetricsIntervalMs must be <= " + STATUS_METRICS_INTERVAL_MS_MAX);
    }
    return statusMetricsIntervalMs;
  }

  static String validateQueueName(String queueName) {
    Objects.requireNonNull(queueName);
    if (queueName.isBlank()) {
      throw new IllegalArgumentException("queueName must not be blank");
    }
    if (queueName.length() > QUEUE_NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "queueName must be at most " + QUEUE_NAME_MAX_LENGTH + " characters");
    }
    if (!QUEUE_NAME_PATTERN.matcher(queueName).matches()) {
      throw new IllegalArgumentException(
          "queueName must contain only letters, numbers, dots, underscores, or dashes");
    }
    return queueName;
  }

  static String validateWorkerId(String workerId) {
    Objects.requireNonNull(workerId);
    if (workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must not be blank");
    }
    if (workerId.length() > WORKER_ID_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "workerId must be at most " + WORKER_ID_MAX_LENGTH + " characters");
    }
    return workerId;
  }

  static Instant plusDuration(String fieldName, Instant base, Duration duration) {
    Objects.requireNonNull(fieldName);
    Objects.requireNonNull(base);
    Objects.requireNonNull(duration);
    try {
      return base.plus(duration);
    } catch (DateTimeException | ArithmeticException e) {
      throw new IllegalArgumentException(
          fieldName + " is outside the supported timestamp range", e);
    }
  }

  static Instant plusDurationWithinDatabaseTimestampRange(
      String fieldName, Instant base, Duration duration) {
    return validateDatabaseTimestamp(fieldName, plusDuration(fieldName, base, duration));
  }

  static Instant validateDatabaseTimestamp(String fieldName, Instant instant) {
    Objects.requireNonNull(fieldName);
    Objects.requireNonNull(instant);
    if (instant.isBefore(DATABASE_TIMESTAMP_MIN) || instant.isAfter(DATABASE_TIMESTAMP_MAX)) {
      throw new IllegalArgumentException(
          fieldName
              + " must be between "
              + DATABASE_TIMESTAMP_MIN
              + " and "
              + DATABASE_TIMESTAMP_MAX
              + " for async job queue JDBC storage");
    }
    return instant;
  }

  static String truncateLastError(String lastError) {
    if (lastError == null || lastError.length() <= LAST_ERROR_MAX_LENGTH) {
      return lastError;
    }
    return lastError.substring(0, LAST_ERROR_MAX_LENGTH);
  }

  static String validateFailureLastError(String lastError) {
    String truncatedLastError = truncateLastError(Objects.requireNonNull(lastError));
    if (truncatedLastError.isBlank()) {
      throw new IllegalArgumentException("lastError must not be blank for failed async jobs");
    }
    return truncatedLastError;
  }

  static AsyncJobStatus validateTerminalStatus(AsyncJobStatus status) {
    Objects.requireNonNull(status);
    if (status != AsyncJobStatus.DONE && status != AsyncJobStatus.FAILED) {
      throw new IllegalArgumentException("status must be terminal: DONE or FAILED");
    }
    return status;
  }

  static int validateStoreQueryLimit(String fieldName, int limit) {
    Objects.requireNonNull(fieldName);
    if (limit <= 0) {
      throw new IllegalArgumentException(fieldName + " must be > 0");
    }
    if (limit > STORE_QUERY_LIMIT_MAX) {
      throw new IllegalArgumentException(fieldName + " must be <= " + STORE_QUERY_LIMIT_MAX);
    }
    return limit;
  }

  static AsyncJobQueueProperties.RetentionSettings validateRetentionSettings(
      AsyncJobQueueProperties.RetentionSettings retentionSettings) {
    Objects.requireNonNull(retentionSettings);
    validateRetentionDurationMs("retention.intervalMs", retentionSettings.getIntervalMs());
    if (retentionSettings.getIntervalMs() > RETENTION_INTERVAL_MS_MAX) {
      throw new IllegalArgumentException(
          "retention.intervalMs must be <= " + RETENTION_INTERVAL_MS_MAX);
    }
    validateRetentionDurationMs(
        "retention.doneRetentionMs", retentionSettings.getDoneRetentionMs());
    validateRetentionDurationMs(
        "retention.failedRetentionMs", retentionSettings.getFailedRetentionMs());
    if (retentionSettings.getDoneRetentionMs() > RETENTION_AGE_MS_MAX) {
      throw new IllegalArgumentException(
          "retention.doneRetentionMs must be <= " + RETENTION_AGE_MS_MAX);
    }
    if (retentionSettings.getFailedRetentionMs() > RETENTION_AGE_MS_MAX) {
      throw new IllegalArgumentException(
          "retention.failedRetentionMs must be <= " + RETENTION_AGE_MS_MAX);
    }
    if (retentionSettings.getBatchSize() <= 0) {
      throw new IllegalArgumentException("retention.batchSize must be > 0");
    }
    if (retentionSettings.getBatchSize() > RETENTION_BATCH_SIZE_MAX) {
      throw new IllegalArgumentException(
          "retention.batchSize must be <= " + RETENTION_BATCH_SIZE_MAX);
    }
    return retentionSettings;
  }

  static AsyncJobQueueProperties.QueueSettings validateQueueSettings(
      AsyncJobQueueProperties.QueueSettings queueSettings) {
    Objects.requireNonNull(queueSettings);
    if (queueSettings.getPollIntervalMs() <= 0) {
      throw new IllegalArgumentException("pollIntervalMs must be > 0");
    }
    if (queueSettings.getPollIntervalMs() > POLL_INTERVAL_MS_MAX) {
      throw new IllegalArgumentException("pollIntervalMs must be <= " + POLL_INTERVAL_MS_MAX);
    }
    if (queueSettings.getMaxPollIntervalMs() <= 0) {
      throw new IllegalArgumentException("maxPollIntervalMs must be > 0");
    }
    if (queueSettings.getMaxPollIntervalMs() > MAX_POLL_INTERVAL_MS_MAX) {
      throw new IllegalArgumentException(
          "maxPollIntervalMs must be <= " + MAX_POLL_INTERVAL_MS_MAX);
    }
    if (queueSettings.getClaimBatchSize() <= 0) {
      throw new IllegalArgumentException("claimBatchSize must be > 0");
    }
    if (queueSettings.getClaimBatchSize() > CLAIM_BATCH_SIZE_MAX) {
      throw new IllegalArgumentException("claimBatchSize must be <= " + CLAIM_BATCH_SIZE_MAX);
    }
    if (queueSettings.getMaxConcurrency() <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be > 0");
    }
    if (queueSettings.getMaxConcurrency() > MAX_CONCURRENCY_MAX) {
      throw new IllegalArgumentException("maxConcurrency must be <= " + MAX_CONCURRENCY_MAX);
    }
    if (queueSettings.getLeaseDurationMs() <= 0) {
      throw new IllegalArgumentException("leaseDurationMs must be > 0");
    }
    if (queueSettings.getLeaseDurationMs() > LEASE_DURATION_MS_MAX) {
      throw new IllegalArgumentException("leaseDurationMs must be <= " + LEASE_DURATION_MS_MAX);
    }
    if (queueSettings.getMaxPollIntervalMs() < queueSettings.getPollIntervalMs()) {
      throw new IllegalArgumentException("maxPollIntervalMs must be >= pollIntervalMs");
    }
    if (queueSettings.getHeartbeatIntervalMs() < 0) {
      throw new IllegalArgumentException("heartbeatIntervalMs must be >= 0");
    }
    if (queueSettings.getHeartbeatIntervalMs() > HEARTBEAT_INTERVAL_MS_MAX) {
      throw new IllegalArgumentException(
          "heartbeatIntervalMs must be <= " + HEARTBEAT_INTERVAL_MS_MAX);
    }
    if (queueSettings.getHeartbeatIntervalMs() >= queueSettings.getLeaseDurationMs()
        && queueSettings.getHeartbeatIntervalMs() > 0) {
      throw new IllegalArgumentException("heartbeatIntervalMs must be < leaseDurationMs");
    }
    if (queueSettings.getMaxAttempts() <= 0) {
      throw new IllegalArgumentException("maxAttempts must be > 0");
    }
    if (queueSettings.getMaxAttempts() > MAX_ATTEMPTS_MAX) {
      throw new IllegalArgumentException("maxAttempts must be <= " + MAX_ATTEMPTS_MAX);
    }
    if (queueSettings.getPollJitterPercent() < 0 || queueSettings.getPollJitterPercent() > 100) {
      throw new IllegalArgumentException("pollJitterPercent must be between 0 and 100");
    }
    if (queueSettings.getMaxRetryDelayMs() <= 0) {
      throw new IllegalArgumentException("maxRetryDelayMs must be > 0");
    }
    if (queueSettings.getMaxRetryDelayMs() > MAX_RETRY_DELAY_MS_MAX) {
      throw new IllegalArgumentException("maxRetryDelayMs must be <= " + MAX_RETRY_DELAY_MS_MAX);
    }
    if (queueSettings.getRetryJitterPercent() < 0 || queueSettings.getRetryJitterPercent() > 100) {
      throw new IllegalArgumentException("retryJitterPercent must be between 0 and 100");
    }
    if (queueSettings.getShutdownAwaitTerminationMs() < 0) {
      throw new IllegalArgumentException("shutdownAwaitTerminationMs must be >= 0");
    }
    if (queueSettings.getShutdownAwaitTerminationMs() > SHUTDOWN_AWAIT_TERMINATION_MS_MAX) {
      throw new IllegalArgumentException(
          "shutdownAwaitTerminationMs must be <= " + SHUTDOWN_AWAIT_TERMINATION_MS_MAX);
    }
    return queueSettings;
  }

  private static long validateRetentionDurationMs(String fieldName, long durationMs) {
    if (durationMs <= 0) {
      throw new IllegalArgumentException(fieldName + " must be > 0");
    }
    return durationMs;
  }
}
