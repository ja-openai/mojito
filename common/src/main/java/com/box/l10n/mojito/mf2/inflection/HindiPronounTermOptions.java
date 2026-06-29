package com.box.l10n.mojito.mf2.inflection;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared option contract for Hindi dependency-aware pronoun `:term` usages. */
final class HindiPronounTermOptions {

  static final String AGREE_WITH = "agreeWith";
  static final String AGREE_WITH_COUNT = "agreeWithCount";
  static final String PERSON = "person";
  static final String REGISTER = "register";

  private static final String HINDI_LOCALE = "hi";
  private static final Set<String> OPTIONS =
      Set.of(
          AGREE_WITH,
          AGREE_WITH_COUNT,
          PERSON,
          REGISTER,
          TermUsageOptions.CASE,
          TermUsageOptions.COUNT);
  private static final Set<String> MARKER_OPTIONS =
      Set.of(AGREE_WITH, AGREE_WITH_COUNT, PERSON, REGISTER);
  private static final Set<String> PERSON_VALUES = Set.of("first", "second", "third");
  private static final Set<String> CASE_VALUES =
      Set.of("accusative", "direct", "ergative", "genitive");
  private static final Set<String> REGISTER_VALUES = Set.of("formal", "informal", "intimate");
  private static final Pattern VARIABLE_REFERENCE = Pattern.compile("\\$[A-Za-z_][\\w.-]*");

  private HindiPronounTermOptions() {}

  static boolean isPronounUsage(String locale, Map<String, String> options) {
    return isHindiLocale(locale) && hasPronounMarker(options);
  }

  static boolean hasPronounMarker(Map<String, String> options) {
    return options.keySet().stream().anyMatch(MARKER_OPTIONS::contains);
  }

  static void validate(Map<String, String> options) {
    for (Map.Entry<String, String> option : options.entrySet()) {
      if (!OPTIONS.contains(option.getKey())) {
        throw new IllegalArgumentException(
            "Unsupported Hindi pronoun term option: " + option.getKey());
      }
      if (option.getValue() == null || option.getValue().isBlank()) {
        throw new IllegalArgumentException(
            "Hindi pronoun term option value must not be blank: " + option.getKey());
      }
    }

    requireAllowedOption(options, PERSON, PERSON_VALUES);
    requireAllowedOption(options, TermUsageOptions.CASE, CASE_VALUES);
    requireAllowedOption(options, REGISTER, REGISTER_VALUES);

    requireVariableReference(options, TermUsageOptions.COUNT);
    requireVariableReference(options, AGREE_WITH);
    requireVariableReference(options, AGREE_WITH_COUNT);
  }

  static String variableReferenceName(String reference, String optionName) {
    if (!VARIABLE_REFERENCE.matcher(reference).matches()) {
      throw new IllegalArgumentException(
          "Hindi pronoun " + optionName + " option must reference a variable: " + reference);
    }
    return reference.substring(1);
  }

  private static boolean isHindiLocale(String locale) {
    return HINDI_LOCALE.equals(locale)
        || (locale != null && (locale.startsWith("hi-") || locale.startsWith("hi_")));
  }

  private static void requireAllowedOption(
      Map<String, String> options, String optionName, Set<String> allowedValues) {
    String value = options.get(optionName);
    if (value != null && !allowedValues.contains(value)) {
      throw new IllegalArgumentException(
          "Unsupported Hindi pronoun " + optionName + " option: " + value);
    }
  }

  private static void requireVariableReference(Map<String, String> options, String optionName) {
    String value = options.get(optionName);
    if (value != null) {
      variableReferenceName(value, optionName);
    }
  }
}
