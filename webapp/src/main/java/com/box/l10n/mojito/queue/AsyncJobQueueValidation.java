package com.box.l10n.mojito.queue;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

final class AsyncJobQueueValidation {

  static final int QUEUE_NAME_MAX_LENGTH = 64;
  static final int WORKER_ID_MAX_LENGTH = 128;
  static final int LAST_ERROR_MAX_LENGTH = 4_000;
  static final int CLAIM_BATCH_SIZE_MAX = 1_000;
  static final int MAX_CONCURRENCY_MAX = 256;
  static final int STORE_QUERY_LIMIT_MAX = 1_000;
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

  static AsyncJobQueueProperties.QueueSettings validateQueueSettings(
      AsyncJobQueueProperties.QueueSettings queueSettings) {
    Objects.requireNonNull(queueSettings);
    if (queueSettings.getPollIntervalMs() <= 0) {
      throw new IllegalArgumentException("pollIntervalMs must be > 0");
    }
    if (queueSettings.getMaxPollIntervalMs() <= 0) {
      throw new IllegalArgumentException("maxPollIntervalMs must be > 0");
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
    if (queueSettings.getMaxPollIntervalMs() < queueSettings.getPollIntervalMs()) {
      throw new IllegalArgumentException("maxPollIntervalMs must be >= pollIntervalMs");
    }
    if (queueSettings.getHeartbeatIntervalMs() < 0) {
      throw new IllegalArgumentException("heartbeatIntervalMs must be >= 0");
    }
    if (queueSettings.getHeartbeatIntervalMs() >= queueSettings.getLeaseDurationMs()
        && queueSettings.getHeartbeatIntervalMs() > 0) {
      throw new IllegalArgumentException("heartbeatIntervalMs must be < leaseDurationMs");
    }
    if (queueSettings.getMaxAttempts() <= 0) {
      throw new IllegalArgumentException("maxAttempts must be > 0");
    }
    if (queueSettings.getPollJitterPercent() < 0 || queueSettings.getPollJitterPercent() > 100) {
      throw new IllegalArgumentException("pollJitterPercent must be between 0 and 100");
    }
    if (queueSettings.getMaxRetryDelayMs() <= 0) {
      throw new IllegalArgumentException("maxRetryDelayMs must be > 0");
    }
    if (queueSettings.getRetryJitterPercent() < 0 || queueSettings.getRetryJitterPercent() > 100) {
      throw new IllegalArgumentException("retryJitterPercent must be between 0 and 100");
    }
    return queueSettings;
  }
}
