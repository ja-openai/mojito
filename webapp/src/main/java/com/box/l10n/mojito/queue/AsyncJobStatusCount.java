package com.box.l10n.mojito.queue;

import java.util.Objects;

public record AsyncJobStatusCount(AsyncJobStatus status, long count) {

  public AsyncJobStatusCount {
    Objects.requireNonNull(status);
    if (count < 0) {
      throw new IllegalArgumentException("count must be >= 0");
    }
  }
}
