package com.box.l10n.mojito.translationintegrity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Complete, canonically ordered integrity and policy outcome for one source/target pair. */
public record TranslationIntegrityEvaluation(
    List<TranslationIntegrityDiagnostic> diagnostics,
    List<TranslationIntegrityDiagnostic> policyDiagnostics,
    TranslationIntegrityDisposition disposition,
    TranslationIntegrityReviewDisposition reviewDisposition,
    TranslationIntegritySafeRepair safeRepair) {

  public TranslationIntegrityEvaluation {
    Objects.requireNonNull(diagnostics, "diagnostics");
    Objects.requireNonNull(policyDiagnostics, "policyDiagnostics");
    Objects.requireNonNull(disposition, "disposition");
    diagnostics = sortedDiagnostics(diagnostics);
    policyDiagnostics = sortedDiagnostics(policyDiagnostics);
    if (disposition == TranslationIntegrityDisposition.AUTO_REPAIR_TARGET) {
      if (safeRepair == null) {
        throw new IllegalArgumentException(
            "safeRepair must be present when disposition is AUTO_REPAIR_TARGET");
      }
      if (policyDiagnostics.stream()
          .anyMatch(diagnostic -> diagnostic.code().equals("check-waived"))) {
        throw new IllegalArgumentException("AUTO_REPAIR_TARGET cannot coexist with a waiver");
      }
      if (!policyDiagnostics.equals(safeRepair.expectedPolicyDiagnostics())) {
        throw new IllegalArgumentException(
            "safeRepair expectedPolicyDiagnostics must equal top-level policyDiagnostics");
      }
    } else if (safeRepair != null) {
      throw new IllegalArgumentException(
          "safeRepair is only valid when disposition is AUTO_REPAIR_TARGET");
    }
  }

  /** Backward-compatible constructor for structural evaluators without policy or repair output. */
  public TranslationIntegrityEvaluation(
      List<TranslationIntegrityDiagnostic> diagnostics,
      TranslationIntegrityDisposition disposition) {
    this(diagnostics, List.of(), disposition, null, null);
  }

  public static TranslationIntegrityEvaluation pass() {
    return new TranslationIntegrityEvaluation(List.of(), TranslationIntegrityDisposition.PASS);
  }

  private static List<TranslationIntegrityDiagnostic> sortedDiagnostics(
      List<TranslationIntegrityDiagnostic> diagnostics) {
    List<TranslationIntegrityDiagnostic> sorted = new ArrayList<>(diagnostics);
    sorted.sort(TranslationIntegrityDiagnostic.CANONICAL_ORDER);
    return List.copyOf(sorted);
  }
}
