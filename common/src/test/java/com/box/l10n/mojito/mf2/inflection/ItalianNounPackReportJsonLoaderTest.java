package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.ItalianNounPackReportJsonLoader.ItalianNounPackReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ItalianNounPackReportJsonLoaderTest {

  private final ItalianNounPackReportJsonLoader loader = new ItalianNounPackReportJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new ItalianNounPackReportJsonLoader(null))
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
    ItalianNounPackReport report = loader.load(italianNounPackReportJson());

    assertThat(report.schema()).isEqualTo(ItalianNounPackReportJsonLoader.EXPECTED_SCHEMA);
    assertThat(report.locale()).isEqualTo("it");
    assertThat(report.sources()).hasSize(2);
    assertThat(report.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(report.provenance().sourceLabels()).hasSize(2);
    assertThat(report.sources().get(0).sha256())
        .isEqualTo("c5836997d872f7e5833fe0c338109591235b91f0306fa3e317f1e7aabbfe42a9");
    assertThat(report.sources().get(1).sha256())
        .isEqualTo("70ad7ba37f88b96607ba367549cc2260453ff774eebb3533d728da0b7c072653");
    assertThat(report.counts().dictionaryEntries()).isEqualTo(412265);
    assertThat(report.counts().supportedEntries()).isEqualTo(63567);
    assertThat(report.counts().uniqueSupportedSurfaces()).isEqualTo(63529);
    assertThat(report.counts().ambiguousSupportedSurfaces()).isEqualTo(17706);
    assertThat(report.counts().genderNumberCandidateSurfaces()).isEqualTo(52542);
    assertThat(report.counts().missingInflectionPatterns()).isZero();
    assertThat(report.features().gender()).containsEntry("feminine", 31698);
    assertThat(report.features().gender()).containsEntry("masculine", 32475);
    assertThat(report.features().ambiguityReasons()).containsEntry("multiple-inflections", 9444);
    assertThat(report.features().patternPartOfSpeech()).containsEntry("noun", 384);
    assertThat(report.features().patternSlotGenders()).containsEntry("neuter", 1);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().stringPoolBytes())
        .isEqualTo(569672);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().rowBytes()).isEqualTo(420336);
    assertThat(report.sizeEstimates().genderNumberMetadataPack().binaryLowerBoundBytes())
        .isEqualTo(990008);
    assertThat(report.articleStrategy().articleForms()).hasSize(20);
    assertThat(report.articleStrategy().counts().articleCandidateSurfaces()).isEqualTo(52542);
    assertThat(report.articleStrategy().counts().dictionaryVowelStartSurfaces()).isEqualTo(7);
    assertThat(report.articleStrategy().counts().masculineLoClassSurfaces()).isEqualTo(1911);
    assertThat(report.articleStrategy().counts().surfaceVowelStartSurfaces()).isEqualTo(11709);
    assertThat(report.articleStrategy().articleClassCounts())
        .containsEntry("standard", 38922)
        .containsEntry("lo", 1911)
        .containsEntry("elision", 11709);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().phraseRows())
        .isEqualTo(105084);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().stringPoolBytes())
        .isEqualTo(1513935);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().phraseRowBytes())
        .isEqualTo(1261008);
    assertThat(report.articleStrategy().sizeEstimates().eagerPhrasePack().binaryLowerBoundBytes())
        .isEqualTo(2774943);
    assertThat(report.reviewPolicy().compactRuntime()).isEqualTo("article-shell-composition");
    assertThat(report.reviewPolicy().automaticExportSurfaces()).isEqualTo(45823);
    assertThat(report.reviewPolicy().reviewRequiredSurfaces()).isEqualTo(6719);
    assertThat(report.reviewPolicy().blockedSurfaces()).isEqualTo(10987);
    assertThat(report.reviewPolicy().reviewRequiredReasons())
        .containsEntry("multiple-inflections", 6671)
        .containsEntry("multiple-parts-of-speech", 6576);
    assertThat(report.reviewPolicy().blockedReasons())
        .containsEntry("missing-gender", 736)
        .containsEntry("multiple-numbers", 8227);
    assertThat(report.articleStrategy().samples().targetedArticleCandidates())
        .extracting("surface")
        .containsExactly("gnomo", "gnomi", "libro", "cani", "acqua", "ape");
    assertThat(report.articleStrategy().samples().targetedArticleCandidates().get(0).phraseForms())
        .containsEntry("definite", "lo gnomo")
        .containsEntry("indefinite", "uno gnomo");
    assertThat(report.articleStrategy().samples().targetedArticleCandidates().get(1).phraseForms())
        .containsEntry("definite", "gli gnomi")
        .containsEntry("indefinite", "degli gnomi");
    assertThat(report.articleStrategy().samples().targetedArticleCandidates().get(2).phraseForms())
        .containsEntry("definite", "il libro")
        .containsEntry("indefinite", "un libro");
    assertThat(report.articleStrategy().samples().targetedArticleCandidates().get(3).phraseForms())
        .containsEntry("definite", "i cani")
        .containsEntry("indefinite", "dei cani");
    assertThat(report.articleStrategy().samples().targetedArticleCandidates().get(4).phraseForms())
        .containsEntry("definite", "l'acqua")
        .containsEntry("indefinite", "un'acqua");
    assertThat(report.articleStrategy().samples().targetedArticleCandidates().get(5).phraseForms())
        .containsEntry("definite", "l'ape")
        .containsEntry("indefinite", "un'ape");
  }

  @Test
  public void loadedReportCollectionsAreImmutable() {
    ItalianNounPackReport report = loader.load(italianNounPackReportJson());

    assertThatThrownBy(() -> report.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.features().ambiguityReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.articleStrategy().articleForms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.articleStrategy().articleClassCounts().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.reviewPolicy().reviewRequiredReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                report
                    .articleStrategy()
                    .samples()
                    .targetedArticleCandidates()
                    .get(0)
                    .phraseForms()
                    .clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        italianNounPackReportJson()
            .replace(
                ItalianNounPackReportJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/it-noun-pack-report/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected Italian noun-pack report schema");
  }

  @Test
  public void rejectUnsupportedArticleClass() {
    String json =
        italianNounPackReportJson()
            .replaceFirst("\"articleClass\": \"standard\"", "\"articleClass\": \"other\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Italian articleClass value");
  }

  @Test
  public void rejectNonIntegralCount() {
    String json =
        italianNounPackReportJson()
            .replace("\"dictionaryEntries\": 412265", "\"dictionaryEntries\": 412265.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectNonIntegralFeatureCount() {
    String json =
        italianNounPackReportJson().replace("\"feminine\": 31698", "\"feminine\": 31698.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: gender.feminine");
  }

  @Test
  public void rejectNonIntegralSampleLine() {
    String json = italianNounPackReportJson().replace("\"line\": 1", "\"line\": 1.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectNonIntegralSourceByteSize() {
    String json =
        italianNounPackReportJson().replace("\"byteSize\": 27327567", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectIncoherentRowEstimate() {
    String json = italianNounPackReportJson().replace("\"rowBytes\": 420336", "\"rowBytes\": 8");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Italian metadata row byte estimate does not match rows");
  }

  @Test
  public void rejectIncoherentArticleClassCounts() {
    String json = italianNounPackReportJson().replace("\"standard\": 38922", "\"standard\": 38923");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Italian article-class counts must sum to candidates");
  }

  @Test
  public void rejectUnsupportedArticlePhraseKey() {
    String json =
        italianNounPackReportJson()
            .replace("\"definite\": \"lo gnomo\"", "\"partitive\": \"lo gnomo\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Italian phraseForms key");
  }

  @Test
  public void rejectUnsupportedCompactRuntime() {
    String json =
        italianNounPackReportJson()
            .replace(
                "\"compactRuntime\": \"article-shell-composition\"",
                "\"compactRuntime\": \"eager-phrase-pack\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Italian compact runtime");
  }

  @Test
  public void rejectIncoherentReviewPolicyCounts() {
    String json =
        italianNounPackReportJson()
            .replace("\"automaticExportSurfaces\": 45823", "\"automaticExportSurfaces\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Italian automatic export count must match exact gender/number surfaces");
  }

  @Test
  public void rejectUnsupportedReviewReasonKey() {
    String json =
        italianNounPackReportJson()
            .replace(
                "\"reviewRequiredReasons\": {\n      \"multiple-analyses\": 26",
                "\"reviewRequiredReasons\": {\n      \"other-reason\": 26");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Italian reviewRequiredReasons key");
  }

  @Test
  public void rejectProvenanceSourceMismatch() {
    String json =
        italianNounPackReportJson()
            .replaceFirst(
                "c5836997d872f7e5833fe0c338109591235b91f0306fa3e317f1e7aabbfe42a9",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Italian report provenance sources must match sources");
  }

  private String italianNounPackReportJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/it_noun_pack_report_fixture.json");
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
