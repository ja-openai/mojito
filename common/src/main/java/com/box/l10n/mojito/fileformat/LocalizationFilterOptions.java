package com.box.l10n.mojito.fileformat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validated, format-owned Mojito extraction and localized-output options. */
public final class LocalizationFilterOptions {

  private static final Pattern INLINE_RULE = Pattern.compile("rule[0-9]+");
  private static final Set<String> ANDROID_OPTIONS =
      Set.of(
          "oldEscaping",
          "removeDescription",
          "postProcessIndent",
          "postRemoveTranslatableFalse",
          "postEmptyResourcesToEmptyFile");
  private static final Set<String> JSON_OPTIONS =
      Set.of(
          "useFullKeyPath",
          "extractAllPairs",
          "exceptions",
          "codeFinderData",
          "noteKeyPattern",
          "usagesKeyPattern",
          "filePositionPathKeyPattern",
          "filePositionLineKeyPattern",
          "filePositionColKeyPattern",
          "noteKeepOrReplace",
          "usagesKeepOrReplace",
          "removeKeySuffix",
          "convertToHtmlCodes");

  private final LocalizationFileFormat format;
  private final Map<String, String> values;
  private final Map<String, Pattern> patterns;
  private final List<Pattern> inlinePatterns;

  private LocalizationFilterOptions(
      LocalizationFileFormat format,
      Map<String, String> values,
      Map<String, Pattern> patterns,
      List<Pattern> inlinePatterns) {
    this.format = format;
    this.values = Map.copyOf(values);
    this.patterns = Map.copyOf(patterns);
    this.inlinePatterns = List.copyOf(inlinePatterns);
  }

  /** Parse existing key=value options while rejecting unsupported behavior explicitly. */
  public static LocalizationFilterOptions parse(
      LocalizationFileFormat format, List<String> options) {
    Map<String, String> values = new LinkedHashMap<>();
    Set<String> supported =
        switch (format) {
          case ANDROID -> ANDROID_OPTIONS;
          case FORMATJS_JSON -> JSON_OPTIONS;
          case YAML -> Set.of("useFullKeyPath", "extractAllPairs", "exceptions");
          case APPLE_STRINGS -> Set.of("removeComment");
          case HTML -> Set.of("processImageUrls", "emptyAndNbspNotTranslatable");
          default -> Set.of();
        };
    if (options != null) {
      for (String option : options) {
        if (option == null || option.indexOf('=') <= 0) {
          throw invalid("Filter options require a nonempty key and an equals sign");
        }
        int separator = option.indexOf('=');
        String key = option.substring(0, separator);
        if (!supported.contains(key)) {
          throw new LocalizationParseException(
              "UNSUPPORTED_FILTER_OPTION", "Unsupported " + format.id() + " filter option: " + key);
        }
        values.put(key, option.substring(separator + 1));
      }
    }
    Map<String, Pattern> patterns = new LinkedHashMap<>();
    List<Pattern> inlinePatterns = List.of();
    for (Map.Entry<String, String> option : values.entrySet()) {
      if (option.getKey().endsWith("Pattern") || "exceptions".equals(option.getKey())) {
        try {
          requirePortablePattern(option.getValue());
          patterns.put(option.getKey(), Pattern.compile(option.getValue()));
        } catch (PatternSyntaxException invalid) {
          throw invalid("Invalid " + option.getKey() + " regular expression");
        }
      }
    }
    if (Boolean.parseBoolean(values.get("convertToHtmlCodes"))) {
      String encoded = values.get("codeFinderData");
      if (encoded == null || !encoded.startsWith("#v1\n")) {
        throw invalid("JSON inline-code finder requires a version-one rule configuration");
      }
      int count = -1;
      for (String line : encoded.split("\\R")) {
        int separator = line.indexOf('=');
        if (separator < 0) {
          continue;
        }
        String key = line.substring(0, separator);
        String value = line.substring(separator + 1);
        if ("count.i".equals(key)) {
          try {
            count = Integer.parseInt(value);
          } catch (NumberFormatException invalid) {
            throw invalid("JSON inline-code finder has an invalid rule count");
          }
        } else if (INLINE_RULE.matcher(key).matches()) {
          try {
            requirePortablePattern(value);
            patterns.put(key, Pattern.compile(value));
          } catch (PatternSyntaxException invalid) {
            throw invalid("Invalid JSON inline-code regular expression");
          }
        }
      }
      if (count <= 0
          || count
              != patterns.keySet().stream()
                  .filter(key -> INLINE_RULE.matcher(key).matches())
                  .count()) {
        throw invalid("JSON inline-code finder rule count does not match its rules");
      }
      List<Pattern> ordered = new java.util.ArrayList<>(count);
      for (int index = 0; index < count; index++) {
        Pattern pattern = patterns.get("rule" + index);
        if (pattern == null) {
          throw invalid("JSON inline-code finder requires consecutively numbered rules");
        }
        ordered.add(pattern);
      }
      inlinePatterns = ordered;
    }
    LocalizationFilterOptions parsed =
        new LocalizationFilterOptions(format, values, patterns, inlinePatterns);
    parsed.validate();
    return parsed;
  }

