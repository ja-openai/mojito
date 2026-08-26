package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.Test;

public class AndroidLocalizedAssetIntegrityValidatorTest {

  private final AndroidLocalizedAssetIntegrityValidator validator =
      new AndroidLocalizedAssetIntegrityValidator();

  @Test
  public void conformsToGeneratedAndroidResourceCorpus() throws IOException {
    JsonNode corpus = new ObjectMapper().readTree(Files.readString(conformanceCorpus()));

    for (JsonNode testCase : corpus.get("cases")) {
      List<AndroidLocalizedAssetIntegrityValidator.Diagnostic> diagnostics =
          diagnostics(
              testCase.get("locale").asText(),
              testCase.get("source").asText(),
              testCase.get("target").asText());
      List<Tuple> expected = new ArrayList<>();
      for (JsonNode diagnostic : testCase.get("expectedDiagnostics")) {
        expected.add(tuple(diagnostic.get("resource").asText(), diagnostic.get("rule").asText()));
      }

      assertThat(diagnostics)
          .as(testCase.get("id").asText())
          .extracting(
              AndroidLocalizedAssetIntegrityValidator.Diagnostic::stringId,
              AndroidLocalizedAssetIntegrityValidator.Diagnostic::rule)
          .containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  @Test
  public void reportsTheLocaleStringAndRuleForInvalidAndroidSyntax() {
    String source = resources("<string name=\"offer_terms\">L\\'offre est soumise.</string>");
    String target = resources("<string name=\"offer_terms\">L'offre est soumise.</string>");

    assertThatThrownBy(() -> validator.validate("x-syntax", source, target))
        .isInstanceOfSatisfying(
            AndroidLocalizedAssetIntegrityException.class,
            exception ->
                assertThat(exception.getDiagnostics())
                    .singleElement()
                    .satisfies(
                        diagnostic -> {
                          assertThat(diagnostic.locale()).isEqualTo("x-syntax");
                          assertThat(diagnostic.stringId()).isEqualTo("offer_terms");
                          assertThat(diagnostic.rule())
                              .isEqualTo(
                                  AndroidLocalizedAssetIntegrityValidator.ANDROID_RESOURCE_SYNTAX);
                        }));
  }

  @Test
  public void acceptsIntentionalBlankLocalizedOutput() {
    validator.validate("x-empty", resources("<string name=\"missing\">Missing</string>"), "\n  ");
  }

  @Test
  public void reportsAssetPathForInvalidGeneratedContent() {
    String source = resources("<string name=\"account\">Account: %1$s.</string>");
    String target = resources("<string name=\"account\">ACCOUNT: %1$@.</string>");

    assertThatThrownBy(() -> validator.validate("x-path", "res/values/strings.xml", source, target))
        .isInstanceOfSatisfying(
            AndroidLocalizedAssetIntegrityException.class,
            exception ->
                assertThat(exception.getDiagnostics())
                    .singleElement()
                    .extracting(AndroidLocalizedAssetIntegrityValidator.Diagnostic::assetPath)
                    .isEqualTo("res/values/strings.xml"));
  }

  @Test
  public void reportsAllIndependentStringContractFailures() {
    String source =
        resources(
            """
            <string name="account">Account: %1$s.</string>
            <string name="settings">[Manage](sample-app://settings/privacy).</string>
            """);
    String target =
        resources(
            """
            <string name="account">ACCOUNT: %1$@.</string>
            <string name="settings">MANAGE.</string>
            """);

    assertThatThrownBy(() -> validator.validate("x-multiple", source, target))
        .isInstanceOfSatisfying(
            AndroidLocalizedAssetIntegrityException.class,
            exception ->
                assertThat(exception.getDiagnostics())
                    .extracting(AndroidLocalizedAssetIntegrityValidator.Diagnostic::rule)
                    .containsExactlyInAnyOrder(
                        AndroidLocalizedAssetIntegrityValidator.PRINTF_PLACEHOLDER_CONTRACT,
                        AndroidLocalizedAssetIntegrityValidator.MARKDOWN_LINK_CONTRACT));
  }

  private List<AndroidLocalizedAssetIntegrityValidator.Diagnostic> diagnostics(
      String locale, String source, String target) {
    try {
      validator.validate(locale, source, target);
      return List.of();
    } catch (AndroidLocalizedAssetIntegrityException exception) {
      return exception.getDiagnostics();
    }
  }

  private static Path conformanceCorpus() {
    Path fromRoot =
        Path.of("translation-integrity", "conformance", "android-generated-resources.json");
    return Files.exists(fromRoot) ? fromRoot : Path.of("..").resolve(fromRoot);
  }

  private static String resources(String content) {
    return "<resources>" + content + "</resources>";
  }
}
