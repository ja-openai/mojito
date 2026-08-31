package com.box.l10n.mojito.translationintegrity.richtag;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Evaluates the language-neutral rich-text-tag cutover contract.
 *
 * <p>The rejection gate intentionally matches the legacy Python checker: compare the exact sets of
 * raw tokens found by {@code re.findall(r"(<.*?>)", message)}. Python's default dot does not cross
 * {@code \n}, so the scanner spells that boundary explicitly. Multiplicity and nesting do not
 * create a cutover rejection when the raw sets are equal; those stricter checks remain extended
 * behavior.
 */
public final class RichTextTagTranslationIntegrityEvaluator {

  private static final Comparator<String> CODE_POINT_ORDER =
      RichTextTagTranslationIntegrityEvaluator::compareByCodePoint;

  public TranslationIntegrityEvaluation evaluate(String source, String target) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");

    TagInventory sourceTags = TagInventory.from(source);
    TagInventory targetTags = TagInventory.from(target);
    if (sourceTags.rawTags().equals(targetTags.rawTags())) {
      return TranslationIntegrityEvaluation.pass();
    }

    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    Set<String> unbalancedNames = unbalancedNames(sourceTags, targetTags, diagnostics);

    Set<String> missing =
        rawDifference(sourceTags.rawTags(), targetTags.rawTags(), unbalancedNames);
    if (!missing.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "rich-tag-missing", Map.of("tags", List.copyOf(missing))));
    }

    Set<String> extra = rawDifference(targetTags.rawTags(), sourceTags.rawTags(), unbalancedNames);
    if (!extra.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "rich-tag-extra", Map.of("tags", List.copyOf(extra))));
    }

    return diagnostics.isEmpty()
        ? TranslationIntegrityEvaluation.pass()
        : new TranslationIntegrityEvaluation(
            diagnostics, TranslationIntegrityDisposition.REJECT_TARGET);
  }

  private static Set<String> unbalancedNames(
      TagInventory source, TagInventory target, List<TranslationIntegrityDiagnostic> diagnostics) {
    Set<String> names = new TreeSet<>(CODE_POINT_ORDER);
    names.addAll(source.countsByName().keySet());
    names.addAll(target.countsByName().keySet());

    Set<String> unbalanced = new TreeSet<>(CODE_POINT_ORDER);
    for (String name : names) {
      TagCounts expected = source.countsByName().getOrDefault(name, TagCounts.EMPTY);
      TagCounts actual = target.countsByName().getOrDefault(name, TagCounts.EMPTY);
      if (!hasRawSetDeltaForName(source.rawTags(), target.rawTags(), name)
          || !expected.isPresent()
          || !actual.isPresent()
          || (!expected.isUnbalanced() && !actual.isUnbalanced())
          || expected.equals(actual)) {
        continue;
      }

      unbalanced.add(name);
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "rich-tag-unbalanced",
              Map.of(
                  "tag",
                  name,
                  "expectedOpenCount",
                  expected.openCount(),
                  "actualOpenCount",
                  actual.openCount(),
                  "expectedCloseCount",
                  expected.closeCount(),
                  "actualCloseCount",
                  actual.closeCount())));
    }
    return Collections.unmodifiableSet(unbalanced);
  }

  private static boolean hasRawSetDeltaForName(
      Set<String> source, Set<String> target, String name) {
    return !rawTagsForName(source, name).equals(rawTagsForName(target, name));
  }

  private static Set<String> rawTagsForName(Set<String> rawTags, String name) {
    Set<String> result = new TreeSet<>(CODE_POINT_ORDER);
    for (String rawTag : rawTags) {
      ParsedTag parsed = parseStructuralTag(rawTag);
      if (parsed != null && parsed.name().equals(name)) {
        result.add(rawTag);
      }
    }
    return result;
  }

  private static Set<String> rawDifference(
      Set<String> left, Set<String> right, Set<String> suppressedNames) {
    Set<String> result = new TreeSet<>(CODE_POINT_ORDER);
    result.addAll(left);
    result.removeAll(right);
    result.removeIf(
        rawTag -> {
          ParsedTag parsed = parseStructuralTag(rawTag);
          return parsed != null && suppressedNames.contains(parsed.name());
        });
    return Collections.unmodifiableSet(result);
  }

  static List<String> extractRawTags(CharSequence message) {
    Objects.requireNonNull(message, "message");

    List<String> rawTags = new ArrayList<>();
    int cursor = 0;
    while (cursor < message.length()) {
      while (cursor < message.length() && message.charAt(cursor) != '<') {
        cursor++;
      }
      if (cursor == message.length()) {
        break;
      }

      int start = cursor++;
      while (cursor < message.length()) {
        char current = message.charAt(cursor++);
        if (current == '>') {
          rawTags.add(message.subSequence(start, cursor).toString());
          break;
        }
        if (current == '\n') {
          break;
        }
      }
    }
    return List.copyOf(rawTags);
  }

  static ParsedTag parseStructuralTag(CharSequence rawTag) {
    Objects.requireNonNull(rawTag, "rawTag");

    int contentEnd = rawTag.length() - 1;
    if (contentEnd < 2 || rawTag.charAt(0) != '<' || rawTag.charAt(contentEnd) != '>') {
      return null;
    }

    int cursor = 1;
    boolean closing = rawTag.charAt(cursor) == '/';
    if (closing) {
      cursor++;
    }

    int nameStart = cursor;
    while (cursor < contentEnd && isStructuralNameCharacter(rawTag.charAt(cursor))) {
      cursor++;
    }
    if (cursor == nameStart) {
      return null;
    }

    boolean selfClosing = cursor < contentEnd && rawTag.charAt(contentEnd - 1) == '/';
    int suffixEnd = selfClosing ? contentEnd - 1 : contentEnd;
    if (!matchesStructuralSuffix(rawTag, cursor, suffixEnd)) {
      return null;
    }

    return new ParsedTag(rawTag.subSequence(nameStart, cursor).toString(), closing, selfClosing);
  }

  private static boolean matchesStructuralSuffix(CharSequence rawTag, int start, int end) {
    if (start == end) {
      return true;
    }
    if (!isRegexWhitespace(rawTag.charAt(start))) {
      return false;
    }

    for (int index = start + 1; index < end; index++) {
      if (rawTag.charAt(index) != '\n') {
        continue;
      }
      for (int suffixIndex = index; suffixIndex < end; suffixIndex++) {
        if (!isRegexWhitespace(rawTag.charAt(suffixIndex))) {
          return false;
        }
      }
      break;
    }
    return true;
  }

  private static boolean isStructuralNameCharacter(char value) {
    return (value >= 'A' && value <= 'Z')
        || (value >= 'a' && value <= 'z')
        || (value >= '0' && value <= '9')
        || value == '_'
        || value == '.'
        || value == ':'
        || value == '-';
  }

  private static boolean isRegexWhitespace(char value) {
    return value == ' '
        || value == '\t'
        || value == '\n'
        || value == '\u000B'
        || value == '\f'
        || value == '\r';
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

  private record TagInventory(Set<String> rawTags, Map<String, TagCounts> countsByName) {

    private static TagInventory from(String message) {
      Set<String> rawTags = new TreeSet<>(CODE_POINT_ORDER);
      Map<String, TagCounts> countsByName = new TreeMap<>(CODE_POINT_ORDER);
      for (String rawTag : extractRawTags(message)) {
        rawTags.add(rawTag);
        ParsedTag parsed = parseStructuralTag(rawTag);
        if (parsed == null || parsed.selfClosing()) {
          continue;
        }
        countsByName.compute(
            parsed.name(),
            (ignored, counts) ->
                (counts == null ? TagCounts.EMPTY : counts).withOccurrence(parsed.closing()));
      }
      return new TagInventory(
          Collections.unmodifiableSet(rawTags), Collections.unmodifiableMap(countsByName));
    }
  }

  record ParsedTag(String name, boolean closing, boolean selfClosing) {}

  private record TagCounts(int openCount, int closeCount) {

    private static final TagCounts EMPTY = new TagCounts(0, 0);

    private TagCounts withOccurrence(boolean closing) {
      return closing
          ? new TagCounts(openCount, closeCount + 1)
          : new TagCounts(openCount + 1, closeCount);
    }

    private boolean isPresent() {
      return openCount > 0 || closeCount > 0;
    }

    private boolean isUnbalanced() {
      return openCount != closeCount;
    }
  }
}
