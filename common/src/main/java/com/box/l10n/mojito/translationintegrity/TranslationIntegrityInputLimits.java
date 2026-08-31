package com.box.l10n.mojito.translationintegrity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared hard bounds applied before parser-backed translation-integrity evaluation. */
public final class TranslationIntegrityInputLimits {

  public static final int MAX_UTF16_CODE_UNITS = 65_536;

  private TranslationIntegrityInputLimits() {}

  public static TranslationIntegrityEvaluation evaluate(String source, String target) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");

    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>(2);
    if (source.length() > MAX_UTF16_CODE_UNITS) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.sourceError(
              "source-input-too-long",
              Map.of(
                  "actualUtf16CodeUnits",
                  source.length(),
                  "maximumUtf16CodeUnits",
                  MAX_UTF16_CODE_UNITS)));
    }
    if (target.length() > MAX_UTF16_CODE_UNITS) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "target-input-too-long",
              Map.of(
                  "actualUtf16CodeUnits",
                  target.length(),
                  "maximumUtf16CodeUnits",
                  MAX_UTF16_CODE_UNITS)));
    }
    if (diagnostics.isEmpty()) {
      return TranslationIntegrityEvaluation.pass();
    }
    TranslationIntegrityDisposition disposition =
        target.length() > MAX_UTF16_CODE_UNITS
            ? TranslationIntegrityDisposition.REJECT_TARGET
            : TranslationIntegrityDisposition.REJECT_SOURCE;
    return new TranslationIntegrityEvaluation(diagnostics, disposition);
  }
}
