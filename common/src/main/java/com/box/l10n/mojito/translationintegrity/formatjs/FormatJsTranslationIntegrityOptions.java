package com.box.l10n.mojito.translationintegrity.formatjs;

/** Immutable feature selection for FormatJS translation-integrity evaluation. */
public record FormatJsTranslationIntegrityOptions(
    boolean richTextTags,
    boolean boundaryWhitespace,
    boolean emailLiterals,
    boolean urlLiterals,
    boolean apostropheBeforeTag) {

  public FormatJsTranslationIntegrityOptions {
    if (apostropheBeforeTag && !richTextTags) {
      throw new IllegalArgumentException("apostropheBeforeTag requires richTextTags=true");
    }
  }

  /** Complete web cutover selection with parser-aware apostrophe rejection. */
  public static FormatJsTranslationIntegrityOptions web() {
    return new FormatJsTranslationIntegrityOptions(true, true, true, true, true);
  }
}
