package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.HindiPackSurveyJsonLoader.HindiPackSurvey;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HindiPackSurveyJsonLoaderTest {

  private final HindiPackSurveyJsonLoader loader = new HindiPackSurveyJsonLoader();

  @Test
  public void loadsGeneratedFixtureAndValidatesPackShape() {
    HindiPackSurvey survey = loader.load(hindiSurveyFixtureJson());

    assertThat(survey.schema()).isEqualTo(HindiPackSurveyJsonLoader.EXPECTED_SCHEMA);
    assertThat(survey.locale()).isEqualTo("hi");
    assertThat(survey.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(survey.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/hi_pack_survey.py");
    assertThat(survey.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_hi.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_hi.xml",
            "inflection/resources/org/unicode/inflection/inflection/pronoun_hi.csv");
    assertThat(survey.sources()).hasSize(3);
    assertThat(survey.sources().get(0).byteSize()).isEqualTo(592562L);
    assertThat(survey.sources().get(0).sha256())
        .isEqualTo("c5b051852706d4c73188d532a072b4f6642b43ddd2efd506394f234428d93398");
    assertThat(survey.sources().get(1).byteSize()).isEqualTo(635966L);
    assertThat(survey.sources().get(1).sha256())
        .isEqualTo("20b67cea017333ce11a041c9150268bb14643fb5185a7a4228f4e1079f1d4f0b");
    assertThat(survey.sources().get(2).byteSize()).isEqualTo(2433L);
    assertThat(survey.sources().get(2).sha256())
        .isEqualTo("586446abbbdc6d8466912941ac2609d7bdac072af5c3422bf575a8715b1bf810");

    assertThat(survey.counts().dictionaryEntries()).isEqualTo(7533);
    assertThat(survey.counts().dictionarySkippedLines()).isEqualTo(90);
    assertThat(survey.counts().termEntries()).isEqualTo(2936);
    assertThat(survey.counts().termSurfaces()).isEqualTo(2936);
    assertThat(survey.counts().agreementEntries()).isEqualTo(3485);
    assertThat(survey.counts().agreementSurfaces()).isEqualTo(3485);
    assertThat(survey.counts().ambiguousAgreementSurfaces()).isEqualTo(1165);
    assertThat(survey.counts().pronounRows()).isEqualTo(38);
    assertThat(survey.counts().inflectionalPatterns()).isEqualTo(258);
    assertThat(survey.counts().usedAgreementPatterns()).isEqualTo(225);
    assertThat(survey.counts().missingInflectionPatterns()).isZero();

    assertThat(survey.features().inflectionPatterns()).hasSize(225).containsEntry("1", 254);
    assertThat(survey.features().partOfSpeech()).containsEntry("noun", 2864);
    assertThat(survey.features().termCase())
        .containsEntry("direct", 1803)
        .containsEntry("oblique", 1856)
        .containsEntry("vocative", 399);
    assertThat(survey.features().termGender())
        .containsEntry("feminine", 1154)
        .containsEntry("masculine", 1823);
    assertThat(survey.features().termNumber())
        .containsEntry("plural", 2011)
        .containsEntry("singular", 1519);
    assertThat(survey.features().termAnimacy())
        .containsEntry("animate", 343)
        .containsEntry("inanimate", 1271);
    assertThat(survey.features().patternSlotCases()).containsEntry("causative", 166);
    assertThat(survey.features().patternSlotOtherAttributes())
        .containsEntry("mood=subjunctive", 757)
        .containsEntry("transitivity=intransitive", 851);
    assertThat(survey.features().pronounFeatures())
        .containsEntry("genitive", 20)
        .containsEntry("second", 24);
    assertThat(survey.features().pronounDependencyFeatures())
        .containsEntry("feminine", 10)
        .containsEntry("masculine", 10);
    assertThat(survey.features().slotsPerPattern()).containsEntry("174", 1);
    assertThat(survey.features().templateRows()).isEqualTo(2818);

    assertThat(survey.packShape().recommendation())
        .isEqualTo("case-form rows plus pronoun agreement table");
    assertThat(survey.packShape().termCases()).containsExactly("direct", "oblique", "vocative");
    assertThat(survey.packShape().metadataBits())
        .containsExactly("gender", "number", "animacy", "partOfSpeech");
    assertThat(survey.packShape().pronounDependencyKeys()).containsExactly("gender", "number");
    assertThat(survey.packShape().pronounTableRows()).isEqualTo(38);
    assertThat(survey.packShape().caseFormPack().candidateTerms()).isEqualTo(1187);
    assertThat(survey.packShape().caseFormPack().formRows()).isEqualTo(3898);
    assertThat(survey.packShape().caseFormPack().skippedTerms())
        .containsEntry("non-direct-singular-surface", 1450)
        .containsEntry("suffix-mismatch", 127);
    assertThat(survey.packShape().caseFormPack().binaryLowerBoundBytes().stringPoolBytes())
        .isEqualTo(75942);
    assertThat(survey.packShape().caseFormPack().binaryLowerBoundBytes().termRowBytes())
        .isEqualTo(23740);
    assertThat(survey.packShape().caseFormPack().binaryLowerBoundBytes().formRowBytes())
        .isEqualTo(46776);
    assertThat(survey.packShape().caseFormPack().binaryLowerBoundBytes().totalBytes())
        .isEqualTo(146458);

    assertThat(survey.samples().caseFormTerms()).hasSize(8);
    assertThat(survey.samples().caseFormTerms().get(1).termId()).isEqualTo("hi.case.अँगीठा");
    assertThat(survey.samples().caseFormTerms().get(1).forms())
        .containsEntry("direct.singular", "अँगीठा")
        .containsEntry("direct.plural", "अँगीठे")
        .containsEntry("oblique.singular", "अँगीठे")
        .containsEntry("oblique.plural", "अँगीठों");
    assertThat(survey.samples().termEntries()).hasSize(8);
    assertThat(survey.samples().termEntries().get(0).surface()).isEqualTo("अ");
    assertThat(survey.samples().ambiguousAgreementSurfaces()).hasSize(8);
    assertThat(survey.samples().ambiguousAgreementSurfaces().get(0).reasons())
        .containsExactly("multiple-cases");
    assertThat(survey.samples().pronouns()).hasSize(8);
    assertThat(survey.samples().pronouns().get(0).value()).isEqualTo("मैं");
    assertThat(survey.samples().pronouns().get(6).features())
        .containsExactly(
            "first", "singular", "genitive", "dependency=singular", "dependency=masculine");
    assertThat(survey.samples().missingInflectionPatterns()).isEmpty();
  }

  @Test
  public void loadedSurveyCollectionsAreImmutable() {
    HindiPackSurvey survey = loader.load(hindiSurveyFixtureJson());

    assertThatThrownBy(() -> survey.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.features().termCase().put("ergative", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.packShape().metadataBits().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.packShape().caseFormPack().skippedTerms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.samples().caseFormTerms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.samples().caseFormTerms().get(1).forms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> survey.samples().pronouns().get(0).features().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new HindiPackSurveyJsonLoader(null))
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
            hindiSurveyFixtureJson(),
            "\"dictionaryEntries\": 7533",
            "\"dictionaryEntries\": 7533.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json = replaceRequired(hindiSurveyFixtureJson(), "\"direct\": 468", "\"direct\": 468.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: patternSlotCases.direct");
  }

  @Test
  public void rejectsNegativeFeatureCount() {
    String json = replaceRequired(hindiSurveyFixtureJson(), "\"direct\": 468", "\"direct\": -1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Feature count must be non-negative for patternSlotCases");
  }

  @Test
  public void rejectsNonIntegralSampleLine() {
    String json = replaceRequired(hindiSurveyFixtureJson(), "\"line\": 1,", "\"line\": 1.5,");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json =
        replaceRequired(hindiSurveyFixtureJson(), "\"byteSize\": 592562", "\"byteSize\": 592562.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsUnexpectedSchema() {
    String json =
        hindiSurveyFixtureJson()
            .replace(
                HindiPackSurveyJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/hi-pack-survey/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected schema");
  }

  @Test
  public void rejectsUnsupportedPatternSlotCase() {
    String json =
        replaceRequired(hindiSurveyFixtureJson(), "\"causative\": 166", "\"instrumental\": 166");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Hindi patternSlotCases key");
  }

  @Test
  public void rejectsUnexpectedTermCases() {
    String json =
        replaceRequired(
            hindiSurveyFixtureJson(),
            """
                "termCases": [
                  "direct",
                  "oblique",
                  "vocative"
                ]\
            """,
            """
                "termCases": [
                  "ergative",
                  "oblique",
                  "vocative"
                ]\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Hindi term cases");
  }

  @Test
  public void rejectsIncoherentTermRowEstimate() {
    String json =
        replaceRequired(
            hindiSurveyFixtureJson(), "\"termRowBytes\": 23740", "\"termRowBytes\": 23741");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi case-form term-row estimate is incoherent");
  }

  @Test
  public void rejectsIncoherentSkippedTermClosure() {
    String json =
        replaceRequired(
            hindiSurveyFixtureJson(),
            "\"non-direct-singular-surface\": 1450",
            "\"non-direct-singular-surface\": 1449");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidate terms and skipped terms");
  }

  @Test
  public void rejectsPronounTableMismatch() {
    String json =
        replaceRequired(
            hindiSurveyFixtureJson(), "\"pronounTableRows\": 38", "\"pronounTableRows\": 37");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi pronoun table rows must match pronoun count");
  }

  @Test
  public void rejectsUnsupportedCaseFormKey() {
    String json =
        replaceRequired(
            hindiSurveyFixtureJson(), "\"direct.singular\": \"अ\"", "\"ergative.singular\": \"अ\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Hindi case-form key");
  }

  @Test
  public void rejectsUnsupportedPronounDependencyFeature() {
    String json =
        replaceRequired(
            hindiSurveyFixtureJson(), "\"dependency=masculine\"", "\"dependency=neuter\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Hindi features value");
  }

  private String hindiSurveyFixtureJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/hi_pack_survey_fixture.json");
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

  private String replaceRequired(String source, String target, String replacement) {
    assertThat(source).contains(target);
    return source.replace(target, replacement);
  }
}
