package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.SpanishNounPackReportJsonLoader.SpanishNounPackReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SpanishNounPackReportJsonLoaderTest {

  private final SpanishNounPackReportJsonLoader loader = new SpanishNounPackReportJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new SpanishNounPackReportJsonLoader(null))
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
    SpanishNounPackReport report = loader.load(spanishNounPackReportJson());

    assertThat(report.schema()).isEqualTo(SpanishNounPackReportJsonLoader.EXPECTED_SCHEMA);
    assertThat(report.locale()).isEqualTo("es");
    assertThat(report.sources()).hasSize(2);
    assertThat(report.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(report.provenance().sourceLabels()).hasSize(2);
    assertThat(report.counts().dictionaryEntries()).isEqualTo(556656);
    assertThat(report.counts().supportedEntries()).isEqualTo(69315);
    assertThat(report.counts().uniqueSupportedSurfaces()).isEqualTo(69243);
    assertThat(report.counts().ambiguousSupportedSurfaces()).isEqualTo(17053);
    assertThat(report.counts().genderNumberCandidateSurfaces()).isEqualTo(63698);
    assertThat(report.counts().missingInflectionPatterns()).isZero();
    assertThat(report.features().gender()).containsEntry("feminine", 37518);
    assertThat(report.features().gender()).containsEntry("masculine", 34829);
    assertThat(report.features().ambiguityReasons()).containsEntry("multiple-inflections", 15023);
    assertThat(report.features().patternPartOfSpeech()).containsEntry("noun", 521);
    assertThat(report.features().patternSlotGenders()).containsEntry("neuter", 3);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().stringPoolBytes())
        .isEqualTo(705215);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().rowBytes()).isEqualTo(509584);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().binaryLowerBoundBytes())
        .isEqualTo(1214799);
    assertThat(report.articleStrategy().articleForms()).hasSize(10);
    assertThat(report.articleStrategy().counts().articleCandidateSurfaces()).isEqualTo(63698);
    assertThat(report.articleStrategy().counts().stressedFeminineSingularOverrides()).isEqualTo(38);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().phraseRows())
        .isEqualTo(127396);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().stringPoolBytes())
        .isEqualTo(1902796);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().phraseRowBytes())
        .isEqualTo(1528752);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().binaryLowerBoundBytes())
        .isEqualTo(3431548);
    assertThat(report.reviewPolicy().compactRuntime()).isEqualTo("article-shell-composition");
    assertThat(report.reviewPolicy().automaticExportSurfaces()).isEqualTo(52190);
    assertThat(report.reviewPolicy().reviewRequiredSurfaces()).isEqualTo(11508);
    assertThat(report.reviewPolicy().blockedSurfaces()).isEqualTo(5545);
    assertThat(report.reviewPolicy().reviewRequiredReasons())
        .containsEntry("multiple-inflections", 11442)
        .containsEntry("multiple-parts-of-speech", 11251);
    assertThat(report.reviewPolicy().blockedReasons())
        .containsEntry("missing-gender", 228)
        .containsEntry("multiple-genders", 3276);
    assertThat(report.articleStrategy().samples().stressedFeminineSingularOverrides())
        .extracting("surface")
        .containsExactly("abra", "acta", "afta", "agua", "ala", "alba", "alca", "alga");
    assertThat(
            report
                .articleStrategy()
                .samples()
                .stressedFeminineSingularOverrides()
                .get(3)
                .phraseForms())
        .containsEntry("definite", "el agua")
        .containsEntry("indefinite", "un agua");
    assertThat(report.samples().genderNumberCandidates())
        .extracting("surface")
        .containsExactly(
            "a", "ababol", "ababoles", "abacería", "abacerías", "abacora", "abad", "abada");
    assertThat(report.samples().blockingAmbiguities())
        .extracting("surface")
        .containsExactly(
            "abacora",
            "abacoras",
            "abanderada",
            "abanderadas",
            "abanderado",
            "abanderados",
            "abandonista",
            "abandonistas");
    assertThat(report.samples().entries()).extracting("surface").contains("ABS", "Abiyán");
    assertThat(report.samples().missingInflectionPatterns()).isEmpty();
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        spanishNounPackReportJson()
            .replace(
                SpanishNounPackReportJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/es-noun-pack-report/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected Spanish noun-pack report schema");
  }

  @Test
  public void rejectUnsupportedFeatureKey() {
    String json = spanishNounPackReportJson().replace("\"masculine\": 34829", "\"common\": 34829");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Spanish gender key");
  }

  @Test
  public void rejectNonIntegralCount() {
    String json =
        spanishNounPackReportJson()
            .replace("\"dictionaryEntries\": 556656", "\"dictionaryEntries\": 556656.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectNonIntegralFeatureCount() {
    String json =
        spanishNounPackReportJson().replace("\"feminine\": 37518", "\"feminine\": 37518.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: gender.feminine");
  }

  @Test
  public void rejectNonIntegralSampleLine() {
    String json = spanishNounPackReportJson().replace("\"line\": 3", "\"line\": 3.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectNonIntegralSourceByteSize() {
    String json =
        spanishNounPackReportJson().replace("\"byteSize\": 37232751", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectIncoherentRowEstimate() {
    String json = spanishNounPackReportJson().replace("\"rowBytes\": 509584", "\"rowBytes\": 8");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Spanish metadata row byte estimate does not match rows");
  }

  @Test
  public void rejectIncoherentBinaryEstimate() {
    String json =
        spanishNounPackReportJson()
            .replace("\"binaryLowerBoundBytes\": 1214799", "\"binaryLowerBoundBytes\": 1214800");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Spanish metadata binary estimate does not match parts");
  }

  @Test
  public void rejectIncoherentArticleCandidateCount() {
    String json =
        spanishNounPackReportJson()
            .replace("\"articleCandidateSurfaces\": 63698", "\"articleCandidateSurfaces\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Spanish article candidate count must match gender/number candidates");
  }

  @Test
  public void rejectIncoherentArticlePhraseEstimate() {
    String json =
        spanishNounPackReportJson()
            .replace("\"phraseRowBytes\": 1528752", "\"phraseRowBytes\": 12");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Spanish article phrase-row byte estimate is incoherent");
  }

  @Test
  public void rejectUnsupportedArticlePhraseKey() {
    String json =
        spanishNounPackReportJson().replace("\"definite\": \"la a\"", "\"partitive\": \"la a\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Spanish phraseForms key");
  }

  @Test
  public void rejectUnsupportedCompactRuntime() {
    String json =
        spanishNounPackReportJson()
            .replace(
                "\"compactRuntime\": \"article-shell-composition\"",
                "\"compactRuntime\": \"eager-phrase-pack\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Spanish compact runtime");
  }

  @Test
  public void rejectIncoherentReviewPolicyCounts() {
    String json =
        spanishNounPackReportJson()
            .replace("\"automaticExportSurfaces\": 52190", "\"automaticExportSurfaces\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Spanish automatic export count must match exact gender/number surfaces");
  }

  @Test
  public void rejectUnsupportedReviewReasonKey() {
    String json =
        spanishNounPackReportJson()
            .replace(
                "\"reviewRequiredReasons\": {\n      \"multiple-analyses\": 53",
                "\"reviewRequiredReasons\": {\n      \"other-reason\": 53");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Spanish reviewRequiredReasons key");
  }

  @Test
  public void rejectProvenanceSourceMismatch() {
    String json =
        spanishNounPackReportJson()
            .replaceFirst(
                "\"path\": \"dictionary_es\\.lst\"", "\"path\": \"dictionary_mismatch.lst\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Spanish report provenance sources must match sources");
  }

  private String spanishNounPackReportJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/es_noun_pack_report_fixture.json");
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
