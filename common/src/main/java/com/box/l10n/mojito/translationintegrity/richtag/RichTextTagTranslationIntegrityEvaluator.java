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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the language-neutral rich-text-tag cutover contract.
 *
 * <p>The rejection gate intentionally matches the legacy Python checker: compare the exact sets of
 * raw tokens found by {@code re.findall(r"(<.*?>)", message)}. Python's default dot does not cross
 * {@code \n}, so the Java pattern spells that boundary explicitly. Multiplicity and nesting do not
 * create a cutover rejection when the raw sets are equal; those stricter checks remain extended
 * behavior.
 */
public final class RichTextTagTranslationIntegrityEvaluator {

  private static final Pattern RAW_TAG = Pattern.compile("<[^\\n]*?>");
  private static final Pattern STRUCTURAL_TAG =
      Pattern.compile("^<(/?)([A-Za-z0-9_.:-]+)(?:\\s[^\\n]*?)?\\s*(/?)>$");
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

  private static ParsedTag parseStructuralTag(String rawTag) {
    Matcher matcher = STRUCTURAL_TAG.matcher(rawTag);
    if (!matcher.matches()) {
      return null;
    }
    return new ParsedTag(
        matcher.group(2), !matcher.group(1).isEmpty(), !matcher.group(3).isEmpty());
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
      Matcher matcher = RAW_TAG.matcher(message);
      while (matcher.find()) {
        String rawTag = matcher.group();
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

  private record ParsedTag(String name, boolean closing, boolean selfClosing) {}

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
