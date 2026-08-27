package com.box.l10n.mojito.translationintegrity.formatjs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Adapter-level syntax assertions read directly from the shared integrity conformance corpus. */
class FormatJsConformanceManifestTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void formatJsMessageSyntaxCasesMatchTheSharedManifest() throws IOException {
    Path conformanceRoot = findConformanceRoot();
    JsonNode manifest = JSON.readTree(conformanceRoot.resolve("manifest.json").toFile());
    JsonNode parserExpectations =
        JSON.readTree(conformanceRoot.resolve("formatjs_parser_expectations.json").toFile());
    Map<String, FormatJsParseErrorKind> oracleErrorKinds =
        readOracleErrorKinds(parserExpectations.path("errorKindByCaseSide"));
    JsonNode adapterDifferences = parserExpectations.path("adapterDifferenceByCaseSide");
    JsonNode policyDifferences = parserExpectations.path("policyDifferenceByCaseSide");
    assertExpectationMetadata(parserExpectations);

    List<String> mismatches = new ArrayList<>();
    int evaluated = 0;
    int sourceDominated = 0;
    boolean checkedSelectorDepth = false;
    boolean checkedStyleDepth = false;
    Set<String> checkedOracleFailures = new LinkedHashSet<>();
    Set<String> checkedAdapterDifferences = new LinkedHashSet<>();
    Set<String> checkedPolicyDifferences = new LinkedHashSet<>();

    for (JsonNode testCase : manifest.path("cases")) {
      if (!testCase.path("profile").asText().equals("formatjs")
          || !containsText(testCase.path("rules"), "message-syntax")) {
        continue;
      }
      evaluated++;
      String id = testCase.path("id").asText();
      int maximumDepth = testCase.path("policy").path("maxNestingDepth").asInt(100);
      FormatJsParserOptions options =
          FormatJsParserOptions.MOJITO_STRICT.toBuilder()
              .ignoreTag(true)
              .maxNestingDepth(maximumDepth)
              .build();

      String sourceMessage = testCase.path("source").path("text").asText();
      ParseObservation source = parse(sourceMessage, options);
      JsonNode sourceDiagnostic = findDiagnostic(testCase, "source-format-invalid");
      compareDeclaredSyntax(
          id,
          "source",
          sourceMessage,
          source,
          sourceDiagnostic,
          maximumDepth,
          oracleErrorKinds,
          adapterDifferences,
          policyDifferences,
          mismatches,
          checkedOracleFailures,
          checkedAdapterDifferences,
          checkedPolicyDifferences);

      // Portable source syntax failure dominates the detector lane. A declared raw-parser adapter
      // difference remains portable-valid, so its target side must still be checked.
      if (sourceDiagnostic != null) {
        sourceDominated++;
        continue;
      }

      String targetMessage = testCase.path("target").path("text").asText();
      ParseObservation target = parse(targetMessage, options);
      JsonNode targetDiagnostic = findDiagnostic(testCase, "target-format-invalid");
      compareDeclaredSyntax(
          id,
          "target",
          targetMessage,
          target,
          targetDiagnostic,
          maximumDepth,
          oracleErrorKinds,
          adapterDifferences,
          policyDifferences,
          mismatches,
          checkedOracleFailures,
          checkedAdapterDifferences,
          checkedPolicyDifferences);
      if (id.equals("formatjs.syntax.selector-depth.reject")) {
        checkedSelectorDepth =
            target.error() != null
                && target.error().kind() == FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED;
      } else if (id.equals("formatjs.syntax.style-depth.reject")) {
        checkedStyleDepth =
            target.error() != null
                && target.error().kind() == FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED;
      }
    }

