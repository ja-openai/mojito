package com.box.l10n.mojito.queue;

import java.util.Objects;

public record AsyncJobId(String value) {

  public AsyncJobId {
    Objects.requireNonNull(value);
    if (value.isBlank()) {
      throw new IllegalArgumentException("Async job id must not be blank");
    }
    try {
      if (Long.parseLong(value) <= 0) {
        throw new IllegalArgumentException("Async job id must be a positive numeric string");
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Async job id must be a positive numeric string", exception);
    }
  }
}
