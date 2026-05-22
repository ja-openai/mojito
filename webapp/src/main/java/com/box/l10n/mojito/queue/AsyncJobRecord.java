package com.box.l10n.mojito.queue;

import java.time.Instant;

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
}
