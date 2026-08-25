package com.box.l10n.mojito.translationintegrity.formatjs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.DateArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.DateTimeSkeleton;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberArgument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberSkeleton;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Parity tests for the bundled @formatjs/icu-skeleton-parser 2.1.9 behavior. */
class FormatJsSkeletonParserTest {

  @Test
  void tokenizesAndParsesNumberSkeletonOptions() {
    NumberArgument argument =
        assertInstanceOf(
            NumberArgument.class,
            FormatJsParser.parse(
                    "{n, number, ::currency/USD compact-short .00## sign-always}", strict())
                .get(0));
    NumberSkeleton skeleton = assertInstanceOf(NumberSkeleton.class, argument.style());

    assertThat(skeleton.tokens()).hasSize(4);
    assertThat(skeleton.tokens().get(0).stem()).isEqualTo("currency");
    assertThat(skeleton.tokens().get(0).options()).containsExactly("USD");
    assertThat(skeleton.parsedOptions())
        .containsEntry("style", "currency")
        .containsEntry("currency", "USD")
        .containsEntry("notation", "compact")
        .containsEntry("minimumFractionDigits", 2)
        .containsEntry("maximumFractionDigits", 4)
        .containsEntry("signDisplay", "always");
  }

  @Test
  void preservesUpstreamPermissiveUnknownSkeletonStems() {
    NumberArgument number =
        assertInstanceOf(
            NumberArgument.class,
            FormatJsParser.parse("{n, number, ::future-stem/value}", strict()).get(0));
    NumberSkeleton numberSkeleton = assertInstanceOf(NumberSkeleton.class, number.style());
    assertThat(numberSkeleton.parsedOptions()).isEmpty();

    DateArgument date =
        assertInstanceOf(
            DateArgument.class, FormatJsParser.parse("{d, date, ::foo}", strict()).get(0));
    DateTimeSkeleton dateSkeleton = assertInstanceOf(DateTimeSkeleton.class, date.style());
    assertThat(dateSkeleton.parsedOptions()).isEmpty();
  }

  @Test
  void rejectsEmptyAndMalformedNumberSkeletons() {
    assertThat(parseError("{n, number, ::}").kind())
        .isEqualTo(FormatJsParseErrorKind.INVALID_NUMBER_SKELETON);
    assertThat(parseError("{n, number, ::currency/}").kind())
        .isEqualTo(FormatJsParseErrorKind.INVALID_NUMBER_SKELETON);
    FormatJsSkeletonException semantic =
        org.junit.jupiter.api.Assertions.assertThrows(
            FormatJsSkeletonException.class,
            () -> FormatJsParser.parse("{n, number, ::Ebad}", strict()));
    assertThat(semantic.skeletonType()).isEqualTo(FormatJsSkeletonException.SkeletonType.NUMBER);
  }

  @Test
  void parsesSupportedDateTimeFieldsAndNormalizesUnsupportedOnes() {
    DateArgument date =
        assertInstanceOf(
            DateArgument.class, FormatJsParser.parse("{d, date, ::yMMMd}", strict()).get(0));
    DateTimeSkeleton skeleton = assertInstanceOf(DateTimeSkeleton.class, date.style());

    assertThat(skeleton.pattern()).isEqualTo("yMMMd");
    assertThat(skeleton.parsedOptions())
        .containsEntry("year", "numeric")
        .containsEntry("month", "short")
        .containsEntry("day", "numeric");
    FormatJsSkeletonException semantic =
        org.junit.jupiter.api.Assertions.assertThrows(
            FormatJsSkeletonException.class,
            () -> FormatJsParser.parse("{d, date, ::YYYY}", strict()));
    assertThat(semantic.skeletonType()).isEqualTo(FormatJsSkeletonException.SkeletonType.DATE_TIME);

    FormatJsSkeletonException weekday =
        org.junit.jupiter.api.Assertions.assertThrows(
            FormatJsSkeletonException.class,
            () -> FormatJsParser.parse("{d, date, ::eee}", strict()));
    assertThat(weekday.getMessage()).isEqualTo("`e..eee` (weekday) patterns are not supported");
  }

