package com.box.l10n.mojito.translationintegrity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TranslationIntegrityInputLimitsTest {

  @Test
  void acceptsTheExactUtf16Limit() {
    String value = "x".repeat(TranslationIntegrityInputLimits.MAX_UTF16_CODE_UNITS);

    assertThat(TranslationIntegrityInputLimits.evaluate(value, value))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void reportsSourceAndTargetLimitsWithoutIncludingInputText() {
    String oversized = "x".repeat(TranslationIntegrityInputLimits.MAX_UTF16_CODE_UNITS + 1);

    TranslationIntegrityEvaluation sourceOnly =
        TranslationIntegrityInputLimits.evaluate(oversized, "target");
    TranslationIntegrityEvaluation targetOnly =
        TranslationIntegrityInputLimits.evaluate("source", oversized);
    TranslationIntegrityEvaluation both =
        TranslationIntegrityInputLimits.evaluate(oversized, oversized);

    assertThat(sourceOnly.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_SOURCE);
    assertThat(sourceOnly.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .containsExactly("source-input-too-long");
    assertThat(targetOnly.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(targetOnly.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .containsExactly("target-input-too-long");
    assertThat(both.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(both.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .containsExactly("source-input-too-long", "target-input-too-long");
    assertThat(both.toString()).hasSizeLessThan(1_000);
  }
}
