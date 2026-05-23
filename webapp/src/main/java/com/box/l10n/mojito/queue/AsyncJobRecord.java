package com.box.l10n.mojito.queue;

import java.time.Instant;
import java.util.Objects;

public record AsyncJobRecord(
    AsyncJobId id,
    String queueName,
    AsyncJobStatus status,
    Instant availableAt,
    Instant leaseUntil,
    String workerId,
    String leaseToken,
    String jobData,
    int attemptCount,
    String lastError,
    Instant createdDate,
    Instant updatedDate,
    boolean leaseReclaimed) {

  public AsyncJobRecord {
    Objects.requireNonNull(id);
    AsyncJobQueueValidation.validateQueueName(queueName);
    Objects.requireNonNull(status);
    Objects.requireNonNull(availableAt);
    jobData = AsyncJobQueueValidation.validateJobData(jobData);
    if (attemptCount < 0) {
      throw new IllegalArgumentException("attemptCount must be >= 0");
    }
    if (attemptCount > AsyncJobQueueValidation.STORED_ATTEMPT_COUNT_MAX) {
      throw new IllegalArgumentException(
          "attemptCount must be <= " + AsyncJobQueueValidation.STORED_ATTEMPT_COUNT_MAX);
    }
    Objects.requireNonNull(createdDate);
    Objects.requireNonNull(updatedDate);
    lastError = AsyncJobQueueValidation.truncateLastError(lastError);

    if (status == AsyncJobStatus.RUNNING) {
      Objects.requireNonNull(leaseUntil);
      AsyncJobQueueValidation.validateWorkerId(workerId);
      validateLeaseToken(leaseToken);
    } else if (leaseUntil != null || workerId != null || leaseToken != null) {
      throw new IllegalArgumentException("only running async jobs can have a lease owner");
    }
    if (status == AsyncJobStatus.FAILED) {
      lastError = AsyncJobQueueValidation.validateFailureLastError(lastError);
    }
  }

  public AsyncJobRecord withLeaseReclaimed(boolean leaseReclaimed) {
    return new AsyncJobRecord(
        id,
        queueName,
        status,
        availableAt,
        leaseUntil,
        workerId,
        leaseToken,
        jobData,
        attemptCount,
        lastError,
        createdDate,
        updatedDate,
        leaseReclaimed);
  }

  private static void validateLeaseToken(String leaseToken) {
    Objects.requireNonNull(leaseToken);
    if (leaseToken.isBlank()) {
      throw new IllegalArgumentException("leaseToken must not be blank");
    }
  }
}
