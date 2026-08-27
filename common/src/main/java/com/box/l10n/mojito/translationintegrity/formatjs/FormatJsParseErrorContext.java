package com.box.l10n.mojito.translationintegrity.formatjs;

/** Parser stage used to normalize ambiguous raw FormatJS error kinds. */
public enum FormatJsParseErrorContext {
  GENERAL,
  ARGUMENT,
  TYPED_ARGUMENT,
  SELECT_ARGUMENT,
  SELECTOR_BRANCH
}
