package com.box.l10n.mojito.translationintegrity.formatjs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Argument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.DateArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Literal;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NamedStyle;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.PluralArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Pound;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.SelectArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Tag;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behavioral parity tests for @formatjs/icu-messageformat-parser 3.5.10. */
class FormatJsParserTest {

  @Test
  void parsesLiteralsArgumentsAndSimpleStyles() {
    List<FormatJsElement> ast =
        FormatJsParser.parse("Hello {name}; {amount, number, currency}; {day, date}");

    assertThat(ast).hasSize(6);
    assertThat(assertInstanceOf(Literal.class, ast.get(0)).value()).isEqualTo("Hello ");
    assertThat(assertInstanceOf(Argument.class, ast.get(1)).value()).isEqualTo("name");
    NumberArgument number = assertInstanceOf(NumberArgument.class, ast.get(3));
    assertThat(number.value()).isEqualTo("amount");
    assertThat(assertInstanceOf(NamedStyle.class, number.style()).value()).isEqualTo("currency");
    assertThat(assertInstanceOf(DateArgument.class, ast.get(5)).style()).isNull();
    assertThat(ast).allMatch(element -> element.location() == null);
  }

  @Test
  void followsIcuApostropheOnlyWhereNeededRules() {
    List<FormatJsElement> ast = FormatJsParser.parse("It''s '{isn''t}' {value} and 'plain'");

    assertThat(assertInstanceOf(Literal.class, ast.get(0)).value()).isEqualTo("It's {isn't} ");
    assertThat(assertInstanceOf(Argument.class, ast.get(1)).value()).isEqualTo("value");
    assertThat(assertInstanceOf(Literal.class, ast.get(2)).value()).isEqualTo(" and 'plain'");
  }

  @Test
  void acceptsAnUnclosedMessageQuoteLikeFormatJs() {
    List<FormatJsElement> ast = FormatJsParser.parse("before '{still literal");

    assertThat(assertInstanceOf(Literal.class, ast.get(0)).value())
        .isEqualTo("before {still literal");
  }

  @Test
  void parsesPluralOrdinalAndSelectOptions() {
    String message =
        "{count, plural, offset:-1 =-1 {none} one {# item} other {# items}} "
            + "{rank, selectordinal, one {#st} other {#th}} "
            + "{tone, select, formal {#} other {{name}}}";
    List<FormatJsElement> ast = FormatJsParser.parse(message);

    PluralArgument plural = assertInstanceOf(PluralArgument.class, ast.get(0));
    assertThat(plural.offset()).isEqualTo(-1);
    assertThat(plural.options()).containsOnlyKeys("=-1", "one", "other");
    assertInstanceOf(Pound.class, plural.options().get("one").value().get(0));

    PluralArgument ordinal = assertInstanceOf(PluralArgument.class, ast.get(2));
    assertThat(ordinal.pluralType()).isEqualTo(FormatJsElement.PluralType.ORDINAL);

    SelectArgument select = assertInstanceOf(SelectArgument.class, ast.get(4));
    assertThat(
            assertInstanceOf(Literal.class, select.options().get("formal").value().get(0)).value())
        .isEqualTo("#");
    assertInstanceOf(Argument.class, select.options().get("other").value().get(0));
  }

  @Test
  void requiresOtherOnlyWhenConfigured() {
    String message = "{kind, select, known {Known}}";

    assertThat(new FormatJsParser(message).parseResult().isSuccess()).isTrue();
    assertThat(parseError(message, FormatJsParserOptions.UPSTREAM_PARSE_DEFAULTS).kind())
        .isEqualTo(FormatJsParseErrorKind.MISSING_OTHER_CLAUSE);
  }

  @Test
  void reportsDuplicateSelectors() {
    assertThat(
            parseError(
                    "{n, plural, one {a} one {b} other {c}}",
                    FormatJsParserOptions.UPSTREAM_PARSE_DEFAULTS)
                .kind())
        .isEqualTo(FormatJsParseErrorKind.DUPLICATE_PLURAL_ARGUMENT_SELECTOR);
    assertThat(
            parseError(
                    "{v, select, a {a} a {b} other {c}}",
                    FormatJsParserOptions.UPSTREAM_PARSE_DEFAULTS)
                .kind())
        .isEqualTo(FormatJsParseErrorKind.DUPLICATE_SELECT_ARGUMENT_SELECTOR);
  }

