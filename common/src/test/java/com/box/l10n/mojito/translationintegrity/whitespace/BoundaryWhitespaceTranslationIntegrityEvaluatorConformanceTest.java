package com.box.l10n.mojito.translationintegrity.whitespace;

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
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BoundaryWhitespaceTranslationIntegrityEvaluatorConformanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void matchesEveryApplicableNeutralCase() throws IOException {
    JsonNode manifest = JSON.readTree(findConformanceRoot().resolve("manifest.json").toFile());
    BoundaryWhitespaceTranslationIntegrityEvaluator evaluator =
        new BoundaryWhitespaceTranslationIntegrityEvaluator();
    List<String> mismatches = new ArrayList<>();
    int evaluated = 0;

    for (JsonNode testCase : manifest.path("cases")) {
      if (!isApplicable(testCase)) {
        continue;
      }
      evaluated++;
      TranslationIntegrityEvaluation expected = expectedEvaluation(testCase.path("expected"));
      TranslationIntegrityEvaluation actual =
          evaluator.evaluate(
              testCase.path("source").path("text").asText(),
              testCase.path("target").path("text").asText());
      if (!expected.equals(actual)) {
        mismatches.add(testCase.path("id").asText() + ": expected " + expected + ", got " + actual);
      }
    }

    assertThat(evaluated).isEqualTo(8);
    assertThat(mismatches).isEmpty();
  }

  @Test
  void usesExactlyTheTwentyNinePythonStripCodePoints() {
    List<Integer> expected =
        List.of(
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x001C, 0x001D, 0x001E, 0x001F, 0x0020, 0x0085,
            0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008,
            0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000);
    List<Integer> actual =
        IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
            .filter(BoundaryWhitespaceTranslationIntegrityEvaluator::isPythonStripWhitespace)
            .boxed()
            .toList();

    assertThat(actual).containsExactlyElementsOf(expected);
  }

  @Test
  void preservesTheTargetCoreExactly() {
    TranslationIntegrityEvaluation actual =
        new BoundaryWhitespaceTranslationIntegrityEvaluator()
            .evaluate("\tSOURCE\n", "  \u200BTARGET \u200B  ");

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.AUTO_REPAIR_TARGET);
    assertThat(actual.safeRepair().expectedTarget()).isEqualTo("\t\u200BTARGET \u200B\n");
  }

  @Test
  void preservesIndependentPolicyAndReviewThroughRepair() {
    TranslationIntegrityDiagnostic reviewDiagnostic =
        new TranslationIntegrityDiagnostic(
            "semantic-review-required",
            Severity.WARNING,
            Subject.TARGET,
            Map.of("finding", "meaning-review"),
            null);
    TranslationIntegrityEvaluation structuralEvaluation =
        new TranslationIntegrityEvaluation(
            List.of(),
            List.of(reviewDiagnostic),
            TranslationIntegrityDisposition.PASS,
            TranslationIntegrityReviewDisposition.REVIEW_REQUIRED,
            null);

    TranslationIntegrityEvaluation actual =
        new BoundaryWhitespaceTranslationIntegrityEvaluator()
            .compose(
                " SOURCE ", "TARGET", structuralEvaluation, ignoredTarget -> structuralEvaluation);

    assertThat(actual.policyDiagnostics()).containsExactly(reviewDiagnostic);
    assertThat(actual.reviewDisposition())
        .isEqualTo(TranslationIntegrityReviewDisposition.REVIEW_REQUIRED);
    assertThat(actual.safeRepair().expectedPolicyDiagnostics()).containsExactly(reviewDiagnostic);
  }

  @Test
  void sourceRejectionDominatesBoundaryEvaluation() {
    TranslationIntegrityEvaluation sourceRejection =
        new TranslationIntegrityEvaluation(
            List.of(
                TranslationIntegrityDiagnostic.sourceError(
                    "source-contract-invalid", Map.of("reason", "test"))),
            TranslationIntegrityDisposition.REJECT_SOURCE);

    TranslationIntegrityEvaluation actual =
        new BoundaryWhitespaceTranslationIntegrityEvaluator()
            .compose(
                " SOURCE ",
                "TARGET",
                sourceRejection,
                ignoredTarget -> {
                  throw new AssertionError("source rejection must short-circuit repair");
                });

    assertThat(actual).isSameAs(sourceRejection);
  }

  @Test
  void suppressesRepairWhenTheRepairedCompositeDoesNotPass() {
    TranslationIntegrityEvaluation repairedRejection =
        new TranslationIntegrityEvaluation(
            List.of(
                TranslationIntegrityDiagnostic.targetError(
                    "variable-missing", Map.of("names", List.of("name")))),
            TranslationIntegrityDisposition.REJECT_TARGET);

    TranslationIntegrityEvaluation actual =
        new BoundaryWhitespaceTranslationIntegrityEvaluator()
            .compose(
                " SOURCE ",
                "TARGET",
                TranslationIntegrityEvaluation.pass(),
                ignoredTarget -> repairedRejection);

    assertThat(actual.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(actual.safeRepair()).isNull();
    assertThat(actual.diagnostics())
        .singleElement()
        .extracting(TranslationIntegrityDiagnostic::code)
        .isEqualTo("boundary-whitespace-mismatch");
  }

  private static boolean isApplicable(JsonNode testCase) {
    if (!containsText(testCase.path("rules"), "boundary-whitespace")
        || !testCase.path("expected").path("policyDiagnostics").isEmpty()) {
      return false;
    }
    for (JsonNode diagnostic : testCase.path("expected").path("diagnostics")) {
      if (!diagnostic.path("code").asText().equals("boundary-whitespace-mismatch")) {
        return false;
      }
    }
    JsonNode safeRepair = testCase.path("expected").path("safeRepair");
    return safeRepair.isMissingNode()
        || safeRepair.path("operations").size() == 1
            && safeRepair
                .path("operations")
                .get(0)
                .asText()
                .equals("COPY_SOURCE_BOUNDARY_WHITESPACE");
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
