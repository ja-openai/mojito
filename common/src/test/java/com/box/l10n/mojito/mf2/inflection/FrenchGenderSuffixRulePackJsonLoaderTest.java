package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.FrenchGenderSuffixRulePackJsonLoader.FrenchGenderSuffixRulePack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class FrenchGenderSuffixRulePackJsonLoaderTest {

  private final FrenchGenderSuffixRulePackJsonLoader loader =
      new FrenchGenderSuffixRulePackJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new FrenchGenderSuffixRulePackJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("objectMapper");
  }

  @Test
  public void rejectNullJson() {
    assertThatThrownBy(() -> loader.load((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("json");
  }

  @Test
  public void loadsGeneratedSuffixRulesAndMatchesInOrder() {
    FrenchGenderSuffixRulePack pack = loader.load(frenchGenderSuffixRulePackJson());

    assertThat(pack.locale()).isEqualTo("fr");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    assertThat(pack.rules()).hasSize(2);
    assertThat(pack.summary().trainingSurfaces()).isEqualTo(16590);
    assertThat(pack.bestRuleFor("modernisation").orElseThrow().suffix()).isEqualTo("isation");
    assertThat(pack.bestRuleFor("classement").orElseThrow().gender()).isEqualTo("masculine");
    assertThat(pack.bestRuleFor("livre")).isEmpty();
  }

  @Test
  public void loadedSuffixRulePackCollectionsAreImmutable() {
    FrenchGenderSuffixRulePack pack = loader.load(frenchGenderSuffixRulePackJson());

    assertThatThrownBy(() -> pack.rules().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.provenance().sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void loadsPythonGeneratedFixture() {
    FrenchGenderSuffixRulePack pack =
        loader.load(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/fr_gender_suffix_rule_pack_fixture.json"));

    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("21e1a3d385db927d10d175a322ad72f55cac455ab6c960564c90fe6ac23fc53b");
    assertThat(pack.rules()).hasSize(8);
    assertThat(pack.summary().rules()).isEqualTo(370);
    assertThat(pack.summary().suffixOnlyAccuracy()).isEqualTo(0.7907);
    assertThat(pack.bestRuleFor("modernisations").orElseThrow().gender()).isEqualTo("feminine");
  }

  @Test
  public void rejectsUnsupportedGender() {
    String json = frenchGenderSuffixRulePackJson().replace("\"feminine\"", "\"neuter\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported French suffix-rule gender");
  }

  @Test
  public void rejectsUnexpectedSchema() {
    String json =
        frenchGenderSuffixRulePackJson()
            .replace(
                FrenchGenderSuffixRulePackJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/fr-gender-suffix-rule-pack/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected French suffix-rule schema");
  }

  @Test
  public void rejectsConfidenceOutsideRange() {
    String json =
        frenchGenderSuffixRulePackJson().replace("\"confidence\": 1.0", "\"confidence\": 1.2");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("confidence must be between 0 and 1");
  }

  @Test
  public void rejectsSummaryRuleMismatch() {
    String json =
        frenchGenderSuffixRulePackJson().replace("\"exportedRules\": 2", "\"exportedRules\": 3");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exported rule count does not match");
  }

  private String frenchGenderSuffixRulePackJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/fr-gender-suffix-rule-pack/v0",
          "locale": "fr",
          "provenance": {
            "license": "Unicode-3.0",
            "generator": "dev-docs/experiments/mf2-inflection/fr_noun_pack_report.py",
            "sources": [
              {
                "path": "inflection/resources/org/unicode/inflection/dictionary/dictionary_fr.lst",
                "byteSize": 17255961,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            ]
          },
          "rules": [
            {
              "suffix": "isation",
              "gender": "feminine",
              "confidence": 1.0,
              "support": 86
            },
            {
              "suffix": "sement",
              "gender": "masculine",
              "confidence": 0.95,
              "support": 60
            }
          ],
          "summary": {
            "trainingSurfaces": 16590,
            "rules": 370,
            "exportedRules": 2,
            "suffixOnlyAccuracy": 0.7907,
            "suffixRuleBytes": 3117,
            "exportedSuffixRuleBytes": 20,
            "maxSuffixLen": 8,
            "minSupport": 20,
            "minConfidence": 0.88
          }
        }
        """;
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
