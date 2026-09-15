package com.box.l10n.mojito.service.review.feedback;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic, bounded metadata. Semantic judgments are deliberately not guessed. */
public final class ReviewEditDiff {
  private ReviewEditDiff() {}

  private static final Pattern TOKENS =
      Pattern.compile("[\\p{L}\\p{M}\\p{N}]+|[^\\s]", Pattern.UNICODE_CHARACTER_CLASS);
  private static final Map<String, Pattern> PROTECTED =
      Map.of(
          "placeholders", Pattern.compile("\\{[^{}\\r\\n]*}|%[0-9$]*[a-zA-Z]"),
          "icu", Pattern.compile("[{}]|\\b(?:plural|selectordinal|select)\\b|#[0-9]*"),
          "html", Pattern.compile("<[^>]+>"),
          "markdown", Pattern.compile("!?\\[[^\\]]*]\\([^)]*\\)|\\*\\*|__|`+"),
          "urls", Pattern.compile("https?://[^\\s<>]+"),
          "numbers", Pattern.compile("[+-]?\\p{N}+(?:[.,]\\p{N}+)*"));

  public record Result(
      boolean rawChanged,
      boolean normalizedChanged,
      String category,
      boolean material,
      int additions,
      int removals,
      int replacements,
      String tokenAlgorithm,
      Set<String> protectedChanges,
      int lengthDelta,
      String instanceSignificance,
      String patternSignificance,
      String removed,
      String added) {}

  public static Result compare(String baseline, String target) {
    String a = Objects.toString(baseline, ""), b = Objects.toString(target, "");
    boolean raw = !Objects.equals(baseline, target);
    String na = normalize(a), nb = normalize(b);
    Set<String> protectedChanges = new TreeSet<>();
    PROTECTED.forEach(
        (kind, pattern) -> {
          if (!matches(pattern, a).equals(matches(pattern, b))) protectedChanges.add(kind);
        });
    String category =
        !raw
            ? "UNCHANGED"
            : baseline == null
                ? "UNKNOWN_MATERIAL_EDIT"
                : a.equals(b)
                    ? "UNKNOWN_MATERIAL_EDIT"
                    : na.equals(nb)
                        ? (Normalizer.normalize(a, Normalizer.Form.NFC)
                                .equals(Normalizer.normalize(b, Normalizer.Form.NFC))
                            ? "NORMALIZATION"
                            : "WHITESPACE")
                        : !protectedChanges.isEmpty()
                            ? "PROTECTED_TOKEN_CHANGE"
                            : quotes(na).equals(quotes(nb))
                                ? "QUOTE_STYLE"
                                : na.equalsIgnoreCase(nb)
                                    ? "CASING"
                                    : punctuation(na).equals(punctuation(nb))
                                        ? "PUNCTUATION"
                                        : punctuation(quotes(na))
                                                .equalsIgnoreCase(punctuation(quotes(nb)))
                                            ? "STYLE"
                                            : "UNKNOWN_MATERIAL_EDIT";
    boolean material =
        category.equals("UNKNOWN_MATERIAL_EDIT") || category.equals("PROTECTED_TOKEN_CHANGE");
    List<String> at = matches(TOKENS, a), bt = matches(TOKENS, b);
    int prefix = 0, suffix = 0;
    while (prefix < at.size() && prefix < bt.size() && at.get(prefix).equals(bt.get(prefix)))
      prefix++;
    while (suffix < at.size() - prefix
        && suffix < bt.size() - prefix
        && at.get(at.size() - 1 - suffix).equals(bt.get(bt.size() - 1 - suffix))) suffix++;
    List<String> removed = at.subList(prefix, at.size() - suffix),
        added = bt.subList(prefix, bt.size() - suffix);
    int common = 0;
    String algorithm = "lcs";
    if ((long) removed.size() * added.size() <= 65536) {
      int[] prev = new int[added.size() + 1];
      for (String token : removed) {
        int[] next = new int[added.size() + 1];
        for (int j = 1; j <= added.size(); j++)
          next[j] =
              token.equals(added.get(j - 1)) ? prev[j - 1] + 1 : Math.max(prev[j], next[j - 1]);
        prev = next;
      }
      common = prev[added.size()];
    } else algorithm = "bounded_changed_span";
    int removals = removed.size() - common, additions = added.size() - common;
    return new Result(
        raw,
        !na.equals(nb),
        category,
        material,
        additions,
        removals,
        Math.min(additions, removals),
        algorithm,
        protectedChanges,
        b.codePointCount(0, b.length()) - a.codePointCount(0, a.length()),
        !raw ? "NONE" : material ? "NEEDS_REVIEW" : "LOW",
        "UNASSESSED",
        String.join(" ", removed),
        String.join(" ", added));
  }

  /** Direction matters: straight-to-curly and curly-to-straight are separate patterns. */
  public static String transform(Result diff, String baseline, String target) {
    String a = Objects.toString(baseline, ""), b = Objects.toString(target, "");
    if (diff.category().equals("QUOTE_STYLE"))
      return marks(a, "[\"'‘’‚‛“”„‟«»‹›]") + " → " + marks(b, "[\"'‘’‚‛“”„‟«»‹›]");
    if (diff.category().equals("WHITESPACE"))
      return marks(a, "(?U)\\s") + " → " + marks(b, "(?U)\\s");
    if (diff.category().equals("PUNCTUATION"))
      return marks(a, "\\p{P}") + " → " + marks(b, "\\p{P}");
    if (diff.category().equals("CASING")) return casing(a) + " → " + casing(b);
    return diff.removed() + " → " + diff.added();
  }

  private static String marks(String text, String regex) {
    return Pattern.compile(regex)
        .matcher(text)
        .results()
        .map(m -> m.group())
        .distinct()
        .map(s -> String.format("U+%04X", s.codePointAt(0)))
        .collect(java.util.stream.Collectors.joining(" "));
  }

  private static String casing(String s) {
    if (s.equals(s.toUpperCase(java.util.Locale.ROOT))) return "upper";
    if (s.equals(s.toLowerCase(java.util.Locale.ROOT))) return "lower";
    return "mixed:"
        + s.codePoints()
            .filter(Character::isLetter)
            .limit(128)
            .mapToObj(c -> Character.isUpperCase(c) ? "U" : "l")
            .collect(java.util.stream.Collectors.joining());
  }

  private static List<String> matches(Pattern p, String text) {
    return p.matcher(text).results().map(m -> m.group()).toList();
  }

  private static String normalize(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
  }

  private static String punctuation(String s) {
    return s.replaceAll("[\\p{P}\\s]", "");
  }

  private static String quotes(String s) {
    return s.replaceAll("[‘’‚‛]", "'").replaceAll("[“”„‟«»‹›]", "\"");
  }
}
