package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.NorwegianBokmalNounMetadataPackJsonLoader.NorwegianBokmalNounMetadataPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class NorwegianBokmalNounMetadataPackJsonLoaderTest {

  private final NorwegianBokmalNounMetadataPackJsonLoader loader =
      new NorwegianBokmalNounMetadataPackJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new NorwegianBokmalNounMetadataPackJsonLoader(null))
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
  public void loadGeneratedFixture() {
    NorwegianBokmalNounMetadataPack pack = loader.load(norwegianBokmalMetadataPackJson());

    assertThat(pack.schema()).isEqualTo(NorwegianBokmalNounMetadataPackJsonLoader.EXPECTED_SCHEMA);
    assertThat(pack.locale()).isEqualTo("nb");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_nb.lst");
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("a65316f4458ace28fe0959047590bae0e130bf8eaf973ed492bb3d955aff4d5b");
    assertThat(pack.generationSummary().dictionaryEntries()).isEqualTo(162215);
    assertThat(pack.generationSummary().metadataCandidateRows()).isEqualTo(114535);
    assertThat(pack.generationSummary().metadataCandidateSurfaces()).isEqualTo(114089);
    assertThat(pack.generationSummary().exportedRows()).isEqualTo(12);
    assertThat(pack.generationSummary().reviewDiagnosticRows()).isEqualTo(2);
    assertThat(pack.generationSummary().caseTaggedNounRows()).isEqualTo(1);
    assertThat(pack.generationSummary().multiGenderRows()).isEqualTo(4024);
    assertThat(pack.generationSummary().multiNumberRows()).isEqualTo(7728);
    assertThat(pack.generationSummary().multiDefinitenessRows()).isEqualTo(1841);
    assertThat(pack.features().gender())
        .containsEntry("masculine", 66763)
        .containsEntry("feminine", 23701)
        .containsEntry("neuter", 30160);
    assertThat(pack.features().number())
        .containsEntry("singular", 59616)
        .containsEntry("plural", 62647);
    assertThat(pack.features().definiteness())
        .containsEntry("indefinite", 50792)
        .containsEntry("definite", 65584);
    assertThat(pack.sizeEstimates().sampleMetadataPack().binaryLowerBoundBytes()).isEqualTo(224);
    assertThat(pack.sizeEstimates().sampleMetadataPack().jsonBytes()).isEqualTo(6404);
    assertThat(pack.sizeEstimates().fullMetadataPack().binaryLowerBoundBytes()).isEqualTo(2696106);

    assertThat(pack.find("hund").orElseThrow().gender()).containsExactly("masculine");
    assertThat(pack.find("jente").orElseThrow().gender()).containsExactly("feminine");
    assertThat(pack.find("barn").orElseThrow().gender()).containsExactly("neuter");
    assertThat(pack.find("barn").orElseThrow().number()).containsExactly("singular", "plural");
    assertThat(pack.find("barn").orElseThrow().reviewDiagnostics())
        .containsExactly("multiple-numbers");
    assertThat(pack.find("bøkene").orElseThrow().gender()).containsExactly("masculine", "feminine");
    assertThat(pack.find("bøkene").orElseThrow().reviewDiagnostics())
        .containsExactly("multiple-genders", "multiple-inflections");
    assertThat(pack.find("missing")).isEmpty();
  }

  @Test
  public void loadedCollectionsAreImmutable() {
    NorwegianBokmalNounMetadataPack pack = loader.load(norwegianBokmalMetadataPackJson());

    assertThatThrownBy(() -> pack.strings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.rowsBySurface().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.features().gender().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.find("barn").orElseThrow().number().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        norwegianBokmalMetadataPackJson()
            .replace(
                NorwegianBokmalNounMetadataPackJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/nb-noun-metadata-pack/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected Norwegian Bokmal metadata schema");
  }

  @Test
  public void rejectUnsupportedGender() {
    String json = norwegianBokmalMetadataPackJson().replaceFirst("\"masculine\"", "\"common\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Norwegian Bokmal gender");
  }

  @Test
  public void rejectNonIntegralRowIntField() {
    String json =
        norwegianBokmalMetadataPackJson()
            .replaceFirst("\"sourceRow\": 82799", "\"sourceRow\": 82799.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: sourceRow");
  }

  @Test
  public void rejectNonIntegralSourceByteSize() {
    String json =
        norwegianBokmalMetadataPackJson()
            .replace("\"byteSize\": 9470180", "\"byteSize\": 9470180.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectNonIntegralFeatureCount() {
    String json =
        norwegianBokmalMetadataPackJson().replace("\"masculine\": 66763", "\"masculine\": 66763.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-negative Norwegian Bokmal count: gender");
  }

  @Test
  public void rejectFeatureBitsThatDoNotMatchMetadata() {
    String json =
        norwegianBokmalMetadataPackJson()
            .replaceFirst("\"featureBits\": 4369", "\"featureBits\": 4370");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Feature bits do not match Norwegian Bokmal metadata");
  }

  @Test
  public void rejectSummaryMismatch() {
    String json =
        norwegianBokmalMetadataPackJson().replace("\"exportedRows\": 12", "\"exportedRows\": 13");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Norwegian Bokmal exported row count does not match rows");
  }

  @Test
  public void rejectProvenanceSourceMismatch() {
    String json =
        norwegianBokmalMetadataPackJson()
            .replaceFirst(
                "inflection/resources/org/unicode/inflection/dictionary/dictionary_nb.lst",
                "inflection/resources/org/unicode/inflection/dictionary/other_nb.lst");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Norwegian Bokmal source labels must match source paths");
  }

  @Test
  public void rejectIncoherentFullEstimate() {
    String json =
        norwegianBokmalMetadataPackJson().replace("\"rowBytes\": 1374420", "\"rowBytes\": 12");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Norwegian Bokmal full row byte estimate does not match candidates");
  }

  private String norwegianBokmalMetadataPackJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/nb_noun_metadata_pack_fixture.json");
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
