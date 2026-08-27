package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TranslationIntegrityCheckerExceptionTest {

  @Test
  public void allowsPassExemptionAndSourceRejection() {
    TranslationIntegrityCheckerException.throwIfTargetRejected(
        "TEST", TranslationIntegrityEvaluation.pass());
    TranslationIntegrityCheckerException.throwIfTargetRejected(
        "TEST",
        new TranslationIntegrityEvaluation(List.of(), TranslationIntegrityDisposition.EXEMPT));
    TranslationIntegrityCheckerException.throwIfTargetRejected(
        "TEST",
        new TranslationIntegrityEvaluation(
            List.of(
                TranslationIntegrityDiagnostic.sourceError(
                    "source-format-invalid", Map.of("reason", "test"))),
            TranslationIntegrityDisposition.REJECT_SOURCE));
  }

  @Test
  public void diagnosticMessageIsDeterministicAndBounded() {
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    for (int index = 99; index >= 0; index--) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "diagnostic-code-%03d-with-padding".formatted(index), Map.of()));
    }
    TranslationIntegrityEvaluation evaluation =
        new TranslationIntegrityEvaluation(
            diagnostics, TranslationIntegrityDisposition.REJECT_TARGET);

    TranslationIntegrityCheckerException first =
        new TranslationIntegrityCheckerException("TEST", evaluation);
    TranslationIntegrityCheckerException second =
        new TranslationIntegrityCheckerException("TEST", evaluation);

    assertEquals(first.getMessage(), second.getMessage());
    assertTrue(first.getMessage().contains("diagnostic-code-000-with-padding"));
    assertTrue(first.getMessage().endsWith(", ..."));
    assertTrue(
        first.getMessage().length() <= TranslationIntegrityCheckerException.MAX_MESSAGE_LENGTH);
  }
}
