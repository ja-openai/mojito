package com.box.l10n.mojito.translationintegrity.formatjs;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Subject;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityRange;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityRepairOperation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityReviewDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegritySafeRepair;
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
      Set.of(
          "message-syntax",
          "argument-contract",
          "select-contract",
          "rich-text-tag-contract",
          "boundary-whitespace",
          "email-literal-contract",
          "url-literal-contract",
          "formatjs-apostrophe-before-tag");

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
      TranslationIntegrityEvaluation actual = evaluate(testCase, evaluator);
      TranslationIntegrityEvaluation expected = expectedEvaluation(testCase.path("expected"));
      String mismatch = mismatch(expected, actual);
      if (mismatch != null) {
        mismatches.add(id + ": " + mismatch);
      }
    }

    assertThat(evaluated).isEqualTo(70);
    assertThat(mismatches).isEmpty();
  }

  @Test
  void matchesOwnedExtendedApostropheSafetyCases() throws IOException {
    JsonNode manifest = JSON.readTree(findConformanceRoot().resolve("manifest.json").toFile());
    FormatJsTranslationIntegrityEvaluator evaluator = new FormatJsTranslationIntegrityEvaluator();
    List<String> mismatches = new ArrayList<>();
    int evaluated = 0;

    for (JsonNode testCase : manifest.path("cases")) {
      if (!isOwnedExtendedApostropheCase(testCase)) {
        continue;
      }
      evaluated++;
      TranslationIntegrityEvaluation actual = evaluate(testCase, evaluator);
      TranslationIntegrityEvaluation expected = expectedEvaluation(testCase.path("expected"));
      String mismatch = mismatch(expected, actual);
      if (mismatch != null) {
        mismatches.add(testCase.path("id").asText() + ": " + mismatch);
      }
    }

    assertThat(evaluated).isEqualTo(18);
    assertThat(mismatches).isEmpty();
  }

  @Test
  void matchesTheDetectorLaneOfTheRemainingPolicyCompositionCase() throws IOException {
    JsonNode manifest = JSON.readTree(findConformanceRoot().resolve("manifest.json").toFile());
    JsonNode testCase = findCase(manifest, "policy.waiver-does-not-short-circuit.reject");
    TranslationIntegrityEvaluation expected = expectedEvaluation(testCase.path("expected"));
    TranslationIntegrityEvaluation actual =
        evaluate(testCase, new FormatJsTranslationIntegrityEvaluator());

    assertThat(actual.diagnostics()).containsExactlyElementsOf(expected.diagnostics());
    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.policyDiagnostics()).isEmpty();
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
  void optionalCompositionPreservesSourceAndTargetSyntaxDominance() {
    FormatJsTranslationIntegrityEvaluator evaluator = new FormatJsTranslationIntegrityEvaluator();

    assertThat(
            evaluator
                .evaluate(
                    " {name help@example.invalid",
                    "TARGET support@example.invalid",
                    false,
                    true,
                    true,
                    true)
                .diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("source-format-invalid");
    assertThat(
            evaluator
                .evaluate(
                    " SOURCE {name} help@example.invalid ",
                    "{name support@example.invalid",
                    false,
                    true,
                    true,
                    true)
                .diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("target-format-invalid");
  }

  @Test
  void enablesLiteralContractsOnlyWhenExplicitlySelected() {
    String source = "help@example.invalid https://docs.example.invalid/start";
    String target = "support@example.invalid https://help.example.invalid/start";
    FormatJsTranslationIntegrityEvaluator evaluator = new FormatJsTranslationIntegrityEvaluator();

    assertThat(evaluator.evaluate(source, target)).isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluator.evaluate(source, target, false, false, true, true).diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .containsExactly(
            "immutable-email-extra",
            "immutable-email-missing",
            "immutable-url-extra",
            "immutable-url-missing");
  }

  @Test
  void enablesApostropheContractOnlyWhenExplicitlySelected() {
    String source = "Read <link>details</link>.";
    String target = "L'<link>DETAIL</link>.";
    FormatJsTranslationIntegrityEvaluator evaluator = new FormatJsTranslationIntegrityEvaluator();

    assertThat(evaluator.evaluate(source, target, true))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluator.evaluate(source, target, true, false, false, false, true).disposition())
        .isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
  }

  @Test
  void enablesOpaqueTagCompatibilityOnlyWithTheRichTextFeature() {
    String message = "<link title=\"{\">TEXT</link>";
    FormatJsTranslationIntegrityEvaluator evaluator = new FormatJsTranslationIntegrityEvaluator();

    assertThat(evaluator.evaluate(message, message).diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("source-format-invalid");
    assertThat(evaluator.evaluate(message, message, true))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void preservesParserRangeAfterOpaqueTagParsing() {
    String source = "🧭 <link title=\"{ignored}\"> SOURCE {name}</link>";
    String target = "🧭 <link title=\"{ignored}\"> TARGET {name</link>";
    int errorStart = target.codePointCount(0, target.indexOf("{name"));
    int errorEnd = errorStart + "{name".codePointCount(0, "{name".length());

    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator().evaluate(source, target, true);

    assertThat(actual.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::range)
        .isEqualTo(new TranslationIntegrityRange(errorStart, errorEnd));
  }

  @Test
  void preservesAnOuterIcuQuoteClosingInsideATagLikeSpan() {
    String source = "'<link title=\"x'{inside}y\"> {name}";
    String target = "'<link title=\"x'{inside}y\"> TARGET";

    TranslationIntegrityEvaluation actual =
        new FormatJsTranslationIntegrityEvaluator().evaluate(source, target, true);

    assertThat(actual)
        .isEqualTo(
            new TranslationIntegrityEvaluation(
                List.of(
                    TranslationIntegrityDiagnostic.targetError(
                        "variable-missing", Map.of("names", List.of("name")))),
                TranslationIntegrityDisposition.REJECT_TARGET));
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

  private static boolean isOwnedExtendedApostropheCase(JsonNode testCase) {
    if (!testCase.path("tier").asText().equals("extended")
        || !testCase.path("profile").asText().equals("formatjs")
        || !containsText(testCase.path("rules"), "formatjs-apostrophe-before-tag")) {
      return false;
    }
    Set<String> rules = new HashSet<>();
    testCase.path("rules").forEach(rule -> rules.add(rule.asText()));
    return SUPPORTED_RULES.containsAll(rules);
  }

  private static TranslationIntegrityEvaluation evaluate(
      JsonNode testCase, FormatJsTranslationIntegrityEvaluator evaluator) {
    return evaluator.evaluate(
        testCase.path("source").path("text").asText(),
        testCase.path("target").path("text").asText(),
        containsText(testCase.path("features"), "rich-text-tags"),
        containsText(testCase.path("rules"), "boundary-whitespace"),
        containsText(testCase.path("rules"), "email-literal-contract"),
        containsText(testCase.path("rules"), "url-literal-contract"),
        containsText(testCase.path("rules"), "formatjs-apostrophe-before-tag"));
  }

  private static JsonNode findCase(JsonNode manifest, String id) {
    for (JsonNode testCase : manifest.path("cases")) {
      if (testCase.path("id").asText().equals(id)) {
        return testCase;
      }
    }
    throw new IllegalArgumentException("unknown case: " + id);
  }

  private static boolean containsText(JsonNode array, String expected) {
    for (JsonNode value : array) {
      if (value.asText().equals(expected)) {
        return true;
      }
    }
    return false;
  }

  private static TranslationIntegrityEvaluation expectedEvaluation(JsonNode expected) {
    List<TranslationIntegrityDiagnostic> diagnostics = diagnostics(expected.path("diagnostics"));
    List<TranslationIntegrityDiagnostic> policyDiagnostics =
        diagnostics(expected.path("policyDiagnostics"));
    TranslationIntegritySafeRepair safeRepair = null;
    if (expected.has("safeRepair")) {
      JsonNode repair = expected.path("safeRepair");
      List<TranslationIntegrityRepairOperation> operations = new ArrayList<>();
      repair
          .path("operations")
          .forEach(
              operation ->
                  operations.add(TranslationIntegrityRepairOperation.valueOf(operation.asText())));
      safeRepair =
          new TranslationIntegritySafeRepair(
              operations,
              repair.path("expectedTarget").asText(),
              diagnostics(repair.path("expectedDiagnostics")),
              diagnostics(repair.path("expectedPolicyDiagnostics")));
    }
    return new TranslationIntegrityEvaluation(
        diagnostics,
        policyDiagnostics,
        TranslationIntegrityDisposition.valueOf(expected.path("disposition").asText()),
        expected.has("reviewDisposition")
            ? TranslationIntegrityReviewDisposition.valueOf(
                expected.path("reviewDisposition").asText())
            : null,
        safeRepair);
  }

  private static List<TranslationIntegrityDiagnostic> diagnostics(JsonNode values) {
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    for (JsonNode diagnostic : values) {
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
    return diagnostics;
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
    if (!expected.policyDiagnostics().equals(actual.policyDiagnostics())) {
      return "expected policy diagnostics "
          + expected.policyDiagnostics()
          + ", got "
          + actual.policyDiagnostics();
    }
    if (expected.reviewDisposition() != actual.reviewDisposition()) {
      return "expected review disposition "
          + expected.reviewDisposition()
          + ", got "
          + actual.reviewDisposition();
    }
    if (!java.util.Objects.equals(expected.safeRepair(), actual.safeRepair())) {
      return "expected safe repair " + expected.safeRepair() + ", got " + actual.safeRepair();
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
