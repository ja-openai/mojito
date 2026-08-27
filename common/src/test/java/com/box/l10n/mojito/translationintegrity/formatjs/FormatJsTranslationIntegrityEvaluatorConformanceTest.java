package com.box.l10n.mojito.translationintegrity.formatjs;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FormatJsTranslationIntegrityEvaluatorConformanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SUPPORTED_RULES =
      Set.of("message-syntax", "argument-contract", "select-contract");

  @Test
  void matchesEveryApplicableCutoverCase() throws IOException {
    JsonNode manifest = JSON.readTree(findConformanceRoot().resolve("manifest.json").toFile());
    FormatJsTranslationIntegrityEvaluator evaluator = new FormatJsTranslationIntegrityEvaluator();
    List<String> mismatches = new ArrayList<>();
    int evaluated = 0;

    for (JsonNode testCase : manifest.path("cases")) {
      if (!isApplicable(testCase)) {
        continue;
      }
      evaluated++;
      String id = testCase.path("id").asText();
      TranslationIntegrityEvaluation actual =
          evaluator.evaluate(
              testCase.path("source").path("text").asText(),
              testCase.path("target").path("text").asText());
      TranslationIntegrityEvaluation expected = expectedEvaluation(testCase.path("expected"));
      String mismatch = mismatch(expected, actual);
      if (mismatch != null) {
        mismatches.add(id + ": " + mismatch);
      }
    }

    assertThat(evaluated).isEqualTo(54);
    assertThat(mismatches).isEmpty();
  }

  @Test
  void preservesEmojiPrefixedParserRangeAsUnicodeCodePoints() {
    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator().evaluate("🧭 Hello {name}", "🧭 TARGET {name");

    assertThat(actual.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::range)
        .isEqualTo(new TranslationIntegrityRange(9, 14));
  }

  @Test
  void leavesRangeAbsentWhenSkeletonValidationHasNoLocation() {
    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator()
            .evaluate("Value: {value}", "VALUE: {value, number, ::Ebad}");

    assertThat(actual.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::range)
        .isNull();
  }

  @Test
  void normalizesDuplicatePluralSelectors() {
    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator()
            .evaluate(
                "{count, plural, one {ONE} other {OTHER}}",
                "{count, plural, few {FIRST} few {SECOND} other {OTHER}}");

    assertThat(actual)
        .isEqualTo(
            new TranslationIntegrityEvaluation(
                List.of(
                    TranslationIntegrityDiagnostic.targetError(
                        "target-format-invalid",
                        Map.of("reason", "duplicate-selector"),
                        new TranslationIntegrityRange(28, 31))),
                TranslationIntegrityDisposition.REJECT_TARGET));
  }

  @Test
  void reportsOnlyOptionDifferencesWhenSelectOptionUnionsDiffer() {
    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator()
            .evaluate(
                "{mode, select, on {A} other {B}}",
                "{mode, select, on {A} maybe {B} other {C}} / "
                    + "{mode, select, on {D} maybe {E} other {F}}");

    assertThat(actual)
        .isEqualTo(
            new TranslationIntegrityEvaluation(
                List.of(
                    TranslationIntegrityDiagnostic.targetError(
                        "select-option-extra",
                        Map.of("argument", "mode", "options", List.of("maybe")))),
                TranslationIntegrityDisposition.REJECT_TARGET));
  }

  @Test
  void reportsOnlyVariableExtraForAnEntirelyNewSelectArgument() {
    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator()
            .evaluate("Static source", "{mode, select, on {A} other {B}}");

    assertThat(actual)
        .isEqualTo(
            new TranslationIntegrityEvaluation(
                List.of(
                    TranslationIntegrityDiagnostic.targetError(
                        "variable-extra", Map.of("names", List.of("mode")))),
                TranslationIntegrityDisposition.REJECT_TARGET));
  }

  @Test
  void generalizesTargetSelectChangesFromTypedSourceArguments() {
    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator()
            .evaluate("Value: {mode, number}", "{mode, select, on {A} other {B}}");

    assertThat(actual)
        .isEqualTo(
            new TranslationIntegrityEvaluation(
                List.of(
                    TranslationIntegrityDiagnostic.targetError(
                        "select-argument-changed",
                        Map.of(
                            "argument", "mode", "expectedType", "number", "actualType", "select"))),
                TranslationIntegrityDisposition.REJECT_TARGET));
  }

  private static boolean isApplicable(JsonNode testCase) {
    if (!testCase.path("tier").asText().equals("cutover")
        || !testCase.path("profile").asText().equals("formatjs")) {
      return false;
    }
    Set<String> rules = new HashSet<>();
    testCase.path("rules").forEach(rule -> rules.add(rule.asText()));
    return SUPPORTED_RULES.containsAll(rules);
  }

  private static TranslationIntegrityEvaluation expectedEvaluation(JsonNode expected) {
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    for (JsonNode diagnostic : expected.path("diagnostics")) {
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
    return new TranslationIntegrityEvaluation(
        diagnostics,
        TranslationIntegrityDisposition.valueOf(expected.path("disposition").asText()));
  }

  private static String mismatch(
      TranslationIntegrityEvaluation expected, TranslationIntegrityEvaluation actual) {
    if (expected.disposition() != actual.disposition()) {
      return "expected disposition " + expected.disposition() + ", got " + actual.disposition();
    }
    if (expected.diagnostics().size() != actual.diagnostics().size()) {
      return "expected diagnostics " + expected.diagnostics() + ", got " + actual.diagnostics();
    }
    for (int index = 0; index < expected.diagnostics().size(); index++) {
      TranslationIntegrityDiagnostic expectedDiagnostic = expected.diagnostics().get(index);
      TranslationIntegrityDiagnostic actualDiagnostic = actual.diagnostics().get(index);
      TranslationIntegrityDiagnostic expectedWithoutRange = withoutRange(expectedDiagnostic);
      TranslationIntegrityDiagnostic actualWithoutRange = withoutRange(actualDiagnostic);
      if (!expectedWithoutRange.equals(actualWithoutRange)) {
        return "expected diagnostic " + expectedDiagnostic + ", got " + actualDiagnostic;
      }
      if (expectedDiagnostic.range() != null
          && !expectedDiagnostic.range().equals(actualDiagnostic.range())) {
        return "expected range " + expectedDiagnostic.range() + ", got " + actualDiagnostic.range();
      }
    }
    return null;
  }

  private static TranslationIntegrityDiagnostic withoutRange(
      TranslationIntegrityDiagnostic diagnostic) {
    return new TranslationIntegrityDiagnostic(
        diagnostic.code(), diagnostic.severity(), diagnostic.subject(), diagnostic.details(), null);
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
}
