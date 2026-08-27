package com.box.l10n.mojito.translationintegrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranslationIntegrityDiagnosticTest {

  private static final String BMP_KEY = "\uE000";
  private static final String SUPPLEMENTARY_KEY = "\uD800\uDC00";

  @Test
  void recursivelyOrdersDetailKeysByUnicodeCodePoint() {
    TranslationIntegrityDiagnostic diagnostic =
        TranslationIntegrityDiagnostic.targetError(
            "diagnostic",
            Map.of(
                SUPPLEMENTARY_KEY,
                "supplementary",
                BMP_KEY,
                Map.of(SUPPLEMENTARY_KEY, "supplementary", BMP_KEY, "bmp")));

    assertThat(diagnostic.details().keySet()).containsExactly(BMP_KEY, SUPPLEMENTARY_KEY);
    assertThat(
            ((Map<?, ?>) diagnostic.details().get(BMP_KEY))
                .keySet().stream().map(Object::toString).toList())
        .containsExactly(BMP_KEY, SUPPLEMENTARY_KEY);
  }

  @Test
  void canonicalOrderingUsesUnicodeCodePointOrderForDetailKeys() {
    TranslationIntegrityDiagnostic first =
        TranslationIntegrityDiagnostic.targetError(
            "diagnostic", Map.of(BMP_KEY, "a", SUPPLEMENTARY_KEY, "b"));
    TranslationIntegrityDiagnostic second =
        TranslationIntegrityDiagnostic.targetError(
            "diagnostic", Map.of(BMP_KEY, "b", SUPPLEMENTARY_KEY, "a"));

    assertThat(
            List.of(second, first).stream().sorted(TranslationIntegrityDiagnostic.CANONICAL_ORDER))
        .containsExactly(first, second);
  }
}
