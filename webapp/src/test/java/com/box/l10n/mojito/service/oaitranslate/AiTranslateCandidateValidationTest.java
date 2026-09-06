package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiTranslateCandidateValidationTest {
  @Test
  public void unrecognizedFormatsDoNotAcquireIcuRequirements() {
    for (String source : List.of("Plain text", "{{template}}", "Literal { unfinished")) {
      TextUnitDTO unit = new TextUnitDTO();
      unit.setSource(source);
      assertThat(
              AiTranslateCandidateValidation.evaluate(unit, "Literal { unfinished", "ar")
                  .disposition())
          .isEqualTo(TranslationIntegrityDisposition.PASS);
    }
  }

  @Test
  public void sourceErrorsPreventRepairEvenWhenThereAreTargetErrors() {
    var evaluation =
        new TranslationIntegrityEvaluation(
            List.of(
                TranslationIntegrityDiagnostic.sourceError("source-input-too-long", Map.of()),
                TranslationIntegrityDiagnostic.targetError("target-input-too-long", Map.of())),
            TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(AiTranslateCandidateValidation.rejected(evaluation)).isTrue();
    assertThat(AiTranslateCandidateValidation.repairable(evaluation)).isFalse();
  }

  @Test
  public void explicitMf2MetadataRoutesSimplePatterns() {
    TextUnitDTO unit = new TextUnitDTO();
    unit.setSource("Hello {$name}");
    unit.setMessageFormat("MF2");
    var result = AiTranslateCandidateValidation.evaluate(unit, "Hello {$invented}", "ar");
    assertThat(AiTranslateCandidateValidation.repairable(result)).isTrue();
    assertThat(result.diagnostics())
        .anySatisfy(d -> assertThat(d.code()).isEqualTo("mf2-unknown-binding"));
  }
}
