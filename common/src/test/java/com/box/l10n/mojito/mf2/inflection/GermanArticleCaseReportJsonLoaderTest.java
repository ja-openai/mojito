package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.GermanArticleCaseReportJsonLoader.GermanArticleCaseReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class GermanArticleCaseReportJsonLoaderTest {

  private final GermanArticleCaseReportJsonLoader loader = new GermanArticleCaseReportJsonLoader();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new GermanArticleCaseReportJsonLoader(null))
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
  public void loadsPythonGeneratedFixture() {
    GermanArticleCaseReport report = loader.load(germanArticleCaseReportJson());

    assertThat(report.schema()).isEqualTo("mojito-mf2-inflection/de-article-case-pack-report/v0");
    assertThat(report.locale()).isEqualTo("de");
    assertThat(report.articleForms()).hasSize(32);
    assertThat(report.sources()).hasSize(2);
    assertThat(report.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(report.provenance().sources()).isEqualTo(report.sources());
    assertThat(report.sources().get(0).sha256())
        .isEqualTo("889018ad62df6258da38973ecd8737e21d98d53b3469530f4870e014d6a3280b");
    assertThat(report.counts().dictionaryEntries()).isEqualTo(206419);
    assertThat(report.counts().supportedEntries()).isEqualTo(161665);
    assertThat(report.counts().missingInflectionPatterns()).isZero();
    assertThat(report.counts().articleCaseCandidateTerms()).isEqualTo(41363);
    assertThat(report.features().grammaticalCase()).containsEntry("dative", 111170);
    assertThat(report.features().gender()).containsEntry("neuter", 39330);
    assertThat(report.features().candidateSkipReasons())
        .containsEntry("singular-plural-surface-not-invariant", 11062);
    assertThat(report.reviewPolicy().runtimeExport()).isEqualTo("closed-world-article-case-forms");
    assertThat(report.reviewPolicy().automaticExportTerms()).isEqualTo(41363);
    assertThat(report.reviewPolicy().reviewRequiredSurfaces()).isEqualTo(104487);
    assertThat(report.reviewPolicy().blockedDictionaryEntries()).isEqualTo(165056);
    assertThat(report.reviewPolicy().reviewRequiredReasons())
        .containsEntry("multiple-cases", 104251)
        .containsEntry("multiple-numbers", 12376);
    assertThat(report.reviewPolicy().blockedReasons())
        .containsEntry("not-nominative", 55676)
        .containsEntry("unsupported-part-of-speech", 44754);
    assertThat(report.sizeEstimates().formRows()).isEqualTo(661808);
    assertThat(report.sizeEstimates().binaryLowerBoundBytes()).isEqualTo(17390735);
    assertThat(report.samples().articleCaseCandidates()).hasSize(8);
    assertThat(report.samples().articleCaseCandidates())
        .extracting("text")
        .contains("1-Cent-Münze", "1-Cent-Stück", "1-Euro-Job");
    assertThat(report.samples().articleCaseCandidates())
        .extracting("text")
        .doesNotContain("1-Euro-Jobs");
    assertThat(report.samples().articleCaseCandidates().get(0).phraseForms())
        .containsEntry("definite.dative.plural", "den 1-Cent-Münzen");
    assertThat(report.samples().articleCaseCandidates().get(1).phraseForms())
        .containsEntry("indefinite.genitive.singular", "eines 1-Cent-Stückes");
  }

  @Test
  public void loadedReportCollectionsAreImmutable() {
    GermanArticleCaseReport report = loader.load(germanArticleCaseReportJson());

    assertThatThrownBy(() -> report.articleForms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.features().candidateSkipReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.reviewPolicy().blockedReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.samples().articleCaseCandidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.samples().articleCaseCandidates().get(0).phraseForms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsUnexpectedLocale() {
    String json = germanArticleCaseReportJson().replace("\"locale\": \"de\"", "\"locale\": \"fr\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected locale de");
  }

  @Test
  public void rejectsUnsupportedArticleValue() {
    String json =
        germanArticleCaseReportJson()
            .replaceFirst("\"article\": \"definite\"", "\"article\": \"partitive\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported German article value");
  }

  @Test
  public void rejectsNonIntegralCount() {
    String json =
        germanArticleCaseReportJson()
            .replace("\"dictionaryEntries\": 206419", "\"dictionaryEntries\": 206419.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json =
        germanArticleCaseReportJson().replace("\"dative\": 111170", "\"dative\": 111170.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: case.dative");
  }

  @Test
  public void rejectsNonIntegralSampleLine() {
    String json = germanArticleCaseReportJson().replace("\"line\": 8", "\"line\": 8.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json =
        germanArticleCaseReportJson().replace("\"byteSize\": 15082193", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsUnsupportedPhraseFormKey() {
    String json =
        germanArticleCaseReportJson()
            .replaceFirst("definite\\.accusative\\.plural", "definite.ablative.plural");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported German phraseForms value");
  }

  @Test
  public void rejectsIncoherentSizeEstimate() {
    String json =
        germanArticleCaseReportJson()
            .replace("\"binaryLowerBoundBytes\": 17390735", "\"binaryLowerBoundBytes\": 17390736");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("German binary lower-bound estimate does not match parts");
  }

  @Test
  public void rejectsIncoherentCandidateCount() {
    String json =
        germanArticleCaseReportJson()
            .replace(
                "\"articleCaseCandidateTerms\": 41363", "\"articleCaseCandidateTerms\": 41364");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("German automatic export terms must match article/case candidates");
  }

  @Test
  public void rejectsUnsupportedRuntimeExportPolicy() {
    String json =
        germanArticleCaseReportJson()
            .replace(
                "\"runtimeExport\": \"closed-world-article-case-forms\"",
                "\"runtimeExport\": \"compact-article-composition\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported German runtime export policy");
  }

  @Test
  public void rejectsIncoherentReviewPolicyCount() {
    String json =
        germanArticleCaseReportJson()
            .replace("\"automaticExportTerms\": 41363", "\"automaticExportTerms\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("German automatic export terms must match article/case candidates");
  }

  @Test
  public void rejectsMismatchedReviewPolicyReasons() {
    String json =
        germanArticleCaseReportJson()
            .replace(
                "\"reviewRequiredReasons\": {\n      \"multiple-analyses\": 1",
                "\"reviewRequiredReasons\": {\n      \"multiple-genders\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("German review-required reasons must match ambiguity reasons");
  }

  @Test
  public void rejectsProvenanceSourceMismatch() {
    String json =
        germanArticleCaseReportJson()
            .replaceFirst(
                "889018ad62df6258da38973ecd8737e21d98d53b3469530f4870e014d6a3280b",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Provenance sources do not match top-level sources");
  }

  private String germanArticleCaseReportJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/de_article_case_report_fixture.json");
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
