package com.box.l10n.mojito.translationintegrity.formatjs;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityRange;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Argument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.DateArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Literal;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.PluralArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Pound;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.SelectArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Tag;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.TimeArgument;
import com.box.l10n.mojito.translationintegrity.richtag.RichTextTagTranslationIntegrityEvaluator;
import com.box.l10n.mojito.translationintegrity.whitespace.BoundaryWhitespaceTranslationIntegrityEvaluator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Evaluates FormatJS cutover contracts: message syntax, argument membership, application-controlled
 * select structure, and optionally the independent rich-text-tag feature.
 */
public final class FormatJsTranslationIntegrityEvaluator {

  private static final Comparator<String> CODE_POINT_ORDER =
      FormatJsTranslationIntegrityEvaluator::compareByCodePoint;
  private static final Comparator<SelectOccurrence> SELECT_OCCURRENCE_ORDER =
      FormatJsTranslationIntegrityEvaluator::compareSelectOccurrences;
  private static final RichTextTagTranslationIntegrityEvaluator RICH_TEXT_TAG_EVALUATOR =
      new RichTextTagTranslationIntegrityEvaluator();
  private static final BoundaryWhitespaceTranslationIntegrityEvaluator
      BOUNDARY_WHITESPACE_EVALUATOR = new BoundaryWhitespaceTranslationIntegrityEvaluator();

  public TranslationIntegrityEvaluation evaluate(String source, String target) {
    return evaluate(source, target, false, false);
  }

  public TranslationIntegrityEvaluation evaluate(
      String source, String target, boolean evaluateRichTextTags) {
    return evaluate(source, target, evaluateRichTextTags, false);
  }

  /** Evaluates explicitly selected, independently composable FormatJS cutover features. */
  public TranslationIntegrityEvaluation evaluate(
      String source,
      String target,
      boolean evaluateRichTextTags,
      boolean evaluateBoundaryWhitespace) {
    TranslationIntegrityEvaluation structuralEvaluation =
        evaluateStructural(source, target, evaluateRichTextTags);
    return evaluateBoundaryWhitespace
        ? BOUNDARY_WHITESPACE_EVALUATOR.compose(
            source,
            target,
            structuralEvaluation,
            repairedTarget -> evaluateStructural(source, repairedTarget, evaluateRichTextTags))
        : structuralEvaluation;
  }

  private TranslationIntegrityEvaluation evaluateStructural(
      String source, String target, boolean evaluateRichTextTags) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");

    ParseObservation sourceParse = parse(source, evaluateRichTextTags);
    if (!sourceParse.isSuccess()) {
      return invalidSyntax(sourceParse, true);
    }

    ParseObservation targetParse = parse(target, evaluateRichTextTags);
    if (!targetParse.isSuccess()) {
      return invalidSyntax(targetParse, false);
    }

    MessageContract sourceContract = MessageContract.from(sourceParse.elements());
    MessageContract targetContract = MessageContract.from(targetParse.elements());
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    evaluateArguments(sourceContract, targetContract, diagnostics);
    evaluateSelects(sourceContract, targetContract, diagnostics);
    if (evaluateRichTextTags) {
      diagnostics.addAll(RICH_TEXT_TAG_EVALUATOR.evaluate(source, target).diagnostics());
    }

