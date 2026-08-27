package com.box.l10n.mojito.translationintegrity.dollartemplate;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DollarTemplateTranslationIntegrityEvaluatorConformanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SUPPORTED_RULES = Set.of("message-syntax", "argument-contract");

  @Test
  void matchesEveryApplicableCutoverCase() throws IOException {
    JsonNode manifest = JSON.readTree(findConformanceRoot().resolve("manifest.json").toFile());
    DollarTemplateTranslationIntegrityEvaluator evaluator =
        new DollarTemplateTranslationIntegrityEvaluator();
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
      if (!expected.equals(actual)) {
        mismatches.add(id + ": expected " + expected + ", got " + actual);
      }
    }

    assertThat(evaluated).isEqualTo(5);
    assertThat(mismatches).isEmpty();
  }

  @Test
  void matchesPythonStringTemplateIdentifierEdgeSpellings() {
    Map<String, Set<String>> cases = new LinkedHashMap<>();
    cases.put("$$", Set.of());
    cases.put("$$$name", Set.of("name"));
    cases.put("$na-me", Set.of("na"));
    cases.put("$name.name", Set.of("name"));
    cases.put("$", Set.of());
    cases.put("$9", Set.of());
    cases.put("${}", Set.of());
    cases.put("${name", Set.of());

    cases.forEach(
        (message, expected) ->
            assertThat(DollarTemplateTranslationIntegrityEvaluator.extractIdentifiers(message))
                .as(message)
                .containsExactlyInAnyOrderElementsOf(expected));
  }

  @Test
  void resumesScanningAfterAnInvalidPlaceholderLikePythonStringTemplate() {
    assertThat(
            DollarTemplateTranslationIntegrityEvaluator.extractIdentifiers(
                "${$nested} $é$following"))
        .containsExactlyInAnyOrder("nested", "following");
  }

  private static boolean isApplicable(JsonNode testCase) {
    if (!testCase.path("tier").asText().equals("cutover")
        || !testCase.path("profile").asText().equals("dollar-template")) {
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
