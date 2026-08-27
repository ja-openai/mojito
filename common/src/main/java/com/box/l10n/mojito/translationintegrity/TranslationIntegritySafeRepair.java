package com.box.l10n.mojito.translationintegrity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A deterministic target repair and the complete expected result after applying it. */
public record TranslationIntegritySafeRepair(
    List<TranslationIntegrityRepairOperation> operations,
    String expectedTarget,
    List<TranslationIntegrityDiagnostic> expectedDiagnostics,
    List<TranslationIntegrityDiagnostic> expectedPolicyDiagnostics) {

  public TranslationIntegritySafeRepair {
    Objects.requireNonNull(operations, "operations");
    Objects.requireNonNull(expectedTarget, "expectedTarget");
    Objects.requireNonNull(expectedDiagnostics, "expectedDiagnostics");
    Objects.requireNonNull(expectedPolicyDiagnostics, "expectedPolicyDiagnostics");
    if (operations.isEmpty()) {
      throw new IllegalArgumentException("operations must not be empty");
    }
    Set<TranslationIntegrityRepairOperation> uniqueOperations = new HashSet<>(operations);
    if (uniqueOperations.size() != operations.size()) {
      throw new IllegalArgumentException("operations must be unique");
    }
    if (uniqueOperations.contains(
            TranslationIntegrityRepairOperation.DOUBLE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG)
        && uniqueOperations.contains(
            TranslationIntegrityRepairOperation
                .REPLACE_ASCII_APOSTROPHE_BEFORE_FORMATJS_TAG_WITH_U2019)) {
      throw new IllegalArgumentException("apostrophe repair operations are mutually exclusive");
    }
    List<TranslationIntegrityRepairOperation> sortedOperations = new ArrayList<>(operations);
    sortedOperations.sort(Comparator.comparing(Enum::name));
    operations = List.copyOf(sortedOperations);
    expectedDiagnostics = sortedDiagnostics(expectedDiagnostics);
    expectedPolicyDiagnostics = sortedDiagnostics(expectedPolicyDiagnostics);
  }

  public TranslationIntegritySafeRepair(
      List<TranslationIntegrityRepairOperation> operations,
      String expectedTarget,
      List<TranslationIntegrityDiagnostic> expectedDiagnostics) {
    this(operations, expectedTarget, expectedDiagnostics, List.of());
  }

  private static List<TranslationIntegrityDiagnostic> sortedDiagnostics(
      List<TranslationIntegrityDiagnostic> diagnostics) {
    List<TranslationIntegrityDiagnostic> sorted = new ArrayList<>(diagnostics);
    sorted.sort(TranslationIntegrityDiagnostic.CANONICAL_ORDER);
    return List.copyOf(sorted);
  }
}
