package com.box.l10n.mojito.queue;

import java.util.Objects;

public record AsyncJobId(String value) {

  public AsyncJobId {
    Objects.requireNonNull(value);
    if (value.isBlank()) {
      throw new IllegalArgumentException("Async job id must not be blank");
    }
  }
}
