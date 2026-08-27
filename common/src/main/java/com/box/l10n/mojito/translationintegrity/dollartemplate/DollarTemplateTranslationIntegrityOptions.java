package com.box.l10n.mojito.translationintegrity.dollartemplate;

/** Immutable feature selection for dollar-template translation-integrity evaluation. */
public record DollarTemplateTranslationIntegrityOptions(
    boolean richTextTags, boolean boundaryWhitespace, boolean emailLiterals, boolean urlLiterals) {

  /** Complete common cutover selection; FormatJS apostrophe handling is intentionally absent. */
  public static DollarTemplateTranslationIntegrityOptions common() {
    return new DollarTemplateTranslationIntegrityOptions(true, true, true, true);
  }
}