    assertThat(mismatches).isEmpty();
    assertThat(evaluated).isGreaterThan(40);
    assertThat(sourceDominated).isGreaterThanOrEqualTo(2);
    assertThat(checkedOracleFailures)
        .containsExactlyInAnyOrderElementsOf(oracleErrorKinds.keySet());
    assertThat(checkedAdapterDifferences)
        .containsExactlyInAnyOrderElementsOf(fieldNames(adapterDifferences));
    assertThat(checkedPolicyDifferences)
        .containsExactlyInAnyOrderElementsOf(fieldNames(policyDifferences));
    assertThat(checkedSelectorDepth).isTrue();
    assertThat(checkedStyleDepth).isTrue();
  }

  private static ParseObservation parse(String message, FormatJsParserOptions options) {
    try {
      FormatJsParseResult result = FormatJsParser.parseResult(message, options);
      return new ParseObservation(result.isSuccess(), result.error(), null);
    } catch (FormatJsSkeletonException exception) {
      return new ParseObservation(false, null, exception);
    }
  }

  private static void compareDeclaredSyntax(
      String id,
      String side,
      String message,
      ParseObservation actual,
      JsonNode diagnostic,
      int maximumDepth,
      Map<String, FormatJsParseErrorKind> oracleErrorKinds,
      JsonNode adapterDifferences,
      JsonNode policyDifferences,
      List<String> mismatches,
      Set<String> checkedOracleFailures,
      Set<String> checkedAdapterDifferences,
      Set<String> checkedPolicyDifferences) {
    String caseSide = id + "/" + side;
    if (diagnostic == null) {
      JsonNode adapterDifference = adapterDifferences.get(caseSide);
      if (adapterDifference != null) {
        checkedAdapterDifferences.add(caseSide);
        compareAdapterDifference(caseSide, message, actual, adapterDifference, mismatches);
        return;
      }
      if (!actual.valid()) {
        mismatches.add(caseSide + ": unexpectedly invalid: " + actual.failureDescription());
      }
      return;
    }

    String reason = diagnostic.path("details").path("reason").asText();
    FormatJsParseErrorKind expectedKind =
        reason.equals("maximum-nesting-depth")
            ? FormatJsParseErrorKind.MAX_NESTING_DEPTH_EXCEEDED
            : oracleErrorKinds.get(caseSide);
    if (expectedKind == null) {
      mismatches.add(caseSide + ": no pinned oracle error kind for " + reason);
      return;
    }
    if (reason.equals("maximum-nesting-depth")) {
      JsonNode policyDifference = policyDifferences.get(caseSide);
      if (policyDifference == null) {
        mismatches.add(caseSide + ": no pinned maximum-depth policy descriptor");
        return;
      }
      checkedPolicyDifferences.add(caseSide);
      int declaredMaximumDepth = policyDifference.path("maxNestingDepth").asInt(-1);
      int fixtureNestingDepth = policyDifference.path("fixtureNestingDepth").asInt(-1);
      String measurement = policyDifference.path("measurement").asText();
      if (declaredMaximumDepth != maximumDepth
          || fixtureNestingDepth <= declaredMaximumDepth
          || (!measurement.equals("select-elements")
              && !measurement.equals("simple-style-braces"))) {
        mismatches.add(
            caseSide
                + ": invalid maximum-depth policy descriptor "
                + policyDifference
                + " for configured depth "
                + maximumDepth);
        return;
      }
    } else {
      checkedOracleFailures.add(caseSide);
    }
    if (actual.error() == null || actual.error().kind() != expectedKind) {
      mismatches.add(
          caseSide
              + ": expected "
              + expectedKind
              + " for "
              + reason
              + ", got "
              + actual.failureDescription());
      return;
    }

    JsonNode range = diagnostic.get("range");
    if (range != null && !range.isNull()) {
      FormatJsCodePointRanges.CodePointRange actualRange =
          FormatJsCodePointRanges.toCodePointRange(message, actual.error().location());
      if (actualRange.start() != range.path("start").asInt()
          || actualRange.end() != range.path("end").asInt()) {
        mismatches.add(caseSide + ": expected code-point range " + range + ", got " + actualRange);
      }
    }
  }

  private static void compareAdapterDifference(
      String caseSide,
      String message,
      ParseObservation actual,
      JsonNode adapterDifference,
      List<String> mismatches) {
    if (!adapterDifference.path("kind").asText().equals("python-tag-span-opacity")) {
      mismatches.add(caseSide + ": unknown adapter difference " + adapterDifference);
      return;
    }
    FormatJsParseErrorKind expectedKind =
        FormatJsParseErrorKind.valueOf(adapterDifference.path("rawErrorKind").asText());
    if (actual.error() == null || actual.error().kind() != expectedKind) {
      mismatches.add(
          caseSide
              + ": expected raw adapter error "
              + expectedKind
              + ", got "
              + actual.failureDescription());
      return;
    }
    JsonNode expectedRange = adapterDifference.path("rawRange");
    FormatJsCodePointRanges.CodePointRange actualRange =
        FormatJsCodePointRanges.toCodePointRange(message, actual.error().location());
    if (actualRange.start() != expectedRange.path("start").asInt()
        || actualRange.end() != expectedRange.path("end").asInt()) {
      mismatches.add(
          caseSide + ": expected raw adapter range " + expectedRange + ", got " + actualRange);
    }
  }

  private static JsonNode findDiagnostic(JsonNode testCase, String code) {
    for (JsonNode diagnostic : testCase.path("expected").path("diagnostics")) {
      if (diagnostic.path("code").asText().equals(code)) {
        return diagnostic;
      }
    }
    return null;
  }

  private static boolean containsText(JsonNode array, String expected) {
    for (JsonNode value : array) {
      if (value.asText().equals(expected)) {
        return true;
      }
    }
    return false;
  }

  private static void assertExpectationMetadata(JsonNode expectations) {
    assertThat(expectations.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(expectations.path("parser").path("package").asText())
        .isEqualTo("@formatjs/icu-messageformat-parser");
    assertThat(expectations.path("parser").path("version").asText()).isEqualTo("3.5.10");
    assertThat(
            expectations
                .path("parser")
                .path("dependencies")
                .path("@formatjs/icu-skeleton-parser")
                .asText())
        .isEqualTo("2.1.9");

    JsonNode declaredOptions = expectations.path("options");
    assertThat(fieldNames(declaredOptions))
        .containsExactlyInAnyOrder(
            "captureLocation", "ignoreTag", "requiresOtherClause", "shouldParseSkeletons");
    FormatJsParserOptions options = FormatJsParserOptions.MOJITO_STRICT;
    assertThat(declaredOptions.path("captureLocation").asBoolean())
        .isEqualTo(options.captureLocation());
    assertThat(declaredOptions.path("ignoreTag").asBoolean()).isEqualTo(options.ignoreTag());
    assertThat(declaredOptions.path("requiresOtherClause").asBoolean())
        .isEqualTo(options.requiresOtherClause());
    assertThat(declaredOptions.path("shouldParseSkeletons").asBoolean())
        .isEqualTo(options.shouldParseSkeletons());
  }

  private static Map<String, FormatJsParseErrorKind> readOracleErrorKinds(JsonNode object) {
    Map<String, FormatJsParseErrorKind> errorKinds = new LinkedHashMap<>();
    object
        .fields()
        .forEachRemaining(
            field ->
                errorKinds.put(
                    field.getKey(), FormatJsParseErrorKind.valueOf(field.getValue().asText())));
    return errorKinds;
  }

  private static Set<String> fieldNames(JsonNode object) {
    Set<String> names = new LinkedHashSet<>();
    object.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static Path findConformanceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int attempt = 0;
        current != null && attempt < 6;
        attempt++, current = current.getParent()) {
      Path candidate = current.resolve("translation-integrity/conformance");
      if (Files.isRegularFile(candidate.resolve("manifest.json"))
          && Files.isRegularFile(candidate.resolve("formatjs_parser_expectations.json"))) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Could not locate translation-integrity/conformance parser fixtures");
  }

  private record ParseObservation(
      boolean valid, FormatJsParseError error, FormatJsSkeletonException skeletonError) {

    private String failureDescription() {
      if (error != null) {
        return error.kind().name();
      }
      if (skeletonError != null) {
        return "skeleton-" + skeletonError.skeletonType();
      }
      return valid ? "valid" : "unknown failure";
    }
  }
}
