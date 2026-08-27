package com.box.l10n.mojito.translationintegrity.literal;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Subject;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class LiteralTranslationIntegrityEvaluatorConformanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void emailEvaluatorMatchesEveryApplicableCutoverCase() throws IOException {
    assertApplicableCases(
        "email",
        "email-literal-contract",
        new EmailLiteralTranslationIntegrityEvaluator()::evaluate,
        9);
  }

  @Test
  void urlEvaluatorMatchesEveryApplicableCutoverCase() throws IOException {
    assertApplicableCases(
        "url", "url-literal-contract", new UrlLiteralTranslationIntegrityEvaluator()::evaluate, 9);
  }

  @Test
  void emailExtractionMatchesLegacyAsciiRegexQuirks() {
    assertThat(EmailLiteralTranslationIntegrityEvaluator.extractEmails("no marker")).isEmpty();
    assertThat(
            EmailLiteralTranslationIntegrityEvaluator.extractEmails(
                "alpha@exämple.invalid élise@example.invalid a@example.invalid2"))
        .containsExactly("a@example.invalid", "lise@example.invalid");
    assertThat(
            EmailLiteralTranslationIntegrityEvaluator.extractEmails(
                "Z+tag@Sub.Example.INVALID then a..b@-example..invalid"))
        .containsExactly("Z+tag@Sub.Example.INVALID", "a..b@-example..invalid");
  }

  @Test
  void urlExtractionMatchesLegacyPartialRegexQuirks() {
    assertThat(UrlLiteralTranslationIntegrityEvaluator.extractUrls("HTTP://example.invalid/path"))
        .isEmpty();
    assertThat(
            UrlLiteralTranslationIntegrityEvaluator.extractUrls(
                "https://example.invalid:8443/search?q=one "
                    + "https://example.invalid/help?from=mail#top "
                    + "https://example.invalid/a.b"))
        .containsExactly(
            "https://example.invalid",
            "https://example.invalid/a",
            "https://example.invalid/help?from");
    assertThat(
            UrlLiteralTranslationIntegrityEvaluator.extractUrls(
                "https://hyphen-host.example.invalid/path"))
        .isEmpty();
  }

  @Test
  void entirelyAbsentDuplicateUsesExactCounts() {
    TranslationIntegrityEvaluation email =
        new EmailLiteralTranslationIntegrityEvaluator()
            .evaluate("Use help@example.invalid twice: help@example.invalid", "NO CONTACT VALUE");
    TranslationIntegrityEvaluation url =
        new UrlLiteralTranslationIntegrityEvaluator()
            .evaluate(
                "Use https://docs.example.invalid twice: https://docs.example.invalid", "NO URL");

    assertThat(email.diagnostics())
        .containsExactly(
            TranslationIntegrityDiagnostic.targetError(
                "immutable-email-missing",
                Map.of("value", "help@example.invalid", "expectedCount", 2, "actualCount", 0)));
    assertThat(url.diagnostics())
        .containsExactly(
            TranslationIntegrityDiagnostic.targetError(
                "immutable-url-missing",
                Map.of(
                    "value",
                    "https://docs.example.invalid",
                    "expectedCount",
                    2,
                    "actualCount",
                    0)));
  }

  private static void assertApplicableCases(
      String kind,
      String rule,
      BiFunction<String, String, TranslationIntegrityEvaluation> evaluator,
      int expectedCount)
      throws IOException {
    JsonNode manifest = JSON.readTree(findConformanceRoot().resolve("manifest.json").toFile());
    List<String> mismatches = new ArrayList<>();
    int evaluated = 0;

    for (JsonNode testCase : manifest.path("cases")) {
      if (!testCase.path("tier").asText().equals("cutover")
          || !containsText(testCase.path("rules"), rule)
          || hasSyntaxDiagnostic(testCase.path("expected").path("diagnostics"))) {
        continue;
      }
      evaluated++;
      TranslationIntegrityEvaluation actual =
          evaluator.apply(
              testCase.path("source").path("text").asText(),
              testCase.path("target").path("text").asText());
      List<TranslationIntegrityDiagnostic> expectedDiagnostics = new ArrayList<>();
      for (JsonNode diagnostic : testCase.path("expected").path("diagnostics")) {
        if (diagnostic.path("code").asText().startsWith("immutable-" + kind + "-")) {
          expectedDiagnostics.add(diagnostic(diagnostic));
        }
      }
      TranslationIntegrityEvaluation expected =
          expectedDiagnostics.isEmpty()
              ? TranslationIntegrityEvaluation.pass()
              : new TranslationIntegrityEvaluation(
                  expectedDiagnostics, TranslationIntegrityDisposition.REJECT_TARGET);
      if (!expected.equals(actual)) {
        mismatches.add(testCase.path("id").asText() + ": expected " + expected + ", got " + actual);
      }
    }

    assertThat(evaluated).isEqualTo(expectedCount);
    assertThat(mismatches).isEmpty();
  }

  private static TranslationIntegrityDiagnostic diagnostic(JsonNode diagnostic) {
    Map<String, Object> details =
        JSON.convertValue(diagnostic.path("details"), new TypeReference<Map<String, Object>>() {});
    return new TranslationIntegrityDiagnostic(
        diagnostic.path("code").asText(),
        Severity.fromWireValue(diagnostic.path("severity").asText()),
        Subject.fromWireValue(diagnostic.path("subject").asText()),
        details,
        null);
  }

  private static boolean containsText(JsonNode array, String expected) {
    for (JsonNode value : array) {
      if (value.asText().equals(expected)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasSyntaxDiagnostic(JsonNode diagnostics) {
    for (JsonNode diagnostic : diagnostics) {
      String code = diagnostic.path("code").asText();
      if (code.equals("source-format-invalid") || code.equals("target-format-invalid")) {
        return true;
      }
    }
    return false;
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
