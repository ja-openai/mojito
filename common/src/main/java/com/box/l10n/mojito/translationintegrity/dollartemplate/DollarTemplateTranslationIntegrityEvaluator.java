package com.box.l10n.mojito.translationintegrity.dollartemplate;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.richtag.RichTextTagTranslationIntegrityEvaluator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates the dollar-template cutover contract used by Python {@code
 * string.Template.get_identifiers()}.
 *
 * <p>The compatibility scanner recognizes ASCII {@code $name} and {@code ${name}} identifiers and
 * treats {@code $$} as an escaped dollar. Invalid placeholder spellings contribute no identifier;
 * strict syntax rejection remains an extended, post-cutover behavior. The independent rich-text-tag
 * feature can be composed explicitly without changing placeholder parsing.
 */
public final class DollarTemplateTranslationIntegrityEvaluator {

  private static final Comparator<String> CODE_POINT_ORDER =
      DollarTemplateTranslationIntegrityEvaluator::compareByCodePoint;
  private static final RichTextTagTranslationIntegrityEvaluator RICH_TEXT_TAG_EVALUATOR =
      new RichTextTagTranslationIntegrityEvaluator();

  public TranslationIntegrityEvaluation evaluate(String source, String target) {
    return evaluate(source, target, false);
  }

  public TranslationIntegrityEvaluation evaluate(
      String source, String target, boolean evaluateRichTextTags) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");

    Set<String> sourceArguments = extractIdentifiers(source);
    Set<String> targetArguments = extractIdentifiers(target);
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();

    Set<String> missing = difference(sourceArguments, targetArguments);
    if (!missing.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "variable-missing", Map.of("names", List.copyOf(missing))));
    }

    Set<String> extra = difference(targetArguments, sourceArguments);
    if (!extra.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "variable-extra", Map.of("names", List.copyOf(extra))));
    }
    if (evaluateRichTextTags) {
      diagnostics.addAll(RICH_TEXT_TAG_EVALUATOR.evaluate(source, target).diagnostics());
    }

    return diagnostics.isEmpty()
        ? TranslationIntegrityEvaluation.pass()
        : new TranslationIntegrityEvaluation(
            diagnostics, TranslationIntegrityDisposition.REJECT_TARGET);
  }

  static Set<String> extractIdentifiers(String message) {
    Objects.requireNonNull(message, "message");
    Set<String> identifiers = new TreeSet<>(CODE_POINT_ORDER);

    for (int index = 0; index < message.length(); ) {
      if (message.charAt(index) != '$') {
        index++;
        continue;
      }

      int tokenStart = index;
      index++;
      if (index >= message.length()) {
        continue;
      }

      char next = message.charAt(index);
      if (next == '$') {
        index++;
        continue;
      }

      if (isIdentifierStart(next)) {
        int identifierStart = index++;
        while (index < message.length() && isIdentifierPart(message.charAt(index))) {
          index++;
        }
        identifiers.add(message.substring(identifierStart, index));
        continue;
      }

      if (next == '{') {
        int identifierStart = index + 1;
        int identifierEnd = identifierStart;
        if (identifierStart < message.length()
            && isIdentifierStart(message.charAt(identifierStart))) {
          identifierEnd++;
          while (identifierEnd < message.length()
              && isIdentifierPart(message.charAt(identifierEnd))) {
            identifierEnd++;
          }
          if (identifierEnd < message.length() && message.charAt(identifierEnd) == '}') {
            identifiers.add(message.substring(identifierStart, identifierEnd));
            index = identifierEnd + 1;
            continue;
          }
        }
      }

      // Python's Template pattern consumes only the '$' for an invalid placeholder. Continue
      // immediately after it so a later '$name', including one inside malformed braces, is found.
      index = tokenStart + 1;
    }

    return Collections.unmodifiableSet(identifiers);
  }

  private static Set<String> difference(Set<String> left, Set<String> right) {
    Set<String> result = new TreeSet<>(CODE_POINT_ORDER);
    result.addAll(left);
    result.removeAll(right);
    return result;
  }

  private static boolean isIdentifierStart(char character) {
    return character == '_'
        || character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z';
  }

  private static boolean isIdentifierPart(char character) {
    return isIdentifierStart(character) || character >= '0' && character <= '9';
  }

  private static int compareByCodePoint(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int comparison = Integer.compare(left.codePointAt(leftIndex), right.codePointAt(rightIndex));
      if (comparison != 0) {
        return comparison;
      }
      leftIndex += Character.charCount(left.codePointAt(leftIndex));
      rightIndex += Character.charCount(right.codePointAt(rightIndex));
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }
}
