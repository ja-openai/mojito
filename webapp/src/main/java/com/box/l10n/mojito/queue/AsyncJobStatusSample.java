package com.box.l10n.mojito.queue;

import java.util.List;
import java.util.Objects;

/** Queue counts and ages collected before publishing a monitoring sample. */
public record AsyncJobStatusSample(
    List<AsyncJobStatusCount> counts,
    AsyncJobReadyStatus ready,
    AsyncJobExpiredLeaseStatus expiredLeases) {
  public AsyncJobStatusSample {
    counts = List.copyOf(counts);
    Objects.requireNonNull(ready);
    Objects.requireNonNull(expiredLeases);
  }
}
