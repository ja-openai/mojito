package com.box.l10n.mojito.translationintegrity.formatjs;

import java.util.Objects;

/** Source range model derived from {@code @formatjs/icu-messageformat-parser} 3.5.10. */
public record FormatJsSourceLocation(FormatJsSourcePosition start, FormatJsSourcePosition end) {

  public FormatJsSourceLocation {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    if (end.offset() < start.offset()) {
      throw new IllegalArgumentException("location end must not precede its start");
    }
  }
}
