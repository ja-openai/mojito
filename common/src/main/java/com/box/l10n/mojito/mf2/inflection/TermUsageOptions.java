package com.box.l10n.mojito.mf2.inflection;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class TermUsageOptions {

  static final String ARTICLE = "article";
  static final String CASE = "case";
  static final String COUNT = "count";
  static final String DEFINITENESS = "definiteness";
  static final String NUMBER = "number";
  static final String PREPOSITION = "preposition";

  private static final Set<String> SUPPORTED_OPTIONS =
      Set.of(ARTICLE, CASE, COUNT, DEFINITENESS, NUMBER, PREPOSITION);
  private static final Set<String> SUPPORTED_ARTICLES = Set.of("definite", "indefinite");
  private static final Set<String> SUPPORTED_CASES =
      Set.of(
          "ablative",
          "accusative",
          "dative",
          "direct",
          "genitive",
          "instrumental",
          "locative",
          "nominative",
          "oblique",
          "prepositional",
          "sociative",
          "vocative");
  private static final Set<String> SUPPORTED_DEFINITENESS =
      Set.of("construct", "definite", "indefinite");
  private static final Set<String> SUPPORTED_NUMBERS = Set.of("dual", "plural", "singular");
  private static final Set<String> SUPPORTED_PREPOSITIONS = Set.of("de", "em", "por");
  private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\$[A-Za-z_][\\w.-]*");

  private TermUsageOptions() {}

  static Set<String> supportedOptions() {
    return SUPPORTED_OPTIONS;
  }

  static Set<String> supportedArticles() {
    return SUPPORTED_ARTICLES;
  }

  static Set<String> supportedCases() {
    return SUPPORTED_CASES;
  }

  static Set<String> supportedDefiniteness() {
    return SUPPORTED_DEFINITENESS;
  }

  static Set<String> supportedNumbers() {
    return SUPPORTED_NUMBERS;
  }

  static Set<String> supportedPrepositions() {
    return SUPPORTED_PREPOSITIONS;
  }

  static void validate(Map<String, String> options) {
    for (Map.Entry<String, String> option : options.entrySet()) {
      if (!SUPPORTED_OPTIONS.contains(option.getKey())) {
        throw new IllegalArgumentException("Unsupported term option: " + option.getKey());
      }
      if (option.getValue() == null || option.getValue().isBlank()) {
        throw new IllegalArgumentException(
            "Term option value must not be blank: " + option.getKey());
      }
    }

    String article = options.get(ARTICLE);
    if (article != null && !SUPPORTED_ARTICLES.contains(article)) {
      throw new IllegalArgumentException("Unsupported article option: " + article);
    }

    String grammaticalCase = options.get(CASE);
    if (grammaticalCase != null && !SUPPORTED_CASES.contains(grammaticalCase)) {
      throw new IllegalArgumentException("Unsupported case option: " + grammaticalCase);
    }

    String definiteness = options.get(DEFINITENESS);
    if (definiteness != null && !SUPPORTED_DEFINITENESS.contains(definiteness)) {
      throw new IllegalArgumentException("Unsupported definiteness option: " + definiteness);
    }
    if (definiteness != null && article != null) {
      throw new IllegalArgumentException(
          "Definiteness option cannot be combined with article option");
    }

    String preposition = options.get(PREPOSITION);
    if (preposition != null && !SUPPORTED_PREPOSITIONS.contains(preposition)) {
      throw new IllegalArgumentException("Unsupported preposition option: " + preposition);
    }
    if (preposition != null && article == null) {
      throw new IllegalArgumentException("Preposition option requires article option");
    }
    if (preposition != null && grammaticalCase != null) {
      throw new IllegalArgumentException("Preposition option cannot be combined with case option");
    }
    if (preposition != null && !isPortuguesePrepositionArticleCombination(preposition, article)) {
      throw new IllegalArgumentException(
          "Unsupported preposition/article combination: " + preposition + " + " + article);
    }

    String count = options.get(COUNT);
    if (count != null && !VARIABLE_REFERENCE.matcher(count).matches()) {
      throw new IllegalArgumentException("Count option must reference a variable: " + count);
    }

    String number = options.get(NUMBER);
    if (number != null && !SUPPORTED_NUMBERS.contains(number)) {
      throw new IllegalArgumentException("Unsupported number option: " + number);
    }
    if (number != null && count != null) {
      throw new IllegalArgumentException("Number option cannot be combined with count option");
    }
  }

  static boolean isPortuguesePrepositionArticleCombination(String preposition, String article) {
    if (preposition == null) {
      return "definite".equals(article) || "indefinite".equals(article);
    }
    return switch (preposition) {
      case "de", "por" -> "definite".equals(article);
      case "em" -> "definite".equals(article) || "indefinite".equals(article);
      default -> false;
    };
  }

  static String countVariableName(String countReference) {
    if (!VARIABLE_REFERENCE.matcher(countReference).matches()) {
      throw new IllegalArgumentException(
          "Count option must reference a variable: " + countReference);
    }
    return countReference.substring(1);
  }
}