  @Test
  void parsesPairedTagsAndTreatsSelfClosingTagsAsLiterals() {
    List<FormatJsElement> ast = FormatJsParser.parse("<b>Hello {name}</b><br/>");

    Tag tag = assertInstanceOf(Tag.class, ast.get(0));
    assertThat(tag.value()).isEqualTo("b");
    assertThat(tag.children()).hasSize(2);
    assertThat(assertInstanceOf(Literal.class, ast.get(1)).value()).isEqualTo("<br/>");
  }

  @Test
  void canTreatTagsAsOpaqueLiteralText() {
    FormatJsParserOptions options =
        FormatJsParserOptions.UPSTREAM_PARSE_DEFAULTS.toBuilder().ignoreTag(true).build();

    List<FormatJsElement> ast = FormatJsParser.parse("<b>{name}</b>", options);

    assertThat(ast).hasSize(3);
    assertThat(assertInstanceOf(Literal.class, ast.get(0)).value()).isEqualTo("<b>");
    assertInstanceOf(Argument.class, ast.get(1));
    assertThat(assertInstanceOf(Literal.class, ast.get(2)).value()).isEqualTo("</b>");
  }

  @Test
  void rawIgnoreTagParserStillParsesIcuSyntaxInsideAttributes() {
    assertThat(parseError("<link title=\"{\">TEXT</link>", strict()).kind())
        .isEqualTo(FormatJsParseErrorKind.MALFORMED_ARGUMENT);
  }

  @Test
  void pythonCompatibilityConsumesRecognizedTagsAtomically() {
    FormatJsParserOptions options = strict().toBuilder().pythonOpaqueTagCompatibility(true).build();
    String message = "<tag title=\"'<\">{outside}";

    assertThat(FormatJsParser.parse(message, strict())).noneMatch(Argument.class::isInstance);

    List<FormatJsElement> ast = FormatJsParser.parse(message, options);

    assertThat(assertInstanceOf(Literal.class, ast.get(0)).value()).isEqualTo("<tag title=\"'<\">");
    assertThat(assertInstanceOf(Argument.class, ast.get(1)).value()).isEqualTo("outside");
  }

  @Test
  void pythonCompatibilityRunsOnlyInMessageContexts() {
    FormatJsParserOptions options = strict().toBuilder().pythonOpaqueTagCompatibility(true).build();
    String quoted = "'<link title=\"x'{inside}y\"> {outside}";

    assertThat(FormatJsParser.parse(quoted, options))
        .filteredOn(Argument.class::isInstance)
        .extracting(element -> ((Argument) element).value())
        .containsExactly("inside", "outside");
    assertThat(parseError("{value<link title=\"{\">}", options).kind())
        .isEqualTo(FormatJsParseErrorKind.MALFORMED_ARGUMENT);
  }

  @Test
  void pythonCompatibilityMatchesUnicodeTagStartAndPluralPoundContexts() {
    FormatJsParserOptions options = strict().toBuilder().pythonOpaqueTagCompatibility(true).build();

    List<FormatJsElement> unicodeTag =
        FormatJsParser.parse("</β title=\"{ignored}>\">{outside}", options);
    assertThat(assertInstanceOf(Literal.class, unicodeTag.get(0)).value())
        .isEqualTo("</β title=\"{ignored}>\">");
    assertThat(assertInstanceOf(Argument.class, unicodeTag.get(1)).value()).isEqualTo("outside");

    PluralArgument plural =
        assertInstanceOf(
            PluralArgument.class,
            FormatJsParser.parse(
                    "{n, plural, other {<tag title=\"'#'{ignored}\">{outside}}}", options)
                .get(0));
    assertThat(plural.options().get("other").value())
        .filteredOn(Argument.class::isInstance)
        .extracting(element -> ((Argument) element).value())
        .containsExactly("outside");

    List<FormatJsElement> nonLetter =
        FormatJsParser.parse("<\u0345 title=\"{notOpaque}\">", options);
    assertThat(nonLetter)
        .filteredOn(Argument.class::isInstance)
        .extracting(element -> ((Argument) element).value())
        .containsExactly("notOpaque");
  }

  @Test
  void recordsOnlyApostrophesRecognizedAsIcuQuoteOpeners() {
    FormatJsParserOptions options = strict().toBuilder().pythonOpaqueTagCompatibility(true).build();

    String closingBeforeTag = "'{name}'<link>TEXT</link>";
    FormatJsParser closingParser = new FormatJsParser(closingBeforeTag, options);
    assertThat(closingParser.parseResult().isSuccess()).isTrue();
    assertThat(closingParser.apostropheQuotes())
        .containsExactly(
            new FormatJsParser.ApostropheQuote(0, closingBeforeTag.indexOf("'<link>")));

    FormatJsParser doubledParser = new FormatJsParser("L''<link>TEXT</link>", options);
    assertThat(doubledParser.parseResult().isSuccess()).isTrue();
    assertThat(doubledParser.apostropheQuotes()).isEmpty();

    FormatJsParser unsafeParser = new FormatJsParser("L'<link>TEXT</link>", options);
    assertThat(unsafeParser.parseResult().isSuccess()).isTrue();
    assertThat(unsafeParser.apostropheQuotes())
        .containsExactly(new FormatJsParser.ApostropheQuote(1, null));
  }

