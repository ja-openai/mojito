package com.box.l10n.mojito.fileformat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Mojito's quoted-key JavaScript/TypeScript resource syntax and original value ownership. */
final class JavaScriptSourceFormat {

  private static final Pattern SAFE_TEMPLATE_EXPRESSION =
      Pattern.compile(
          "\\$\\{[A-Za-z_$][A-Za-z0-9_$]*(?:(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)|(?:\\[(?:[0-9]+|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')\\]))*}");

  private JavaScriptSourceFormat() {}

  static LocalizationCatalog parse(LocalizationFileFormat format, String source) {
    LocalizationCatalog catalog = new LocalizationCatalog(format);
    for (Entry entry : entries(source)) {
      catalog.add(entry.id(), message(entry));
    }
    return catalog;
  }

  static LocalizationSourceSkeleton extract(LocalizationFileFormat format, byte[] bytes) {
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes);
    String source = LocalizationFileConverters.decode(bytes, null);
    LocalizationCatalog catalog = new LocalizationCatalog(format);
    List<LocalizationSourceSkeleton.LocalizationSourceSlot> slots = new ArrayList<>();
    for (Entry entry : entries(source)) {
      catalog.add(entry.id(), message(entry));
      slots.add(
          new LocalizationSourceSkeleton.LocalizationSourceSlot(
              entry.id(),
              null,
              encoding.offset(source, entry.start()),
              encoding.offset(source, entry.end())));
    }
    return new LocalizationSourceSkeleton(1, format.id(), encoding.name(), source, slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalidSkeleton("Unsupported JavaScript source skeleton version");
    }
    LocalizationFileFormat format = LocalizationFileFormat.fromId(skeleton.sourceFormat());
    if (format != LocalizationFileFormat.JAVASCRIPT
        && format != LocalizationFileFormat.TYPESCRIPT) {
      throw invalidSkeleton("Unsupported JavaScript source skeleton format");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationSourceSkeleton expected = extract(format, original);
    if (!expected.slots().equals(skeleton.slots())) {
      throw invalidSkeleton("JavaScript source slots do not own their original values");
    }
    Map<String, Entry> entries = new LinkedHashMap<>();
    for (Entry entry : entries(skeleton.source())) {
      entries.put(entry.id(), entry);
    }
    for (String id : translations.keySet()) {
      if (!entries.containsKey(id)) {
        throw new LocalizationParseException(
            "UNKNOWN_SKELETON_SLOT", "Translation has no JavaScript source slot: " + id);
      }
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(original.length);
    int copied = 0;
    for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : skeleton.slots()) {
      output.write(original, copied, slot.start() - copied);
      String translation = translations.get(slot.id());
      if (translation == null) {
        output.write(original, slot.start(), slot.end() - slot.start());
      } else {
        byte[] escaped = escape(translation, entries.get(slot.id())).getBytes(encoding.charset());
        output.write(escaped, 0, escaped.length);
      }
      copied = slot.end();
    }
    output.write(original, copied, original.length - copied);
    return output.toByteArray();
  }

