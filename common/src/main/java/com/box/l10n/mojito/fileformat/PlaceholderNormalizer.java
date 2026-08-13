package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlaceholderNormalizer {

  private static final Pattern PRINTF =
      Pattern.compile(
          "%\\(([^)]+)\\)([a-zA-Z])|%(?:(\\d+)\\$)?[-+#0 ,(<]*(?:\\d+)?(?:\\.\\d+)?(?:hh|ll|h|l|L|z|j|t)?([a-zA-Z@%])");

  private PlaceholderNormalizer() {}

  static String normalize(String input, List<LocalizationPlaceholder> placeholders) {
    return normalize(input, placeholders, null);
  }

  static String normalize(
      String input, List<LocalizationPlaceholder> placeholders, String forcedName) {
    return normalize(input, placeholders, forcedName, null, null, false);
  }

  static String normalizeFoundation(String input, List<LocalizationPlaceholder> placeholders) {
    return normalize(input, placeholders, null, null, null, true);
  }

  static String normalizeFoundationPlural(
      String input,
      List<LocalizationPlaceholder> placeholders,
      String forcedName,
      Integer pluralPosition) {
    return normalize(input, placeholders, forcedName, pluralPosition, null, true);
  }

  static String normalizeFoundationSubstitution(
      String input,
      List<LocalizationPlaceholder> placeholders,
      String forcedName,
      int substitutionPosition) {
    return normalize(
        input, placeholders, forcedName, substitutionPosition, substitutionPosition, true);
  }

  static String normalizePlural(
      String input,
      List<LocalizationPlaceholder> placeholders,
      String forcedName,
      Integer pluralPosition) {
    return normalize(input, placeholders, forcedName, pluralPosition, null, false);
  }

  static String normalizeSubstitution(
      String input,
      List<LocalizationPlaceholder> placeholders,
      String forcedName,
      int substitutionPosition) {
    return normalize(
        input, placeholders, forcedName, substitutionPosition, substitutionPosition, false);
  }

  private static String normalize(
      String input,
      List<LocalizationPlaceholder> placeholders,
      String forcedName,
      Integer pluralPosition,
      Integer implicitStart,
      boolean foundation) {
    Matcher matcher = PRINTF.matcher(input);
    StringBuilder result = new StringBuilder();
    int previous = 0;
    int implicitPosition = implicitStart == null ? 0 : implicitStart - 1;
    Integer previousPosition = null;
    String previousName = null;
    while (matcher.find()) {
      result.append(input, previous, matcher.start());
      String source = matcher.group();
      String named = matcher.group(1);
      String conversion = named == null ? matcher.group(4) : matcher.group(2);
      if ("%".equals(conversion)) {
        result.append('%');
      } else if ("n".equals(conversion) && named == null) {
        result.append('\n');
        if (foundation && matcher.group(3) == null) {
          implicitPosition++;
        }
      } else {
        boolean reusePrevious = named == null && source.contains("<");
        if (reusePrevious && previousName == null) {
          result.append(source);
          previous = matcher.end();
          continue;
        }
        String kind = kind(source, conversion);
        boolean numeric = "integer".equals(kind) || "number".equals(kind);
        if (foundation
            && matcher.group(3) == null
            && !reusePrevious
            && pluralPosition != null
            && numeric
            && implicitStart == null) {
          implicitPosition++;
        }
        Integer position =
            named != null
                ? null
                : reusePrevious
                    ? previousPosition
                    : matcher.group(3) != null
                        ? Integer.parseInt(matcher.group(3))
                        : pluralPosition != null && numeric && implicitStart == null
                            ? pluralPosition
                            : ++implicitPosition;
        String name =
            forcedName != null
                    && (!foundation && pluralPosition == null
                        || numeric && (pluralPosition == null || pluralPosition.equals(position)))
                ? forcedName
                : named != null ? named : reusePrevious ? previousName : "arg" + (position - 1);
        LocalizationPlaceholder placeholder =
            new LocalizationPlaceholder(name, source, kind, position, null);
        if (!placeholders.contains(placeholder)) {
          placeholders.add(placeholder);
        }
        previousPosition = position;
        previousName = name;
        result.append('{').append(name).append('}');
      }
      previous = matcher.end();
    }
    result.append(input, previous, input.length());
    return result.toString();
  }

  static List<LocalizationPlaceholder> placeholders() {
    return new ArrayList<>();
  }

  static List<Integer> escapedPercentPositions(String input) {
    List<Integer> positions = new ArrayList<>();
    Matcher matcher = PRINTF.matcher(input);
    while (matcher.find()) {
      if ("%%".equals(matcher.group())) {
        String prefix = normalize(input.substring(0, matcher.start()), placeholders());
        positions.add(prefix.codePointCount(0, prefix.length()));
      }
    }
    return positions;
  }

  static List<Integer> rawPercentOccurrences(String input) {
    List<Integer> escaped = escapedPercentPositions(input);
    String normalized = normalize(input, placeholders());
    List<Integer> raw = new ArrayList<>();
    int position = 0;
    int occurrence = 0;
    for (int offset = 0; offset < normalized.length(); ) {
      int character = normalized.codePointAt(offset);
      if (character == '%') {
        if (!escaped.contains(position)) {
          raw.add(occurrence);
        }
        occurrence++;
      }
      offset += Character.charCount(character);
      position++;
    }
    return raw;
  }

  static List<Map<String, Object>> printfLineSeparators(String input) {
    return printfLineSeparators(input, null, null);
  }

  static List<Map<String, Object>> foundationPrintfLineSeparators(String input) {
    return foundationPrintfLineSeparators(input, null, null, null);
  }

  static List<Map<String, Object>> foundationPluralPrintfLineSeparators(
      String input, String forcedName, Integer pluralPosition) {
    return foundationPrintfLineSeparators(input, forcedName, pluralPosition, null);
  }

  static List<Map<String, Object>> foundationSubstitutionPrintfLineSeparators(
      String input, String forcedName, int substitutionPosition) {
    return foundationPrintfLineSeparators(
        input, forcedName, substitutionPosition, substitutionPosition);
  }

  private static List<Map<String, Object>> foundationPrintfLineSeparators(
      String input, String forcedName, Integer pluralPosition, Integer implicitStart) {
    List<Map<String, Object>> separators = new ArrayList<>();
    Matcher matcher = PRINTF.matcher(input);
    int implicitPosition = implicitStart == null ? 0 : implicitStart - 1;
    boolean visibleAfterDisabled = false;
    while (matcher.find()) {
      String named = matcher.group(1);
      String conversion = named == null ? matcher.group(4) : matcher.group(2);
      if ("%".equals(conversion)) {
        continue;
      }
      String kind = kind(matcher.group(), conversion);
      boolean numeric = "integer".equals(kind) || "number".equals(kind);
      Integer position;
      if (named != null || matcher.group().contains("<")) {
        position = null;
      } else if (matcher.group(3) != null) {
        position = Integer.parseInt(matcher.group(3));
      } else {
        implicitPosition++;
        position =
            pluralPosition != null && numeric && implicitStart == null
                ? pluralPosition
                : implicitPosition;
      }
      if ("n".equals(conversion) && named == null) {
        String prefix =
            normalize(
                input.substring(0, matcher.start()),
                placeholders(),
                forcedName,
                pluralPosition,
                implicitStart,
                true);
        Map<String, Object> separator = new LinkedHashMap<>();
        separator.put("position", prefix.codePointCount(0, prefix.length()));
        separator.put("source", matcher.group());
        separator.put("argumentPosition", position);
        separators.add(separator);
      } else {
        visibleAfterDisabled |= !separators.isEmpty();
      }
    }
    if (!visibleAfterDisabled) {
      separators.forEach(separator -> separator.remove("argumentPosition"));
    }
    return separators;
  }

  static List<Map<String, Object>> printfLineSeparators(
      String input, String forcedName, Integer pluralPosition) {
    List<Map<String, Object>> separators = new ArrayList<>();
    Matcher matcher = PRINTF.matcher(input);
    while (matcher.find()) {
      if (matcher.group(1) != null || !"n".equals(matcher.group(4))) {
        continue;
      }
      String prefix =
          pluralPosition == null
              ? normalize(input.substring(0, matcher.start()), placeholders(), forcedName)
              : normalizePlural(
                  input.substring(0, matcher.start()), placeholders(), forcedName, pluralPosition);
      Map<String, Object> separator = new LinkedHashMap<>();
      separator.put("position", prefix.codePointCount(0, prefix.length()));
      separator.put("source", matcher.group());
      separators.add(separator);
    }
    return separators;
  }

  static List<Integer> printfLineSeparatorOccurrences(String input) {
    List<Integer> positions =
        printfLineSeparators(input).stream()
            .map(separator -> ((Number) separator.get("position")).intValue())
            .toList();
    String normalized = normalize(input, placeholders());
    List<Integer> occurrences = new ArrayList<>();
    int position = 0;
    int occurrence = 0;
    for (int offset = 0; offset < normalized.length(); ) {
      int character = normalized.codePointAt(offset);
      if (character == '\n') {
        if (positions.contains(position)) {
          occurrences.add(occurrence);
        }
        occurrence++;
      }
      offset += Character.charCount(character);
      position++;
    }
    return occurrences;
  }

  static void validateAndroid(String input) {
    int substitutions = 0;
    boolean implicit = false;
    int index = 0;
    while (index < input.length()) {
      if (input.charAt(index) == '%' && index + 1 < input.length()) {
        index++;
        if (input.charAt(index) == '%' || input.charAt(index) == 'n') {
          index++;
          continue;
        }
        substitutions++;
        int firstDigit = index;
        while (index < input.length() && input.charAt(index) >= '0' && input.charAt(index) <= '9') {
          index++;
        }
        if (index > firstDigit) {
          if (index < input.length() && input.charAt(index) != '$') {
            implicit = true;
          }
        } else if (input.charAt(index) == '<') {
          implicit = true;
          index++;
          if (index < input.length() && input.charAt(index) == '$') {
            index++;
          }
        } else {
          implicit = true;
        }
        while (index < input.length()) {
          char character = input.charAt(index);
          if (character != '-'
              && character != '#'
              && character != '+'
              && character != ' '
              && character != ','
              && character != '('
              && (character < '0' || character > '9')) {
            break;
          }
          index++;
        }
        if (index < input.length() && "DFKMWZkmwyz".indexOf(input.charAt(index)) >= 0) {
          return;
        }
      }
      if (index < input.length()) {
        index++;
      }
    }
    if (substitutions > 1 && implicit) {
      throw new LocalizationParseException(
          "INVALID_PLACEHOLDER", "Android requires explicit positions for multiple substitutions");
    }
  }

  static String plural(String selector, java.util.Map<String, String> variants) {
    StringBuilder message = new StringBuilder("{").append(selector).append(", plural,");
    variants.forEach(
        (category, text) -> {
          message.append(' ').append(category).append(" {");
          boolean quotingHashes = false;
          for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '#') {
              if (!quotingHashes) {
                message.append('\'');
                quotingHashes = true;
              }
            } else if (quotingHashes) {
              message.append('\'');
              quotingHashes = false;
            }
            message.append(character);
          }
          if (quotingHashes) {
            message.append('\'');
          }
          message.append('}');
        });
    return message.append('}').toString();
  }

  private static String kind(String source, String conversion) {
    if (source.length() >= 2 && source.charAt(source.length() - 2) == 't') {
      return "value";
    }
    return switch (conversion) {
      case "@", "s", "S" -> "string";
      case "d", "i", "u", "o", "x", "X" -> "integer";
      case "f", "F", "e", "E", "g", "G", "a", "A" -> "number";
      case "c", "C" -> "character";
      default -> "value";
    };
  }
}
