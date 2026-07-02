package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.PortugueseNounPackReportJsonLoader.PortugueseNounPackReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class PortugueseNounPackReportJsonLoaderTest {

  private final PortugueseNounPackReportJsonLoader loader =
      new PortugueseNounPackReportJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new PortugueseNounPackReportJsonLoader(null))
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
    PortugueseNounPackReport report = loader.load(portugueseNounPackReportJson());

    assertThat(report.schema()).isEqualTo(PortugueseNounPackReportJsonLoader.EXPECTED_SCHEMA);
    assertThat(report.locale()).isEqualTo("pt");
    assertThat(report.sources()).hasSize(2);
    assertThat(report.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(report.provenance().sourceLabels()).hasSize(2);
    assertThat(report.sources().get(0).sha256())
        .isEqualTo("00a2c13c4b5c437cc8677e7115a7e8ad3ec62dcba2115698fb283541cdabee70");
    assertThat(report.sources().get(1).sha256())
        .isEqualTo("b223e087bd3ea5470fabebb6277e500561d24a7c7fbd5f867a1d115d2f3d3aef");
    assertThat(report.counts().dictionaryEntries()).isEqualTo(35188);
    assertThat(report.counts().supportedEntries()).isEqualTo(6628);
    assertThat(report.counts().uniqueSupportedSurfaces()).isEqualTo(6614);
    assertThat(report.counts().ambiguousSupportedSurfaces()).isEqualTo(498);
    assertThat(report.counts().genderNumberCandidateSurfaces()).isEqualTo(6424);
    assertThat(report.counts().missingInflectionPatterns()).isZero();
    assertThat(report.features().gender()).containsEntry("feminine", 3239);
    assertThat(report.features().gender()).containsEntry("masculine", 3442);
    assertThat(report.features().ambiguityReasons()).containsEntry("multiple-inflections", 353);
    assertThat(report.features().patternPartOfSpeech()).containsEntry("noun", 208);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().stringPoolBytes())
        .isEqualTo(60293);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().rowBytes()).isEqualTo(51392);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().binaryLowerBoundBytes())
        .isEqualTo(111685);
    assertThat(report.agreementStrategy().agreementForms()).hasSize(64);
    assertThat(report.agreementStrategy().counts().agreementCandidateSurfaces()).isEqualTo(6424);
    assertThat(report.agreementStrategy().counts().agreementFormCategories()).isEqualTo(16);
    assertThat(report.agreementStrategy().sizeEstimates().eagerPhrasePack().phraseRows())
        .isEqualTo(102784);
    assertThat(report.agreementStrategy().sizeEstimates().eagerPhrasePack().stringPoolBytes())
        .isEqualTo(1542208);
    assertThat(report.agreementStrategy().sizeEstimates().eagerPhrasePack().phraseRowBytes())
        .isEqualTo(1233408);
    assertThat(report.agreementStrategy().sizeEstimates().eagerPhrasePack().binaryLowerBoundBytes())
        .isEqualTo(2775616);
    assertThat(report.agreementStrategy().samples().targetedAgreementCandidates())
        .extracting("surface")
        .containsExactly("campo", "casa", "campos", "casas", "primo", "prima");
    assertThat(report.reviewPolicy().compactRuntime()).isEqualTo("agreement-shell-composition");
    assertThat(report.reviewPolicy().automaticExportSurfaces()).isEqualTo(6116);
    assertThat(report.reviewPolicy().reviewRequiredSurfaces()).isEqualTo(308);
    assertThat(report.reviewPolicy().blockedSurfaces()).isEqualTo(190);
    assertThat(report.reviewPolicy().reviewRequiredReasons())
        .containsEntry("multiple-inflections", 273)
        .containsEntry("multiple-parts-of-speech", 244);
    assertThat(report.reviewPolicy().blockedReasons())
        .containsEntry("missing-gender", 31)
        .containsEntry("multiple-genders", 86);
    assertThat(
            report.agreementStrategy().samples().targetedAgreementCandidates().get(0).phraseForms())
        .containsEntry("definiteArticle", "o campo")
        .containsEntry("indefiniteArticle", "um campo")
        .containsEntry("deDefiniteArticle", "do campo")
        .containsEntry("emDefiniteArticle", "no campo")
        .containsEntry("porDefiniteArticle", "pelo campo")
        .containsEntry("possessiveArticle", "seu campo");
    assertThat(
            report.agreementStrategy().samples().targetedAgreementCandidates().get(1).phraseForms())
        .containsEntry("definiteArticle", "a casa")
        .containsEntry("indefiniteArticle", "uma casa")
        .containsEntry("deDefiniteArticle", "da casa")
        .containsEntry("emDefiniteArticle", "na casa")
        .containsEntry("porDefiniteArticle", "pela casa")
        .containsEntry("possessiveArticle", "sua casa");
    assertThat(
            report.agreementStrategy().samples().targetedAgreementCandidates().get(2).phraseForms())
        .containsEntry("definiteArticle", "os campos")
        .containsEntry("deDefiniteArticle", "dos campos");
    assertThat(
            report.agreementStrategy().samples().targetedAgreementCandidates().get(3).phraseForms())
        .containsEntry("definiteArticle", "as casas")
        .containsEntry("emDefiniteArticle", "nas casas");
  }

  @Test
  public void loadedReportCollectionsAreImmutable() {
    PortugueseNounPackReport report = loader.load(portugueseNounPackReportJson());

    assertThatThrownBy(() -> report.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.features().ambiguityReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.agreementStrategy().agreementForms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                report
                    .agreementStrategy()
                    .samples()
                    .targetedAgreementCandidates()
                    .get(0)
                    .phraseForms()
                    .clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.reviewPolicy().blockedReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        portugueseNounPackReportJson()
            .replace(
                PortugueseNounPackReportJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/pt-noun-pack-report/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected Portuguese noun-pack report schema");
  }

  @Test
  public void rejectUnsupportedAgreementCategory() {
    String json =
        portugueseNounPackReportJson()
            .replaceFirst("\"category\": \"deDefiniteArticle\"", "\"category\": \"other\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Portuguese category value");
  }

  @Test
  public void rejectNonIntegralCount() {
    String json =
        portugueseNounPackReportJson()
            .replace("\"dictionaryEntries\": 35188", "\"dictionaryEntries\": 35188.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectNonIntegralFeatureCount() {
    String json =
        portugueseNounPackReportJson().replace("\"feminine\": 3239", "\"feminine\": 3239.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: gender.feminine");
  }

  @Test
  public void rejectNonIntegralSampleLine() {
    String json = portugueseNounPackReportJson().replace("\"line\": 1", "\"line\": 1.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectNonIntegralSourceByteSize() {
    String json =
        portugueseNounPackReportJson().replace("\"byteSize\": 2345650", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectIncoherentRowEstimate() {
    String json = portugueseNounPackReportJson().replace("\"rowBytes\": 51392", "\"rowBytes\": 8");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Portuguese metadata row byte estimate does not match rows");
  }

  @Test
  public void rejectIncoherentAgreementCandidateCount() {
    String json =
        portugueseNounPackReportJson()
            .replace("\"agreementCandidateSurfaces\": 6424", "\"agreementCandidateSurfaces\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Portuguese agreement candidate count must match gender/number candidates");
  }

  @Test
  public void rejectIncoherentAgreementPhraseEstimate() {
    String json =
        portugueseNounPackReportJson()
            .replace("\"phraseRowBytes\": 1233408", "\"phraseRowBytes\": 12");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Portuguese agreement phrase-row byte estimate is incoherent");
  }

  @Test
  public void rejectUnsupportedAgreementPhraseKey() {
    String json =
        portugueseNounPackReportJson()
            .replace("\"definiteArticle\": \"o abacaxi\"", "\"partitiveArticle\": \"o abacaxi\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Portuguese phraseForms key");
  }

  @Test
  public void rejectUnsupportedReviewReasonKey() {
    String json =
        portugueseNounPackReportJson()
            .replace(
                "\"reviewRequiredReasons\": {\n      \"multiple-analyses\": 1",
                "\"reviewRequiredReasons\": {\n      \"other-reason\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Portuguese reviewRequiredReasons key");
  }

  @Test
  public void rejectUnsupportedCompactRuntime() {
    String json =
        portugueseNounPackReportJson()
            .replace(
                "\"compactRuntime\": \"agreement-shell-composition\"",
                "\"compactRuntime\": \"eager-phrase-pack\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Portuguese compact runtime");
  }

  @Test
  public void rejectIncoherentReviewPolicyCounts() {
    String json =
        portugueseNounPackReportJson()
            .replace("\"automaticExportSurfaces\": 6116", "\"automaticExportSurfaces\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Portuguese automatic export count must match exact gender/number surfaces");
  }

  @Test
  public void rejectProvenanceSourceMismatch() {
    String json =
        portugueseNounPackReportJson()
            .replaceFirst(
                "\"path\": \"dictionary_pt\\.lst\"", "\"path\": \"dictionary_mismatch.lst\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Portuguese report provenance sources must match sources");
  }

  private String portugueseNounPackReportJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/pt_noun_pack_report_fixture.json");
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
