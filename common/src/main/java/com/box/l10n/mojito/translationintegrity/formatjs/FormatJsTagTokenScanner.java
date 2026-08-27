package com.box.l10n.mojito.translationintegrity.formatjs;

/** Exact portable tag-token grammar used by the FormatJS apostrophe contract. */
final class FormatJsTagTokenScanner {

  private FormatJsTagTokenScanner() {}

  static TagToken scan(String value, int offset) {
    if (offset >= value.length() || value.charAt(offset) != '<') {
      return null;
    }
    int position = offset + 1;
    boolean opening = true;
    if (position < value.length() && value.charAt(position) == '/') {
      opening = false;
      position++;
    }
    if (position >= value.length() || !isAsciiAlpha(value.charAt(position))) {
      return null;
    }
    position++;
    while (position < value.length() && isAsciiTagNameCharacter(value.charAt(position))) {
      position++;
    }

    if (!opening) {
      position = skipWhitespace(value, position);
      return position < value.length() && value.charAt(position) == '>'
          ? new TagToken(position + 1, value.substring(offset, position + 1), false)
          : null;
    }

    while (position < value.length()) {
      int boundary = position;
      position = skipWhitespace(value, position);
      if (position < value.length() && value.charAt(position) == '>') {
        return new TagToken(position + 1, value.substring(offset, position + 1), true);
      }
      if (position + 1 < value.length()
          && value.charAt(position) == '/'
          && value.charAt(position + 1) == '>') {
        return new TagToken(position + 2, value.substring(offset, position + 2), true);
      }
      if (position == boundary
          || position >= value.length()
          || !isAsciiAttributeNameStart(value.charAt(position))) {
        return null;
      }

      position++;
      while (position < value.length() && isAsciiAttributeNameCharacter(value.charAt(position))) {
        position++;
      }
      int attributeEnd = position;
      int equals = skipWhitespace(value, position);
      if (equals >= value.length() || value.charAt(equals) != '=') {
        position = attributeEnd;
        continue;
      }

      position = skipWhitespace(value, equals + 1);
      if (position >= value.length()) {
        return null;
      }
      char quote = value.charAt(position);
      if (quote == '\'' || quote == '"') {
        int close = value.indexOf(quote, position + 1);
        if (close < 0) {
          return null;
        }
        position = close + 1;
        continue;
      }

      int valueStart = position;
      while (position < value.length()
          && !isWhitespace(value.charAt(position))
          && "\"'=<>`".indexOf(value.charAt(position)) < 0) {
        position++;
      }
      if (position == valueStart) {
        return null;
      }
    }
    return null;
  }

  private static int skipWhitespace(String value, int position) {
    while (position < value.length() && isWhitespace(value.charAt(position))) {
      position++;
    }
    return position;
  }

  private static boolean isWhitespace(char character) {
    return character >= 0x09 && character <= 0x0D
        || character == 0x20
        || character == 0x85
        || character >= 0x200E && character <= 0x200F
        || character >= 0x2028 && character <= 0x2029;
  }

  private static boolean isAsciiAlpha(char character) {
    return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
  }

  private static boolean isAsciiTagNameCharacter(char character) {
    return isAsciiAlpha(character)
        || character >= '0' && character <= '9'
        || character == '-'
        || character == '.'
        || character == ':';
  }

  private static boolean isAsciiAttributeNameStart(char character) {
    return isAsciiAlpha(character) || character == '_' || character == ':';
  }

  private static boolean isAsciiAttributeNameCharacter(char character) {
    return isAsciiAttributeNameStart(character)
        || character >= '0' && character <= '9'
        || character == '-'
        || character == '.';
  }

  record TagToken(int endOffset, String value, boolean opening) {}
}
