package com.box.l10n.mojito.translationintegrity.richtag;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Subject;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityRange;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RichTextTagTranslationIntegrityEvaluatorConformanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern LEGACY_RAW_TAG = Pattern.compile("<[^\\n]*?>");
  private static final Pattern LEGACY_STRUCTURAL_TAG =
      Pattern.compile("^<(/?)([A-Za-z0-9_.:-]+)(?:\\s[^\\n]*?)?\\s*(/?)>$");

  @Test
  void matchesEveryNonDominatedCutoverRuleExpectationAcrossProfiles() throws IOException {
    JsonNode manifest = JSON.readTree(findConformanceRoot().resolve("manifest.json").toFile());
    RichTextTagTranslationIntegrityEvaluator evaluator =
        new RichTextTagTranslationIntegrityEvaluator();
    List<String> mismatches = new ArrayList<>();
    Map<String, Integer> profiles = new TreeMap<>();
    int evaluated = 0;

    for (JsonNode testCase : manifest.path("cases")) {
      if (!isApplicable(testCase)) {
        continue;
      }
      evaluated++;
      profiles.merge(testCase.path("profile").asText(), 1, Integer::sum);
      TranslationIntegrityEvaluation actual =
          evaluator.evaluate(
              testCase.path("source").path("text").asText(),
              testCase.path("target").path("text").asText());
      TranslationIntegrityEvaluation expected = expectedRuleEvaluation(manifest, testCase);
      if (!expected.equals(actual)) {
        mismatches.add(testCase.path("id").asText() + ": expected " + expected + ", got " + actual);
      }
    }

    assertThat(evaluated).isEqualTo(17);
    assertThat(profiles)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("formatjs", 12, "dollar-template", 3, "double-brace", 2));
    assertThat(mismatches).isEmpty();
  }

  @Test
  void leavesMultiplicityAndMisnestingForTheExtendedContract() {
    RichTextTagTranslationIntegrityEvaluator evaluator =
        new RichTextTagTranslationIntegrityEvaluator();

    assertThat(
            evaluator.evaluate(
                "<strong>ONE</strong> <strong>TWO</strong>", "<strong>TARGET</strong>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(
            evaluator.evaluate(
                "<strong><link>SOURCE</link></strong>", "<strong><link>TARGET</strong></link>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluator.evaluate("<link><link></link>", "<link></link>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void doesNotWakeUpUnchangedMultiplicityWhenAnotherRawTokenDiffers() {
    RichTextTagTranslationIntegrityEvaluator evaluator =
        new RichTextTagTranslationIntegrityEvaluator();

    assertThat(
            evaluator.evaluate(
                "<link><link>SOURCE</link> <strong>OTHER</strong>", "<link>TARGET</link>"))
        .isEqualTo(
            new TranslationIntegrityEvaluation(
                List.of(
                    TranslationIntegrityDiagnostic.targetError(
                        "rich-tag-missing", Map.of("tags", List.of("</strong>", "<strong>")))),
                TranslationIntegrityDisposition.REJECT_TARGET));
  }

  @Test
  void preservesArbitraryLegacyAngleBracketTokens() {
    RichTextTagTranslationIntegrityEvaluator evaluator =
        new RichTextTagTranslationIntegrityEvaluator();

    assertThat(evaluator.evaluate("Value is < 2 >", "VALUE"))
        .isEqualTo(
            new TranslationIntegrityEvaluation(
                List.of(
                    TranslationIntegrityDiagnostic.targetError(
                        "rich-tag-missing", Map.of("tags", List.of("< 2 >")))),
                TranslationIntegrityDisposition.REJECT_TARGET));
  }

  @Test
  void attributesMalformedSourceTagsToTheTargetLaneOnly() {
    RichTextTagTranslationIntegrityEvaluator evaluator =
        new RichTextTagTranslationIntegrityEvaluator();

    TranslationIntegrityEvaluation evaluation =
        evaluator.evaluate("<link>SOURCE", "<link>TARGET</link>");

    assertThat(evaluation.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(evaluation.diagnostics())
        .allMatch(
            diagnostic -> diagnostic.subject() == TranslationIntegrityDiagnostic.Subject.TARGET);
  }

  @Test
  void matchesPythonNewlineAndFirstClosingAngleBracketSemantics() {
    RichTextTagTranslationIntegrityEvaluator evaluator =
        new RichTextTagTranslationIntegrityEvaluator();

    assertThat(evaluator.evaluate("<link\ntitle=\"one\">", "<link\ntitle=\"two\">"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluator.evaluate("<link\rtitle=\"one\">", "<link\rtitle=\"two\">"))
        .extracting(TranslationIntegrityEvaluation::disposition)
        .isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(evaluator.evaluate("<link\u2028title=\"one\">", "<link\u2028title=\"two\">"))
        .extracting(TranslationIntegrityEvaluation::disposition)
        .isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(
            evaluator.evaluate(
                "<link title=\"same>source\">SOURCE</link>",
                "<link title=\"same>target\">TARGET</link>"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void rawTagScannerMatchesLegacyRegexBoundaries() {
    for (String message :
        List.of(
            "",
            "no tags",
            "<>",
            "<<>>",
            "<a><b>",
            "<no close",
            "<no\n<yes>",
            "<a\rb>",
            "<a\u2028b>",
            "prefix <a title=\"same>remainder\"> suffix")) {
      assertThat(RichTextTagTranslationIntegrityEvaluator.extractRawTags(message))
          .as("legacy matches for %s", message)
          .containsExactlyElementsOf(legacyRawTags(message));
    }
  }

  @Test
  void structuralTagScannerMatchesLegacyRegexCaptures() {
    for (String rawTag :
        List.of(
            "<a>",
            "</a>",
            "<a/>",
            "<a />",
            "<a x=y>",
            "<a / >",
            "<a//>",
            "<>",
            "<a!>",
            "<a\rtitle>",
            "<a\u2028title>",
            "<a\nx>",
            "<a \n >",
            "<a \n x>")) {
      assertThat(RichTextTagTranslationIntegrityEvaluator.parseStructuralTag(rawTag))
          .as("legacy structural parse for %s", rawTag)
          .isEqualTo(legacyStructuralTag(rawTag));
    }
  }

  @Test
  void scannersHaveBoundedCharacterVisitsForHostileNearMisses() {
    CountingCharSequence rawNearMiss = new CountingCharSequence("<".repeat(4_096));
    CountingCharSequence structuralNearMiss =
        new CountingCharSequence("<a " + " ".repeat(4_096) + "/ >");

    assertThat(RichTextTagTranslationIntegrityEvaluator.extractRawTags(rawNearMiss)).isEmpty();
    assertThat(rawNearMiss.characterReads()).isLessThanOrEqualTo(2L * rawNearMiss.length());
    assertThat(RichTextTagTranslationIntegrityEvaluator.parseStructuralTag(structuralNearMiss))
        .isEqualTo(new RichTextTagTranslationIntegrityEvaluator.ParsedTag("a", false, false));
    assertThat(structuralNearMiss.characterReads())
        .isLessThanOrEqualTo(3L * structuralNearMiss.length());
  }

  private static boolean isApplicable(JsonNode testCase) {
    return testCase.path("tier").asText().equals("cutover")
        && containsText(testCase.path("rules"), "rich-text-tag-contract")
        && !hasSyntaxDiagnostic(testCase);
  }

  private static boolean hasSyntaxDiagnostic(JsonNode testCase) {
    for (JsonNode diagnostic : testCase.path("expected").path("diagnostics")) {
      String code = diagnostic.path("code").asText();
      if (code.equals("source-format-invalid") || code.equals("target-format-invalid")) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsText(JsonNode array, String expected) {
    for (JsonNode value : array) {
      if (value.asText().equals(expected)) {
        return true;
      }
    }
    return false;
  }

  private static TranslationIntegrityEvaluation expectedRuleEvaluation(
      JsonNode manifest, JsonNode testCase) {
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    for (JsonNode diagnostic : testCase.path("expected").path("diagnostics")) {
      if (!manifest
          .path("diagnosticRules")
          .path(diagnostic.path("code").asText())
          .asText()
          .equals("rich-text-tag-contract")) {
        continue;
      }
      Map<String, Object> details =
          JSON.convertValue(
              diagnostic.path("details"), new TypeReference<Map<String, Object>>() {});
      TranslationIntegrityRange range =
          diagnostic.has("range")
              ? new TranslationIntegrityRange(
                  diagnostic.path("range").path("start").asInt(),
                  diagnostic.path("range").path("end").asInt())
              : null;
      diagnostics.add(
          new TranslationIntegrityDiagnostic(
              diagnostic.path("code").asText(),
              Severity.fromWireValue(diagnostic.path("severity").asText()),
              Subject.fromWireValue(diagnostic.path("subject").asText()),
              details,
              range));
    }
    return diagnostics.isEmpty()
        ? TranslationIntegrityEvaluation.pass()
        : new TranslationIntegrityEvaluation(
            diagnostics, TranslationIntegrityDisposition.REJECT_TARGET);
  }

  private static List<String> legacyRawTags(String message) {
    List<String> matches = new ArrayList<>();
    Matcher matcher = LEGACY_RAW_TAG.matcher(message);
    while (matcher.find()) {
      matches.add(matcher.group());
    }
    return List.copyOf(matches);
  }

  private static RichTextTagTranslationIntegrityEvaluator.ParsedTag legacyStructuralTag(
      String rawTag) {
    Matcher matcher = LEGACY_STRUCTURAL_TAG.matcher(rawTag);
    if (!matcher.matches()) {
      return null;
    }
    return new RichTextTagTranslationIntegrityEvaluator.ParsedTag(
        matcher.group(2), !matcher.group(1).isEmpty(), !matcher.group(3).isEmpty());
  }

  private static Path findConformanceRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate = current.resolve("translation-integrity/conformance");
      if (Files.isRegularFile(candidate.resolve("manifest.json"))) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate translation-integrity/conformance fixtures");
  }

  private static final class CountingCharSequence implements CharSequence {

    private final String value;
    private long characterReads;

    private CountingCharSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      characterReads++;
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return value.subSequence(start, end);
    }

    private long characterReads() {
      return characterReads;
    }
  }
}
