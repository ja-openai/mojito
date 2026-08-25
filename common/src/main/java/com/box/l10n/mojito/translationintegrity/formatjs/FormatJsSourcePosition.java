package com.box.l10n.mojito.translationintegrity.formatjs;

/**
 * A position in a FormatJS message.
 *
 * <p>Semantics are pinned to {@code @formatjs/icu-messageformat-parser} 3.5.10: offsets are
 * zero-based UTF-16 code-unit offsets, while lines and columns are one-based and columns advance by
 * Unicode code point.
 */
public record FormatJsSourcePosition(int offset, int line, int column) {

  public FormatJsSourcePosition {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
    if (line < 1) {
      throw new IllegalArgumentException("line must be positive");
    }
    if (column < 1) {
      throw new IllegalArgumentException("column must be positive");
    }
  }
}
