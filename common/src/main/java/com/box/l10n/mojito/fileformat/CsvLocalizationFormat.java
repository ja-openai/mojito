package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mojito's fixed-column CSV resources, including Adobe Magento's source-as-ID variant. */
final class CsvLocalizationFormat {

  private CsvLocalizationFormat() {}

  static LocalizationCatalog parse(LocalizationFileFormat format, String source) {
    LocalizationCatalog catalog = new LocalizationCatalog(format);
    for (Row row : rows(source)) {
      if (row.fields().size() <= sourceColumn(format)) {
        continue;
      }
      String id = raw(source, row.fields().get(0));
      String message = row.fields().get(sourceColumn(format)).value();
      if (id.isEmpty()) {
        continue;
      }
      String description =
          format == LocalizationFileFormat.CSV && row.fields().size() > 3
              ? raw(source, row.fields().get(3))
              : null;
      catalog.add(id, LocalizationMessage.of(message, description, null, null, null));
    }
    return catalog;
  }

  static String write(LocalizationFileFormat format, LocalizationCatalog catalog) {
    if (!format.id().equals(catalog.sourceFormat())) {
      throw invalid("INVALID_SOURCE_FORMAT", "CSV catalog does not match its requested format");
    }
    StringBuilder output = new StringBuilder();
    for (Map.Entry<String, LocalizationMessage> entry : catalog.messages().entrySet()) {
      if (format == LocalizationFileFormat.CSV) {
        output
            .append(rawField(entry.getKey()))
            .append(',')
            .append(sourceField(entry.getValue().defaultMessage()))
            .append(',')
            .append(sourceField(entry.getValue().defaultMessage()))
            .append(',')
            .append(rawField(entry.getValue().description()));
      } else {
        output
            .append(sourceField(entry.getValue().defaultMessage()))
            .append(',')
            .append(sourceField(entry.getValue().defaultMessage()));
      }
      output.append('\n');
    }
    return output.toString();
  }

