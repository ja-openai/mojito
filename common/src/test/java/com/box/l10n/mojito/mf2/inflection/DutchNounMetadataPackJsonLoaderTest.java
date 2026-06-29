package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.DutchNounMetadataPackJsonLoader.DutchNounMetadataPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DutchNounMetadataPackJsonLoaderTest {

  private final DutchNounMetadataPackJsonLoader loader = new DutchNounMetadataPackJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new DutchNounMetadataPackJsonLoader(null))
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
    DutchNounMetadataPack pack = loader.load(dutchMetadataPackJson());

    assertThat(pack.schema()).isEqualTo(DutchNounMetadataPackJsonLoader.EXPECTED_SCHEMA);
    assertThat(pack.locale()).isEqualTo("nl");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_nl.lst");
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("9ccee2f7e50685c4239432b8d59ea5ba362f5bd5e3f529993a2ea7db5e2fa8c7");
    assertThat(pack.generationSummary().dictionaryEntries()).isEqualTo(13093);
    assertThat(pack.generationSummary().termRows()).isEqualTo(6317);
    assertThat(pack.generationSummary().metadataCandidateRows()).isEqualTo(5312);
    assertThat(pack.generationSummary().metadataCandidateSurfaces()).isEqualTo(5308);
    assertThat(pack.generationSummary().diminutiveRows()).isEqualTo(2782);
    assertThat(pack.generationSummary().metadataDiminutiveRows()).isEqualTo(2412);
    assertThat(pack.generationSummary().exportedRows()).isEqualTo(20);
    assertThat(pack.generationSummary().exportedDiminutiveRows()).isEqualTo(10);
    assertThat(pack.generationSummary().reviewDiagnosticRows()).isZero();
    assertThat(pack.generationSummary().caseTaggedTermRows()).isEqualTo(80);
    assertThat(pack.generationSummary().definitenessTaggedTermRows()).isEqualTo(12);
    assertThat(pack.features().gender())
        .containsEntry("masculine", 1962)
        .containsEntry("feminine", 1141)
        .containsEntry("neuter", 2550);
    assertThat(pack.features().number())
        .containsEntry("singular", 3012)
        .containsEntry("plural", 2537);
    assertThat(pack.features().diminutive())
        .containsEntry("true", 2412)
        .containsEntry("false", 2900);
    assertThat(pack.sizeEstimates().sampleMetadataPack().binaryLowerBoundBytes()).isEqualTo(386);
    assertThat(pack.sizeEstimates().sampleMetadataPack().jsonBytes()).isEqualTo(8797);
    assertThat(pack.sizeEstimates().fullMetadataPack().binaryLowerBoundBytes()).isEqualTo(114496);

    assertThat(pack.find("boek").orElseThrow().diminutive()).isFalse();
    assertThat(pack.find("boekje").orElseThrow().diminutive()).isTrue();
    assertThat(pack.find("man").orElseThrow().gender()).containsExactly("masculine");
    assertThat(pack.find("mannetje").orElseThrow().gender()).containsExactly("neuter");
    assertThat(pack.find("vrouwtjes").orElseThrow().gender()).containsExactly("feminine");
    assertThat(pack.find("vrouwtjes").orElseThrow().number()).containsExactly("plural");
    assertThat(pack.find("missing")).isEmpty();
  }

  @Test
  public void loadedCollectionsAreImmutable() {
    DutchNounMetadataPack pack = loader.load(dutchMetadataPackJson());

    assertThatThrownBy(() -> pack.strings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.rowsBySurface().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.features().diminutive().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.find("boekje").orElseThrow().number().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        dutchMetadataPackJson()
            .replace(
                DutchNounMetadataPackJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/nl-noun-metadata-pack/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected Dutch metadata schema");
  }

  @Test
  public void rejectUnsupportedGender() {
    String json = dutchMetadataPackJson().replaceFirst("\"neuter\"", "\"common\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Dutch gender");
  }

  @Test
  public void rejectNonIntegralRowIntField() {
    String json =
        dutchMetadataPackJson().replaceFirst("\"sourceRow\": 1495", "\"sourceRow\": 1495.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: sourceRow");
  }

  @Test
  public void rejectNonIntegralSourceByteSize() {
    String json = dutchMetadataPackJson().replace("\"byteSize\": 780817", "\"byteSize\": 780817.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectNonIntegralFeatureCount() {
    String json = dutchMetadataPackJson().replace("\"neuter\": 2550", "\"neuter\": 2550.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-negative Dutch count: gender");
  }

  @Test
  public void rejectFeatureBitsThatDoNotMatchMetadata() {
    String json =
        dutchMetadataPackJson().replaceFirst("\"featureBits\": 321", "\"featureBits\": 322");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Feature bits do not match Dutch metadata");
  }

  @Test
  public void rejectSummaryMismatch() {
    String json = dutchMetadataPackJson().replace("\"exportedRows\": 20", "\"exportedRows\": 21");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dutch exported row count does not match rows");
  }

  @Test
  public void rejectProvenanceSourceMismatch() {
    String json =
        dutchMetadataPackJson()
            .replaceFirst(
                "inflection/resources/org/unicode/inflection/dictionary/dictionary_nl.lst",
                "inflection/resources/org/unicode/inflection/dictionary/other_nl.lst");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dutch source labels must match source paths");
  }

  @Test
  public void rejectIncoherentFullEstimate() {
    String json = dutchMetadataPackJson().replace("\"rowBytes\": 63744", "\"rowBytes\": 12");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Dutch full row byte estimate does not match candidates");
  }

  private String dutchMetadataPackJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/nl_noun_metadata_pack_fixture.json");
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
