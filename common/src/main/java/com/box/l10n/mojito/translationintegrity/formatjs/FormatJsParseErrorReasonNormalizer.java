package com.box.l10n.mojito.translationintegrity.formatjs;

/** Maps raw FormatJS parser failures to the portable conformance reason vocabulary. */
final class FormatJsParseErrorReasonNormalizer {

  private FormatJsParseErrorReasonNormalizer() {}

  static String normalize(FormatJsParseError error) {
    return switch (error.kind()) {
      case DUPLICATE_PLURAL_ARGUMENT_SELECTOR, DUPLICATE_SELECT_ARGUMENT_SELECTOR ->
          "duplicate-selector";
      case EMPTY_ARGUMENT, EXPECT_ARGUMENT_TYPE -> "missing-argument-or-selector";
      case MALFORMED_ARGUMENT -> normalizeMalformedArgument(error);
      case INVALID_ARGUMENT_TYPE -> "invalid-argument-type";
      case EXPECT_ARGUMENT_STYLE,
              EXPECT_DATE_TIME_SKELETON,
              EXPECT_NUMBER_SKELETON,
              INVALID_DATE_TIME_SKELETON,
              INVALID_NUMBER_SKELETON ->
          "invalid-argument-style";
      case UNCLOSED_QUOTE_IN_ARGUMENT_STYLE -> "unclosed-style-quote";
      case EXPECT_SELECT_ARGUMENT_OPTIONS -> "missing-other-branch";
      case EXPECT_PLURAL_ARGUMENT_OFFSET_VALUE -> normalizeExpectedPluralOffset(error);
      case INVALID_PLURAL_ARGUMENT_OFFSET_VALUE -> "invalid-plural-offset";
      case EXPECT_SELECT_ARGUMENT_SELECTOR -> normalizeExpectedSelectSelector(error);
      case EXPECT_PLURAL_ARGUMENT_SELECTOR -> normalizeExpectedPluralSelector(error);
      case EXPECT_SELECT_ARGUMENT_SELECTOR_FRAGMENT, EXPECT_PLURAL_ARGUMENT_SELECTOR_FRAGMENT ->
          normalizeSelectorFragment(error);
      case INVALID_PLURAL_ARGUMENT_SELECTOR -> "invalid-selector";
      case MISSING_OTHER_CLAUSE -> isAtEnd(error) ? "unclosed-selector" : "missing-other-branch";
      case EXPECT_ARGUMENT_CLOSING_BRACE -> normalizeExpectedClosingBrace(error);
      case MAX_NESTING_DEPTH_EXCEEDED -> "maximum-nesting-depth";
      case INVALID_TAG, INVALID_TAG_NAME, UNCLOSED_TAG, UNMATCHED_CLOSING_TAG ->
          throw new IllegalStateException(
              "Tag syntax must be opaque for the FormatJS integrity evaluator: " + error.kind());
    };
  }

  private static String normalizeMalformedArgument(FormatJsParseError error) {
    Integer current = codePointAtError(error);
    if (current != null
        && !Character.isLetterOrDigit(current)
        && !Character.isWhitespace(current)) {
      return "invalid-argument-name";
    }
    return "missing-argument-delimiter";
  }

  private static String normalizeExpectedPluralOffset(FormatJsParseError error) {
    Integer current = codePointAtError(error);
    return current == null || current == '}'
        ? "missing-argument-or-selector"
        : "invalid-plural-offset";
  }

  private static String normalizeExpectedSelectSelector(FormatJsParseError error) {
    Integer current = codePointAtError(error);
    return current == null || current == '}' || current == '{'
        ? "missing-argument-or-selector"
        : "invalid-selector";
  }

  private static String normalizeExpectedPluralSelector(FormatJsParseError error) {
    Integer current = codePointAtError(error);
    return current == null || current == '}' || current == '{'
        ? "missing-argument-or-selector"
        : "invalid-selector";
  }

  private static String normalizeSelectorFragment(FormatJsParseError error) {
    Integer current = codePointAtError(error);
    if (current != null && current == ':') {
      String previousIdentifier = previousIdentifier(error.originalMessage(), errorOffset(error));
      if (previousIdentifier.equals("offset")) {
        return "invalid-plural-offset";
      }
    }
    if (current != null
        && !Character.isLetterOrDigit(current)
        && current != '{'
        && current != '}') {
      return "invalid-selector";
    }
    return "unbraced-selector-branch";
  }

  private static String normalizeExpectedClosingBrace(FormatJsParseError error) {
    return switch (error.context()) {
      case ARGUMENT -> "unclosed-argument";
      case TYPED_ARGUMENT -> isAtEnd(error) ? "unclosed-typed-argument" : "missing-type-delimiter";
      case SELECT_ARGUMENT -> "unclosed-selector";
      case SELECTOR_BRANCH -> "unclosed-selector-branch";
      case GENERAL -> "unclosed-argument";
    };
  }

  private static boolean isAtEnd(FormatJsParseError error) {
    return errorOffset(error) >= error.originalMessage().length();
  }

  private static Integer codePointAtError(FormatJsParseError error) {
    int offset = errorOffset(error);
    return offset >= error.originalMessage().length()
        ? null
        : error.originalMessage().codePointAt(offset);
  }

  private static int errorOffset(FormatJsParseError error) {
    return error.location().end().offset();
  }

  private static String previousIdentifier(String message, int offset) {
    int end = offset;
    while (end > 0 && Character.isWhitespace(message.codePointBefore(end))) {
      end -= Character.charCount(message.codePointBefore(end));
    }
    int start = end;
    while (start > 0) {
      int codePoint = message.codePointBefore(start);
      if (!Character.isLetterOrDigit(codePoint) && codePoint != '_') {
        break;
      }
      start -= Character.charCount(codePoint);
    }
    return message.substring(start, end);
  }
}
