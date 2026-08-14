package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JavaPropertiesParser {

  LocalizationCatalog parse(String source) {
    return parse(source, false);
  }

  LocalizationCatalog parseForMojito(String source) {
    return parse(source, true);
  }

  private LocalizationCatalog parse(String source, boolean preserveCommentWhitespace) {
    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.JAVA_PROPERTIES);
    List<String> comments = new ArrayList<>();
    for (String logical : logicalLines(source)) {
      String leading = stripPropertyWhitespace(logical);
      if (leading.isEmpty()) {
        comments.clear();
        continue;
      }
      if (leading.startsWith("#") || leading.startsWith("!")) {
        String comment = leading.substring(1);
        comments.add(preserveCommentWhitespace ? comment : comment.strip());
        continue;
      }
      int keyEnd = 0;
      boolean escaped = false;
      while (keyEnd < leading.length()) {
        char character = leading.charAt(keyEnd);
        if (!escaped && (character == '=' || character == ':' || isPropertyWhitespace(character))) {
          break;
        }
        if (character == '\\') {
          escaped = !escaped;
        } else {
          escaped = false;
        }
        keyEnd++;
      }
      int valueStart = keyEnd;
      while (valueStart < leading.length() && isPropertyWhitespace(leading.charAt(valueStart))) {
        valueStart++;
      }
      if (valueStart < leading.length()
          && (leading.charAt(valueStart) == '=' || leading.charAt(valueStart) == ':')) {
        valueStart++;
      }
      while (valueStart < leading.length() && isPropertyWhitespace(leading.charAt(valueStart))) {
        valueStart++;
      }
      String key = unescape(leading.substring(0, keyEnd));
      String value = unescape(leading.substring(valueStart));
      List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
      Map<String, Object> metadata = new LinkedHashMap<>();
      List<Integer> escapedPercents = PlaceholderNormalizer.escapedPercentPositions(value);
      if (!escapedPercents.isEmpty()) {
        metadata.put("javaPropertiesEscapedPercents", escapedPercents);
      }
      List<Map<String, Object>> lineSeparators = PlaceholderNormalizer.printfLineSeparators(value);
      if (!lineSeparators.isEmpty()) {
        metadata.put("javaPropertiesPrintfLineSeparator", true);
        metadata.put("javaPropertiesPrintfLineSeparators", lineSeparators);
      }
      LocalizationMessage message =
          LocalizationMessage.of(
              PlaceholderNormalizer.normalize(value, placeholders),
              String.join(" ", comments),
              null,
              placeholders,
              metadata);
      if (!message.equals(catalog.messages().get(key))) {
        catalog.add(key, message);
      }
      comments.clear();
    }
    return catalog;
  }

  private static List<String> logicalLines(String source) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean continuing = false;
    boolean continued = false;
    List<String> physical = physicalLines(source);
    for (int line = 0; line < physical.size(); line++) {
      String natural = physical.get(line);
      String leading = stripPropertyWhitespace(natural);
      if (!continuing && (leading.startsWith("#") || leading.startsWith("!"))) {
        result.add(natural);
        continue;
      }
      if (continuing) {
        int start = 0;
        while (start < natural.length() && isPropertyWhitespace(natural.charAt(start))) {
          start++;
        }
        current.append(natural, start, natural.length());
      } else {
        current.append(natural);
      }
      int slashes = 0;
      for (int index = current.length() - 1; index >= 0 && current.charAt(index) == '\\'; index--) {
        slashes++;
      }
      continuing = line + 1 < physical.size() && slashes % 2 == 1;
      if (continuing) {
        current.setLength(current.length() - 1);
        continued = true;
      } else {
        result.add(
            continued && stripPropertyWhitespace(current.toString()).isEmpty()
                ? "\\"
                : current.toString());
        current.setLength(0);
        continued = false;
      }
    }
    if (!current.isEmpty()) {
      result.add(current.toString());
    }
    return result;
  }

  private static List<String> physicalLines(String source) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '\n' || character == '\r') {
        lines.add(source.substring(start, index));
        if (character == '\r' && index + 1 < source.length() && source.charAt(index + 1) == '\n') {
          index++;
        }
        start = index + 1;
      }
    }
    lines.add(source.substring(start));
    return lines;
  }

  static String unescape(String value) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character != '\\') {
        result.append(character);
        continue;
      }
      if (++index >= value.length()) {
        break;
      }
      char escaped = value.charAt(index);
      switch (escaped) {
        case 't' -> result.append('\t');
        case 'r' -> result.append('\r');
        case 'n' -> result.append('\n');
        case 'f' -> result.append('\f');
        case 'u' -> {
          if (index + 4 >= value.length()) {
            throw new LocalizationParseException(
                "INVALID_UNICODE_ESCAPE", "Short properties Unicode escape");
          }
          try {
            result.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
          } catch (NumberFormatException exception) {
            throw new LocalizationParseException(
                "INVALID_UNICODE_ESCAPE", "Invalid properties Unicode escape", exception);
          }
          index += 4;
        }
        default -> result.append(escaped);
      }
    }
    for (int index = 0; index < result.length(); index++) {
      char character = result.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= result.length() || !Character.isLowSurrogate(result.charAt(index))) {
          throw new LocalizationParseException(
              "INVALID_UNICODE_ESCAPE", "Missing low properties Unicode surrogate");
        }
      } else if (Character.isLowSurrogate(character)) {
        throw new LocalizationParseException(
            "INVALID_UNICODE_ESCAPE", "Unexpected low properties Unicode surrogate");
      }
    }
    return result.toString();
  }

  private static String stripPropertyWhitespace(String line) {
    int start = 0;
    while (start < line.length() && isPropertyWhitespace(line.charAt(start))) {
      start++;
    }
    return line.substring(start);
  }

  private static boolean isPropertyWhitespace(char character) {
    return character == ' ' || character == '\t' || character == '\f';
  }
}
