package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic UTF-8 properties regeneration checked against java.util.Properties. */
final class JavaPropertiesWriter {

  private static final Pattern ARGUMENT = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.-]*)\\}");
  private static final Comparator<String> UNICODE_SCALAR_ORDER =
      (left, right) -> {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
          int first = left.codePointAt(leftIndex);
          int second = right.codePointAt(rightIndex);
          if (first != second) {
            return Integer.compare(first, second);
          }
          leftIndex += Character.charCount(first);
          rightIndex += Character.charCount(second);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
      };

  String write(LocalizationCatalog catalog) {
    if (!LocalizationFileFormat.JAVA_PROPERTIES.id().equals(catalog.sourceFormat())) {
      throw new LocalizationParseException(
          "INVALID_SOURCE_FORMAT", "Java properties writer requires a Java properties catalog");
    }
    TreeMap<String, LocalizationMessage> messages = new TreeMap<>(UNICODE_SCALAR_ORDER);
    messages.putAll(catalog.messages());
    StringBuilder output = new StringBuilder();
    for (Map.Entry<String, LocalizationMessage> entry : messages.entrySet()) {
      LocalizationMessage message = entry.getValue();
      if (message.variants() != null) {
        throw new LocalizationParseException(
            "UNSUPPORTED_PROPERTIES_VARIANTS", "Java properties cannot represent plural variants");
      }
      if (message.description() != null) {
        if (message.description().indexOf('\n') >= 0 || message.description().indexOf('\r') >= 0) {
          throw new LocalizationParseException(
              "INVALID_PROPERTIES_COMMENT", "Java properties comments must fit on one line");
        }
        output.append("# ").append(message.description()).append('\n');
      }
      output.append(escape(entry.getKey(), true)).append('=');
      output.append(escape(restore(message), false)).append('\n');
    }
    return output.toString();
  }

  private static String restore(LocalizationMessage message) {
    return restore(message, message.defaultMessage());
  }

  static String render(LocalizationMessage message, String canonical) {
    return escape(restore(message, canonical), false, false);
  }

  private static String restore(LocalizationMessage message, String canonical) {
    Map<String, List<LocalizationPlaceholder>> placeholders = new HashMap<>();
    if (message.placeholders() != null) {
      for (LocalizationPlaceholder placeholder : message.placeholders()) {
        placeholders
            .computeIfAbsent(placeholder.name(), unused -> new ArrayList<>())
            .add(placeholder);
      }
    }
    Set<Integer> escapedPercents = new HashSet<>();
    if (message.metadata() != null
        && message.metadata().get("javaPropertiesEscapedPercents") instanceof List<?> offsets) {
      for (Object offset : offsets) {
        if (offset instanceof Number number) {
          escapedPercents.add(number.intValue());
        }
      }
    }
    boolean printfLineSeparator =
        message.metadata() != null
            && Boolean.TRUE.equals(message.metadata().get("javaPropertiesPrintfLineSeparator"));
    Map<Integer, String> lineSeparators = new HashMap<>();
    if (message.metadata() != null
        && message.metadata().get("javaPropertiesPrintfLineSeparators")
            instanceof List<?> separators) {
      for (Object separator : separators) {
        if (separator instanceof Map<?, ?> value
            && value.get("position") instanceof Number position
            && value.get("source") instanceof String source) {
          lineSeparators.put(position.intValue(), source);
        }
      }
    }
    Map<String, Integer> occurrences = new HashMap<>();
    Matcher matcher = ARGUMENT.matcher(canonical);
    StringBuilder output = new StringBuilder();
    int previous = 0;
    int scalarOffset = 0;
    while (matcher.find()) {
      List<LocalizationPlaceholder> choices = placeholders.get(matcher.group(1));
      if (choices == null) {
        continue;
      }
      scalarOffset =
          appendLiteral(
              output,
              canonical.substring(previous, matcher.start()),
              scalarOffset,
              escapedPercents,
              printfLineSeparator,
              lineSeparators);
      int occurrence = occurrences.getOrDefault(matcher.group(1), 0);
      occurrences.put(matcher.group(1), occurrence + 1);
      output.append(choices.get(Math.min(occurrence, choices.size() - 1)).source());
      scalarOffset += canonical.codePointCount(matcher.start(), matcher.end());
      previous = matcher.end();
    }
    appendLiteral(
        output,
        canonical.substring(previous),
        scalarOffset,
        escapedPercents,
        printfLineSeparator,
        lineSeparators);
    return output.toString();
  }

  private static int appendLiteral(
      StringBuilder output,
      String literal,
      int scalarOffset,
      Set<Integer> escapedPercents,
      boolean printfLineSeparator,
      Map<Integer, String> lineSeparators) {
    for (int index = 0; index < literal.length(); ) {
      int character = literal.codePointAt(index);
      if (character == '%' && escapedPercents.contains(scalarOffset)) {
        output.append("%%");
      } else if (character == '\n' && lineSeparators.containsKey(scalarOffset)) {
        output.append(lineSeparators.get(scalarOffset));
      } else if (character == '\n' && printfLineSeparator && lineSeparators.isEmpty()) {
        output.append("%n");
      } else {
        output.appendCodePoint(character);
      }
      scalarOffset++;
      index += Character.charCount(character);
    }
    return scalarOffset;
  }

  private static String escape(String value, boolean key) {
    return escape(value, key, true);
  }

  private static String escape(String value, boolean key, boolean normalized) {
    StringBuilder output = new StringBuilder();
    boolean leading = true;
    for (int index = 0; index < value.length(); ) {
      int character = value.codePointAt(index);
      switch (character) {
        case ' ' -> {
          if (key || leading) {
            output.append('\\');
          }
          output.append(' ');
        }
        case '\t' -> output.append("\\t");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\f' -> output.append("\\f");
        case '\\' -> output.append("\\\\");
        case '#', '!', '=', ':' -> {
          if (key || normalized) {
            output.append('\\');
          }
          output.appendCodePoint(character);
        }
        default -> {
          if (character < 0x20 || character == 0x7f) {
            output.append("\\u").append(String.format("%04X", character));
          } else {
            output.appendCodePoint(character);
          }
        }
      }
      if (character != ' ') {
        leading = false;
      }
      index += Character.charCount(character);
    }
    return output.toString();
  }
}