    return diagnostics.isEmpty()
        ? TranslationIntegrityEvaluation.pass()
        : new TranslationIntegrityEvaluation(
            diagnostics, TranslationIntegrityDisposition.REJECT_TARGET);
  }

  private static ParseObservation parse(String message, boolean useOpaqueTagCompatibility) {
    try {
      FormatJsParserOptions options =
          useOpaqueTagCompatibility
              ? FormatJsParserOptions.MOJITO_STRICT.toBuilder()
                  .pythonOpaqueTagCompatibility(true)
                  .build()
              : FormatJsParserOptions.MOJITO_STRICT;
      FormatJsParseResult result = FormatJsParser.parseResult(message, options);
      return result.isSuccess()
          ? ParseObservation.success(result.value())
          : ParseObservation.failure(result.error());
    } catch (FormatJsSkeletonException exception) {
      return ParseObservation.skeletonFailure();
    }
  }

  private static TranslationIntegrityEvaluation invalidSyntax(
      ParseObservation observation, boolean source) {
    String reason =
        observation.error() == null
            ? "invalid-argument-style"
            : FormatJsParseErrorReasonNormalizer.normalize(observation.error());
    TranslationIntegrityRange range = parserRange(observation.error());
    TranslationIntegrityDiagnostic diagnostic =
        source
            ? TranslationIntegrityDiagnostic.sourceError(
                "source-format-invalid", Map.of("reason", reason), range)
            : TranslationIntegrityDiagnostic.targetError(
                "target-format-invalid", Map.of("reason", reason), range);
    return new TranslationIntegrityEvaluation(
        List.of(diagnostic),
        source
            ? TranslationIntegrityDisposition.REJECT_SOURCE
            : TranslationIntegrityDisposition.REJECT_TARGET);
  }

  private static TranslationIntegrityRange parserRange(FormatJsParseError error) {
    if (error == null) {
      return null;
    }
    FormatJsCodePointRanges.CodePointRange range =
        FormatJsCodePointRanges.toCodePointRange(error.originalMessage(), error.location());
    return new TranslationIntegrityRange(range.start(), range.end());
  }

  private static void evaluateArguments(
      MessageContract source,
      MessageContract target,
      List<TranslationIntegrityDiagnostic> diagnostics) {
    Set<String> missing = difference(source.arguments(), target.arguments());
    if (!missing.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "variable-missing", Map.of("names", List.copyOf(missing))));
    }
    Set<String> extra = difference(target.arguments(), source.arguments());
    if (!extra.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "variable-extra", Map.of("names", List.copyOf(extra))));
    }
  }

  private static void evaluateSelects(
      MessageContract source,
      MessageContract target,
      List<TranslationIntegrityDiagnostic> diagnostics) {
    Set<String> selectArguments = new TreeSet<>(CODE_POINT_ORDER);
    selectArguments.addAll(source.selects().keySet());
    selectArguments.addAll(target.selects().keySet());

    for (String argument : selectArguments) {
      List<SelectOccurrence> sourceOccurrences = source.selects().getOrDefault(argument, List.of());
      List<SelectOccurrence> targetOccurrences = target.selects().getOrDefault(argument, List.of());
      if (sourceOccurrences.isEmpty()) {
        if (source.arguments().contains(argument)) {
          diagnostics.add(
              TranslationIntegrityDiagnostic.targetError(
                  "select-argument-changed",
                  Map.of(
                      "argument",
                      argument,
                      "expectedType",
                      source.firstNonSelectType(argument),
                      "actualType",
                      "select")));
        }
        continue;
      }
      if (targetOccurrences.isEmpty()) {
        if (target.arguments().contains(argument)) {
          diagnostics.add(
              TranslationIntegrityDiagnostic.targetError(
                  "select-argument-changed",
                  Map.of(
                      "argument",
                      argument,
                      "expectedType",
                      "select",
                      "actualType",
                      target.firstNonSelectType(argument))));
        }
        continue;
      }

      Set<String> sourceSelectors = selectors(sourceOccurrences);
      Set<String> targetSelectors = selectors(targetOccurrences);
      Set<String> missingOptions = difference(sourceSelectors, targetSelectors);
      if (!missingOptions.isEmpty()) {
        diagnostics.add(
            TranslationIntegrityDiagnostic.targetError(
                "select-option-missing",
                Map.of("argument", argument, "options", List.copyOf(missingOptions))));
      }
      Set<String> extraOptions = difference(targetSelectors, sourceSelectors);
      if (!extraOptions.isEmpty()) {
        diagnostics.add(
            TranslationIntegrityDiagnostic.targetError(
                "select-option-extra",
                Map.of("argument", argument, "options", List.copyOf(extraOptions))));
      }
      if (!missingOptions.isEmpty() || !extraOptions.isEmpty()) {
        continue;
      }

      Map<SelectOccurrence, Integer> sourceCounts = occurrenceCounts(sourceOccurrences);
      Map<SelectOccurrence, Integer> targetCounts = occurrenceCounts(targetOccurrences);
      Set<SelectOccurrence> signatures = new TreeSet<>(SELECT_OCCURRENCE_ORDER);
      signatures.addAll(sourceCounts.keySet());
      signatures.addAll(targetCounts.keySet());
      for (SelectOccurrence signature : signatures) {
        int expectedCount = sourceCounts.getOrDefault(signature, 0);
        int actualCount = targetCounts.getOrDefault(signature, 0);
        if (expectedCount == actualCount) {
          continue;
        }
        diagnostics.add(
            TranslationIntegrityDiagnostic.targetError(
                expectedCount > actualCount
                    ? "select-occurrence-missing"
                    : "select-occurrence-extra",
                Map.of(
                    "argument",
                    argument,
                    "selectors",
                    List.copyOf(signature.selectors()),
                    "expectedCount",
                    expectedCount,
                    "actualCount",
                    actualCount)));
      }
    }
  }

  private static Map<SelectOccurrence, Integer> occurrenceCounts(
      Collection<SelectOccurrence> occurrences) {
    Map<SelectOccurrence, Integer> result = new TreeMap<>(SELECT_OCCURRENCE_ORDER);
    occurrences.forEach(occurrence -> result.merge(occurrence, 1, Integer::sum));
    return result;
  }

  private static Set<String> selectors(Collection<SelectOccurrence> occurrences) {
    Set<String> result = new TreeSet<>(CODE_POINT_ORDER);
    occurrences.forEach(occurrence -> result.addAll(occurrence.selectors()));
    return result;
  }

  private static Set<String> difference(Set<String> left, Set<String> right) {
    Set<String> result = new TreeSet<>(CODE_POINT_ORDER);
    result.addAll(left);
    result.removeAll(right);
    return result;
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

  private static int compareSelectOccurrences(SelectOccurrence left, SelectOccurrence right) {
    var leftIterator = left.selectors().iterator();
    var rightIterator = right.selectors().iterator();
    while (leftIterator.hasNext() && rightIterator.hasNext()) {
      int comparison = CODE_POINT_ORDER.compare(leftIterator.next(), rightIterator.next());
      if (comparison != 0) {
        return comparison;
      }
    }
    return Boolean.compare(leftIterator.hasNext(), rightIterator.hasNext());
  }

  private record ParseObservation(List<FormatJsElement> elements, FormatJsParseError error) {

    private static ParseObservation success(List<FormatJsElement> elements) {
      return new ParseObservation(List.copyOf(elements), null);
    }

    private static ParseObservation failure(FormatJsParseError error) {
      return new ParseObservation(null, Objects.requireNonNull(error));
    }

    private static ParseObservation skeletonFailure() {
      return new ParseObservation(null, null);
    }

    private boolean isSuccess() {
      return elements != null;
    }
  }

  private record SelectOccurrence(Set<String> selectors) {

    private SelectOccurrence {
      Set<String> sorted = new TreeSet<>(CODE_POINT_ORDER);
      sorted.addAll(selectors);
      selectors = Collections.unmodifiableSet(sorted);
    }
  }

  private record MessageContract(
      Set<String> arguments,
      Map<String, Set<String>> argumentTypes,
      Map<String, List<SelectOccurrence>> selects) {

    private static MessageContract from(List<FormatJsElement> elements) {
      Builder builder = new Builder();
      builder.visit(elements);
      return builder.build();
    }

    private String firstNonSelectType(String argument) {
      return argumentTypes.getOrDefault(argument, Set.of("missing")).stream()
          .filter(type -> !type.equals("select"))
          .sorted(CODE_POINT_ORDER)
          .findFirst()
          .orElse("missing");
    }

    private static final class Builder {

      private final Set<String> arguments = new TreeSet<>(CODE_POINT_ORDER);
      private final Map<String, Set<String>> argumentTypes = new HashMap<>();
      private final Map<String, List<SelectOccurrence>> selects = new HashMap<>();

      private void visit(List<FormatJsElement> elements) {
        elements.forEach(this::visit);
      }

      private void visit(FormatJsElement element) {
        switch (element) {
          case Literal ignored -> {}
          case Pound ignored -> {}
          case Argument argument -> addArgument(argument.value(), "plain");
          case NumberArgument argument -> addArgument(argument.value(), "number");
          case DateArgument argument -> addArgument(argument.value(), "date");
          case TimeArgument argument -> addArgument(argument.value(), "time");
          case SelectArgument argument -> {
            addArgument(argument.value(), "select");
            selects
                .computeIfAbsent(argument.value(), ignored -> new ArrayList<>())
                .add(new SelectOccurrence(argument.options().keySet()));
            argument.options().values().forEach(option -> visit(option.value()));
          }
          case PluralArgument argument -> {
            addArgument(
                argument.value(),
                argument.pluralType() == FormatJsElement.PluralType.CARDINAL
                    ? "plural"
                    : "selectordinal");
            argument.options().values().forEach(option -> visit(option.value()));
          }
          case Tag tag -> visit(tag.children());
        }
      }

      private void addArgument(String name, String type) {
        arguments.add(name);
        argumentTypes.computeIfAbsent(name, ignored -> new TreeSet<>(CODE_POINT_ORDER)).add(type);
      }

      private MessageContract build() {
        Map<String, Set<String>> immutableTypes = new LinkedHashMap<>();
        argumentTypes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(CODE_POINT_ORDER))
            .forEach(entry -> immutableTypes.put(entry.getKey(), sortedSet(entry.getValue())));
        Map<String, List<SelectOccurrence>> immutableSelects = new LinkedHashMap<>();
        selects.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(CODE_POINT_ORDER))
            .forEach(entry -> immutableSelects.put(entry.getKey(), List.copyOf(entry.getValue())));
        return new MessageContract(
            sortedSet(arguments), Map.copyOf(immutableTypes), Map.copyOf(immutableSelects));
      }

      private static Set<String> sortedSet(Collection<String> values) {
        Set<String> result = new TreeSet<>(CODE_POINT_ORDER);
        result.addAll(values);
        return Collections.unmodifiableSet(result);
      }
    }
  }
}