  @Test
  void recordsPythonCompatibleOpaqueTagSpansInMessageContexts() {
    FormatJsParserOptions options = strict().toBuilder().pythonOpaqueTagCompatibility(true).build();
    String message = "<β {bad L'<link>'> {outside}";
    FormatJsParser parser = new FormatJsParser(message, options);

    assertThat(parser.parseResult().isSuccess()).isTrue();
    assertThat(parser.pythonOpaqueTagSpans())
        .containsExactly(new FormatJsParser.OpaqueTagSpan(0, message.indexOf(" {outside}")));
    assertThat(parser.apostropheQuotes()).isEmpty();

    FormatJsParser argumentHeader = new FormatJsParser("{value<link>}", options);
    assertThat(argumentHeader.parseResult().isSuccess()).isFalse();
    assertThat(argumentHeader.pythonOpaqueTagSpans()).isEmpty();
  }

  @Test
  void rejectsTagAttributesAndMismatchedTags() {
    assertThat(parseError("<b class=x>x</b>", upstreamWithLocations()).kind())
        .isEqualTo(FormatJsParseErrorKind.INVALID_TAG);
    assertThat(parseError("<b>x</i>", upstreamWithLocations()).kind())
        .isEqualTo(FormatJsParseErrorKind.UNMATCHED_CLOSING_TAG);
  }

  @Test
  void acceptsUnicodeAndPrivateUseArgumentNames() {
    String bmpPrivate = "\uE000";
    String supplementaryPrivate = new String(Character.toChars(0xF0000));

    List<FormatJsElement> ast = FormatJsParser.parse("{" + bmpPrivate + supplementaryPrivate + "}");

    assertThat(assertInstanceOf(Argument.class, ast.get(0)).value())
        .isEqualTo(bmpPrivate + supplementaryPrivate);
    assertThat(parseError("{bad!name}", strict()).kind())
        .isEqualTo(FormatJsParseErrorKind.MALFORMED_ARGUMENT);
  }

  @Test
  void mirrorsLrmRlmWhitespaceAndIdentifierAsymmetry() {
    SelectArgument select =
        assertInstanceOf(
            SelectArgument.class,
            FormatJsParser.parse("{\u200Emode\u200F, select, \u200Eon {ON} \u200Fother {OFF}}")
                .get(0));

    // Leading bidi marks are consumed by bumpSpace; a trailing mark remains identifier text.
    assertThat(select.value()).isEqualTo("mode\u200F");
    assertThat(select.options()).containsOnlyKeys("on", "other");
  }

  @Test
  void doesNotTreatNbspAsBumpSpace() {
    assertThat(parseError("{\u00A0name}", strict()).kind())
        .isEqualTo(FormatJsParseErrorKind.MALFORMED_ARGUMENT);
    assertThat(parseError("{name\u00A0}", strict()).kind())
        .isEqualTo(FormatJsParseErrorKind.MALFORMED_ARGUMENT);
  }

  @Test
  void nestedSelectResetsPluralPoundSemanticsAndReturnRestoresThem() {
    PluralArgument plural =
        assertInstanceOf(
            PluralArgument.class,
            FormatJsParser.parse("{n, plural, other {{m, select, other {#}} #}}").get(0));
    List<FormatJsElement> pluralBranch = plural.options().get("other").value();
    SelectArgument select = assertInstanceOf(SelectArgument.class, pluralBranch.get(0));

    assertThat(
            assertInstanceOf(Literal.class, select.options().get("other").value().get(0)).value())
        .isEqualTo("#");
    assertInstanceOf(Pound.class, pluralBranch.get(2));
  }

