package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JDK-faithful source ownership that keeps physical-line and continuation layout intact. */
final class JavaPropertiesSourceSkeleton {

  private JavaPropertiesSourceSkeleton() {}

  static LocalizationSourceSkeleton extract(byte[] bytes, Charset charset) {
    SourceSkeletonEncoding encoding =
        StandardCharsets.ISO_8859_1.equals(charset)
            ? SourceSkeletonEncoding.named("ISO-8859-1")
            : SourceSkeletonEncoding.detect(bytes);
    String source = LocalizationFileConverters.decode(bytes, encoding.charset());
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.JAVA_PROPERTIES, bytes, charset);
    List<LocalizationSourceSlot> slots = new ArrayList<>();
    for (LogicalLine line : logicalLines(source)) {
      String logical = line.text();
      int leading = 0;
      while (leading < logical.length() && whitespace(logical.charAt(leading))) {
        leading++;
      }
      if (leading == logical.length()
          || logical.charAt(leading) == '#'
          || logical.charAt(leading) == '!') {
        continue;
      }
      int keyEnd = leading;
      boolean escaped = false;
      while (keyEnd < logical.length()) {
        char value = logical.charAt(keyEnd);
        if (!escaped && (value == '=' || value == ':' || whitespace(value))) {
          break;
        }
        escaped = value == '\\' && !escaped;
        keyEnd++;
      }
      int valueStart = keyEnd;
      while (valueStart < logical.length() && whitespace(logical.charAt(valueStart))) {
        valueStart++;
      }
      if (valueStart < logical.length()
          && (logical.charAt(valueStart) == '=' || logical.charAt(valueStart) == ':')) {
        valueStart++;
      }
      while (valueStart < logical.length() && whitespace(logical.charAt(valueStart))) {
        valueStart++;
      }
      String id = JavaPropertiesParser.unescape(logical.substring(leading, keyEnd));
      if (!catalog.messages().containsKey(id)) {
        throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Unmapped Java properties key");
      }
      int start =
          valueStart == logical.length()
              ? line.end() == source.length() && oddTrailingSlash(logical, 0, logical.length())
                  ? line.positions().get(logical.length() - 1)
                  : line.end()
              : line.positions().get(valueStart);
      slots.add(
          new LocalizationSourceSlot(
              id, null, encoding.offset(source, start), encoding.offset(source, line.end())));
    }
    if (slots.size() < catalog.messages().size()) {
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Java properties source value");
    }
    return new LocalizationSourceSkeleton(
        1, LocalizationFileFormat.JAVA_PROPERTIES.id(), encoding.name(), source, slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported Java properties source skeleton");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(
            LocalizationFileFormat.JAVA_PROPERTIES, original, encoding.charset());
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.variant() != null) {
        throw invalid("INVALID_SKELETON", "Invalid Java properties slot");
      }
      known.add(slot.id());
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no Java properties source slot");
    }
    Set<String> separatorless = separatorlessDeclarations(skeleton.source());
    ByteArrayOutputStream result = new ByteArrayOutputStream(original.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Invalid Java properties source-slot range");
      }
      result.write(original, previous, slot.start() - previous);
      String translated = translations.get(slot.id());
      if (translated == null) {
        result.write(original, slot.start(), slot.end() - slot.start());
      } else {
        LocalizationMessage message = catalog.messages().get(slot.id());
        if (message == null) {
          throw invalid("INVALID_SKELETON", "Missing Java properties source descriptor");
        }
        String originalValue = encoding.decode(original, slot.start(), slot.end());
        String value = JavaPropertiesWriter.render(message, translated);
        if (StandardCharsets.ISO_8859_1.equals(encoding.charset())) {
          value = latin1(value);
        }
        value = preserveContinuations(originalValue, value);
        if ((slot.start() == slot.end() || "\\".equals(originalValue))
            && separatorless.contains(slot.id())) {
          value = "=" + value;
        }
        byte[] rendered = value.getBytes(encoding.charset());
        result.write(rendered, 0, rendered.length);
      }
      previous = slot.end();
    }
    result.write(original, previous, original.length - previous);
    return result.toByteArray();
  }

  static String removeEntries(String source, Set<String> removed) {
    if (removed.isEmpty()) {
      return source;
    }
    StringBuilder result = new StringBuilder(source.length());
    int previous = 0;
    for (LogicalLine line : logicalLines(source)) {
      String logical = line.text();
      int leading = 0;
      while (leading < logical.length() && whitespace(logical.charAt(leading))) {
        leading++;
      }
      if (leading == logical.length()) {
        continue;
      }
      int end = leading;
      boolean escaped = false;
      while (end < logical.length()) {
        char character = logical.charAt(end);
        if (!escaped && (character == '=' || character == ':' || whitespace(character))) {
          break;
        }
        escaped = character == '\\' && !escaped;
        end++;
      }
      if (!removed.contains(JavaPropertiesParser.unescape(logical.substring(leading, end)))) {
        continue;
      }
      result.append(source, previous, line.positions().get(0));
      previous = line.end();
      if (previous < source.length()) {
        if (source.charAt(previous++) == '\r'
            && previous < source.length()
            && source.charAt(previous) == '\n') {
          previous++;
        }
      }
    }
    return result.append(source, previous, source.length()).toString();
  }

  private static Set<String> separatorlessDeclarations(String source) {
    Set<String> result = new HashSet<>();
    for (LogicalLine line : logicalLines(source)) {
      String logical = line.text();
      int leading = 0;
      while (leading < logical.length() && whitespace(logical.charAt(leading))) {
        leading++;
      }
      if (leading == logical.length()
          || logical.charAt(leading) == '#'
          || logical.charAt(leading) == '!') {
        continue;
      }
      int end = leading;
      boolean escaped = false;
      while (end < logical.length()) {
        char character = logical.charAt(end);
        if (!escaped && (character == '=' || character == ':' || whitespace(character))) {
          break;
        }
        escaped = character == '\\' && !escaped;
        end++;
      }
      if (end == logical.length()) {
        result.add(JavaPropertiesParser.unescape(logical.substring(leading, end)));
      }
    }
    return result;
  }

  private static List<LogicalLine> logicalLines(String source) {
    List<LogicalLine> result = new ArrayList<>();
    StringBuilder logical = new StringBuilder();
    List<Integer> positions = new ArrayList<>();
    boolean continuing = false;
    int physical = 0;
    while (physical < source.length()) {
      int end = physical;
      while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
        end++;
      }
      int next = end;
      if (next < source.length()) {
        if (source.charAt(next++) == '\r'
            && next < source.length()
            && source.charAt(next) == '\n') {
          next++;
        }
      }
      int start = physical;
      if (continuing) {
        while (start < end && whitespace(source.charAt(start))) {
          start++;
        }
      }
      if (!continuing) {
        int first = start;
        while (first < end && whitespace(source.charAt(first))) {
          first++;
        }
        if (first < end && (source.charAt(first) == '#' || source.charAt(first) == '!')) {
          physical = next;
          continue;
        }
      }
      for (int index = start; index < end; index++) {
        logical.append(source.charAt(index));
        positions.add(index);
      }
      int slashes = 0;
      for (int index = logical.length() - 1; index >= 0 && logical.charAt(index) == '\\'; index--) {
        slashes++;
      }
      continuing = end < source.length() && slashes % 2 == 1;
      if (continuing) {
        logical.setLength(logical.length() - 1);
        positions.remove(positions.size() - 1);
      } else {
        result.add(new LogicalLine(logical.toString(), List.copyOf(positions), end));
        logical.setLength(0);
        positions.clear();
      }
      physical = next;
    }
    if (!logical.isEmpty()) {
      result.add(new LogicalLine(logical.toString(), List.copyOf(positions), source.length()));
    }
    return result;
  }

  private static String preserveContinuations(String source, String translated) {
    List<Continuation> continuations = new ArrayList<>();
    int segment = 0;
    for (int index = 0; index < source.length(); index++) {
      if (source.charAt(index) != '\n' && source.charAt(index) != '\r') {
        continue;
      }
      int slash = index - 1;
      while (slash >= segment && source.charAt(slash) == '\\') {
        slash--;
      }
      if ((index - slash - 1) % 2 == 0) {
        continue;
      }
      int delimiterStart = index - 1;
      int delimiterEnd = index + 1;
      if (source.charAt(index) == '\r'
          && delimiterEnd < source.length()
          && source.charAt(delimiterEnd) == '\n') {
        delimiterEnd++;
      }
      while (delimiterEnd < source.length() && whitespace(source.charAt(delimiterEnd))) {
        delimiterEnd++;
      }
      continuations.add(
          new Continuation(
              source.substring(delimiterStart, delimiterEnd),
              source.codePointCount(segment, delimiterStart)));
      segment = delimiterEnd;
      index = delimiterEnd - 1;
    }
    if (continuations.isEmpty()) {
      return translated;
    }
    StringBuilder result = new StringBuilder(translated.length() + source.length());
    int position = 0;
    for (Continuation continuation : continuations) {
      int remaining = translated.codePointCount(position, translated.length());
      int count = Math.min(continuation.width(), remaining);
      int next = translated.offsetByCodePoints(position, count);
      while (next < translated.length() && whitespace(translated.charAt(next))) {
        next++;
      }
      while (next < translated.length() && oddTrailingSlash(translated, position, next)) {
        next = translated.offsetByCodePoints(next, 1);
      }
      result.append(translated, position, next).append(continuation.source());
      position = next;
    }
    return result.append(translated, position, translated.length()).toString();
  }

  private static boolean oddTrailingSlash(String value, int start, int end) {
    int count = 0;
    while (--end >= start && value.charAt(end) == '\\') {
      count++;
    }
    return count % 2 == 1;
  }

  private static String latin1(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character > 0xff) {
        result.append(String.format("\\u%04X", (int) character));
      } else {
        result.append(character);
      }
    }
    return result.toString();
  }

  private static boolean whitespace(char value) {
    return value == ' ' || value == '\t' || value == '\f';
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private record LogicalLine(String text, List<Integer> positions, int end) {}

  private record Continuation(String source, int width) {}
}
