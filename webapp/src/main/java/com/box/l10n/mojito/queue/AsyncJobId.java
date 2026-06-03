package com.box.l10n.mojito.queue;

import java.util.Objects;
import java.util.regex.Pattern;

public record AsyncJobId(String value) {

  private static final Pattern CANONICAL_POSITIVE_NUMERIC = Pattern.compile("[1-9][0-9]*");

  public AsyncJobId {
    Objects.requireNonNull(value);
    if (value.isBlank()) {
      throw new IllegalArgumentException("Async job id must not be blank");
    }
    if (!CANONICAL_POSITIVE_NUMERIC.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Async job id must be a canonical positive numeric string");
    }
    try {
      Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Async job id must be a canonical positive numeric string", exception);
    }
  }
}
