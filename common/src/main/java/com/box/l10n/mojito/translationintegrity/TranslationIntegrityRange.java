package com.box.l10n.mojito.translationintegrity;

/** Zero-based, half-open Unicode code-point range. */
public record TranslationIntegrityRange(int start, int end) {

  public TranslationIntegrityRange {
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("range must satisfy 0 <= start <= end");
    }
  }
}
