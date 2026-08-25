package com.box.l10n.mojito.translationintegrity.formatjs;

import java.util.Objects;

/** Non-throwing parse error model derived from FormatJS parser 3.5.10. */
public record FormatJsParseError(
    FormatJsParseErrorKind kind, String originalMessage, FormatJsSourceLocation location) {

  public FormatJsParseError {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(originalMessage, "originalMessage");
    Objects.requireNonNull(location, "location");
  }
}
