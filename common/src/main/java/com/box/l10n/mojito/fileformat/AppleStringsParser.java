package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class AppleStringsParser {

  private static final int[] NEXTSTEP_UNICODE = {
    0x00A0, 0x00C0, 0x00C1, 0x00C2, 0x00C3, 0x00C4, 0x00C5, 0x00C7,
    0x00C8, 0x00C9, 0x00CA, 0x00CB, 0x00CC, 0x00CD, 0x00CE, 0x00CF,
    0x00D0, 0x00D1, 0x00D2, 0x00D3, 0x00D4, 0x00D5, 0x00D6, 0x00D9,
    0x00DA, 0x00DB, 0x00DC, 0x00DD, 0x00DE, 0x00B5, 0x00D7, 0x00F7,
    0x00A9, 0x00A1, 0x00A2, 0x00A3, 0x2044, 0x00A5, 0x0192, 0x00A7,
    0x00A4, 0x2019, 0x201C, 0x00AB, 0x2039, 0x203A, 0xFB01, 0xFB02,
    0x00AE, 0x2013, 0x2020, 0x2021, 0x00B7, 0x00A6, 0x00B6, 0x2022,
    0x201A, 0x201E, 0x201D, 0x00BB, 0x2026, 0x2030, 0x00AC, 0x00BF,
    0x00B9, 0x02CB, 0x00B4, 0x02C6, 0x02DC, 0x00AF, 0x02D8, 0x02D9,
    0x00A8, 0x00B2, 0x02DA, 0x00B8, 0x00B3, 0x02DD, 0x02DB, 0x02C7,
    0x2014, 0x00B1, 0x00BC, 0x00BD, 0x00BE, 0x00E0, 0x00E1, 0x00E2,
    0x00E3, 0x00E4, 0x00E5, 0x00E7, 0x00E8, 0x00E9, 0x00EA, 0x00EB,
    0x00EC, 0x00C6, 0x00ED, 0x00AA, 0x00EE, 0x00EF, 0x00F0, 0x00F1,
    0x0141, 0x00D8, 0x0152, 0x00BA, 0x00F2, 0x00F3, 0x00F4, 0x00F5,
    0x00F6, 0x00E6, 0x00F9, 0x00FA, 0x00FB, 0x0131, 0x00FC, 0x00FD,
    0x0142, 0x00F8, 0x0153, 0x00DF, 0x00FE, 0x00FF, 0xFFFD, 0xFFFD
  };

  private final String source;
  private final LocalizationCatalog catalog =
      new LocalizationCatalog(LocalizationFileFormat.APPLE_STRINGS);
  private final List<String> comments = new ArrayList<>();
  private int index;

  AppleStringsParser(String source) {
    this.source = source;
  }

  static String decodeSourceToken(String source) {
    AppleStringsParser parser = new AppleStringsParser(source);
    String value = parser.token();
    if (parser.index != source.length()) {
      throw new LocalizationParseException("INVALID_SKELETON", "Invalid Apple source token");
    }
    return value;
  }

  LocalizationCatalog parse() {
    if (source.stripLeading().startsWith("<")) {
      return parseXmlPropertyList();
    }
    skipTrivia(true);
    boolean wrapped = index < source.length() && source.charAt(index) == '{';
    if (wrapped) {
      index++;
      comments.clear();
    }
    while (true) {
      skipTrivia(true);
      if (index >= source.length()) {
        if (wrapped) {
          throw invalid("Unclosed OpenStep strings dictionary");
        }
        return catalog;
      }
      if (wrapped && source.charAt(index) == '}') {
        index++;
        skipTrivia(false);
        if (index != source.length()) {
          throw invalid("Unexpected content after OpenStep strings dictionary");
        }
        return catalog;
      }
      String key = token();
      skipTrivia(false);
      String value;
      if (index < source.length() && source.charAt(index) == ';') {
        value = key;
      } else {
        require('=');
        skipTrivia(false);
        value = token();
        skipTrivia(false);
      }
      require(';');
      addMessage(key, value);
      comments.clear();
    }
  }

  private LocalizationCatalog parseXmlPropertyList() {
    Document document = SecureXmlParser.parseApplePlist(source);
    Element root = document.getDocumentElement();
    Element dictionary;
    if ("dict".equals(root.getTagName())) {
      dictionary = root;
    } else if ("plist".equals(root.getTagName())) {
      dictionary = onlyElement(root);
      if (dictionary == null || !"dict".equals(dictionary.getTagName())) {
        throw invalid("Apple strings property list requires a top-level dictionary");
      }
    } else {
      throw invalid("Apple strings property list requires a plist or dictionary root");
    }

    String key = null;
    NodeList children = dictionary.getChildNodes();
    for (int position = 0; position < children.getLength(); position++) {
      Node child = children.item(position);
      if (child instanceof Comment comment) {
        if (key == null) {
          comments.add(normalizeComment(comment.getData()));
        }
      } else if (child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
        // Foundation ignores processing instructions between dictionary entries.
      } else if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
        throw invalid("CDATA is not allowed between Apple property-list dictionary entries");
      } else if (child instanceof Element element) {
        if (key == null) {
          if (!"key".equals(element.getTagName()) || hasNestedElement(element)) {
            throw invalid("Apple strings property list requires a plain dictionary key");
          }
          key = element.getTextContent();
        } else {
          if (!"string".equals(element.getTagName()) || hasNestedElement(element)) {
            throw invalid("Apple strings property list values must be strings");
          }
          addMessage(key, element.getTextContent());
          comments.clear();
          key = null;
        }
      } else if (!xmlWhitespace(child.getTextContent())) {
        throw invalid("Unexpected text inside Apple strings property-list dictionary");
      }
    }
    if (key != null) {
      throw invalid("Apple strings property-list dictionary has an unpaired key");
    }
    return catalog;
  }

  private Element onlyElement(Element parent) {
    Element result = null;
    NodeList children = parent.getChildNodes();
    for (int position = 0; position < children.getLength(); position++) {
      Node child = children.item(position);
      if (child instanceof Element element) {
        if (result != null) {
          throw invalid("Apple strings property list contains more than one root value");
        }
        result = element;
      } else if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
        throw invalid("CDATA is not allowed between Apple property-list root values");
      } else if (!(child instanceof Comment)
          && child.getNodeType() != Node.PROCESSING_INSTRUCTION_NODE
          && !xmlWhitespace(child.getTextContent())) {
        throw invalid("Unexpected text inside Apple strings property-list element");
      }
    }
    return result;
  }

  private static boolean hasNestedElement(Element parent) {
    NodeList children = parent.getChildNodes();
    for (int position = 0; position < children.getLength(); position++) {
      if (children.item(position) instanceof Element
          || children.item(position) instanceof Comment
          || children.item(position).getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
        return true;
      }
    }
    return false;
  }

  private void addMessage(String key, String value) {
    catalog.add(key, message(value, String.join(" ", comments)));
  }

  static LocalizationMessage message(String value, String description) {
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized = PlaceholderNormalizer.normalizeFoundation(value, placeholders);
    Map<String, Object> metadata = new LinkedHashMap<>();
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationPrintfLineSeparators(value);
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized = withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put("appleDisabledPrintfConversions", disabled);
    }
    if (normalized.indexOf('<') >= 0) {
      normalized = normalized.replace("'", "''").replace("<", "'<'");
      metadata.put("appleMarkupEscaping", "icu-quoted-angle");
    }
    return LocalizationMessage.of(normalized, description, null, placeholders, metadata);
  }

  static String withoutDisabledPrintfConversions(
      String normalized,
      List<Map<String, Object>> conversions,
      List<Map<String, Object>> disabled) {
    StringBuilder visible = new StringBuilder(normalized.length());
    int conversion = 0;
    int position = 0;
    int visiblePosition = 0;
    for (int offset = 0; offset < normalized.length(); ) {
      int character = normalized.codePointAt(offset);
      if (conversion < conversions.size()
          && ((Number) conversions.get(conversion).get("position")).intValue() == position) {
        Map<String, Object> occurrence = new LinkedHashMap<>();
        occurrence.put("position", visiblePosition);
        occurrence.put("source", conversions.get(conversion).get("source"));
        if (conversions.get(conversion).containsKey("argumentPosition")) {
          occurrence.put("argumentPosition", conversions.get(conversion).get("argumentPosition"));
        }
        disabled.add(occurrence);
        conversion++;
      } else {
        visible.appendCodePoint(character);
        visiblePosition++;
      }
      offset += Character.charCount(character);
      position++;
    }
    return visible.toString();
  }

  private void skipTrivia(boolean collectComments) {
    while (index < source.length()) {
      skipWhitespace();
      if (source.startsWith("/*", index)) {
        int end = source.indexOf("*/", index + 2);
        if (end < 0) {
          throw invalid("Unclosed Apple strings block comment");
        }
        if (collectComments) {
          comments.add(normalizeComment(source.substring(index + 2, end)));
        }
        index = end + 2;
      } else if (source.startsWith("//", index)) {
        int end = index + 2;
        while (end < source.length()
            && source.charAt(end) != '\n'
            && source.charAt(end) != '\r'
            && source.charAt(end) != '\u2028'
            && source.charAt(end) != '\u2029') {
          end++;
        }
        if (collectComments) {
          comments.add(source.substring(index + 2, end).replaceAll("(?U)^\\s+|\\s+$", ""));
        }
        index = end;
      } else {
        return;
      }
    }
  }

  private String token() {
    if (index >= source.length()) {
      throw invalid("Expected an Apple strings token");
    }
    if (source.charAt(index) != '"' && source.charAt(index) != '\'') {
      int start = index;
      while (index < source.length() && isUnquotedCharacter(source.charAt(index))) {
        index++;
      }
      if (start == index) {
        throw invalid("Expected an Apple strings key or value");
      }
      return source.substring(start, index);
    }
    char delimiter = source.charAt(index++);
    StringBuilder result = new StringBuilder();
    while (index < source.length()) {
      char character = source.charAt(index++);
      if (character == delimiter) {
        return result.toString();
      }
      if (character != '\\') {
        result.append(character);
        continue;
      }
      if (index >= source.length()) {
        throw invalid("Trailing Apple strings escape");
      }
      char escaped = source.charAt(index++);
      switch (escaped) {
        case 'n' -> result.append('\n');
        case 'r' -> result.append('\r');
        case 't' -> result.append('\t');
        case 'a' -> result.append('\u0007');
        case 'b' -> result.append('\b');
        case 'f' -> result.append('\f');
        case 'v' -> result.append('\u000b');
        case '\n' -> result.append('\n');
        case '\r' -> result.append('\r');
        case 'U' -> appendUnicode(result);
        default -> {
          if (escaped >= '0' && escaped <= '7') {
            int value = escaped - '0';
            for (int count = 1; count < 3 && index < source.length(); count++) {
              char digit = source.charAt(index);
              if (digit < '0' || digit > '7') {
                break;
              }
              value = value * 8 + digit - '0';
              index++;
            }
            int byteValue = value & 0xff;
            int scalar = byteValue < 0x80 ? byteValue : NEXTSTEP_UNICODE[byteValue - 0x80];
            result.append((char) (scalar == 0xfffd ? 0 : scalar));
          } else {
            result.append(escaped);
          }
        }
      }
    }
    throw invalid("Unclosed Apple strings quoted value");
  }

  private void appendUnicode(StringBuilder result) {
    char first = unicodeUnit();
    if (Character.isHighSurrogate(first)) {
      if (index + 2 > source.length()
          || source.charAt(index) != '\\'
          || source.charAt(index + 1) != 'U') {
        throw invalid("Missing low Apple Unicode surrogate");
      }
      index += 2;
      char second = unicodeUnit();
      if (!Character.isLowSurrogate(second)) {
        throw invalid("Invalid Apple Unicode surrogate");
      }
      result.append(first).append(second);
    } else if (Character.isLowSurrogate(first)) {
      throw invalid("Invalid Apple Unicode scalar");
    } else {
      result.append(first);
    }
  }

  private char unicodeUnit() {
    int value = 0;
    int end = Math.min(index + 4, source.length());
    while (index < end) {
      char character = source.charAt(index);
      int digit =
          character >= '0' && character <= '9'
              ? character - '0'
              : character >= 'a' && character <= 'f'
                  ? character - 'a' + 10
                  : character >= 'A' && character <= 'F' ? character - 'A' + 10 : -1;
      if (digit < 0) {
        break;
      }
      value = value * 16 + digit;
      index++;
    }
    return (char) value;
  }

  private void skipWhitespace() {
    while (index < source.length() && isFoundationWhitespace(source.charAt(index))) {
      index++;
    }
  }

  private static boolean isFoundationWhitespace(char character) {
    return character >= '\t' && character <= '\r'
        || character == ' '
        || character == '\u2028'
        || character == '\u2029';
  }

  private static boolean xmlWhitespace(String value) {
    return value
        .chars()
        .allMatch(
            character ->
                character == ' ' || character == '\t' || character == '\n' || character == '\r');
  }

  private static String normalizeComment(String comment) {
    return comment.replaceAll("(?U)\\s+", " ").replaceAll("^ +| +$", "");
  }

  private static boolean isUnquotedCharacter(char character) {
    return character >= 'a' && character <= 'z'
        || character >= 'A' && character <= 'Z'
        || character >= '0' && character <= '9'
        || character == '_'
        || character == '$'
        || character == '/'
        || character == ':'
        || character == '.'
        || character == '-';
  }

  private void require(char expected) {
    if (index >= source.length() || source.charAt(index) != expected) {
      throw invalid("Expected '" + expected + "' in Apple strings file");
    }
    index++;
  }

  private LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_APPLE_STRINGS", message + " at offset " + index);
  }
}
