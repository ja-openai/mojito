package com.box.l10n.mojito.queue;

import java.time.Instant;
import java.util.Objects;

public record AsyncJobReadyStatus(long count, Instant oldestAvailableAt, Instant observedAt) {

  public AsyncJobReadyStatus {
    if (count < 0) {
      throw new IllegalArgumentException("count must be >= 0");
    }
    Objects.requireNonNull(observedAt);
    if (count == 0 && oldestAvailableAt != null) {
      throw new IllegalArgumentException("oldestAvailableAt must be null when count is 0");
    }
    if (count > 0) {
      Objects.requireNonNull(oldestAvailableAt);
      if (oldestAvailableAt.isAfter(observedAt)) {
        throw new IllegalArgumentException("oldestAvailableAt must not be after observedAt");
      }
    }
  }
}
