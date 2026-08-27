package com.box.l10n.mojito.translationintegrity;

/** Outcome for a source/target translation-integrity evaluation. */
public enum TranslationIntegrityDisposition {
  PASS,
  AUTO_REPAIR_TARGET,
  REJECT_TARGET,
  REJECT_SOURCE,
  EXEMPT
}
