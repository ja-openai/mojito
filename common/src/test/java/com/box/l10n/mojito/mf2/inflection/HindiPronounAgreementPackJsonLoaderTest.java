package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader.HindiPronounAgreementPack;
import com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader.Request;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HindiPronounAgreementPackJsonLoaderTest {

  private final HindiPronounAgreementPackJsonLoader loader =
      new HindiPronounAgreementPackJsonLoader();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new HindiPronounAgreementPackJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("objectMapper");
  }

  @Test
  public void rejectsNullJson() {
    assertThatThrownBy(() -> loader.load((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("json");
  }

  @Test
  public void rejectsNullRoot() {
    assertThatThrownBy(() -> loader.load((JsonNode) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("root");
  }

  @Test
  public void rejectsNonObjectRoot() {
    assertThatThrownBy(() -> loader.load(objectMapper.readTreeUnchecked("[]")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected object root: Hindi pronoun agreement pack");
  }

  @Test
  public void loadsGeneratedFixtureAndRendersPronounAgreement() {
    HindiPronounAgreementPack pack = loader.load(hindiPronounAgreementPackJson());

    assertThat(pack.schema()).isEqualTo(HindiPronounAgreementPackJsonLoader.EXPECTED_SCHEMA);
    assertThat(pack.locale()).isEqualTo("hi");
    assertThat(pack.packShape()).isEqualTo(HindiPronounAgreementPackJsonLoader.EXPECTED_PACK_SHAPE);
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/hi_pack_survey.py");
    assertThat(pack.provenance().sourceLabels())
        .containsExactly("inflection/resources/org/unicode/inflection/inflection/pronoun_hi.csv");
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.provenance().sources().get(0).byteSize()).isEqualTo(2433L);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("586446abbbdc6d8466912941ac2609d7bdac072af5c3422bf575a8715b1bf810");

    assertThat(pack.summary().rows()).isEqualTo(38);
    assertThat(pack.summary().uniqueValues()).isEqualTo(30);
    assertThat(pack.summary().genitiveRows()).isEqualTo(20);
    assertThat(pack.summary().dependencyRows()).isEqualTo(20);
    assertThat(pack.summary().invariantNumberRows()).isEqualTo(14);
    assertThat(pack.summary().binaryLowerBoundBytes().stringPoolBytes()).isEqualTo(426);
    assertThat(pack.summary().binaryLowerBoundBytes().rowBytes()).isEqualTo(304);
    assertThat(pack.summary().binaryLowerBoundBytes().totalBytes()).isEqualTo(730);
    assertThat(pack.rows()).hasSize(38);
    assertThat(pack.rows().get(0).value()).isEqualTo("मैं");
    assertThat(pack.rows().get(6).dependencyGender()).isEqualTo("masculine");

    assertThat(pack.renderPronoun(new Request("first", "singular", "direct", null, null, null)))
        .isEqualTo("मैं");
    assertThat(
            pack.renderPronoun(
                new Request("first", "plural", "genitive", null, "masculine", "plural")))
        .isEqualTo("हमारे");
    assertThat(
            pack.renderPronoun(
                new Request("first", "singular", "genitive", null, "feminine", "plural")))
        .isEqualTo("मेरी");
    assertThat(
            pack.renderPronoun(new Request("second", "singular", "direct", "intimate", null, null)))
        .isEqualTo("तू");
    assertThat(
            pack.renderPronoun(new Request("second", "plural", "direct", "intimate", null, null)))
        .isEqualTo("तुम");
    assertThat(
            pack.renderPronoun(new Request("second", "singular", "direct", "informal", null, null)))
        .isEqualTo("तुम");
    assertThat(
            pack.renderPronoun(
                new Request("second", "plural", "genitive", "formal", "masculine", "plural")))
        .isEqualTo("आपके");
  }

  @Test
  public void loadedPackCollectionsAreImmutable() {
    HindiPronounAgreementPack pack = loader.load(hindiPronounAgreementPackJson());

    assertThatThrownBy(() -> pack.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.provenance().sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsUnexpectedSchema() {
    String json =
        hindiPronounAgreementPackJson()
            .replace(
                HindiPronounAgreementPackJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/hi-pronoun-agreement-pack/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected schema");
  }

  @Test
  public void rejectsUnexpectedPackShape() {
    String json =
        hindiPronounAgreementPackJson()
            .replace(
                HindiPronounAgreementPackJsonLoader.EXPECTED_PACK_SHAPE, "generic-term-rows-v0");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected packShape");
  }

  @Test
  public void rejectsUnsupportedNumberValue() {
    String json =
        hindiPronounAgreementPackJson().replaceFirst("\"number\": \"any\"", "\"number\": \"dual\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Hindi pronoun number value");
  }

  @Test
  public void rejectsNonIntegralLineNumber() {
    String json = hindiPronounAgreementPackJson().replaceFirst("\"line\": 1", "\"line\": 1.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectsNonIntegralSummaryCount() {
    String json = hindiPronounAgreementPackJson().replace("\"rows\": 38", "\"rows\": 38.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: rows");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json =
        hindiPronounAgreementPackJson().replace("\"byteSize\": 2433", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsMalformedSourceSha256() {
    String json =
        hindiPronounAgreementPackJson()
            .replace(
                "\"sha256\": \"586446abbbdc6d8466912941ac2609d7bdac072af5c3422bf575a8715b1bf810\"",
                "\"sha256\": \"abc123\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256 must be a 64-character lowercase SHA-256 hex digest");
  }

  @Test
  public void rejectsIncompleteGenitiveDependency() {
    String json =
        hindiPronounAgreementPackJson()
            .replaceFirst("\"dependencyGender\": \"masculine\"", "\"dependencyGender\": null");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi genitive pronoun row requires dependency");
  }

  @Test
  public void rejectsIncoherentRowEstimate() {
    String json = hindiPronounAgreementPackJson().replace("\"rowBytes\": 304", "\"rowBytes\": 303");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi pronoun row byte estimate is incoherent");
  }

  @Test
  public void rejectsAmbiguousSelectorAfterWildcardExpansion() {
    String json =
        hindiPronounAgreementPackJson()
            .replace("\"invariantNumberRows\": 14", "\"invariantNumberRows\": 15")
            .replaceFirst("\"number\": \"singular\"", "\"number\": \"any\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Ambiguous Hindi pronoun selector");
  }

  @Test
  public void failsMissingRegisterOrDependencyRequest() {
    HindiPronounAgreementPack pack = loader.load(hindiPronounAgreementPackJson());

    assertThatThrownBy(
            () -> pack.renderPronoun(new Request("second", "singular", "direct", null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing Hindi pronoun form");
    assertThatThrownBy(() -> new Request("first", "singular", "genitive", null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi genitive pronoun request requires dependency");
  }

  private String hindiPronounAgreementPackJson() {
    return readResource(
        "com/box/l10n/mojito/mf2/inflection/hi_pronoun_agreement_pack_fixture.json");
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
