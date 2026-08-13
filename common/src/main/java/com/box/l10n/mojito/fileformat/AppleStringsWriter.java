package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic UTF-8 Apple strings regeneration checked against actual Foundation. */
final class AppleStringsWriter {

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
    if (!LocalizationFileFormat.APPLE_STRINGS.id().equals(catalog.sourceFormat())) {
      throw new LocalizationParseException(
          "INVALID_SOURCE_FORMAT", "Apple strings writer requires an Apple strings catalog");
    }
    TreeMap<String, LocalizationMessage> messages = new TreeMap<>(UNICODE_SCALAR_ORDER);
    messages.putAll(catalog.messages());
    if (messages.isEmpty()) {
      return "// Empty localization catalog.\n";
    }
    StringBuilder output = new StringBuilder();
    for (Map.Entry<String, LocalizationMessage> entry : messages.entrySet()) {
      LocalizationMessage message = entry.getValue();
      if (message.variants() != null) {
        throw new LocalizationParseException(
            "UNSUPPORTED_APPLE_VARIANTS", "Apple strings files cannot represent plural variants");
      }
      if (message.description() != null) {
        if (message.description().indexOf('\n') >= 0
            || message.description().indexOf('\r') >= 0
            || message.description().indexOf('\u2028') >= 0
            || message.description().indexOf('\u2029') >= 0) {
          throw new LocalizationParseException(
              "INVALID_APPLE_COMMENT", "Apple strings comments must fit on one physical line");
        }
        output.append("// ").append(message.description()).append('\n');
      }
      output
          .append('"')
          .append(escape(entry.getKey(), false, false))
          .append("\" = \"")
          .append(render(message))
          .append("\";\n");
    }
    return output.toString();
  }

  private static String render(LocalizationMessage message) {
    return render(message, message.defaultMessage());
  }

  static String render(LocalizationMessage message, String canonical) {
    return render(message, canonical, false);
  }

  static String nativeValue(LocalizationMessage message, String canonical) {
    return render(message, canonical, true);
  }

  private static String render(LocalizationMessage message, String canonical, boolean nativeXml) {
    Map<String, List<LocalizationPlaceholder>> placeholders = new HashMap<>();
    if (message.placeholders() != null) {
      for (LocalizationPlaceholder placeholder : message.placeholders()) {
        placeholders
            .computeIfAbsent(placeholder.name(), unused -> new ArrayList<>())
            .add(placeholder);
      }
    }
    Object markupEscaping =
        message.metadata() == null ? null : message.metadata().get("appleMarkupEscaping");
    if (markupEscaping != null && !"icu-quoted-angle".equals(markupEscaping)) {
      throw new LocalizationParseException(
          "INVALID_APPLE_MARKUP", "Unsupported Apple strings markup escaping");
    }
    String source =
        markupEscaping == null ? canonical : canonical.replace("'<'", "<").replace("''", "'");
    String original =
        markupEscaping == null
            ? message.defaultMessage()
            : message.defaultMessage().replace("'<'", "<").replace("''", "'");
    DisabledConversions conversions = new DisabledConversions(message, original, source);
    Map<String, Integer> occurrences = new HashMap<>();
    Matcher matcher = ARGUMENT.matcher(source);
    StringBuilder output = new StringBuilder();
    int previous = 0;
    while (matcher.find()) {
      List<LocalizationPlaceholder> choices = placeholders.get(matcher.group(1));
      if (choices == null) {
        continue;
      }
      String literal = source.substring(previous, matcher.start());
      conversions.appendLiteral(output, literal, nativeXml);
      conversions.beforePlaceholder(output, matcher.group());
      int occurrence = occurrences.getOrDefault(matcher.group(1), 0);
      occurrences.put(matcher.group(1), occurrence + 1);
      String placeholder = choices.get(Math.min(occurrence, choices.size() - 1)).source();
      output.append(nativeXml ? placeholder : escape(placeholder, false, false));
      previous = matcher.end();
    }
    String remaining = source.substring(previous);
    conversions.appendLiteral(output, remaining, nativeXml);
    conversions.finish(output);
    return output.toString();
  }

  private static final class DisabledConversions {

    private final List<Integer> positions = new ArrayList<>();
    private final List<String> sources = new ArrayList<>();
    private int conversion;
    private int position;

    DisabledConversions(LocalizationMessage message, String original, String translated) {
      if (message.metadata() == null
          || !message.metadata().containsKey("appleDisabledPrintfConversions")) {
        return;
      }
      if (!(message.metadata().get("appleDisabledPrintfConversions") instanceof List<?> values)
          || values.isEmpty()) {
        throw invalidConversion();
      }
      int sourceLength = original.codePointCount(0, original.length());
      int targetLength = translated.codePointCount(0, translated.length());
      int previous = 0;
      for (Object raw : values) {
        if (!(raw instanceof Map<?, ?> occurrence)
            || !(occurrence.get("position") instanceof Number index)
            || !(occurrence.get("source") instanceof String spelling)
            || !spelling.matches("%(?:[1-9][0-9]*\\$)?n")
            || occurrence.size() > 3
            || occurrence.containsKey("argumentPosition")
                && (!(occurrence.get("argumentPosition") instanceof Number argument)
                    || argument.intValue() <= 0
                    || spelling.contains("$")
                        && Integer.parseInt(spelling.substring(1, spelling.indexOf('$')))
                            != argument.intValue())) {
          throw invalidConversion();
        }
        int offset = index.intValue();
        if (offset < previous || offset > sourceLength) {
          throw invalidConversion();
        }
        previous = offset;
        int scaled =
            sourceLength == 0
                ? 0
                : (int) (((long) offset * targetLength + sourceLength / 2L) / sourceLength);
        positions.add(scaled);
        sources.add(spelling);
      }
    }

    void appendLiteral(StringBuilder output, String value, boolean nativeXml) {
      for (int index = 0; index < value.length(); ) {
        flush(output, position);
        int character = value.codePointAt(index);
        String text = new String(Character.toChars(character));
        output.append(nativeXml ? nativeText(text, true, false) : escape(text, true, false));
        position++;
        index += Character.charCount(character);
      }
    }

    void beforePlaceholder(StringBuilder output, String matched) {
      int end = position + matched.codePointCount(0, matched.length());
      flush(output, end - 1);
      position = end;
    }

    void finish(StringBuilder output) {
      flush(output, Integer.MAX_VALUE);
    }

    private void flush(StringBuilder output, int limit) {
      while (conversion < positions.size() && positions.get(conversion) <= limit) {
        output.append(sources.get(conversion++));
      }
    }

    private static LocalizationParseException invalidConversion() {
      return new LocalizationParseException(
          "INVALID_APPLE_PRINTF_CONVERSION", "Invalid disabled Foundation printf conversion");
    }
  }

  private static String nativeText(
      String value, boolean escapePercent, boolean printfLineSeparator) {
    StringBuilder output = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); ) {
      int character = value.codePointAt(index);
      if (character == '%' && escapePercent) {
        output.append("%%");
      } else if (character == '\n' && printfLineSeparator) {
        output.append("%n");
      } else {
        output.appendCodePoint(character);
      }
      index += Character.charCount(character);
    }
    return output.toString();
  }

  private static String escape(String value, boolean escapePercent, boolean printfLineSeparator) {
    StringBuilder output = new StringBuilder();
    for (int index = 0; index < value.length(); ) {
      int character = value.codePointAt(index);
      switch (character) {
        case '\\' -> output.append("\\\\");
        case '"' -> output.append("\\\"");
        case '\n' -> output.append(printfLineSeparator ? "%n" : "\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '%' -> output.append(escapePercent ? "%%" : "%");
        default -> {
          if (character < 0x20 || character == 0x7f) {
            output.append("\\U").append(String.format("%04X", character));
          } else {
            output.appendCodePoint(character);
          }
        }
      }
      index += Character.charCount(character);
    }
    return output.toString();
  }
}