  @Test
  void preservesPinnedDateTimeRegexQuirks() {
    DateTimeSkeleton quotedMonth = dateSkeleton("{d, date, ::yyyy 'MM' dd}");
    assertThat(quotedMonth.parsedOptions())
        .containsEntry("year", "numeric")
        .containsEntry("day", "2-digit")
        .doesNotContainKey("month");

    assertThat(dateSkeleton("{d, date, ::jjjSSSggg}").parsedOptions()).isEmpty();
    assertThat(dateSkeleton("{d, date, ::MMMMMM}").parsedOptions())
        .containsEntry("month", "numeric");

    FormatJsSkeletonException splitWeekday =
        org.junit.jupiter.api.Assertions.assertThrows(
            FormatJsSkeletonException.class,
            () -> FormatJsParser.parse("{d, date, ::eeeeeee}", strict()));
    assertThat(splitWeekday.getMessage())
        .isEqualTo("`e..eee` (weekday) patterns are not supported");
  }

  @Test
  void skeletonOptionParsingCanBeDisabledWithoutSkippingNumberTokenization() {
    FormatJsParserOptions noOptions =
        FormatJsParserOptions.LOW_LEVEL_DEFAULTS.toBuilder().requiresOtherClause(true).build();

    NumberArgument number =
        assertInstanceOf(
            NumberArgument.class, FormatJsParser.parse("{n, number, ::Ebad}", noOptions).get(0));
    assertThat(assertInstanceOf(NumberSkeleton.class, number.style()).parsedOptions()).isEmpty();
    assertThat(FormatJsParser.parse("{d, date, ::YYYY}", noOptions)).hasSize(1);
    assertThat(FormatJsParser.parseResult("{n, number, ::currency/}", noOptions).error().kind())
        .isEqualTo(FormatJsParseErrorKind.INVALID_NUMBER_SKELETON);
  }

  @Test
  void numberSkeletonWhitespaceMatchesThePinnedPackage() {
    NumberArgument number =
        assertInstanceOf(
            NumberArgument.class,
            FormatJsParser.parse("{n, number, ::percent\u0085group-off}", strict()).get(0));
    NumberSkeleton skeleton = assertInstanceOf(NumberSkeleton.class, number.style());

    assertThat(skeleton.tokens())
        .extracting(FormatJsElement.NumberSkeletonToken::stem)
        .containsExactly("percent", "group-off");
    assertThat(skeleton.parsedOptions()).containsEntry("style", "percent");
    assertThat(skeleton.parsedOptions()).containsEntry("useGrouping", false);
  }

  @Test
  void numberTokenizerUsesThePinnedWhitespaceSetButNotNbsp() {
    String separators = "\t\n\u000B\f\r \u0085\u200E\u200F\u2028\u2029";
    StringBuilder skeletonText = new StringBuilder("percent");
    for (int index = 0; index < separators.length(); index++) {
      skeletonText.append(separators.charAt(index)).append("group-off");
    }
    NumberSkeleton split = numberSkeleton("{n, number, ::" + skeletonText + "}");
    assertThat(split.tokens()).hasSize(1 + separators.length());

    NumberSkeleton nbsp = numberSkeleton("{n, number, ::percent\u00A0group-off}");
    assertThat(nbsp.tokens()).hasSize(1);
    assertThat(nbsp.tokens().get(0).stem()).isEqualTo("percent\u00A0group-off");
    assertThat(nbsp.parsedOptions()).isEmpty();
  }

