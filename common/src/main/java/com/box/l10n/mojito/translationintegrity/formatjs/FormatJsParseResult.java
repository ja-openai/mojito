package com.box.l10n.mojito.translationintegrity.formatjs;

import java.util.List;
import java.util.Objects;

/** Result facade corresponding to FormatJS's internal parser result union. */
public record FormatJsParseResult(List<FormatJsElement> value, FormatJsParseError error) {

  public FormatJsParseResult {
    if ((value == null) == (error == null)) {
      throw new IllegalArgumentException("exactly one of value and error must be present");
    }
    if (value != null) {
      value = List.copyOf(value);
    }
  }

  public static FormatJsParseResult success(List<FormatJsElement> value) {
    return new FormatJsParseResult(Objects.requireNonNull(value, "value"), null);
  }

  public static FormatJsParseResult failure(FormatJsParseError error) {
    return new FormatJsParseResult(null, Objects.requireNonNull(error, "error"));
  }

  public boolean isSuccess() {
    return error == null;
  }

  public List<FormatJsElement> orElseThrow() {
    if (error != null) {
      throw new FormatJsParseException(error);
    }
    return value;
  }
}
