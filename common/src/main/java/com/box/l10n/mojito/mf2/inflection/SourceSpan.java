package com.box.l10n.mojito.mf2.inflection;

/**
 * End-exclusive source range for an MF2 expression.
 *
 * <p>Offsets are Java {@link String} offsets, i.e. UTF-16 code-unit indexes into the original
 * message source. They are intended for substring/highlight diagnostics and are not byte,
 * code-point, or grapheme-cluster offsets.
 */
public record SourceSpan(int start, int end) {

  public SourceSpan {
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("Invalid source span: " + start + "-" + end);
    }
  }

  public String format() {
    return start + "-" + end;
  }
}