  @Test
  void parsesNumberSkeletonBranchFamilies() {
    NumberSkeleton skeleton =
        numberSkeleton(
            "{n, number, ::unit/length-meter integer-width/*000 @@## E+!00 "
                + "unit-width-narrow rounding-mode-half-even}");

    assertThat(skeleton.parsedOptions())
        .containsEntry("style", "unit")
        .containsEntry("unit", "meter")
        .containsEntry("minimumIntegerDigits", 2)
        .containsEntry("minimumSignificantDigits", 2)
        .containsEntry("maximumSignificantDigits", 4)
        .containsEntry("notation", "scientific")
        .containsEntry("signDisplay", "always")
        .containsEntry("unitDisplay", "narrow")
        .containsEntry("roundingMode", "halfEven");

    assertThat(numberSkeleton("{n, number, ::EE0}").parsedOptions())
        .containsEntry("notation", "engineering")
        .containsEntry("minimumIntegerDigits", 1);
    assertThat(numberSkeleton("{n, number, ::.00*}").parsedOptions())
        .containsEntry("minimumFractionDigits", 2)
        .doesNotContainKey("maximumFractionDigits");
    assertThat(numberSkeleton("{n, number, ::.###}").parsedOptions())
        .containsEntry("maximumFractionDigits", 3)
        .doesNotContainKey("minimumFractionDigits");
  }

  @Test
  void preservesUndefinedCurrencyAndJavaScriptParseFloatEdges() {
    NumberSkeleton missingCurrency = numberSkeleton("{n, number, ::currency}");
    assertThat(missingCurrency.parsedOptions()).containsKey("currency");
    assertThat(missingCurrency.parsedOptions().get("currency")).isNull();

    for (String value : List.of("Infinity", "+Infinity")) {
      NumberSkeleton scale = numberSkeleton("{n, number, ::scale/" + value + "}");
      assertThat((Double) scale.parsedOptions().get("scale")).isPositive().isInfinite();
    }
    NumberSkeleton negative = numberSkeleton("{n, number, ::scale/-Infinity}");
    assertThat((Double) negative.parsedOptions().get("scale")).isNegative().isInfinite();
    NumberSkeleton nbsp = numberSkeleton("{n, number, ::scale/\u00A0Infinity}");
    assertThat((Double) nbsp.parsedOptions().get("scale")).isPositive().isInfinite();
    NumberSkeleton numericPrefix = numberSkeleton("{n, number, ::scale/\u00A01.5suffix}");
    assertThat(numericPrefix.parsedOptions()).containsEntry("scale", 1.5d);
  }

  @Test
  void regexMatchingIsDeterministicAcrossSkeletonParses() {
    org.junit.jupiter.api.Assertions.assertThrows(
        FormatJsSkeletonException.class,
        () -> FormatJsParser.parse("{n, number, ::.00/foo/bar}", strict()));

    NumberSkeleton next = numberSkeleton("{n, number, ::.0}");
    assertThat(next.parsedOptions())
        .containsEntry("minimumFractionDigits", 1)
        .containsEntry("maximumFractionDigits", 1);
  }

  @Test
  void simpleStyleTrimmingUsesNativeEcmaScriptWhitespaceSemantics() {
    for (String trailing : List.of("\t", "\n", "\uFEFF")) {
      NumberArgument number =
          assertInstanceOf(
              NumberArgument.class,
              FormatJsParser.parse("{n, number, percent" + trailing + "}", strict()).get(0));
      assertThat(assertInstanceOf(FormatJsElement.NamedStyle.class, number.style()).value())
          .isEqualTo("percent");
    }

    NumberArgument nextLine =
        assertInstanceOf(
            NumberArgument.class,
            FormatJsParser.parse("{n, number, percent\u0085}", strict()).get(0));
    assertThat(assertInstanceOf(FormatJsElement.NamedStyle.class, nextLine.style()).value())
        .isEqualTo("percent\u0085");
  }

  private static FormatJsParseError parseError(String message) {
    FormatJsParseResult result = FormatJsParser.parseResult(message, strict());
    assertThat(result.isSuccess()).isFalse();
    return result.error();
  }

  private static NumberSkeleton numberSkeleton(String message) {
    NumberArgument argument =
        assertInstanceOf(NumberArgument.class, FormatJsParser.parse(message, strict()).get(0));
    return assertInstanceOf(NumberSkeleton.class, argument.style());
  }

  private static DateTimeSkeleton dateSkeleton(String message) {
    DateArgument argument =
        assertInstanceOf(DateArgument.class, FormatJsParser.parse(message, strict()).get(0));
    return assertInstanceOf(DateTimeSkeleton.class, argument.style());
  }

  private static FormatJsParserOptions strict() {
    return FormatJsParserOptions.MOJITO_STRICT;
  }
}
