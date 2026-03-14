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
    Instant createdDate,
    Instant updatedDate) {}
