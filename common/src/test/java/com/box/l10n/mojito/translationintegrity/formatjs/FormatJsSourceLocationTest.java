package com.box.l10n.mojito.translationintegrity.formatjs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsCodePointRanges.CodePointRange;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Argument;
import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.Literal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormatJsSourceLocationTest {

  @Test
  void lowLevelParserAlwaysCapturesWhileFacadeCanPrune() {
    Argument lowLevel =
        assertInstanceOf(Argument.class, new FormatJsParser("{name}").parseOrThrow().get(0));
    Argument facade = assertInstanceOf(Argument.class, FormatJsParser.parse("{name}").get(0));

    assertThat(lowLevel.location()).isNotNull();
    assertThat(facade.location()).isNull();
  }

  @Test
  void preservesUtf16OffsetsAndCodePointColumnsForAstralCharacters() {
    String message = "😀{name}";
    List<FormatJsElement> ast = FormatJsParser.parse(message, strictWithLocations());

    Literal literal = assertInstanceOf(Literal.class, ast.get(0));
    assertThat(literal.location().start()).isEqualTo(new FormatJsSourcePosition(0, 1, 1));
    assertThat(literal.location().end()).isEqualTo(new FormatJsSourcePosition(2, 1, 2));

    Argument argument = assertInstanceOf(Argument.class, ast.get(1));
    assertThat(argument.location().start()).isEqualTo(new FormatJsSourcePosition(2, 1, 2));
    assertThat(argument.location().end()).isEqualTo(new FormatJsSourcePosition(8, 1, 8));
    assertThat(FormatJsCodePointRanges.toCodePointRange(message, argument.location()))
        .isEqualTo(new CodePointRange(1, 7));
  }

  @Test
  void convertsOffsetsAndRejectsOffsetsInsideSurrogatePairs() {
    String message = "a😀b";

    assertThat(FormatJsCodePointRanges.toCodePointOffset(message, 0)).isZero();
    assertThat(FormatJsCodePointRanges.toCodePointOffset(message, 1)).isEqualTo(1);
    assertThat(FormatJsCodePointRanges.toCodePointOffset(message, 3)).isEqualTo(2);
    assertThat(FormatJsCodePointRanges.toCodePointOffset(message, 4)).isEqualTo(3);
    assertThrows(
        IllegalArgumentException.class,
        () -> FormatJsCodePointRanges.toCodePointOffset(message, 2));
  }

  @Test
  void tracksLinesLikeFormatJs() {
    List<FormatJsElement> ast = FormatJsParser.parse("a\n{name}", strictWithLocations());
    Argument argument = assertInstanceOf(Argument.class, ast.get(1));

    assertThat(argument.location().start()).isEqualTo(new FormatJsSourcePosition(2, 2, 1));
    assertThat(argument.location().end()).isEqualTo(new FormatJsSourcePosition(8, 2, 7));
  }

  @Test
  void carriageReturnAdvancesColumnBeforeLineFeedResetsIt() {
    List<FormatJsElement> ast = FormatJsParser.parse("a\r\n{name}", strictWithLocations());
    Literal literal = assertInstanceOf(Literal.class, ast.get(0));
    Argument argument = assertInstanceOf(Argument.class, ast.get(1));

    assertThat(literal.location().end()).isEqualTo(new FormatJsSourcePosition(3, 2, 1));
    assertThat(argument.location().start()).isEqualTo(new FormatJsSourcePosition(3, 2, 1));
    assertThat(argument.location().end()).isEqualTo(new FormatJsSourcePosition(9, 2, 7));
  }

  private static FormatJsParserOptions strictWithLocations() {
    return FormatJsParserOptions.MOJITO_STRICT;
  }
}