  static byte[] removeEntries(LocalizationSourceSkeleton skeleton, Set<String> removed) {
    LocalizationFileFormat format = LocalizationFileFormat.fromId(skeleton.sourceFormat());
    if (format != LocalizationFileFormat.JAVASCRIPT
        && format != LocalizationFileFormat.TYPESCRIPT) {
      throw invalidSkeleton("Unsupported JavaScript source skeleton format");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    if (!extract(format, original).slots().equals(skeleton.slots())) {
      throw invalidSkeleton("JavaScript source slots do not own their original values");
    }
    List<Range> ranges = new ArrayList<>();
    for (Entry entry : entries(skeleton.source())) {
      if (!removed.contains(entry.id())) {
        continue;
      }
      int start = lineStart(skeleton.source(), entry.start());
      int end = nextLine(skeleton.source(), lineEnd(skeleton.source(), entry.end()));
      ranges.add(new Range(start, end));
    }
    return encoding.encode(removeRanges(skeleton.source(), ranges));
  }

  private static int lineStart(String source, int position) {
    int newline =
        Math.max(source.lastIndexOf('\n', position - 1), source.lastIndexOf('\r', position - 1));
    return newline + 1;
  }

  private static String removeRanges(String source, List<Range> ranges) {
    StringBuilder result = new StringBuilder(source.length());
    int previous = 0;
    for (Range range : ranges) {
      result.append(source, previous, range.start());
      previous = range.end();
    }
    return result.append(source, previous, source.length()).toString();
  }

  private static List<Entry> entries(String source) {
    List<Entry> entries = new ArrayList<>();
    String description = null;
    int position = 0;
    while (position < source.length()) {
      int lineEnd = lineEnd(source, position);
      int first = position;
      while (first < lineEnd
          && (source.charAt(first) == ' '
              || source.charAt(first) == '\t'
              || source.charAt(first) == '\f')) {
        first++;
      }
      if (first + 1 < lineEnd && source.startsWith("//", first)) {
        description = source.substring(first + 2, lineEnd).trim();
        position = nextLine(source, lineEnd);
        continue;
      }
      if (first >= lineEnd || source.charAt(first) != '"') {
        position = nextLine(source, lineEnd);
        continue;
      }
      int keyEnd = closing(source, first + 1, lineEnd, '"');
      if (keyEnd < 0) {
        throw invalid("Unterminated JavaScript message key");
      }
      String id = source.substring(first + 1, keyEnd);
      int delimiter = keyEnd + 1;
      while (delimiter < lineEnd
          && source.charAt(delimiter) != '"'
          && source.charAt(delimiter) != '`') {
        delimiter++;
      }
      if (delimiter == lineEnd) {
        throw invalid("Missing JavaScript message value");
      }
      boolean template = source.charAt(delimiter) == '`';
      int end =
          closing(
              source,
              delimiter + 1,
              template ? source.length() : lineEnd,
              source.charAt(delimiter));
      if (end < 0) {
        throw invalid("Unterminated JavaScript message value");
      }
      entries.add(
          new Entry(
              id, source.substring(delimiter + 1, end), description, template, delimiter + 1, end));
      description = null;
      position = nextLine(source, lineEnd(source, end));
    }
    return entries;
  }

  private static int lineEnd(String source, int start) {
    int end = start;
    while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
      end++;
    }
    return end;
  }

  private static int nextLine(String source, int lineEnd) {
    if (lineEnd >= source.length()) {
      return lineEnd;
    }
    return source.charAt(lineEnd) == '\r'
            && lineEnd + 1 < source.length()
            && source.charAt(lineEnd + 1) == '\n'
        ? lineEnd + 2
        : lineEnd + 1;
  }

  private static int closing(String source, int start, int limit, char delimiter) {
    for (int index = start; index < limit; index++) {
      if (source.charAt(index) == delimiter && !escapedAt(source, index)) {
        return index;
      }
    }
    return -1;
  }

