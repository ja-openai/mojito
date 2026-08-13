package com.box.l10n.mojito.fileformat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mojito's quoted-key JavaScript/TypeScript resource syntax and original value ownership. */
final class JavaScriptSourceFormat {

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
        byte[] escaped =
            escape(translation, entries.get(slot.id()).template()).getBytes(encoding.charset());
        output.write(escaped, 0, escaped.length);
      }
      copied = slot.end();
    }
    output.write(original, copied, original.length - copied);
    return output.toByteArray();
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
      if (source.charAt(index) == delimiter && source.charAt(index - 1) != '\\') {
        return index;
      }
    }
    return -1;
  }

  private static String unescape(String value, boolean template) {
    String result = value.replace("\\r", "\r").replace("\\n", "\n");
    result = result.replace("\\\"", "\"").replace("\\'", "'");
    return template ? result.replace("\\`", "`") : result;
  }

  private static LocalizationMessage message(Entry entry) {
    return LocalizationMessage.of(
        unescape(entry.value(), entry.template()),
        entry.description(),
        null,
        null,
        entry.template() ? Map.of("javascriptTemplate", true) : null);
  }

  private static String escape(String value, boolean template) {
    StringBuilder result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      switch (current) {
        case '\n' -> result.append(template ? "\n" : "\\n");
        case '\r' -> result.append("\\r");
        case '"' -> result.append("\\\"");
        case '`' -> result.append(template ? "\\`" : "`");
        default -> result.append(current);
      }
    }
    return result.toString();
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_JAVASCRIPT", message);
  }

  private static LocalizationParseException invalidSkeleton(String message) {
    return new LocalizationParseException("INVALID_SKELETON", message);
  }

  private record Entry(
      String id, String value, String description, boolean template, int start, int end) {}
}