  static LocalizationSourceSkeleton extract(LocalizationFileFormat format, byte[] bytes) {
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes);
    String source = LocalizationFileConverters.decode(bytes, encoding.charset());
    LocalizationCatalog catalog = parse(format, source);
    List<LocalizationSourceSlot> slots = new ArrayList<>();
    for (Row row : rows(source)) {
      if (row.fields().size() <= sourceColumn(format)) {
        continue;
      }
      String id = raw(source, row.fields().get(0));
      if (!catalog.messages().containsKey(id)) {
        continue;
      }
      int target = targetColumn(format);
      Field field = row.fields().size() > target ? row.fields().get(target) : null;
      int position = field == null ? row.end() : field.start();
      slots.add(
          new LocalizationSourceSlot(
              id,
              null,
              encoding.offset(source, position),
              encoding.offset(source, field == null ? position : field.end())));
    }
    return new LocalizationSourceSkeleton(1, format.id(), encoding.name(), source, slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported CSV source skeleton");
    }
    LocalizationFileFormat format = LocalizationFileFormat.fromId(skeleton.sourceFormat());
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationCatalog catalog = parse(format, skeleton.source());
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.variant() != null || slot.selector() != null || !known.add(slot.id())) {
        throw invalid("INVALID_SKELETON", "Invalid CSV source slot");
      }
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no original CSV source slot");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(original.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Invalid CSV source-slot range");
      }
      output.write(original, previous, slot.start() - previous);
      String translation = translations.get(slot.id());
      if (translation == null) {
        output.write(original, slot.start(), slot.end() - slot.start());
      } else {
        if (!catalog.messages().containsKey(slot.id())) {
          throw invalid("INVALID_SKELETON", "Missing CSV source descriptor");
        }
        String previousValue = encoding.decode(original, slot.start(), slot.end());
        String value =
            (slot.start() == slot.end() ? "," : "")
                + escape(translation, previousValue.startsWith("\""));
        byte[] encoded = value.getBytes(encoding.charset());
        output.write(encoded, 0, encoded.length);
      }
      previous = slot.end();
    }
    output.write(original, previous, original.length - previous);
    return output.toByteArray();
  }

  static byte[] localize(
      LocalizationFileFormat format,
      byte[] source,
      Map<String, String> translations,
      boolean removeUntranslated) {
    LocalizationSourceSkeleton skeleton = extract(format, source);
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      known.add(slot.id());
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no original CSV source slot");
    }
    if (!removeUntranslated) {
      return render(skeleton, translations);
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    String sourceText = skeleton.source();
    StringBuilder retained = new StringBuilder(sourceText.length());
    int previous = 0;
    for (Row row : rows(sourceText)) {
      if (row.fields().size() <= sourceColumn(format)
          || translations.containsKey(raw(sourceText, row.fields().get(0)))) {
        retained.append(sourceText, previous, row.terminatorEnd());
      } else {
        retained.append(sourceText, previous, row.start());
      }
      previous = row.terminatorEnd();
    }
    retained.append(sourceText, previous, sourceText.length());
    byte[] filtered = encoding.encode(retained.toString());
    Map<String, String> selected = new LinkedHashMap<>();
    for (LocalizationSourceSlot slot : extract(format, filtered).slots()) {
      selected.put(slot.id(), translations.get(slot.id()));
    }
    return render(extract(format, filtered), selected);
  }

  private static int sourceColumn(LocalizationFileFormat format) {
    return format == LocalizationFileFormat.CSV ? 1 : 0;
  }

  private static int targetColumn(LocalizationFileFormat format) {
    return format == LocalizationFileFormat.CSV ? 2 : 1;
  }

  private static String raw(String source, Field field) {
    return source.substring(field.start(), field.end()).trim();
  }

  private static String rawField(String value) {
    if (value == null) {
      return "";
    }
    return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
        ? value
        : escape(value, false);
  }

  private static String sourceField(String value) {
    if (value.indexOf('"') < 0) {
      return escape(value, false);
    }
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      escaped.append(current);
      if (current == '"') {
        if (index + 1 < value.length() && value.charAt(index + 1) == '"') {
          escaped.append(value.charAt(++index));
        } else {
          escaped.append('"');
        }
      }
    }
    return escaped.append('"').toString();
  }

  private static String escape(String value, boolean preserveQuotes) {
    if (value == null) {
      return "";
    }
    return preserveQuotes
            || value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0
        ? '"' + value.replace("\"", "\"\"") + '"'
        : value;
  }

  private static List<Row> rows(String source) {
    List<Row> rows = new ArrayList<>();
    int position = 0;
    while (position < source.length()) {
      int start = position;
      List<Field> fields = new ArrayList<>();
      while (true) {
        int fieldStart = position;
        StringBuilder value = new StringBuilder();
        boolean quoted = position < source.length() && source.charAt(position) == '"';
        if (quoted) {
          position++;
          boolean closed = false;
          while (position < source.length()) {
            char current = source.charAt(position++);
            if (current != '"') {
              value.append(current);
            } else if (position < source.length() && source.charAt(position) == '"') {
              value.append("\"\"");
              position++;
            } else {
              closed = true;
              break;
            }
          }
          if (!closed) {
            throw invalid("INVALID_CSV", "Unterminated quoted CSV field");
          }
          if (position < source.length() && ",\r\n".indexOf(source.charAt(position)) < 0) {
            throw invalid("INVALID_CSV", "Unexpected character after quoted CSV field");
          }
        } else {
          while (position < source.length() && ",\r\n".indexOf(source.charAt(position)) < 0) {
            char current = source.charAt(position++);
            if (current == '"') {
              throw invalid("INVALID_CSV", "Quote inside unquoted CSV field");
            }
            value.append(current);
          }
        }
        fields.add(
            new Field(quoted ? value.toString() : value.toString().trim(), fieldStart, position));
        if (position >= source.length() || source.charAt(position) != ',') {
          break;
        }
        position++;
      }
      int end = position;
      if (position < source.length() && source.charAt(position) == '\r') {
        position++;
      }
      if (position < source.length() && source.charAt(position) == '\n') {
        position++;
      }
      rows.add(new Row(start, end, position, fields));
    }
    return rows;
  }

  private record Field(String value, int start, int end) {}

  private record Row(int start, int end, int terminatorEnd, List<Field> fields) {}

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }
}
