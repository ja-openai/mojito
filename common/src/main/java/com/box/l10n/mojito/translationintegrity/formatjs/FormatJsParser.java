package com.box.l10n.mojito.translationintegrity.formatjs;

import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Argument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.DateArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.DateTimeSkeleton;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.FormatJsStyle;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Literal;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NamedStyle;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberSkeleton;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberSkeletonToken;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Option;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.PluralArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.PluralType;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Pound;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.SelectArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Tag;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.TimeArgument;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validation-only Java port of {@code @formatjs/icu-messageformat-parser} 3.5.10.
 *
 * <p>This class follows the upstream recursive-descent state machine rather than delegating to
 * ICU4J. Mojito adds an optional maximum nesting depth and keeps validation locale-neutral; it does
 * not expose FormatJS's rendering-oriented {@code Intl.Locale} skeleton resolution. Use {@link
 * FormatJsParserOptions#LOW_LEVEL_DEFAULTS} for upstream low-level constructor behavior, {@link
 * FormatJsParserOptions#UPSTREAM_PARSE_DEFAULTS} for the exported FormatJS facade behavior, or
 * {@link FormatJsParserOptions#MOJITO_STRICT} for integrity validation.
 *
 * <p>Instances are deterministically single-use. This intentionally avoids the upstream low-level
 * parser's accidental ability to retry only when a failed parse leaves its offset at zero.
 */
public final class FormatJsParser {

  private static final BigInteger MAX_SAFE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);
  private static final BigInteger MIN_SAFE_INTEGER = MAX_SAFE_INTEGER.negate();

  private final String message;
  private final FormatJsParserOptions options;
  private int offset;
  private int line = 1;
  private int column = 1;
  private boolean used;

  /** Creates the equivalent of FormatJS's low-level {@code new Parser(message)}. */
  public FormatJsParser(String message) {
    this(message, FormatJsParserOptions.LOW_LEVEL_DEFAULTS);
  }

  public FormatJsParser(String message, FormatJsParserOptions options) {
    this.message = Objects.requireNonNull(message, "message");
    this.options = Objects.requireNonNull(options, "options");
  }

  /** Parses once and returns the low-level success/error union without throwing. */
  public FormatJsParseResult parseResult() {
    if (used) {
      throw new IllegalStateException("parser can only be used once");
    }
    used = true;
    try {
      return FormatJsParseResult.success(parseMessage(0, ParentArgumentType.NONE, false));
    } catch (ParseFailure failure) {
      return FormatJsParseResult.failure(failure.error);
    }
  }

  public List<FormatJsElement> parseOrThrow() {
    return parseResult().orElseThrow();
  }

  /** Equivalent to FormatJS's exported {@code parse(message)} facade. */
  public static List<FormatJsElement> parse(String message) {
    return parse(message, FormatJsParserOptions.UPSTREAM_PARSE_DEFAULTS);
  }

  public static List<FormatJsElement> parse(String message, FormatJsParserOptions options) {
    List<FormatJsElement> value = new FormatJsParser(message, options).parseOrThrow();
    return options.captureLocation() ? value : withoutLocations(value);
  }

  public static FormatJsParseResult parseResult(String message, FormatJsParserOptions options) {
    return new FormatJsParser(message, options).parseResult();
  }

  private List<FormatJsElement> parseMessage(
      int nestingLevel, ParentArgumentType parentArgumentType, boolean expectingCloseTag) {
    List<FormatJsElement> elements = new ArrayList<>();
    while (!isEof()) {
      int current = currentCodePoint();
      if (current == '{') {
        elements.add(parseArgument(nestingLevel, expectingCloseTag));
      } else if (current == '}' && nestingLevel > 0) {
        break;
      } else if (current == '#'
          && (parentArgumentType == ParentArgumentType.PLURAL
              || parentArgumentType == ParentArgumentType.SELECTORDINAL)) {
        FormatJsSourcePosition start = position();
        bump();
        elements.add(new Pound(astLocation(location(start, position()))));
      } else if (current == '<' && !options.ignoreTag() && peekCodeUnit() == '/') {
        if (expectingCloseTag) {
          break;
        }
        fail(FormatJsParseErrorKind.UNMATCHED_CLOSING_TAG, location(position(), position()));
      } else if (current == '<' && !options.ignoreTag() && isAsciiAlpha(peekCodeUnit())) {
        elements.add(parseTag(nestingLevel, parentArgumentType));
      } else {
        elements.add(parseLiteral(nestingLevel, parentArgumentType));
      }
    }
    return List.copyOf(elements);
  }

  private FormatJsElement parseTag(int nestingLevel, ParentArgumentType parentArgumentType) {
    FormatJsSourcePosition start = position();
    bump();
    String tagName = parseTagName();
    bumpSpace();
    if (bumpIf("/>")) {
      return new Literal("<" + tagName + "/>", astLocation(location(start, position())));
    }
    if (!bumpIf(">")) {
      fail(FormatJsParseErrorKind.INVALID_TAG, location(start, position()));
    }

    ensureCanDescend(nestingLevel, start);
    List<FormatJsElement> children = parseMessage(nestingLevel + 1, parentArgumentType, true);
    FormatJsSourcePosition endTagStart = position();
    if (!bumpIf("</")) {
      fail(FormatJsParseErrorKind.UNCLOSED_TAG, location(start, position()));
    }
    if (isEof() || !isAsciiAlpha(currentCodePoint())) {
      fail(FormatJsParseErrorKind.INVALID_TAG, location(endTagStart, position()));
    }
    FormatJsSourcePosition closingNameStart = position();
    String closingName = parseTagName();
    if (!tagName.equals(closingName)) {
      fail(FormatJsParseErrorKind.UNMATCHED_CLOSING_TAG, location(closingNameStart, position()));
    }
    bumpSpace();
    if (!bumpIf(">")) {
      fail(FormatJsParseErrorKind.INVALID_TAG, location(endTagStart, position()));
    }
    return new Tag(tagName, children, astLocation(location(start, position())));
  }

  private String parseTagName() {
    int start = offset;
    bump();
    while (!isEof() && isPotentialElementNameChar(currentCodePoint())) {
      bump();
    }
    return message.substring(start, offset);
  }

  private Literal parseLiteral(int nestingLevel, ParentArgumentType parentArgumentType) {
    FormatJsSourcePosition start = position();
    StringBuilder value = new StringBuilder();
    while (true) {
      String quoted = tryParseQuote(parentArgumentType);
      if (quoted != null) {
        value.append(quoted);
        continue;
      }
      String unquoted = tryParseUnquoted(nestingLevel, parentArgumentType);
      if (unquoted != null) {
        value.append(unquoted);
        continue;
      }
      String leftAngle = tryParseLeftAngleBracket();
      if (leftAngle != null) {
        value.append(leftAngle);
        continue;
      }
      break;
    }
    return new Literal(value.toString(), astLocation(location(start, position())));
  }

  private String tryParseLeftAngleBracket() {
    if (!isEof()
        && currentCodePoint() == '<'
        && (options.ignoreTag() || !isAsciiAlphaOrSlash(peekCodeUnit()))) {
      bump();
      return "<";
    }
    return null;
  }

  private String tryParseQuote(ParentArgumentType parentArgumentType) {
    if (isEof() || currentCodePoint() != '\'') {
      return null;
    }
    Integer next = peekCodeUnit();
    if (next != null && next == '\'') {
      bump();
      bump();
      return "'";
    }
    boolean isSyntax = next != null && (next == '{' || next == '<' || next == '>' || next == '}');
    if (next != null && next == '#') {
      isSyntax =
          parentArgumentType == ParentArgumentType.PLURAL
              || parentArgumentType == ParentArgumentType.SELECTORDINAL;
    }
    if (!isSyntax) {
      return null;
    }

    bump();
    StringBuilder value = new StringBuilder();
    value.appendCodePoint(currentCodePoint());
    bump();
    while (!isEof()) {
      int current = currentCodePoint();
      if (current == '\'') {
        if (peekCodeUnit() != null && peekCodeUnit() == '\'') {
          value.append('\'');
          bump();
        } else {
          bump();
          break;
        }
      } else {
        value.appendCodePoint(current);
      }
      bump();
    }
    return value.toString();
  }

  private String tryParseUnquoted(int nestingLevel, ParentArgumentType parentArgumentType) {
    if (isEof()) {
      return null;
    }
    int current = currentCodePoint();
    if (current == '<'
        || current == '{'
        || (current == '#'
            && (parentArgumentType == ParentArgumentType.PLURAL
                || parentArgumentType == ParentArgumentType.SELECTORDINAL))
        || (current == '}' && nestingLevel > 0)) {
      return null;
    }
    bump();
    return new String(Character.toChars(current));
  }

  private FormatJsElement parseArgument(int nestingLevel, boolean expectingCloseTag) {
    FormatJsSourcePosition openingBrace = position();
    bump();
    bumpSpace();
    if (isEof()) {
      fail(
          FormatJsParseErrorKind.EXPECT_ARGUMENT_CLOSING_BRACE,
          location(openingBrace, position()),
          FormatJsParseErrorContext.ARGUMENT);
    }
    if (currentCodePoint() == '}') {
      bump();
      fail(FormatJsParseErrorKind.EMPTY_ARGUMENT, location(openingBrace, position()));
    }
    Identifier value = parseIdentifierIfPossible();
    if (value.value().isEmpty()) {
      fail(FormatJsParseErrorKind.MALFORMED_ARGUMENT, location(openingBrace, position()));
    }
    bumpSpace();
    if (isEof()) {
      fail(
          FormatJsParseErrorKind.EXPECT_ARGUMENT_CLOSING_BRACE,
          location(openingBrace, position()),
          FormatJsParseErrorContext.ARGUMENT);
    }
    if (currentCodePoint() == '}') {
      bump();
      return new Argument(value.value(), astLocation(location(openingBrace, position())));
    }
    if (currentCodePoint() == ',') {
      bump();
      bumpSpace();
      if (isEof()) {
        fail(
            FormatJsParseErrorKind.EXPECT_ARGUMENT_CLOSING_BRACE,
            location(openingBrace, position()),
            FormatJsParseErrorContext.TYPED_ARGUMENT);
      }
      return parseArgumentOptions(nestingLevel, expectingCloseTag, value.value(), openingBrace);
    }
    fail(FormatJsParseErrorKind.MALFORMED_ARGUMENT, location(openingBrace, position()));
    throw new AssertionError("unreachable");
  }

  private Identifier parseIdentifierIfPossible() {
    FormatJsSourcePosition start = position();
    int startOffset = offset;
    while (!isEof()) {
      int current = currentCodePoint();
      if (isUnicodeWhiteSpace(current) || isPatternSyntax(current)) {
        break;
      }
      bump();
    }
    return new Identifier(message.substring(startOffset, offset), location(start, position()));
  }

  private FormatJsElement parseArgumentOptions(
      int nestingLevel,
      boolean expectingCloseTag,
      String value,
      FormatJsSourcePosition openingBrace) {
    FormatJsSourcePosition typeStart = position();
    String argumentType = parseIdentifierIfPossible().value();
    FormatJsSourcePosition typeEnd = position();
    if (argumentType.isEmpty()) {
      fail(FormatJsParseErrorKind.EXPECT_ARGUMENT_TYPE, location(typeStart, typeEnd));
    }
    return switch (argumentType) {
      case "number", "date", "time" ->
          parseSimpleArgument(nestingLevel, argumentType, value, openingBrace);
      case "plural", "selectordinal", "select" ->
          parseComplexArgument(
              nestingLevel, expectingCloseTag, argumentType, value, openingBrace, typeEnd);
      default -> {
        fail(FormatJsParseErrorKind.INVALID_ARGUMENT_TYPE, location(typeStart, typeEnd));
        throw new AssertionError("unreachable");
      }
    };
  }

  private FormatJsElement parseSimpleArgument(
      int nestingLevel, String argumentType, String value, FormatJsSourcePosition openingBrace) {
    bumpSpace();
    StyleAndLocation style = null;
    if (bumpIf(",")) {
      bumpSpace();
      FormatJsSourcePosition styleStart = position();
      String parsedStyle = trimEcmaScriptEnd(parseSimpleArgumentStyle(nestingLevel));
      if (parsedStyle.isEmpty()) {
        fail(FormatJsParseErrorKind.EXPECT_ARGUMENT_STYLE, location(position(), position()));
      }
      style = new StyleAndLocation(parsedStyle, location(styleStart, position()));
    }
    tryParseArgumentClose(openingBrace, FormatJsParseErrorContext.TYPED_ARGUMENT);
    FormatJsSourceLocation argumentLocation = location(openingBrace, position());
    FormatJsStyle parsedStyle = style == null ? null : new NamedStyle(style.style());
    if (style != null && style.style().startsWith("::")) {
      String skeleton = trimEcmaScriptStart(style.style().substring(2));
      if (argumentType.equals("number")) {
        parsedStyle = parseNumberSkeleton(skeleton, style.location());
      } else {
        if (skeleton.isEmpty()) {
          fail(FormatJsParseErrorKind.EXPECT_DATE_TIME_SKELETON, argumentLocation);
        }
        String pattern = skeleton;
        Map<String, Object> parsedOptions = Map.of();
        if (options.shouldParseSkeletons()) {
          try {
            parsedOptions = FormatJsSkeletonParser.parseDateTimeSkeleton(pattern);
          } catch (FormatJsSkeletonParser.SkeletonSyntaxException exception) {
            throw new FormatJsSkeletonException(
                FormatJsSkeletonException.SkeletonType.DATE_TIME,
                exception.getMessage(),
                exception);
          }
        }
        parsedStyle = new DateTimeSkeleton(pattern, parsedOptions, astLocation(style.location()));
      }
    }
    FormatJsSourceLocation captured = astLocation(argumentLocation);
    return switch (argumentType) {
      case "number" -> new NumberArgument(value, parsedStyle, captured);
      case "date" -> new DateArgument(value, parsedStyle, captured);
      case "time" -> new TimeArgument(value, parsedStyle, captured);
      default -> throw new AssertionError("unexpected simple argument type");
    };
  }

  private NumberSkeleton parseNumberSkeleton(
      String skeleton, FormatJsSourceLocation styleLocation) {
    List<NumberSkeletonToken> tokens;
    try {
      tokens = FormatJsSkeletonParser.tokenizeNumberSkeleton(skeleton);
    } catch (FormatJsSkeletonParser.NumberSkeletonTokenizationException exception) {
      fail(FormatJsParseErrorKind.INVALID_NUMBER_SKELETON, styleLocation);
      throw new AssertionError("unreachable");
    }
    Map<String, Object> parsedOptions = Map.of();
    if (options.shouldParseSkeletons()) {
      try {
        parsedOptions = FormatJsSkeletonParser.parseNumberSkeleton(tokens);
      } catch (FormatJsSkeletonParser.SkeletonSyntaxException exception) {
        throw new FormatJsSkeletonException(
            FormatJsSkeletonException.SkeletonType.NUMBER, exception.getMessage(), exception);
      }
    }
    return new NumberSkeleton(tokens, parsedOptions, astLocation(styleLocation));
  }

  private FormatJsElement parseComplexArgument(
      int nestingLevel,
      boolean expectingCloseTag,
      String argumentType,
      String value,
      FormatJsSourcePosition openingBrace,
      FormatJsSourcePosition typeEnd) {
    bumpSpace();
    if (!bumpIf(",")) {
      fail(FormatJsParseErrorKind.EXPECT_SELECT_ARGUMENT_OPTIONS, location(typeEnd, typeEnd));
    }
    bumpSpace();
    Identifier firstSelector = parseIdentifierIfPossible();
    double pluralOffset = 0;
    if (!argumentType.equals("select") && firstSelector.value().equals("offset")) {
      if (!bumpIf(":")) {
        fail(
            FormatJsParseErrorKind.EXPECT_PLURAL_ARGUMENT_OFFSET_VALUE,
            location(position(), position()));
      }
      bumpSpace();
      pluralOffset =
          parseDecimalInteger(
              FormatJsParseErrorKind.EXPECT_PLURAL_ARGUMENT_OFFSET_VALUE,
              FormatJsParseErrorKind.INVALID_PLURAL_ARGUMENT_OFFSET_VALUE);
      bumpSpace();
      firstSelector = parseIdentifierIfPossible();
    }

    ParentArgumentType parentType =
        switch (argumentType) {
          case "select" -> ParentArgumentType.SELECT;
          case "plural" -> ParentArgumentType.PLURAL;
          case "selectordinal" -> ParentArgumentType.SELECTORDINAL;
          default -> throw new AssertionError("unexpected complex argument type");
        };
    Map<String, Option> parsedOptions =
        parsePluralOrSelectOptions(nestingLevel, parentType, expectingCloseTag, firstSelector);
    tryParseArgumentClose(openingBrace, FormatJsParseErrorContext.SELECT_ARGUMENT);
    FormatJsSourceLocation captured = astLocation(location(openingBrace, position()));
    if (parentType == ParentArgumentType.SELECT) {
      return new SelectArgument(value, parsedOptions, captured);
    }
    return new PluralArgument(
        value,
        parsedOptions,
        pluralOffset,
        parentType == ParentArgumentType.PLURAL ? PluralType.CARDINAL : PluralType.ORDINAL,
        captured);
  }

  private Map<String, Option> parsePluralOrSelectOptions(
      int nestingLevel,
      ParentArgumentType parentType,
      boolean expectingCloseTag,
      Identifier firstSelector) {
    boolean hasOtherClause = false;
    Map<String, Option> parsedOptions = new LinkedHashMap<>();
    Set<String> parsedSelectors = new LinkedHashSet<>();
    String selector = firstSelector.value();
    FormatJsSourceLocation selectorLocation = firstSelector.location();
    while (true) {
      if (selector.isEmpty()) {
        FormatJsSourcePosition selectorStart = position();
        if (parentType != ParentArgumentType.SELECT && bumpIf("=")) {
          parseDecimalInteger(
              FormatJsParseErrorKind.EXPECT_PLURAL_ARGUMENT_SELECTOR,
              FormatJsParseErrorKind.INVALID_PLURAL_ARGUMENT_SELECTOR);
          selectorLocation = location(selectorStart, position());
          selector = message.substring(selectorStart.offset(), offset);
        } else {
          break;
        }
      }
      if (!parsedSelectors.add(selector)) {
        fail(
            parentType == ParentArgumentType.SELECT
                ? FormatJsParseErrorKind.DUPLICATE_SELECT_ARGUMENT_SELECTOR
                : FormatJsParseErrorKind.DUPLICATE_PLURAL_ARGUMENT_SELECTOR,
            selectorLocation);
      }
      if (selector.equals("other")) {
        hasOtherClause = true;
      }
      bumpSpace();
      FormatJsSourcePosition openingBrace = position();
      if (!bumpIf("{")) {
        fail(
            parentType == ParentArgumentType.SELECT
                ? FormatJsParseErrorKind.EXPECT_SELECT_ARGUMENT_SELECTOR_FRAGMENT
                : FormatJsParseErrorKind.EXPECT_PLURAL_ARGUMENT_SELECTOR_FRAGMENT,
            location(position(), position()));
      }
      ensureCanDescend(nestingLevel, openingBrace);
      List<FormatJsElement> fragment =
          parseMessage(nestingLevel + 1, parentType, expectingCloseTag);
      tryParseArgumentClose(openingBrace, FormatJsParseErrorContext.SELECTOR_BRANCH);
      parsedOptions.put(
          selector, new Option(fragment, astLocation(location(openingBrace, position()))));
      bumpSpace();
      Identifier nextSelector = parseIdentifierIfPossible();
      selector = nextSelector.value();
      selectorLocation = nextSelector.location();
    }
    if (parsedOptions.isEmpty()) {
      fail(
          parentType == ParentArgumentType.SELECT
              ? FormatJsParseErrorKind.EXPECT_SELECT_ARGUMENT_SELECTOR
              : FormatJsParseErrorKind.EXPECT_PLURAL_ARGUMENT_SELECTOR,
          location(position(), position()));
    }
    if (options.requiresOtherClause() && !hasOtherClause) {
      fail(FormatJsParseErrorKind.MISSING_OTHER_CLAUSE, location(position(), position()));
    }
    return parsedOptions;
  }

  private void tryParseArgumentClose(
      FormatJsSourcePosition openingBrace, FormatJsParseErrorContext context) {
    if (isEof() || currentCodePoint() != '}') {
      fail(
          FormatJsParseErrorKind.EXPECT_ARGUMENT_CLOSING_BRACE,
          location(openingBrace, position()),
          context);
    }
    bump();
  }

  private String parseSimpleArgumentStyle(int nestingLevel) {
    int nestedBraces = 0;
    FormatJsSourcePosition start = position();
    while (!isEof()) {
      switch (currentCodePoint()) {
        case '\'' -> {
          bump();
          FormatJsSourcePosition apostrophe = position();
          if (!bumpUntil("'")) {
            fail(
                FormatJsParseErrorKind.UNCLOSED_QUOTE_IN_ARGUMENT_STYLE,
                location(apostrophe, position()));
          }
          bump();
        }
        case '{' -> {
          nestedBraces++;
          int maximum = options.maxNestingDepth();
          if (maximum > 0 && nestingLevel + 1 + nestedBraces > maximum) {
            fail(
                FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED,
                location(position(), position()));
          }
          bump();
        }
        case '}' -> {
          if (nestedBraces > 0) {
            // 3.5.10 deliberately observes this brace again on the next loop iteration.
            nestedBraces--;
          } else {
            return message.substring(start.offset(), offset);
          }
        }
        default -> bump();
      }
    }
    return message.substring(start.offset(), offset);
  }

  private double parseDecimalInteger(
      FormatJsParseErrorKind expectNumberError, FormatJsParseErrorKind invalidNumberError) {
    FormatJsSourcePosition start = position();
    if (bumpIf("+")) {
      // Positive by default.
    } else {
      bumpIf("-");
    }
    int digitsStart = offset;
    while (!isEof()) {
      int current = currentCodePoint();
      if (current < '0' || current > '9') {
        break;
      }
      bump();
    }
    FormatJsSourceLocation numberLocation = location(start, position());
    if (digitsStart == offset) {
      fail(expectNumberError, numberLocation);
    }
    BigInteger parsed;
    try {
      parsed = new BigInteger(message.substring(start.offset(), offset));
    } catch (NumberFormatException exception) {
      fail(invalidNumberError, numberLocation);
      throw new AssertionError("unreachable");
    }
    if (parsed.compareTo(MIN_SAFE_INTEGER) < 0 || parsed.compareTo(MAX_SAFE_INTEGER) > 0) {
      fail(invalidNumberError, numberLocation);
    }
    if (parsed.signum() == 0 && message.charAt(start.offset()) == '-') {
      return -0.0d;
    }
    return parsed.doubleValue();
  }

  private void ensureCanDescend(int nestingLevel, FormatJsSourcePosition start) {
    int maximum = options.maxNestingDepth();
    if (maximum > 0 && nestingLevel + 1 > maximum) {
      fail(FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED, location(start, position()));
    }
  }

  private boolean isEof() {
    return offset == message.length();
  }

  private int currentCodePoint() {
    if (isEof()) {
      throw new IllegalStateException("offset is out of bounds");
    }
    return message.codePointAt(offset);
  }

  private Integer peekCodeUnit() {
    if (isEof()) {
      return null;
    }
    int width = Character.charCount(currentCodePoint());
    int next = offset + width;
    return next < message.length() ? (int) message.charAt(next) : null;
  }

  private void bump() {
    if (isEof()) {
      return;
    }
    int current = currentCodePoint();
    if (current == '\n') {
      line++;
      column = 1;
      offset++;
    } else {
      column++;
      offset += Character.charCount(current);
    }
  }

  private boolean bumpIf(String prefix) {
    if (!message.startsWith(prefix, offset)) {
      return false;
    }
    int target = offset + prefix.length();
    while (offset < target) {
      bump();
    }
    return true;
  }

  private boolean bumpUntil(String pattern) {
    int index = message.indexOf(pattern, offset);
    if (index < 0) {
      bumpTo(message.length());
      return false;
    }
    bumpTo(index);
    return true;
  }

  private void bumpTo(int targetOffset) {
    if (targetOffset < offset) {
      throw new IllegalArgumentException("targetOffset must not precede current offset");
    }
    int target = Math.min(targetOffset, message.length());
    while (offset < target) {
      bump();
      if (offset > target) {
        throw new IllegalArgumentException("targetOffset is inside a surrogate pair");
      }
    }
  }

  private void bumpSpace() {
    while (!isEof() && isFormatJsWhitespace(currentCodePoint())) {
      bump();
    }
  }

  private FormatJsSourcePosition position() {
    return new FormatJsSourcePosition(offset, line, column);
  }

  private static FormatJsSourceLocation location(
      FormatJsSourcePosition start, FormatJsSourcePosition end) {
    return new FormatJsSourceLocation(start, end);
  }

  private FormatJsSourceLocation astLocation(FormatJsSourceLocation location) {
    // The low-level Parser always captures locations. The exported parse facade prunes them.
    return location;
  }

  private static List<FormatJsElement> withoutLocations(List<FormatJsElement> elements) {
    List<FormatJsElement> pruned = new ArrayList<>(elements.size());
    for (FormatJsElement element : elements) {
      pruned.add(withoutLocation(element));
    }
    return List.copyOf(pruned);
  }

  private static FormatJsElement withoutLocation(FormatJsElement element) {
    return switch (element) {
      case Literal literal -> new Literal(literal.value(), null);
      case Argument argument -> new Argument(argument.value(), null);
      case NumberArgument number ->
          new NumberArgument(number.value(), withoutLocation(number.style()), null);
      case DateArgument date -> new DateArgument(date.value(), withoutLocation(date.style()), null);
      case TimeArgument time -> new TimeArgument(time.value(), withoutLocation(time.style()), null);
      case SelectArgument select ->
          new SelectArgument(select.value(), withoutLocations(select.options()), null);
      case PluralArgument plural ->
          new PluralArgument(
              plural.value(),
              withoutLocations(plural.options()),
              plural.offset(),
              plural.pluralType(),
              null);
      case Pound ignored -> new Pound(null);
      case Tag tag -> new Tag(tag.value(), withoutLocations(tag.children()), null);
    };
  }

  private static FormatJsStyle withoutLocation(FormatJsStyle style) {
    return switch (style) {
      case null -> null;
      case NamedStyle named -> named;
      case NumberSkeleton number ->
          new NumberSkeleton(number.tokens(), number.parsedOptions(), null);
      case DateTimeSkeleton dateTime ->
          new DateTimeSkeleton(dateTime.pattern(), dateTime.parsedOptions(), null);
    };
  }

  private static Map<String, Option> withoutLocations(Map<String, Option> options) {
    Map<String, Option> pruned = new LinkedHashMap<>();
    options.forEach(
        (selector, option) ->
            pruned.put(selector, new Option(withoutLocations(option.value()), null)));
    return pruned;
  }

  private void fail(FormatJsParseErrorKind kind, FormatJsSourceLocation location) {
    fail(kind, location, FormatJsParseErrorContext.GENERAL);
  }

  private void fail(
      FormatJsParseErrorKind kind,
      FormatJsSourceLocation location,
      FormatJsParseErrorContext context) {
    throw new ParseFailure(new FormatJsParseError(kind, message, location, context));
  }

  private static String trimEcmaScriptStart(String value) {
    int index = 0;
    while (index < value.length()) {
      int current = value.codePointAt(index);
      if (!isEcmaScriptTrimWhitespace(current)) {
        break;
      }
      index += Character.charCount(current);
    }
    return value.substring(index);
  }

  private static String trimEcmaScriptEnd(String value) {
    int index = value.length();
    while (index > 0) {
      int current = value.codePointBefore(index);
      if (!isEcmaScriptTrimWhitespace(current)) {
        break;
      }
      index -= Character.charCount(current);
    }
    return value.substring(0, index);
  }

  private static boolean isEcmaScriptTrimWhitespace(int value) {
    return (value >= 0x09 && value <= 0x0D)
        || value == 0x20
        || value == 0xA0
        || value == 0x1680
        || (value >= 0x2000 && value <= 0x200A)
        || value == 0x2028
        || value == 0x2029
        || value == 0x202F
        || value == 0x205F
        || value == 0x3000
        || value == 0xFEFF;
  }

  /** Mirrors the explicit 3.5.10 parser helper, including LRM and RLM. */
  private static boolean isFormatJsWhitespace(int value) {
    return (value >= 0x09 && value <= 0x0D)
        || value == 0x20
        || value == 0x85
        || (value >= 0x200E && value <= 0x200F)
        || value == 0x2028
        || value == 0x2029;
  }

  private static boolean isUnicodeWhiteSpace(int value) {
    return (value >= 0x09 && value <= 0x0D)
        || value == 0x20
        || value == 0x85
        || value == 0xA0
        || value == 0x1680
        || (value >= 0x2000 && value <= 0x200A)
        || value == 0x2028
        || value == 0x2029
        || value == 0x202F
        || value == 0x205F
        || value == 0x3000;
  }

  /** Unicode Pattern_Syntax is stable and intentionally independent of Java identifier rules. */
  private static boolean isPatternSyntax(int value) {
    return (value >= 0x21 && value <= 0x2F)
        || (value >= 0x3A && value <= 0x40)
        || (value >= 0x5B && value <= 0x5E)
        || value == 0x60
        || (value >= 0x7B && value <= 0x7E)
        || (value >= 0xA1 && value <= 0xA7)
        || value == 0xA9
        || (value >= 0xAB && value <= 0xAC)
        || value == 0xAE
        || (value >= 0xB0 && value <= 0xB1)
        || value == 0xB6
        || value == 0xBB
        || value == 0xBF
        || value == 0xD7
        || value == 0xF7
        || (value >= 0x2010 && value <= 0x2027)
        || (value >= 0x2030 && value <= 0x203E)
        || (value >= 0x2041 && value <= 0x2053)
        || (value >= 0x2055 && value <= 0x205E)
        || (value >= 0x2190 && value <= 0x245F)
        || (value >= 0x2500 && value <= 0x2775)
        || (value >= 0x2794 && value <= 0x2BFF)
        || (value >= 0x2E00 && value <= 0x2E7F)
        || (value >= 0x3001 && value <= 0x3003)
        || (value >= 0x3008 && value <= 0x3020)
        || value == 0x3030
        || (value >= 0xFD3E && value <= 0xFD3F)
        || (value >= 0xFE45 && value <= 0xFE46);
  }

  private static boolean isAsciiAlpha(Integer value) {
    return value != null && ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z'));
  }

  private static boolean isAsciiAlphaOrSlash(Integer value) {
    return isAsciiAlpha(value) || (value != null && value == '/');
  }

  private static boolean isPotentialElementNameChar(int value) {
    return value == '-'
        || value == '.'
        || (value >= '0' && value <= '9')
        || value == '_'
        || (value >= 'a' && value <= 'z')
        || (value >= 'A' && value <= 'Z')
        || value == 0xB7
        || (value >= 0xC0 && value <= 0xD6)
        || (value >= 0xD8 && value <= 0xF6)
        || (value >= 0xF8 && value <= 0x37D)
        || (value >= 0x37F && value <= 0x1FFF)
        || (value >= 0x200C && value <= 0x200D)
        || (value >= 0x203F && value <= 0x2040)
        || (value >= 0x2070 && value <= 0x218F)
        || (value >= 0x2C00 && value <= 0x2FEF)
        || (value >= 0x3001 && value <= 0xD7FF)
        || (value >= 0xF900 && value <= 0xFDCF)
        || (value >= 0xFDF0 && value <= 0xFFFD)
        || (value >= 0x10000 && value <= 0xEFFFF);
  }

  private enum ParentArgumentType {
    NONE,
    SELECT,
    PLURAL,
    SELECTORDINAL
  }

  private record Identifier(String value, FormatJsSourceLocation location) {}

  private record StyleAndLocation(String style, FormatJsSourceLocation location) {}

  private static final class ParseFailure extends RuntimeException {

    private final FormatJsParseError error;

    private ParseFailure(FormatJsParseError error) {
      super(null, null, false, false);
      this.error = error;
    }
  }
}