  @Test
  void enforcesJavaScriptSafeIntegerRangeForOffsetsAndExactSelectors() {
    assertThat(
            FormatJsParser.parse(
                "{n, plural, offset:9007199254740991 =-9007199254740991 {x} other {y}}"))
        .hasSize(1);

    PluralArgument negativeZero =
        assertInstanceOf(
            PluralArgument.class, FormatJsParser.parse("{n, plural, offset:-0 other {x}}").get(0));
    assertThat(Double.doubleToRawLongBits(negativeZero.offset()))
        .isEqualTo(Double.doubleToRawLongBits(-0.0d));

    assertThat(parseError("{n, plural, offset:9007199254740992 other {x}}", strict()).kind())
        .isEqualTo(FormatJsParseErrorKind.INVALID_PLURAL_ARGUMENT_OFFSET_VALUE);
    assertThat(parseError("{n, plural, =-9007199254740992 {x} other {y}}", strict()).kind())
        .isEqualTo(FormatJsParseErrorKind.INVALID_PLURAL_ARGUMENT_SELECTOR);
  }

  @Test
  void enforcesMaximumDepthForTagsAndArgumentFragments() {
    FormatJsParserOptions depthTwo = strict().toBuilder().maxNestingDepth(2).build();
    FormatJsParserOptions tagDepthTwo = depthTwo.toBuilder().ignoreTag(false).build();

    assertThat(FormatJsParser.parse("<a><b>x</b></a>", tagDepthTwo)).hasSize(1);
    assertThat(parseError("<a><b><c>x</c></b></a>", tagDepthTwo).kind())
        .isEqualTo(FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED);

    String nestedSelect = "{a, select, other {{b, select, other {{c, select, other {x}}}}}}";
    assertThat(parseError(nestedSelect, depthTwo).kind())
        .isEqualTo(FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED);
  }

  @Test
  void enforcesMaximumDepthWhileScanningNestedSimpleStyles() {
    FormatJsParserOptions depthTwo = strict().toBuilder().maxNestingDepth(2).build();

    assertThat(FormatJsParser.parse("{v, number, {x}}", depthTwo)).isNotEmpty();
    assertThat(parseError("{v, number, {{x}}}", depthTwo).kind())
        .isEqualTo(FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED);
  }

  @Test
  void quotedSimpleStyleBracesDoNotConsumeDepthBudget() {
    FormatJsParserOptions depthOne = strict().toBuilder().maxNestingDepth(1).build();

    NumberArgument quoted =
        assertInstanceOf(
            NumberArgument.class, FormatJsParser.parse("{v, number, '{{{{'}", depthOne).get(0));
    assertThat(assertInstanceOf(NamedStyle.class, quoted.style()).value()).isEqualTo("'{{{{'");
    assertThat(parseError("{v, number, {x}}", depthOne).kind())
        .isEqualTo(FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED);
  }

  @Test
  void lowLevelDefaultsHaveNoDepthLimitOrOtherRequirement() {
    assertThat(FormatJsParserOptions.LOW_LEVEL_DEFAULTS.maxNestingDepth()).isZero();
    assertThat(FormatJsParserOptions.LOW_LEVEL_DEFAULTS.requiresOtherClause()).isFalse();
    assertThat(FormatJsParserOptions.LOW_LEVEL_DEFAULTS.shouldParseSkeletons()).isFalse();
    assertThat(FormatJsParserOptions.MOJITO_STRICT.maxNestingDepth()).isEqualTo(100);
    assertThat(FormatJsParserOptions.MOJITO_STRICT.captureLocation()).isTrue();
    assertThat(FormatJsParserOptions.MOJITO_STRICT.ignoreTag()).isTrue();
    assertThat(FormatJsParserOptions.MOJITO_STRICT.pythonOpaqueTagCompatibility()).isFalse();
    assertThrows(
        IllegalArgumentException.class,
        () -> FormatJsParserOptions.builder().pythonOpaqueTagCompatibility(true).build());
  }

  @Test
  void throwingFacadeExposesStableErrorAndOriginalMessage() {
    FormatJsParseException exception =
        assertThrows(FormatJsParseException.class, () -> FormatJsParser.parse("{broken"));

    assertThat(exception.getMessage()).isEqualTo("EXPECT_ARGUMENT_CLOSING_BRACE");
    assertThat(exception.error().kind())
        .isEqualTo(FormatJsParseErrorKind.EXPECT_ARGUMENT_CLOSING_BRACE);
    assertThat(exception.error().originalMessage()).isEqualTo("{broken");
  }

  private static FormatJsParseError parseError(String message, FormatJsParserOptions options) {
    FormatJsParseResult result = FormatJsParser.parseResult(message, options);
    assertThat(result.isSuccess()).isFalse();
    return result.error();
  }

  private static FormatJsParserOptions strict() {
    return FormatJsParserOptions.MOJITO_STRICT;
  }

  private static FormatJsParserOptions upstreamWithLocations() {
    return FormatJsParserOptions.UPSTREAM_PARSE_DEFAULTS.toBuilder().captureLocation(true).build();
  }
}