  public LocalizationFileFormat format() {
    return format;
  }

  public boolean contains(String option) {
    return values.containsKey(option);
  }

  public boolean enabled(String option) {
    return Boolean.parseBoolean(values.get(option));
  }

  public String value(String option) {
    return values.get(option);
  }

  public int indentation() {
    return values.containsKey("postProcessIndent")
        ? Integer.parseInt(values.get("postProcessIndent"))
        : 2;
  }

  public Pattern pattern(String option) {
    return patterns.get(option);
  }

  List<Pattern> inlinePatterns() {
    return inlinePatterns;
  }

  boolean changesAndroidOutput() {
    return contains("removeDescription")
        || contains("postProcessIndent")
        || contains("postRemoveTranslatableFalse")
        || contains("postEmptyResourcesToEmptyFile");
  }

  private void validate() {
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String key = entry.getKey();
      if (Set.of(
              "oldEscaping",
              "removeDescription",
              "postRemoveTranslatableFalse",
              "postEmptyResourcesToEmptyFile",
              "removeComment",
              "processImageUrls",
              "emptyAndNbspNotTranslatable",
              "useFullKeyPath",
              "extractAllPairs",
              "noteKeepOrReplace",
              "usagesKeepOrReplace",
              "convertToHtmlCodes")
          .contains(key)) {
        if (!"true".equalsIgnoreCase(entry.getValue())
            && !"false".equalsIgnoreCase(entry.getValue())) {
          throw invalid("Boolean filter option " + key + " requires true or false");
        }
      } else if ("postProcessIndent".equals(key)) {
        try {
          int indent = Integer.parseInt(entry.getValue());
          if (indent < 0 || indent > 32) {
            throw invalid("Android post-processing indentation must be between 0 and 32");
          }
        } catch (NumberFormatException invalid) {
          throw LocalizationFilterOptions.invalid("Android post-processing indentation is invalid");
        }
      }
    }
    if (enabled("oldEscaping")) {
      throw new LocalizationParseException(
          "UNSUPPORTED_FILTER_OPTION",
          "Legacy oldEscaping=true cannot safely replace compiler-correct Android escaping");
    }
    if (contains("codeFinderData")
        && !value("codeFinderData").isEmpty()
        && !enabled("convertToHtmlCodes")) {
      throw new LocalizationParseException(
          "UNSUPPORTED_FILTER_OPTION",
          "JSON inline-code matching requires convertToHtmlCodes=true");
    }
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_FILTER_OPTION", message);
  }

  private static void requirePortablePattern(String pattern) {
    boolean escaped = false;
    boolean inClass = false;
    for (int index = 0; index < pattern.length(); index++) {
      char current = pattern.charAt(index);
      if (escaped) {
        if (!inClass && current >= '1' && current <= '9') {
          throw invalid("Portable regular expressions cannot contain backreferences");
        }
        escaped = false;
        continue;
      }
      if (current == '\\') {
        escaped = true;
      } else if (current == '[') {
        inClass = true;
      } else if (current == ']') {
        inClass = false;
      } else if (!inClass && current == '(' && index + 2 < pattern.length()) {
        if (pattern.charAt(index + 1) == '?') {
          char extension = pattern.charAt(index + 2);
          if (extension == '=' || extension == '!' || extension == '<') {
            throw invalid("Portable regular expressions cannot contain look-around assertions");
          }
        }
      }
    }
  }
}
