package com.box.l10n.mojito.queue;

import java.time.Instant;
import java.util.Objects;

public record AsyncJobExpiredLeaseStatus(long count, Instant oldestLeaseUntil, Instant observedAt) {

  public AsyncJobExpiredLeaseStatus {
    if (count < 0) {
      throw new IllegalArgumentException("count must be >= 0");
    }
    Objects.requireNonNull(observedAt);
    if (count == 0 && oldestLeaseUntil != null) {
      throw new IllegalArgumentException("oldestLeaseUntil must be null when count is 0");
    }
    if (count > 0) {
      Objects.requireNonNull(oldestLeaseUntil);
      if (oldestLeaseUntil.isAfter(observedAt)) {
        throw new IllegalArgumentException("oldestLeaseUntil must not be after observedAt");
      }
    }
  }
}
