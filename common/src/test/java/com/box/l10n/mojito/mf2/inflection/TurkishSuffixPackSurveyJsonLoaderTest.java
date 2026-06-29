package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.TurkishSuffixPackSurveyJsonLoader.TurkishSuffixPackSurvey;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TurkishSuffixPackSurveyJsonLoaderTest {

  private final TurkishSuffixPackSurveyJsonLoader loader = new TurkishSuffixPackSurveyJsonLoader();

  @Test
  public void loadsGeneratedFixtureAndValidatesPackShape() {
    TurkishSuffixPackSurvey survey = loader.load(turkishSurveyFixtureJson());

    assertThat(survey.schema()).isEqualTo("mojito-mf2-inflection/tr-suffix-pack-survey/v0");
    assertThat(survey.locale()).isEqualTo("tr");
    assertThat(survey.provenance().license())
        .isEqualTo("CC0-1.0 dictionary data; Unicode-3.0 repository packaging");
    assertThat(survey.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/tr_suffix_pack_survey.py");
    assertThat(survey.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_tr.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_tr.xml",
            "inflection/resources/org/unicode/inflection/dictionary/supplemental_tr.lst");
    assertThat(survey.sources()).hasSize(3);
    assertThat(survey.sources().get(0).byteSize()).isEqualTo(57207L);
    assertThat(survey.sources().get(0).sha256())
        .isEqualTo("32097e8a123c591d4470a51e883b8afb6cebf9b5b60c0857cffd07a7c513e391");
    assertThat(survey.sources().get(1).byteSize()).isEqualTo(21129L);
    assertThat(survey.sources().get(1).sha256())
        .isEqualTo("810637ded03292c0d48511dcfa1e293d63e044ae312293245a590585640c1445");
    assertThat(survey.sources().get(2).byteSize()).isEqualTo(43876L);
    assertThat(survey.sources().get(2).sha256())
        .isEqualTo("17fc7f47aea713943bdedf5c25a1cca909120b96b266653f46b817c4ab0c10d5");

    assertThat(survey.counts().dictionaryEntries()).isEqualTo(2117);
    assertThat(survey.counts().supportedEntries()).isEqualTo(1384);
    assertThat(survey.counts().uniqueSupportedSurfaces()).isEqualTo(1383);
    assertThat(survey.counts().defaultInflectionEntries()).isEqualTo(1091);
    assertThat(survey.counts().explicitInflectionEntries()).isEqualTo(256);
    assertThat(survey.counts().missingInflectionPatterns()).isZero();
    assertThat(survey.counts().supplementalEntries()).isEqualTo(970);
    assertThat(survey.counts().supplementalOnlySurfaces()).isEqualTo(958);

    assertThat(survey.features().grammaticalCase()).containsEntry("accusative", 31);
    assertThat(survey.features().number()).containsEntry("plural", 69);
    assertThat(survey.features().partOfSpeech()).containsEntry("noun", 1346);
    assertThat(survey.features().patternSlotCases()).containsEntry("dative", 13);
    assertThat(survey.features().patternSlotNumbers()).containsEntry("plural", 49);
    assertThat(survey.features().patternSlotOtherAttributes())
        .containsEntry("definiteness=definite", 1);
    assertThat(survey.features().supplementalFlags())
        .containsEntry("foreign", 855)
        .containsEntry("exception", 113);
    assertThat(survey.features().supplementalCombinations())
        .containsEntry("foreign consonant-end back-unround", 249);
    assertThat(survey.features().inflectionPatterns()).hasSize(72).containsEntry("1", 1091);
    assertThat(survey.features().templateRows()).isEqualTo(162);

    assertThat(survey.packShape().recommendation()).isEqualTo("rule-plus-exception suffix pack");
    assertThat(survey.packShape().metadataBits())
        .containsExactly(
            "vowelEnd",
            "frontVowel",
            "roundedVowel",
            "foreign",
            "exception",
            "hardConsonant",
            "softConsonant",
            "compound");
    assertThat(survey.packShape().supplementalExceptionRows()).isEqualTo(970);
    assertThat(survey.packShape().supplementalStringPoolBytes()).isEqualTo(6595);
    assertThat(survey.packShape().supplementalRowBytes()).isEqualTo(7760);
    assertThat(survey.packShape().supplementalMetadataLowerBoundBytes()).isEqualTo(14355);
    assertThat(survey.packShape().explicitTemplateRows()).isEqualTo(162);
    assertThat(survey.packShape().explicitTemplateRowBytes()).isEqualTo(1944);
    assertThat(survey.packShape().explicitTemplateBaseCandidateTerms()).isEqualTo(71);
    assertThat(survey.packShape().explicitTemplateCompiledFormRows()).isEqualTo(279);
    assertThat(survey.packShape().explicitTemplateCompiledStringPoolBytes()).isEqualTo(3431);
    assertThat(survey.packShape().explicitTemplateCompiledTermRowBytes()).isEqualTo(1420);
    assertThat(survey.packShape().explicitTemplateCompiledFormRowBytes()).isEqualTo(3348);
    assertThat(survey.packShape().explicitTemplateCompiledLowerBoundBytes()).isEqualTo(8199);
    assertThat(survey.packShape().explicitTemplateCompiledJsonBytes()).isEqualTo(44990);

    assertThat(survey.compositionPolicy().ruleSafeInflection()).isEqualTo("1");
    assertThat(survey.compositionPolicy().rendererScope())
        .containsExactly("plural", "nominative", "accusative", "dative", "locative", "ablative");
    assertThat(survey.compositionPolicy().mutationStrategy()).isEqualTo("explicit-template-forms");
    assertThat(survey.compositionPolicy().mutationStrategyReason())
        .contains("explicit templates are safer");
    assertThat(survey.compositionPolicy().requiresExplicitFormFlags())
        .containsExactly("exception", "foreign", "soft-consonant");
    assertThat(survey.compositionPolicy().supplementalRowsRequiringExplicitReview()).isEqualTo(968);
    assertThat(survey.compositionPolicy().caseTemplatePatterns()).isEqualTo(23);
    assertThat(survey.compositionPolicy().caseTemplateRows()).isEqualTo(60);
    assertThat(survey.compositionPolicy().caseTemplateRowBytes()).isEqualTo(720);
    assertThat(survey.compositionPolicy().emptySuffixCaseTemplateRows()).isEqualTo(41);
    assertThat(survey.compositionPolicy().suffixPreservingCaseTemplateRows()).isEqualTo(10);
    assertThat(survey.compositionPolicy().consonantMutationPatterns()).isEqualTo(5);
    assertThat(survey.compositionPolicy().consonantMutationTemplateRows()).isEqualTo(9);
    assertThat(survey.compositionPolicy().consonantMutationTemplateRowBytes()).isEqualTo(108);
    assertThat(survey.compositionPolicy().pluralTemplateRows()).isEqualTo(56);
    assertThat(survey.compositionPolicy().pluralTemplateRowBytes()).isEqualTo(672);

    assertThat(survey.samples().defaultInflectionCoveredBySupplemental()).hasSize(8);
    assertThat(survey.samples().defaultInflectionCoveredBySupplemental().get(1).surface())
        .isEqualTo("bulut");
    assertThat(survey.samples().defaultInflectionCoveredBySupplemental().get(1).supplementalFlags())
        .containsExactly("exception", "consonant-end", "back-round");
    assertThat(survey.samples().defaultInflectionMissingSupplemental().get(0).surface())
        .isEqualTo("Alzheimer");
    assertThat(survey.samples().explicitInflectionEntries().get(0).surface()).isEqualTo("amel");
    assertThat(survey.samples().supportedWithoutInflection().get(0).surface())
        .isEqualTo("Adalar Denizi");
    assertThat(survey.samples().supplementalOnlySurfaces().get(0).surface()).isEqualTo("3g");
    assertThat(survey.samples().inflectionPatterns().get(0).name()).isEqualTo("1");
    assertThat(survey.samples().consonantMutationTemplates()).hasSize(8);
    assertThat(survey.samples().consonantMutationTemplates().get(0).pattern()).isEqualTo("14");
    assertThat(survey.samples().consonantMutationTemplates().get(0).template())
        .isEqualTo("{stem}\u011f\u0131");
    assertThat(survey.samples().consonantMutationTemplates().get(0).surfaces())
        .contains("çakmak", "çakmağı");
    assertThat(survey.samples().missingInflectionPatterns()).isEmpty();
  }

  @Test
  public void loadedSurveyCollectionsAreImmutable() {
    TurkishSuffixPackSurvey survey = loader.load(turkishSurveyFixtureJson());

    assertThatThrownBy(() -> survey.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.features().grammaticalCase().put("dative", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.packShape().metadataBits().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.compositionPolicy().rendererScope().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.samples().inflectionPatterns().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.samples().consonantMutationTemplates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                survey.samples().consonantMutationTemplates().get(0).attrs().put("case", "dative"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> survey.samples().consonantMutationTemplates().get(0).surfaces().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.samples().inflectionPatterns().get(1).slots().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                survey
                    .samples()
                    .inflectionPatterns()
                    .get(1)
                    .slots()
                    .get(1)
                    .attrs()
                    .put("case", "locative"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new TurkishSuffixPackSurveyJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("objectMapper");
  }

  @Test
  public void rejectsNullJson() {
    assertThatThrownBy(() -> loader.load((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("json");
  }

  @Test
  public void rejectsNonIntegralCount() {
    String json =
        replaceRequired(
            turkishSurveyFixtureJson(),
            "\"dictionaryEntries\": 2117",
            "\"dictionaryEntries\": 2117.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json =
        replaceRequired(turkishSurveyFixtureJson(), "\"ablative\": 25", "\"ablative\": 25.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: case.ablative");
  }

  @Test
  public void rejectsNegativeFeatureCount() {
    String json =
        replaceRequired(turkishSurveyFixtureJson(), "\"ablative\": 25", "\"ablative\": -1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Feature count must be non-negative for case");
  }

  @Test
  public void rejectsNonIntegralSampleLine() {
    String json = replaceRequired(turkishSurveyFixtureJson(), "\"line\": 226", "\"line\": 226.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json =
        replaceRequired(turkishSurveyFixtureJson(), "\"byteSize\": 57207", "\"byteSize\": 57207.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsUnexpectedLocale() {
    String json = turkishSurveyFixtureJson().replace("\"locale\": \"tr\"", "\"locale\": \"ru\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected locale tr");
  }

  @Test
  public void rejectsUnsupportedCaseKey() {
    String json = turkishSurveyFixtureJson().replace("\"ablative\": 25", "\"instrumental\": 25");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Turkish case key");
  }

  @Test
  public void rejectsUnexpectedMetadataBits() {
    String json = turkishSurveyFixtureJson().replace("\"frontVowel\"", "\"frontness\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Turkish metadata bits");
  }

  @Test
  public void rejectsUnsupportedSupplementalFlag() {
    String json = turkishSurveyFixtureJson().replace("\"foreign\": 855", "\"alien\": 855");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Turkish supplementalFlags key");
  }

  @Test
  public void rejectsIncoherentSupplementalCoverage() {
    String json =
        turkishSurveyFixtureJson()
            .replace("\"supplementalOnlySurfaces\": 958", "\"supplementalOnlySurfaces\": 957");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supplemental coverage does not close");
  }

  @Test
  public void rejectsIncoherentSupplementalSizeEstimate() {
    String json =
        turkishSurveyFixtureJson()
            .replace(
                "\"supplementalMetadataLowerBoundBytes\": 14355",
                "\"supplementalMetadataLowerBoundBytes\": 14356");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supplemental lower-bound estimate");
  }

  @Test
  public void rejectsIncoherentFullExplicitTemplateSizeEstimate() {
    String json =
        turkishSurveyFixtureJson()
            .replace(
                "\"explicitTemplateCompiledLowerBoundBytes\": 8199",
                "\"explicitTemplateCompiledLowerBoundBytes\": 8200");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit-template lower-bound estimate");
  }

  @Test
  public void rejectsUnexpectedRendererScope() {
    String json =
        turkishSurveyFixtureJson()
            .replace(
                """
                "rendererScope": [
                      "plural",
                      "nominative",
                      "accusative",
                      "dative",
                      "locative",
                      "ablative"
                    ]\
                """,
                """
                "rendererScope": [
                      "plural",
                      "ergative",
                      "accusative",
                      "dative",
                      "locative",
                      "ablative"
                    ]\
                """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Turkish renderer scope");
  }

  @Test
  public void rejectsUnexpectedRuleSafeInflection() {
    String json =
        turkishSurveyFixtureJson()
            .replace("\"ruleSafeInflection\": \"1\"", "\"ruleSafeInflection\": \"14\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Turkish rule-safe inflection");
  }

  @Test
  public void rejectsUnexpectedExplicitReviewFlags() {
    String json =
        turkishSurveyFixtureJson()
            .replace(
                """
                "requiresExplicitFormFlags": [
                      "exception",
                      "foreign",
                      "soft-consonant"
                    ]\
                """,
                """
                "requiresExplicitFormFlags": [
                      "exception",
                      "foreign"
                    ]\
                """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Turkish explicit-review flags");
  }

  @Test
  public void rejectsIncoherentCaseTemplatePolicy() {
    String json =
        turkishSurveyFixtureJson()
            .replace(
                "\"consonantMutationTemplateRows\": 9", "\"consonantMutationTemplateRows\": 8");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("case-template row policy does not close");
  }

  @Test
  public void rejectsUnexpectedMutationStrategy() {
    String json =
        turkishSurveyFixtureJson()
            .replace(
                "\"mutationStrategy\": \"explicit-template-forms\"",
                "\"mutationStrategy\": \"compact-mutation-bits\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Turkish mutation strategy");
  }

  @Test
  public void rejectsIncoherentMutationByteEstimate() {
    String json =
        turkishSurveyFixtureJson()
            .replace(
                "\"consonantMutationTemplateRowBytes\": 108",
                "\"consonantMutationTemplateRowBytes\": 109");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutation byte estimate");
  }

  @Test
  public void rejectsSuffixPreservingMutationSample() {
    String json =
        turkishSurveyFixtureJson()
            .replace("\"template\": \"{stem}\u011f\u0131\"", "\"template\": \"{stem}k\u0131\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutation sample must not preserve the original suffix");
  }

  private String turkishSurveyFixtureJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/tr_suffix_pack_survey_fixture.json");
  }

  private String replaceRequired(String source, String target, String replacement) {
    assertThat(source).contains(target);
    return source.replace(target, replacement);
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
