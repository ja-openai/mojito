package com.box.l10n.mojito.translationintegrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Subject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranslationIntegrityEvaluationTest {

  @Test
  void modelsTheCompleteConformanceEnvelopeImmutably() {
    TranslationIntegrityDiagnostic boundaryDiagnostic =
        TranslationIntegrityDiagnostic.targetError(
            "boundary-whitespace-mismatch", Map.of("actualLeading", ""));
    TranslationIntegrityDiagnostic variableDiagnostic =
        TranslationIntegrityDiagnostic.targetError(
            "variable-missing", Map.of("names", List.of("name")));
    TranslationIntegrityDiagnostic reviewDiagnostic =
        new TranslationIntegrityDiagnostic(
            "semantic-review-required",
            Severity.WARNING,
            Subject.TARGET,
            Map.of("finding", "meaning-review"),
            null);

    List<TranslationIntegrityDiagnostic> diagnostics =
        new ArrayList<>(List.of(variableDiagnostic, boundaryDiagnostic));
    List<TranslationIntegrityDiagnostic> policyDiagnostics =
        new ArrayList<>(List.of(reviewDiagnostic));
    List<TranslationIntegrityRepairOperation> operations =
        new ArrayList<>(
            List.of(TranslationIntegrityRepairOperation.COPY_SOURCE_BOUNDARY_WHITESPACE));
    TranslationIntegritySafeRepair safeRepair =
        new TranslationIntegritySafeRepair(
            operations, " TARGET ", List.of(), List.of(reviewDiagnostic));

    TranslationIntegrityEvaluation evaluation =
        new TranslationIntegrityEvaluation(
            diagnostics,
            policyDiagnostics,
            TranslationIntegrityDisposition.AUTO_REPAIR_TARGET,
            TranslationIntegrityReviewDisposition.REVIEW_REQUIRED,
            safeRepair);
    diagnostics.clear();
    policyDiagnostics.clear();
    operations.clear();

    assertThat(evaluation.diagnostics())
        .containsExactly(boundaryDiagnostic, variableDiagnostic)
        .isUnmodifiable();
    assertThat(evaluation.policyDiagnostics()).containsExactly(reviewDiagnostic).isUnmodifiable();
    assertThat(evaluation.disposition())
        .isEqualTo(TranslationIntegrityDisposition.AUTO_REPAIR_TARGET);
    assertThat(evaluation.reviewDisposition())
        .isEqualTo(TranslationIntegrityReviewDisposition.REVIEW_REQUIRED);
    assertThat(evaluation.safeRepair()).isEqualTo(safeRepair);
    assertThat(safeRepair.operations())
        .containsExactly(TranslationIntegrityRepairOperation.COPY_SOURCE_BOUNDARY_WHITESPACE)
        .isUnmodifiable();
    assertThat(safeRepair.expectedPolicyDiagnostics()).containsExactly(reviewDiagnostic);
  }

  @Test
  void modelsAnExemptWaiverWithoutARepair() {
    TranslationIntegrityDiagnostic boundaryDiagnostic =
        TranslationIntegrityDiagnostic.targetError(
            "boundary-whitespace-mismatch", Map.of("actualLeading", ""));
    TranslationIntegrityDiagnostic waiverDiagnostic =
        new TranslationIntegrityDiagnostic(
            "check-waived",
            Severity.INFO,
            Subject.POLICY,
            Map.of("rule", "boundary-whitespace", "waiverCount", 1),
            null);

    TranslationIntegrityEvaluation evaluation =
        new TranslationIntegrityEvaluation(
            List.of(boundaryDiagnostic),
            List.of(waiverDiagnostic),
            TranslationIntegrityDisposition.EXEMPT,
            null,
            null);

    assertThat(evaluation.diagnostics()).containsExactly(boundaryDiagnostic);
    assertThat(evaluation.policyDiagnostics()).containsExactly(waiverDiagnostic);
    assertThat(evaluation.disposition()).isEqualTo(TranslationIntegrityDisposition.EXEMPT);
    assertThat(evaluation.safeRepair()).isNull();
  }

  @Test
  void keepsTheTwoArgumentStructuralConstructor() {
    TranslationIntegrityEvaluation evaluation =
        new TranslationIntegrityEvaluation(List.of(), TranslationIntegrityDisposition.PASS);

    assertThat(evaluation.policyDiagnostics()).isEmpty();
    assertThat(evaluation.reviewDisposition()).isNull();
    assertThat(evaluation.safeRepair()).isNull();
  }

  @Test
  void requiresRepairOutputExactlyForAutoRepairDisposition() {
    assertThatThrownBy(
            () ->
                new TranslationIntegrityEvaluation(
                    List.of(),
                    List.of(),
                    TranslationIntegrityDisposition.AUTO_REPAIR_TARGET,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresRepairPolicyDiagnosticsToMatchTheTopLevelOutcome() {
    TranslationIntegrityDiagnostic reviewDiagnostic =
        new TranslationIntegrityDiagnostic(
            "semantic-review-required",
            Severity.WARNING,
            Subject.TARGET,
            Map.of("finding", "meaning-review"),
            null);
    TranslationIntegritySafeRepair safeRepair =
        new TranslationIntegritySafeRepair(
            List.of(TranslationIntegrityRepairOperation.COPY_SOURCE_BOUNDARY_WHITESPACE),
            " TARGET ",
            List.of());

    assertThatThrownBy(
            () ->
                new TranslationIntegrityEvaluation(
                    List.of(),
                    List.of(reviewDiagnostic),
                    TranslationIntegrityDisposition.AUTO_REPAIR_TARGET,
                    TranslationIntegrityReviewDisposition.REVIEW_REQUIRED,
                    safeRepair))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedPolicyDiagnostics");
  }

  @Test
  void forbidsCombiningAnAutomaticRepairWithAWaiver() {
    TranslationIntegrityDiagnostic waiverDiagnostic =
        new TranslationIntegrityDiagnostic(
            "check-waived",
            Severity.INFO,
            Subject.POLICY,
            Map.of("rule", "boundary-whitespace", "waiverCount", 1),
            null);
    TranslationIntegritySafeRepair safeRepair =
        new TranslationIntegritySafeRepair(
            List.of(TranslationIntegrityRepairOperation.COPY_SOURCE_BOUNDARY_WHITESPACE),
            " TARGET ",
            List.of(),
            List.of(waiverDiagnostic));

    assertThatThrownBy(
            () ->
                new TranslationIntegrityEvaluation(
                    List.of(),
                    List.of(waiverDiagnostic),
                    TranslationIntegrityDisposition.AUTO_REPAIR_TARGET,
                    null,
                    safeRepair))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("waiver");
  }

  @Test
  void parsesEveryDiagnosticWireValueInTheConformanceEnvelope() {
    assertThat(Severity.fromWireValue("error")).isEqualTo(Severity.ERROR);
    assertThat(Severity.fromWireValue("warning")).isEqualTo(Severity.WARNING);
    assertThat(Severity.fromWireValue("info")).isEqualTo(Severity.INFO);
    assertThat(Subject.fromWireValue("source")).isEqualTo(Subject.SOURCE);
    assertThat(Subject.fromWireValue("target")).isEqualTo(Subject.TARGET);
    assertThat(Subject.fromWireValue("policy")).isEqualTo(Subject.POLICY);
  }
}
