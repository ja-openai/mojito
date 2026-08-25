package com.box.l10n.mojito.translationintegrity.formatjs;

import java.util.Objects;

/** Exception facade corresponding to FormatJS's throwing {@code parse()} function. */
public final class FormatJsParseException extends IllegalArgumentException {

  private final FormatJsParseError error;

  public FormatJsParseException(FormatJsParseError error) {
    super(Objects.requireNonNull(error, "error").kind().name());
    this.error = error;
  }

  public FormatJsParseError error() {
    return error;
  }
}
