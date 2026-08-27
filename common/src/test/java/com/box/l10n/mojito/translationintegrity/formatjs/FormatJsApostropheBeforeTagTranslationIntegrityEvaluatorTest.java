package com.box.l10n.mojito.translationintegrity.formatjs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormatJsApostropheBeforeTagTranslationIntegrityEvaluatorTest {

  private final FormatJsApostropheBeforeTagTranslationIntegrityEvaluator evaluator =
      new FormatJsApostropheBeforeTagTranslationIntegrityEvaluator();

  @Test
  void ignoresIcuQuotesBeforeNonTagAngleBracketSequences() {
    assertThat(evaluator.evaluate("VALUE '< 2 > 1"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluator.evaluate("VALUE '<link,not-a-tag>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluator.evaluate("VALUE '<link{bad}>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluator.evaluate("VALUE '</link unexpected>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void recognizesASingleLetterFormatJsTag() {
    TranslationIntegrityEvaluation actual = evaluator.evaluate("L'<b>TEXT</b>");

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.safeRepair()).isNull();
    assertThat(actual.diagnostics())
        .containsExactly(
            TranslationIntegrityDiagnostic.targetError(
                "unrenderable-tag-apostrophe", Map.of("tag", "<b>", "occurrence", 1)));
  }

  @Test
  void recognizesValidAttributesSelfClosingTagsAndClosingTags() {
    assertRejectedTag("L'<link title=\"x\">TEXT</link>", "<link title=\"x\">");
    assertRejectedTag("L'<br/>", "<br/>");
    assertRejectedTag("TEXT'</b>", "</b>");
  }

  @Test
  void leavesMalformedMessageOwnershipToTheSyntaxLane() {
    assertThat(evaluator.evaluate("{name L'<link>TEXT</link>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());

    TranslationIntegrityEvaluation composite =
        new FormatJsTranslationIntegrityEvaluator()
            .evaluate("{name}", "{name L'<link>TEXT</link>", true, false, false, false, true);

    assertThat(composite.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("target-format-invalid");
  }

  @Test
  void rejectsAnApostropheFindingWithoutProposingARepair() {
    TranslationIntegrityEvaluation actual = evaluator.evaluate("L'<link>{bad</link>");

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.safeRepair()).isNull();
    assertThat(actual.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("unrenderable-tag-apostrophe");
  }

  @Test
  void continuesAfterSyntaxHiddenByTheBroaderTagAdapter() {
    String source = "<x {bad> TEXT <link>SOURCE</link>";
    String target = "<x {bad> L'<link>TARGET</link>";

    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator()
            .evaluate(source, target, true, false, false, false, true);

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.diagnostics())
        .containsExactly(
            TranslationIntegrityDiagnostic.targetError(
                "unrenderable-tag-apostrophe", Map.of("tag", "<link>", "occurrence", 1)));
  }

  @Test
  void nonExactOpaqueSpansDoNotSkipApostropheCandidates() {
    FormatJsTranslationIntegrityEvaluator composite = new FormatJsTranslationIntegrityEvaluator();

    for (String message :
        List.of(
            "<β {bad L'<link>'>",
            "<β {badL'<link>'>",
            "<β {name L'<link>'>",
            "<β {name '<link>'>")) {
      assertThat(composite.evaluate(message, message, true, false, false, false, true))
          .satisfies(
              actual -> {
                assertThat(actual.disposition())
                    .isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
                assertThat(actual.diagnostics())
                    .containsExactly(
                        TranslationIntegrityDiagnostic.targetError(
                            "unrenderable-tag-apostrophe",
                            Map.of("tag", "<link>", "occurrence", 1)));
              });
    }
  }

  @Test
  void nonExactOpaqueSpansPreserveLegacyFindings() {
    FormatJsTranslationIntegrityEvaluator composite = new FormatJsTranslationIntegrityEvaluator();

    for (String message :
        List.of(
            "<β {n, bogus, L'<link>'}>",
            "<β {, number, L'<link>'}>",
            "<β {n, plural!, L'<link>'}>",
            "<β title=\"{n, number, foo L'<link>\">",
            "<β title=\"{n, number, foo L'<link>'\">")) {
      TranslationIntegrityEvaluation actual =
          composite.evaluate(message, message, true, false, false, false, true);

      assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
      assertThat(actual.safeRepair()).isNull();
      assertThat(actual.diagnostics())
          .containsExactly(
              TranslationIntegrityDiagnostic.targetError(
                  "unrenderable-tag-apostrophe", Map.of("tag", "<link>", "occurrence", 1)));
    }
  }

  @Test
  void nonExactOpaqueSpansKeepCompleteNestedTagTokensAtomic() {
    String message = "<β {bad <link title=\"L'<strong>\">>";

    assertThat(
            new FormatJsTranslationIntegrityEvaluator()
                .evaluate(message, message, true, false, false, false, true))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void nonExactOpaqueSpanScanningIsBoundedAndContinues() {
    String message = "<β {n, plural, ,} L'<link>'>";

    TranslationIntegrityEvaluation actual =
        assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () ->
                new FormatJsTranslationIntegrityEvaluator()
                    .evaluate(message, message, true, false, false, false, true));

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.diagnostics())
        .containsExactly(
            TranslationIntegrityDiagnostic.targetError(
                "unrenderable-tag-apostrophe", Map.of("tag", "<link>", "occurrence", 1)));
  }

  @Test
  void opaqueSpanInspectionRemainsBoundedAtDeepIcuLookingText() {
    String message =
        "<β " + "{n, plural, other {".repeat(102) + "L'<link>'" + "}}".repeat(102) + ">";

    TranslationIntegrityEvaluation actual =
        assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () ->
                new FormatJsTranslationIntegrityEvaluator()
                    .evaluate(message, message, true, false, false, false, true));

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.diagnostics())
        .containsExactly(
            TranslationIntegrityDiagnostic.targetError(
                "unrenderable-tag-apostrophe", Map.of("tag", "<link>", "occurrence", 1)));
  }

  @Test
  void parserAndOpaqueSpanPathsPreserveDoubledAndQuoteClosingApostrophes() {
    FormatJsTranslationIntegrityEvaluator composite = new FormatJsTranslationIntegrityEvaluator();

    for (String message :
        List.of(
            "<x {bad> L''<link>TEXT</link>",
            "<x {bad> '{name}'<link>TEXT</link>",
            "<β title=\"L''<link>\">",
            "<β title=\"'{name}'<link>\">")) {
      assertThat(composite.evaluate(message, message, true, false, false, false, true))
          .isEqualTo(TranslationIntegrityEvaluation.pass());
    }
  }

  @Test
  void realParserOwnsPluralOffsetQuoteStateOutsideOpaqueSpans() {
    String message = "<x {bad> {n, plural, offset:1 other {'#'<link>}}";

    assertThat(
            new FormatJsTranslationIntegrityEvaluator()
                .evaluate(message, message, true, false, false, false, true))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void structuralSyntaxDiagnosticsStillDominateBeforeApostropheAnalysis() {
    FormatJsTranslationIntegrityEvaluator composite = new FormatJsTranslationIntegrityEvaluator();

    TranslationIntegrityEvaluation sourceInvalid =
        composite.evaluate(
            "<x {bad> {name", "<x {bad> L'<link>TEXT</link>", true, false, false, false, true);
    assertThat(sourceInvalid.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("source-format-invalid");

    TranslationIntegrityEvaluation targetInvalid =
        composite.evaluate(
            "<x {bad> SOURCE",
            "<x {bad> {name L'<link>TEXT</link>",
            true,
            false,
            false,
            false,
            true);
    assertThat(targetInvalid.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("target-format-invalid");
  }

  @Test
  void preservesExistingStructuralDiagnosticsWhenRejecting() {
    TranslationIntegrityEvaluation structuralRejection =
        new TranslationIntegrityEvaluation(
            List.of(
                TranslationIntegrityDiagnostic.targetError(
                    "variable-missing", Map.of("names", List.of("name")))),
            TranslationIntegrityDisposition.REJECT_TARGET);

    TranslationIntegrityEvaluation actual =
        evaluator.compose("L'<link>TEXT</link>", structuralRejection);

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.safeRepair()).isNull();
    assertThat(actual.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .containsExactly("unrenderable-tag-apostrophe", "variable-missing");
  }

  private void assertRejectedTag(String target, String tag) {
    TranslationIntegrityEvaluation actual = evaluator.evaluate(target);

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.safeRepair()).isNull();
    assertThat(actual.diagnostics())
        .containsExactly(
            TranslationIntegrityDiagnostic.targetError(
                "unrenderable-tag-apostrophe", Map.of("tag", tag, "occurrence", 1)));
  }
}