  private static String unescape(String value, boolean template) {
    StringBuilder result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current != '\\' || index + 1 == value.length()) {
        result.append(current);
        continue;
      }
      char escaped = value.charAt(index + 1);
      switch (escaped) {
        case '\\' -> result.append('\\');
        case 'r' -> result.append('\r');
        case 'n' -> result.append('\n');
        case '"' -> result.append('"');
        case '\'' -> result.append('\'');
        case '`' -> {
          if (template) {
            result.append('`');
          } else {
            result.append("\\`");
          }
        }
        default -> {
          result.append('\\');
          continue;
        }
      }
      index++;
    }
    return result.toString();
  }

  private static LocalizationMessage message(Entry entry) {
    return LocalizationMessage.of(
        entry.template() ? unescapeTemplateValue(entry.value()) : unescape(entry.value(), false),
        entry.description(),
        null,
        null,
        entry.template() ? Map.of("javascriptTemplate", true) : null);
  }

  private static String escape(String value, Entry source) {
    StringBuilder result = new StringBuilder(value.length());
    List<TemplateExpression> sourceExpressions = templateExpressions(source.value());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (source.template()
          && current == '$'
          && index + 1 < value.length()
          && value.charAt(index + 1) == '{') {
        ExpressionMatch match = matchingExpression(value, index, sourceExpressions);
        if (match != null) {
          match.expression().used = true;
          result.append(match.expression().source);
          index += match.length() - 1;
          continue;
        }
        result.append("\\${");
        index++;
        continue;
      }
      switch (current) {
        case '\\' -> result.append("\\\\");
        case '\n' -> result.append(source.template() ? "\n" : "\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        case '\0' -> result.append("\\x00");
        case '\u000b' -> result.append("\\v");
        case '\u2028' -> result.append("\\u2028");
        case '\u2029' -> result.append("\\u2029");
        case '"' -> result.append("\\\"");
        case '`' -> result.append(source.template() ? "\\`" : "`");
        default -> {
          if (current < 0x20 || current == 0x7f) {
            result.append(String.format("\\u%04x", (int) current));
          } else {
            result.append(current);
          }
        }
      }
    }
    return result.toString();
  }

  private static boolean escapedAt(String value, int index) {
    int backslashes = 0;
    while (index > backslashes && value.charAt(index - backslashes - 1) == '\\') {
      backslashes++;
    }
    return backslashes % 2 != 0;
  }

  private static String unescapeTemplateValue(String value) {
    StringBuilder result = new StringBuilder(value.length());
    int copied = 0;
    for (int index = 0; index + 1 < value.length(); index++) {
      if (value.charAt(index) != '$' || value.charAt(index + 1) != '{' || escapedAt(value, index)) {
        continue;
      }
      int end = templateExpressionEnd(value, index + 2);
      if (end < 0) {
        continue;
      }
      String expression = value.substring(index, end + 1);
      if (!SAFE_TEMPLATE_EXPRESSION.matcher(expression).matches()) {
        index = end;
        continue;
      }
      result.append(unescape(value.substring(copied, index), true));
      result.append(expression);
      copied = end + 1;
      index = end;
    }
    return result.append(unescape(value.substring(copied), true)).toString();
  }

  private static List<TemplateExpression> templateExpressions(String source) {
    List<TemplateExpression> result = new ArrayList<>();
    for (int index = 0; index + 1 < source.length(); index++) {
      if (source.charAt(index) != '$'
          || source.charAt(index + 1) != '{'
          || escapedAt(source, index)) {
        continue;
      }
      int end = templateExpressionEnd(source, index + 2);
      if (end < 0) {
        continue;
      }
      String original = source.substring(index, end + 1);
      if (!SAFE_TEMPLATE_EXPRESSION.matcher(original).matches()) {
        index = end;
        continue;
      }
      result.add(new TemplateExpression(original));
      index = end;
    }
    return result;
  }

  private static int templateExpressionEnd(String source, int start) {
    int braces = 1;
    char quote = 0;
    for (int index = start; index < source.length(); index++) {
      char current = source.charAt(index);
      if (quote != 0) {
        if (current == quote && !escapedAt(source, index)) {
          quote = 0;
        }
        continue;
      }
      if (current == '\'' || current == '"' || current == '`') {
        quote = current;
      } else if (current == '{') {
        braces++;
      } else if (current == '}' && --braces == 0) {
        return index;
      }
    }
    return -1;
  }

  private static ExpressionMatch matchingExpression(
      String translation, int start, List<TemplateExpression> sourceExpressions) {
    ExpressionMatch matched = null;
    for (TemplateExpression sourceExpression : sourceExpressions) {
      if (sourceExpression.used) {
        continue;
      }
      String candidate = sourceExpression.source;
      if (translation.startsWith(candidate, start)
          && (matched == null || candidate.length() > matched.length())) {
        matched = new ExpressionMatch(sourceExpression, candidate.length());
      }
    }
    return matched;
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_JAVASCRIPT", message);
  }

  private static LocalizationParseException invalidSkeleton(String message) {
    return new LocalizationParseException("INVALID_SKELETON", message);
  }

  private record Entry(
      String id, String value, String description, boolean template, int start, int end) {}

  private static final class TemplateExpression {
    private final String source;
    private boolean used;

    private TemplateExpression(String source) {
      this.source = source;
    }
  }

  private record ExpressionMatch(TemplateExpression expression, int length) {}

  private record Range(int start, int end) {}
}
