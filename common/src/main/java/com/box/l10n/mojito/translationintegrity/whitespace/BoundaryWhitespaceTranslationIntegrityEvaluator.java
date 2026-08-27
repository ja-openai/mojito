package com.box.l10n.mojito.translationintegrity.whitespace;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityRepairOperation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegritySafeRepair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Evaluates and safely repairs the profile-neutral boundary-whitespace contract. */
public final class BoundaryWhitespaceTranslationIntegrityEvaluator {

  public TranslationIntegrityEvaluation evaluate(String source, String target) {
    return compose(
        source,
        target,
        TranslationIntegrityEvaluation.pass(),
        ignoredTarget -> TranslationIntegrityEvaluation.pass());
  }

  /**
   * Composes boundary whitespace with an already-evaluated structural contract.
   *
   * <p>The callback evaluates the repaired target with repair disabled. Automatic repair is offered
   * only when the complete structural contract and boundary detector both reach an exact
   * fixed-point pass.
   */
  public TranslationIntegrityEvaluation compose(
      String source,
      String target,
      TranslationIntegrityEvaluation structuralEvaluation,
      Function<String, TranslationIntegrityEvaluation> repairedTargetEvaluator) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(structuralEvaluation, "structuralEvaluation");
    Objects.requireNonNull(repairedTargetEvaluator, "repairedTargetEvaluator");

    if (structuralEvaluation.disposition() == TranslationIntegrityDisposition.REJECT_SOURCE
        || hasSyntaxDiagnostic(structuralEvaluation)) {
      return structuralEvaluation;
    }

    Boundaries sourceBoundaries = Boundaries.from(source);
    Boundaries targetBoundaries = Boundaries.from(target);
    TranslationIntegrityDiagnostic boundaryDiagnostic =
        boundaryDiagnostic(sourceBoundaries, targetBoundaries);
    if (boundaryDiagnostic == null) {
      return structuralEvaluation;
    }

    List<TranslationIntegrityDiagnostic> diagnostics =
        new ArrayList<>(structuralEvaluation.diagnostics());
    diagnostics.add(boundaryDiagnostic);
    if (!isStructurallyClean(structuralEvaluation)
        || sourceBoundaries.core().isEmpty()
        || targetBoundaries.core().isEmpty()) {
      return new TranslationIntegrityEvaluation(
          diagnostics,
          structuralEvaluation.policyDiagnostics(),
          TranslationIntegrityDisposition.REJECT_TARGET,
          structuralEvaluation.reviewDisposition(),
          null);
    }

    String repairedTarget = repairTarget(sourceBoundaries, targetBoundaries);
    TranslationIntegrityEvaluation repairedStructure =
        Objects.requireNonNull(
            repairedTargetEvaluator.apply(repairedTarget), "repairedTargetEvaluator returned null");
    TranslationIntegrityEvaluation repairedBoundary = evaluateWithoutRepair(source, repairedTarget);
    boolean fixedPoint =
        repairTarget(sourceBoundaries, Boundaries.from(repairedTarget)).equals(repairedTarget);
    if (!isStructurallyClean(repairedStructure)
        || !repairedStructure.policyDiagnostics().equals(structuralEvaluation.policyDiagnostics())
        || repairedStructure.reviewDisposition() != structuralEvaluation.reviewDisposition()
        || !isStructurallyClean(repairedBoundary)
        || !fixedPoint) {
      return new TranslationIntegrityEvaluation(
          diagnostics,
          structuralEvaluation.policyDiagnostics(),
          TranslationIntegrityDisposition.REJECT_TARGET,
          structuralEvaluation.reviewDisposition(),
          null);
    }

    TranslationIntegritySafeRepair safeRepair =
        new TranslationIntegritySafeRepair(
            List.of(TranslationIntegrityRepairOperation.COPY_SOURCE_BOUNDARY_WHITESPACE),
            repairedTarget,
            List.of(),
            structuralEvaluation.policyDiagnostics());
    return new TranslationIntegrityEvaluation(
        diagnostics,
        structuralEvaluation.policyDiagnostics(),
        TranslationIntegrityDisposition.AUTO_REPAIR_TARGET,
        structuralEvaluation.reviewDisposition(),
        safeRepair);
  }

  TranslationIntegrityEvaluation evaluateWithoutRepair(String source, String target) {
    Boundaries sourceBoundaries = Boundaries.from(Objects.requireNonNull(source, "source"));
    Boundaries targetBoundaries = Boundaries.from(Objects.requireNonNull(target, "target"));
    TranslationIntegrityDiagnostic diagnostic =
        boundaryDiagnostic(sourceBoundaries, targetBoundaries);
    return diagnostic == null
        ? TranslationIntegrityEvaluation.pass()
        : new TranslationIntegrityEvaluation(
            List.of(diagnostic), TranslationIntegrityDisposition.REJECT_TARGET);
  }

  static boolean isPythonStripWhitespace(int codePoint) {
    return codePoint >= 0x0009 && codePoint <= 0x000D
        || codePoint >= 0x001C && codePoint <= 0x001F
        || codePoint == 0x0020
        || codePoint == 0x0085
        || codePoint == 0x00A0
        || codePoint == 0x1680
        || codePoint >= 0x2000 && codePoint <= 0x200A
        || codePoint >= 0x2028 && codePoint <= 0x2029
        || codePoint == 0x202F
        || codePoint == 0x205F
        || codePoint == 0x3000;
  }

  private static TranslationIntegrityDiagnostic boundaryDiagnostic(
      Boundaries source, Boundaries target) {
    if (source.leading().equals(target.leading()) && source.trailing().equals(target.trailing())) {
      return null;
    }
    return TranslationIntegrityDiagnostic.targetError(
        "boundary-whitespace-mismatch",
        Map.of(
            "expectedLeading",
            source.leading(),
            "expectedTrailing",
            source.trailing(),
            "actualLeading",
            target.leading(),
            "actualTrailing",
            target.trailing()));
  }

  private static boolean hasSyntaxDiagnostic(TranslationIntegrityEvaluation evaluation) {
    return evaluation.diagnostics().stream()
        .anyMatch(
            diagnostic ->
                diagnostic.code().equals("source-format-invalid")
                    || diagnostic.code().equals("target-format-invalid"));
  }

  private static boolean isStructurallyClean(TranslationIntegrityEvaluation evaluation) {
    return evaluation.disposition() == TranslationIntegrityDisposition.PASS
        && evaluation.diagnostics().isEmpty()
        && evaluation.safeRepair() == null
        && evaluation.policyDiagnostics().stream()
            .noneMatch(diagnostic -> diagnostic.code().equals("check-waived"));
  }

  private static String repairTarget(Boundaries source, Boundaries target) {
    return source.leading() + target.core() + source.trailing();
  }

  private record Boundaries(String leading, String core, String trailing) {

    private static Boundaries from(String value) {
      int leadingEnd = 0;
      while (leadingEnd < value.length()) {
        int codePoint = value.codePointAt(leadingEnd);
        if (!isPythonStripWhitespace(codePoint)) {
          break;
        }
        leadingEnd += Character.charCount(codePoint);
      }

      int trailingStart = value.length();
      while (trailingStart > 0) {
        int codePoint = value.codePointBefore(trailingStart);
        if (!isPythonStripWhitespace(codePoint)) {
          break;
        }
        trailingStart -= Character.charCount(codePoint);
      }

      String core = leadingEnd <= trailingStart ? value.substring(leadingEnd, trailingStart) : "";
      return new Boundaries(value.substring(0, leadingEnd), core, value.substring(trailingStart));
    }
  }
}
