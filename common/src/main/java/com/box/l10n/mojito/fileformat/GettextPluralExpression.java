package com.box.l10n.mojito.fileformat;

import java.util.function.LongUnaryOperator;

/** Parses the restricted C-expression grammar allowed in gettext Plural-Forms headers. */
final class GettextPluralExpression {

  private final String source;
  private int index;

  private GettextPluralExpression(String source) {
    this.source = source;
  }

  static LongUnaryOperator parse(String source) {
    GettextPluralExpression parser = new GettextPluralExpression(source);
    LongUnaryOperator expression = parser.conditional();
    parser.whitespace();
    if (parser.index != source.length()) {
      throw invalid("Unexpected gettext plural expression token");
    }
    return expression;
  }

  static String trimHorizontalWhitespace(String source) {
    int start = 0;
    int end = source.length();
    while (start < end && horizontalWhitespace(source.charAt(start))) {
      start++;
    }
    while (end > start && horizontalWhitespace(source.charAt(end - 1))) {
      end--;
    }
    return source.substring(start, end);
  }

  static String trimLeadingZeroes(String digits) {
    int first = 0;
    while (first < digits.length() - 1 && digits.charAt(first) == '0') {
      first++;
    }
    return digits.substring(first);
  }

  private LongUnaryOperator conditional() {
    LongUnaryOperator condition = logicalOr();
    if (consume("?")) {
      LongUnaryOperator yes = conditional();
      require(":");
      LongUnaryOperator no = conditional();
      return n -> condition.applyAsLong(n) != 0 ? yes.applyAsLong(n) : no.applyAsLong(n);
    }
    return condition;
  }

  private LongUnaryOperator logicalOr() {
    LongUnaryOperator result = logicalAnd();
    while (consume("||")) {
      LongUnaryOperator left = result;
      LongUnaryOperator right = logicalAnd();
      result = n -> left.applyAsLong(n) != 0 || right.applyAsLong(n) != 0 ? 1 : 0;
    }
    return result;
  }

  private LongUnaryOperator logicalAnd() {
    LongUnaryOperator result = equality();
    while (consume("&&")) {
      LongUnaryOperator left = result;
      LongUnaryOperator right = equality();
      result = n -> left.applyAsLong(n) != 0 && right.applyAsLong(n) != 0 ? 1 : 0;
    }
    return result;
  }

  private LongUnaryOperator equality() {
    LongUnaryOperator result = comparison();
    while (true) {
      if (consume("==")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = comparison();
        result = n -> left.applyAsLong(n) == right.applyAsLong(n) ? 1 : 0;
      } else if (consume("!=")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = comparison();
        result = n -> left.applyAsLong(n) != right.applyAsLong(n) ? 1 : 0;
      } else {
        return result;
      }
    }
  }

  private LongUnaryOperator comparison() {
    LongUnaryOperator result = addition();
    while (true) {
      if (consume(">=")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = addition();
        result = n -> left.applyAsLong(n) >= right.applyAsLong(n) ? 1 : 0;
      } else if (consume("<=")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = addition();
        result = n -> left.applyAsLong(n) <= right.applyAsLong(n) ? 1 : 0;
      } else if (consume(">")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = addition();
        result = n -> left.applyAsLong(n) > right.applyAsLong(n) ? 1 : 0;
      } else if (consume("<")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = addition();
        result = n -> left.applyAsLong(n) < right.applyAsLong(n) ? 1 : 0;
      } else {
        return result;
      }
    }
  }

  private LongUnaryOperator addition() {
    LongUnaryOperator result = multiplication();
    while (true) {
      if (consume("+")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = multiplication();
        result = n -> Math.addExact(left.applyAsLong(n), right.applyAsLong(n));
      } else if (consume("-")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = multiplication();
        result = n -> Math.subtractExact(left.applyAsLong(n), right.applyAsLong(n));
      } else {
        return result;
      }
    }
  }

  private LongUnaryOperator multiplication() {
    LongUnaryOperator result = unary();
    while (true) {
      if (consume("*")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = unary();
        result = n -> Math.multiplyExact(left.applyAsLong(n), right.applyAsLong(n));
      } else if (consume("/")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = unary();
        result = n -> Math.divideExact(left.applyAsLong(n), right.applyAsLong(n));
      } else if (consume("%")) {
        LongUnaryOperator left = result;
        LongUnaryOperator right = unary();
        result = n -> left.applyAsLong(n) % right.applyAsLong(n);
      } else {
        return result;
      }
    }
  }

  private LongUnaryOperator unary() {
    if (consume("!")) {
      LongUnaryOperator value = unary();
      return n -> value.applyAsLong(n) == 0 ? 1 : 0;
    }
    if (consume("(")) {
      LongUnaryOperator value = conditional();
      require(")");
      return value;
    }
    if (consume("n")) {
      return n -> n;
    }
    whitespace();
    int start = index;
    while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') {
      index++;
    }
    if (start == index) {
      throw invalid("Expected gettext plural expression operand");
    }
    try {
      long value = Long.parseLong(trimLeadingZeroes(source.substring(start, index)));
      return n -> value;
    } catch (NumberFormatException exception) {
      throw invalid("Gettext plural expression number is out of range");
    }
  }

  private boolean consume(String token) {
    whitespace();
    if (source.startsWith(token, index)) {
      index += token.length();
      return true;
    }
    return false;
  }

  private void require(String token) {
    if (!consume(token)) {
      throw invalid("Expected '" + token + "' in gettext plural expression");
    }
  }

  private void whitespace() {
    while (index < source.length() && horizontalWhitespace(source.charAt(index))) {
      index++;
    }
  }

  private static boolean horizontalWhitespace(char character) {
    return character == ' ' || character == '\t';
  }

  static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_GETTEXT_PLURAL_FORMS", message);
  }
}
