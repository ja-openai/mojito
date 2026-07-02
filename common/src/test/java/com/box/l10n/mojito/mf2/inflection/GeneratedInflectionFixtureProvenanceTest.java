package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class GeneratedInflectionFixtureProvenanceTest {

  private static final List<String> FIXTURES =
      List.of(
          "ar_compiled_approved_explicit_form_pack_fixture.json",
          "ar_compiled_explicit_form_pack_fixture.json",
          "ar_pack_audit_fixture.json",
          "da_compiled_genitive_definiteness_pack_fixture.json",
          "de_article_case_report_fixture.json",
          "de_compiled_article_case_pack_fixture.json",
          "es_compiled_article_pack_fixture.json",
          "es_noun_pack_report_fixture.json",
          "fr_gender_suffix_rule_pack_fixture.json",
          "fr_inflection_profile_pack_fixture.json",
          "fr_noun_metadata_pack_fixture.json",
          "he_compiled_construct_form_pack_fixture.json",
          "hi_compiled_case_form_pack_fixture.json",
          "hi_pack_survey_fixture.json",
          "hi_pronoun_agreement_pack_fixture.json",
          "it_compiled_article_pack_fixture.json",
          "it_noun_pack_report_fixture.json",
          "low_inflection_locale_audit_fixture.json",
          "ml_compiled_approved_case_form_pack_fixture.json",
          "ml_compiled_case_form_pack_fixture.json",
          "nb_noun_metadata_pack_fixture.json",
          "nl_noun_metadata_pack_fixture.json",
          "pt_compiled_agreement_pack_fixture.json",
          "pt_noun_pack_report_fixture.json",
          "ru_case_form_pack_fixture.json",
          "ru_case_pack_audit_fixture.json",
          "ru_compiled_case_form_pack_fixture.json",
          "sr_case_form_pack_fixture.json",
          "sr_case_pack_report_fixture.json",
          "sr_compiled_case_form_pack_fixture.json",
          "sv_compiled_genitive_definiteness_pack_fixture.json",
          "tr_compiled_explicit_template_auto_pack_fixture.json",
          "tr_compiled_explicit_template_pack_fixture.json",
          "tr_compiled_suffix_pack_fixture.json",
          "tr_suffix_pack_survey_fixture.json");

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void generatedFixturesCarryStableProvenanceAndSourceMetadata() {
    for (String fixture : FIXTURES) {
      JsonNode root = readFixture(fixture);
      assertThat(assertText(root, "schema", fixture))
          .as("%s schema", fixture)
          .startsWith("mojito-mf2-inflection/");

      JsonNode provenance = root.path("provenance");
      assertThat(provenance.isObject()).as("%s provenance", fixture).isTrue();
      assertAcceptedLicense(assertText(provenance, "license", fixture), fixture);
      String generator = assertText(provenance, "generator", fixture);
      assertThat(generator)
          .as("%s generator", fixture)
          .satisfiesAnyOf(
              value -> assertThat(value).startsWith("dev-docs/experiments/mf2-inflection/"),
              value -> assertThat(value).isEqualTo("glossary-term-inflection-profile/v0"));

      validateSourceLabels(fixture, provenance, root);
      validateSourceRows(fixture, root);
    }
  }

  private void validateSourceLabels(String fixture, JsonNode provenance, JsonNode root) {
    JsonNode sourceLabels = provenance.path("sourceLabels");
    if (sourceLabels.isMissingNode()) {
      return;
    }

    assertThat(sourceLabels.isArray()).as("%s sourceLabels", fixture).isTrue();
    Set<String> labels = new HashSet<>();
    for (JsonNode label : sourceLabels) {
      String value = assertNodeText(label, fixture + " sourceLabels");
      assertThat(labels.add(value)).as("%s duplicate source label %s", fixture, value).isTrue();
    }

    int sourceCount = sourceArray(root).size();
    assertThat(sourceLabels.size()).as("%s sourceLabels count", fixture).isEqualTo(sourceCount);
  }

  private void validateSourceRows(String fixture, JsonNode root) {
    JsonNode sources = sourceArray(root);
    if (!sources.isEmpty()) {
      for (JsonNode source : sources) {
        validateMaterializedSource(fixture, source);
      }
      return;
    }

    if (isLowInflectionAudit(root)) {
      validateLowInflectionSources(fixture, root);
    } else if (!"Mojito-authored".equals(root.path("provenance").path("license").asText())) {
      throw new AssertionError(fixture + " source-backed fixture has no source metadata");
    }
  }

  private JsonNode sourceArray(JsonNode root) {
    JsonNode provenanceSources = root.path("provenance").path("sources");
    if (provenanceSources.isArray() && !provenanceSources.isEmpty()) {
      return provenanceSources;
    }
    JsonNode topLevelSources = root.path("sources");
    if (topLevelSources.isArray()) {
      return topLevelSources;
    }
    return provenanceSources.isArray() ? provenanceSources : topLevelSources;
  }

  private void validateMaterializedSource(String fixture, JsonNode source) {
    assertText(source, "path", fixture);
    assertNonNegativeLong(source, "byteSize", fixture);
    assertSha256Hex(assertText(source, "sha256", fixture), fixture + " sha256");
    assertThat(source.path("gitLfsPointer").isBoolean()).as("%s gitLfsPointer", fixture).isTrue();
  }

  private void validateLowInflectionSources(String fixture, JsonNode root) {
    for (JsonNode locale : root.path("locales")) {
      validateLowInflectionSource(fixture, locale.path("sources").path("dictionary"));
      validateLowInflectionSource(fixture, locale.path("sources").path("inflectional"));
      validateLowInflectionSource(fixture, locale.path("sources").path("pronouns"));
    }
  }

  private void validateLowInflectionSource(String fixture, JsonNode source) {
    assertText(source, "path", fixture);
    assertThat(source.path("exists").isBoolean()).as("%s exists", fixture).isTrue();
    assertThat(source.path("gitLfsPointer").isBoolean()).as("%s gitLfsPointer", fixture).isTrue();
    if (source.path("exists").asBoolean()) {
      assertNonNegativeLong(source, "byteSize", fixture);
      assertSha256Hex(assertText(source, "sha256", fixture), fixture + " sha256");
    }
    if (source.path("gitLfsPointer").asBoolean()) {
      assertSha256Hex(assertText(source, "gitLfsOidSha256", fixture), fixture + " gitLfsOidSha256");
      assertNonNegativeLong(source, "gitLfsObjectSize", fixture);
    }
  }

  private boolean isLowInflectionAudit(JsonNode root) {
    return "mojito-mf2-inflection/low-inflection-locale-audit/v0"
        .equals(root.path("schema").asText());
  }

  private void assertAcceptedLicense(String license, String fixture) {
    assertThat(license)
        .as("%s license", fixture)
        .satisfiesAnyOf(
            value -> assertThat(value).contains("Unicode-3.0"),
            value -> assertThat(value).contains("CC0-1.0"),
            value -> assertThat(value).isEqualTo("Mojito-authored"));
  }

  private String assertText(JsonNode node, String field, String fixture) {
    return assertNodeText(node.path(field), fixture + " " + field);
  }

  private String assertNodeText(JsonNode node, String description) {
    assertThat(node.isTextual()).as(description).isTrue();
    assertThat(node.asText()).as(description).isNotBlank();
    return node.asText();
  }

  private void assertSha256Hex(String value, String description) {
    assertThat(value).as(description).matches("[0-9a-f]{64}");
  }

  private void assertNonNegativeLong(JsonNode node, String field, String fixture) {
    JsonNode value = node.path(field);
    assertThat(value.isIntegralNumber()).as("%s %s", fixture, field).isTrue();
    assertThat(value.asLong()).as("%s %s", fixture, field).isGreaterThanOrEqualTo(0);
  }

  private JsonNode readFixture(String fixture) {
    return objectMapper.readTreeUnchecked(
        readResource("com/box/l10n/mojito/mf2/inflection/" + fixture));
  }

  private String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
