package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reversible Foundation/OpenStep and XML property-list strings-file source ownership. */
final class AppleSourceSkeleton {

  private final String source;
  private final SourceSkeletonEncoding encoding;
  private final LocalizationCatalog catalog;
  private final List<LocalizationSourceSlot> slots = new ArrayList<>();
  private int index;

  private AppleSourceSkeleton(
      String source, SourceSkeletonEncoding encoding, LocalizationCatalog catalog) {
    this.source = source;
    this.encoding = encoding;
    this.catalog = catalog;
  }

  static LocalizationSourceSkeleton extract(byte[] bytes) {
    if (AppleBinaryPlistParser.matches(bytes)) {
      throw invalid(
          "UNSUPPORTED_SKELETON_SOURCE", "Binary Apple property lists require binary slots");
    }
    var declared =
        LocalizationFileConverters.xmlCharset(LocalizationFileFormat.APPLE_STRINGS, bytes);
    String source = LocalizationFileConverters.decode(bytes, declared);
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.APPLE_STRINGS, bytes);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes, declared);
    AppleSourceSkeleton scanner = new AppleSourceSkeleton(source, encoding, catalog);
    if (source.stripLeading().startsWith("<")) {
      scanner.scanXml();
    } else {
      scanner.scan();
    }
    if (scanner.slots.size() != catalog.messages().size()) {
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Foundation source-value slot");
    }
    return new LocalizationSourceSkeleton(
        1, LocalizationFileFormat.APPLE_STRINGS.id(), encoding.name(), source, scanner.slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported Apple source-skeleton version");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.APPLE_STRINGS, original);
    boolean xml = skeleton.source().stripLeading().startsWith("<");
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.variant() != null || !known.add(slot.id())) {
        throw invalid("INVALID_SKELETON", "Invalid or duplicated Apple source slot");
      }
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no original Apple source slot");
    }
    ByteArrayOutputStream result = new ByteArrayOutputStream(original.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Overlapping or out-of-range Apple source slot");
      }
      result.write(original, previous, slot.start() - previous);
      String translation = translations.get(slot.id());
      if (translation == null) {
        result.write(original, slot.start(), slot.end() - slot.start());
      } else {
        LocalizationMessage message = catalog.messages().get(slot.id());
        if (message == null) {
          throw invalid("INVALID_SKELETON", "Apple source slot has no canonical descriptor");
        }
        String replacement;
        if (xml) {
          String nativeValue = AppleStringsWriter.nativeValue(message, translation);
          String body = encoding.decode(original, slot.start(), slot.end());
          if (body.startsWith("/") && body.endsWith(">")) {
            String prefix = encoding.decode(original, encoding.bom().length, slot.start());
            int opening = prefix.lastIndexOf('<');
            if (opening < 0) {
              throw invalid("INVALID_SKELETON", "Self-closing Apple string has no opening tag");
            }
            String name = prefix.substring(opening + 1).stripLeading().split("\\s+", 2)[0];
            replacement = ">" + xmlText(nativeValue) + "</" + name + ">";
          } else {
            replacement =
                body.contains("<![CDATA[")
                    ? preserveCdata(body, nativeValue)
                    : xmlText(nativeValue);
          }
        } else {
          String rendered = AppleStringsWriter.render(message, translation);
          String before = encoding.decode(original, encoding.bom().length, slot.start());
          String after = encoding.decode(original, slot.end(), original.length);
          char opening = before.isEmpty() ? 0 : before.charAt(before.length() - 1);
          char closing = after.isEmpty() ? 0 : after.charAt(0);
          if ((opening == '\'' || opening == '"') && opening == closing) {
            replacement = opening == '\'' ? singleQuoted(rendered) : rendered;
          } else if (slot.start() == slot.end()) {
            replacement = " = \"" + rendered + "\"";
          } else {
            replacement = "\"" + rendered + "\"";
          }
        }
        byte[] encoded = replacement.getBytes(encoding.charset());
        result.write(encoded, 0, encoded.length);
      }
      previous = slot.end();
    }
    result.write(original, previous, original.length - previous);
    return result.toByteArray();
  }

  private void scan() {
    skipTrivia();
    boolean wrapped = index < source.length() && source.charAt(index) == '{';
    if (wrapped) {
      index++;
    }
    while (true) {
      skipTrivia();
      if (index == source.length()) {
        return;
      }
      if (wrapped && source.charAt(index) == '}') {
        return;
      }
      int keyStart = index;
      int keyEnd = token();
      String id = AppleStringsParser.decodeSourceToken(source.substring(keyStart, keyEnd));
      skipTrivia();
      int start;
      int end;
      if (index < source.length() && source.charAt(index) == ';') {
        start = index;
        end = index;
      } else {
        require('=');
        skipTrivia();
        int valueStart = index;
        int valueEnd = token();
        char delimiter = source.charAt(valueStart);
        if (delimiter == '\'' || delimiter == '"') {
          start = valueStart + 1;
          end = valueEnd - 1;
        } else {
          start = valueStart;
          end = valueEnd;
        }
        skipTrivia();
      }
      require(';');
      if (!catalog.messages().containsKey(id)) {
        throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Unmapped Foundation source key");
      }
      slots.add(
          new LocalizationSourceSlot(
              id, null, encoding.offset(source, start), encoding.offset(source, end)));
    }
  }

  private void scanXml() {
    Deque<XmlElement> stack = new ArrayDeque<>();
    String key = null;
    for (int position = 0; position < source.length(); ) {
      if (source.charAt(position) != '<') {
        position++;
        continue;
      }
      if (source.startsWith("<!--", position)) {
        position = sectionEnd(position, "-->");
        continue;
      }
      if (source.startsWith("<![CDATA[", position)) {
        position = sectionEnd(position, "]]>");
        continue;
      }
      if (source.startsWith("<?", position)) {
        position = sectionEnd(position, "?>");
        continue;
      }
      int end = tagEnd(position);
      String token = source.substring(position + 1, end).trim();
      if (token.startsWith("!")) {
        position = end + 1;
        continue;
      }
      if (token.startsWith("/")) {
        XmlElement current = stack.pop();
        XmlElement parent = stack.peek();
        if (parent != null && "dict".equals(parent.name()) && "key".equals(current.name())) {
          key = xmlKey(source.substring(current.bodyStart(), position));
        } else if (parent != null
            && "dict".equals(parent.name())
            && "string".equals(current.name())) {
          addXmlSlot(key, current.bodyStart(), position);
          key = null;
        }
      } else {
        boolean empty = token.endsWith("/");
        if (empty) {
          token = token.substring(0, token.length() - 1).trim();
        }
        String name = token.split("\\s+", 2)[0];
        XmlElement parent = stack.peek();
        if (empty) {
          if (parent != null && "dict".equals(parent.name()) && "string".equals(name)) {
            int slash = source.lastIndexOf('/', end);
            addXmlSlot(key, slash, end + 1);
            key = null;
          }
        } else {
          stack.push(new XmlElement(name, end + 1));
        }
      }
      position = end + 1;
    }
  }

  private void addXmlSlot(String key, int start, int end) {
    if (key == null || !catalog.messages().containsKey(key)) {
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Unmapped XML property-list source key");
    }
    slots.add(
        new LocalizationSourceSlot(
            key, null, encoding.offset(source, start), encoding.offset(source, end)));
  }

  private String xmlKey(String body) {
    LocalizationCatalog parsed =
        new AppleStringsParser("<dict><key>" + body + "</key><string/></dict>").parse();
    return parsed.messages().keySet().iterator().next();
  }

  private int tagEnd(int start) {
    char quote = 0;
    for (int position = start + 1; position < source.length(); position++) {
      char value = source.charAt(position);
      if (quote == 0 && (value == '\'' || value == '"')) {
        quote = value;
      } else if (value == quote) {
        quote = 0;
      } else if (value == '>' && quote == 0) {
        return position;
      }
    }
    throw invalid("INVALID_SKELETON", "Unterminated Apple property-list XML tag");
  }

  private int sectionEnd(int start, String delimiter) {
    int end = source.indexOf(delimiter, start);
    if (end < 0) {
      throw invalid("INVALID_SKELETON", "Unterminated Apple property-list XML section");
    }
    return end + delimiter.length();
  }

  static String preserveCdata(String source, String translated) {
    List<XmlPart> parts = new ArrayList<>();
    for (int position = 0; position < source.length(); ) {
      int begin = source.indexOf("<![CDATA[", position);
      if (begin < 0) {
        parts.add(new XmlPart(false, source.substring(position)));
        break;
      }
      if (begin > position) {
        parts.add(new XmlPart(false, source.substring(position, begin)));
      }
      int end = source.indexOf("]]>", begin + "<![CDATA[".length());
      if (end < 0) {
        throw invalid("INVALID_SKELETON", "Unterminated Apple property-list CDATA source");
      }
      parts.add(new XmlPart(true, source.substring(begin + "<![CDATA[".length(), end)));
      position = end + 3;
    }
    StringBuilder result = new StringBuilder();
    int target = 0;
    int remaining = translated.codePointCount(0, translated.length());
    for (int position = 0; position < parts.size(); position++) {
      XmlPart part = parts.get(position);
      int count =
          position + 1 == parts.size()
              ? remaining
              : Math.min(xmlLength(part.source(), part.cdata()), remaining);
      int end = translated.offsetByCodePoints(target, count);
      String value = translated.substring(target, end);
      if (part.cdata()) {
        result.append("<![CDATA[").append(value.replace("]]>", "]]]]><![CDATA[>")).append("]]>");
      } else {
        result.append(xmlText(value));
      }
      target = end;
      remaining -= count;
    }
    return result.toString();
  }

  private static int xmlLength(String source, boolean cdata) {
    if (cdata) {
      return source.codePointCount(0, source.length());
    }
    int count = 0;
    for (int position = 0; position < source.length(); ) {
      if (source.charAt(position) == '&') {
        int end = source.indexOf(';', position + 1);
        if (end < 0) {
          throw invalid("INVALID_SKELETON", "Unterminated Apple XML character reference");
        }
        position = end + 1;
      } else {
        position += Character.charCount(source.codePointAt(position));
      }
      count++;
    }
    return count;
  }

  static String xmlText(String source) {
    StringBuilder result = new StringBuilder(source.length());
    for (int position = 0; position < source.length(); ) {
      int value = source.codePointAt(position);
      switch (value) {
        case '&' -> result.append("&amp;");
        case '<' -> result.append("&lt;");
        case '>' -> result.append("&gt;");
        case '\n' -> result.append("&#10;");
        case '\r' -> result.append("&#13;");
        case '\t' -> result.append("&#9;");
        default -> result.appendCodePoint(value);
      }
      position += Character.charCount(value);
    }
    return result.toString();
  }

  private int token() {
    if (index >= source.length()) {
      throw invalid("INVALID_SKELETON", "Missing Apple strings token");
    }
    char delimiter = source.charAt(index);
    if (delimiter == '\'' || delimiter == '"') {
      index++;
      while (index < source.length()) {
        char character = source.charAt(index++);
        if (character == '\\') {
          if (index >= source.length()) {
            throw invalid("INVALID_SKELETON", "Unterminated Apple source token escape");
          }
          index++;
        } else if (character == delimiter) {
          return index;
        }
      }
      throw invalid("INVALID_SKELETON", "Unterminated Apple source token");
    }
    int start = index;
    while (index < source.length() && unquoted(source.charAt(index))) {
      index++;
    }
    if (index == start) {
      throw invalid("INVALID_SKELETON", "Invalid unquoted Apple source token");
    }
    return index;
  }

  private void skipTrivia() {
    while (index < source.length()) {
      char value = source.charAt(index);
      if (value >= '\t' && value <= '\r'
          || value == ' '
          || value == '\u2028'
          || value == '\u2029') {
        index++;
      } else if (source.startsWith("/*", index)) {
        int end = source.indexOf("*/", index + 2);
        if (end < 0) {
          throw invalid("INVALID_SKELETON", "Unterminated Foundation block comment");
        }
        index = end + 2;
      } else if (source.startsWith("//", index)) {
        index += 2;
        while (index < source.length()
            && source.charAt(index) != '\n'
            && source.charAt(index) != '\r'
            && source.charAt(index) != '\u2028'
            && source.charAt(index) != '\u2029') {
          index++;
        }
      } else {
        return;
      }
    }
  }

  private void require(char expected) {
    if (index >= source.length() || source.charAt(index++) != expected) {
      throw invalid("INVALID_SKELETON", "Unexpected Foundation strings separator");
    }
  }

  private static boolean unquoted(char value) {
    return value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z'
        || value >= '0' && value <= '9'
        || value == '_'
        || value == '$'
        || value == '/'
        || value == ':'
        || value == '.'
        || value == '-';
  }

  private static String singleQuoted(String source) {
    StringBuilder result = new StringBuilder(source.length());
    for (int index = 0; index < source.length(); index++) {
      char value = source.charAt(index);
      if (value == '\\' && index + 1 < source.length() && source.charAt(index + 1) == '"') {
        result.append('"');
        index++;
      } else if (value == '\'') {
        result.append("\\'");
      } else {
        result.append(value);
      }
    }
    return result.toString();
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private record XmlElement(String name, int bodyStart) {}

  private record XmlPart(boolean cdata, String source) {}
}
