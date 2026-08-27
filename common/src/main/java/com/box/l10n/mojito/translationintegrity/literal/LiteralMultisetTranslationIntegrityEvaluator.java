package com.box.l10n.mojito.translationintegrity.literal;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared sorted-multiset comparison for immutable literal contracts. */
final class LiteralMultisetTranslationIntegrityEvaluator {

  private static final Comparator<String> CODE_POINT_ORDER =
      LiteralMultisetTranslationIntegrityEvaluator::compareByCodePoint;

  private LiteralMultisetTranslationIntegrityEvaluator() {}

  static TranslationIntegrityEvaluation evaluate(
      List<String> sourceValues,
      List<String> targetValues,
      String missingDiagnosticCode,
      String extraDiagnosticCode) {
    Objects.requireNonNull(sourceValues, "sourceValues");
    Objects.requireNonNull(targetValues, "targetValues");

    Map<String, Integer> sourceCounts = counts(sourceValues);
    Map<String, Integer> targetCounts = counts(targetValues);
    Set<String> values = new TreeSet<>(CODE_POINT_ORDER);
    values.addAll(sourceCounts.keySet());
    values.addAll(targetCounts.keySet());

    List<String> missingValues = new ArrayList<>();
    List<String> extraValues = new ArrayList<>();
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    for (String value : values) {
      int expectedCount = sourceCounts.getOrDefault(value, 0);
      int actualCount = targetCounts.getOrDefault(value, 0);
      if (expectedCount == actualCount) {
        continue;
      }
      if (expectedCount > 1 || actualCount > 1) {
        diagnostics.add(
            TranslationIntegrityDiagnostic.targetError(
                expectedCount > actualCount ? missingDiagnosticCode : extraDiagnosticCode,
                Map.of(
                    "value", value,
                    "expectedCount", expectedCount,
                    "actualCount", actualCount)));
      } else if (actualCount == 0) {
        missingValues.add(value);
      } else if (expectedCount == 0) {
        extraValues.add(value);
      }
    }
    if (!missingValues.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              missingDiagnosticCode, Map.of("values", List.copyOf(missingValues))));
    }
    if (!extraValues.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              extraDiagnosticCode, Map.of("values", List.copyOf(extraValues))));
    }

    return diagnostics.isEmpty()
        ? TranslationIntegrityEvaluation.pass()
        : new TranslationIntegrityEvaluation(
            diagnostics, TranslationIntegrityDisposition.REJECT_TARGET);
  }

  static List<String> extract(String message, String fastPathMarker, Pattern pattern) {
    Objects.requireNonNull(message, "message");
    if (!message.contains(fastPathMarker)) {
      return List.of();
    }
    List<String> matches = new ArrayList<>();
    Matcher matcher = pattern.matcher(message);
    while (matcher.find()) {
      matches.add(matcher.group());
    }
    matches.sort(CODE_POINT_ORDER);
    return List.copyOf(matches);
  }

  private static Map<String, Integer> counts(List<String> values) {
    Map<String, Integer> counts = new TreeMap<>(CODE_POINT_ORDER);
    values.forEach(value -> counts.merge(value, 1, Integer::sum));
    return counts;
  }

  private static int compareByCodePoint(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int leftCodePoint = left.codePointAt(leftIndex);
      int rightCodePoint = right.codePointAt(rightIndex);
      int comparison = Integer.compare(leftCodePoint, rightCodePoint);
      if (comparison != 0) {
        return comparison;
      }
      leftIndex += Character.charCount(leftCodePoint);
      rightIndex += Character.charCount(rightCodePoint);
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }
}
