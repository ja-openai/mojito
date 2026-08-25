package com.box.l10n.mojito.translationintegrity.formatjs;

import java.util.Objects;

/**
 * Converts FormatJS's UTF-16 parser offsets into the Unicode code-point offsets used by Mojito's
 * translation-integrity diagnostics.
 */
public final class FormatJsCodePointRanges {

  private FormatJsCodePointRanges() {}

  public static int toCodePointOffset(String message, int utf16Offset) {
    Objects.requireNonNull(message, "message");
    if (utf16Offset < 0 || utf16Offset > message.length()) {
      throw new IllegalArgumentException("UTF-16 offset is outside the message");
    }
    if (utf16Offset > 0
        && utf16Offset < message.length()
        && Character.isHighSurrogate(message.charAt(utf16Offset - 1))
        && Character.isLowSurrogate(message.charAt(utf16Offset))) {
      throw new IllegalArgumentException("UTF-16 offset is inside a surrogate pair");
    }
    return message.codePointCount(0, utf16Offset);
  }

  public static CodePointRange toCodePointRange(String message, FormatJsSourceLocation location) {
    Objects.requireNonNull(location, "location");
    return new CodePointRange(
        toCodePointOffset(message, location.start().offset()),
        toCodePointOffset(message, location.end().offset()));
  }

  /** Zero-based, half-open code-point range. */
  public record CodePointRange(int start, int end) {

    public CodePointRange {
      if (start < 0 || end < start) {
        throw new IllegalArgumentException("invalid code-point range");
      }
    }
  }
}
