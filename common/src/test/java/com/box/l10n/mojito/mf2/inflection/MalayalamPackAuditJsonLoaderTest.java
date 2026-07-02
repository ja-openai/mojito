package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.MalayalamPackAuditJsonLoader.MalayalamPackAudit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class MalayalamPackAuditJsonLoaderTest {

  private final MalayalamPackAuditJsonLoader loader = new MalayalamPackAuditJsonLoader();

  @Test
  public void loadsGeneratedFixtureAndValidatesAuditContract() {
    MalayalamPackAudit audit = loader.load(malayalamAuditFixtureJson());

    assertThat(audit.schema()).isEqualTo(MalayalamPackAuditJsonLoader.EXPECTED_SCHEMA);
    assertThat(audit.locale()).isEqualTo("ml");
    assertThat(audit.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(audit.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/ml_pack_audit.py");
    assertThat(audit.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_ml.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_ml.xml",
            "inflection/resources/org/unicode/inflection/inflection/pronoun_ml.csv");
    assertThat(audit.provenance().sources()).hasSize(3);
    assertThat(audit.provenance().sources().get(0).byteSize()).isEqualTo(53_958_746L);
    assertThat(audit.provenance().sources().get(0).sha256())
        .isEqualTo("6bda9371a2aa17c08328381e678b77e769269f4ee74749dd4f9e0bd5890cf59c");
    assertThat(audit.provenance().sources().get(1).byteSize()).isEqualTo(613_479L);
    assertThat(audit.provenance().sources().get(1).sha256())
        .isEqualTo("1868dab352ff2648c2ba495bc08a3877409eadf177f573817fd03ae07174b12f");
    assertThat(audit.provenance().sources().get(2).byteSize()).isEqualTo(4_450L);
    assertThat(audit.provenance().sources().get(2).sha256())
        .isEqualTo("6806fdc7db0c9e944e7e4e68b607cc5eaff843ead9c29798f1ae3b2832d7e41b");

    assertThat(audit.counts().dictionaryEntries()).isEqualTo(748_662);
    assertThat(audit.counts().skippedDictionaryLines()).isEqualTo(75);
    assertThat(audit.counts().termEntries()).isEqualTo(730_439);
    assertThat(audit.counts().termSurfaces()).isEqualTo(730_205);
    assertThat(audit.counts().agreementEntries()).isEqualTo(733_006);
    assertThat(audit.counts().agreementSurfaces()).isEqualTo(732_772);
    assertThat(audit.counts().caseTaggedAgreementEntries()).isEqualTo(721_993);
    assertThat(audit.counts().genderTaggedAgreementEntries()).isEqualTo(2_262);
    assertThat(audit.counts().inflectionPatterns()).isEqualTo(363);
    assertThat(audit.counts().usedAgreementPatterns()).isEqualTo(363);
    assertThat(audit.counts().usedTermPatterns()).isEqualTo(363);
    assertThat(audit.counts().missingAgreementPatterns()).isZero();
    assertThat(audit.counts().ambiguousTermSurfaces()).isEqualTo(3_707);

    assertThat(audit.features().partOfSpeech())
        .containsEntry("noun", 716_465)
        .containsEntry("proper-noun", 16_439)
        .containsEntry("verb", 15_319);
    assertThat(audit.features().termPartOfSpeech())
        .containsEntry("noun", 716_465)
        .containsEntry("proper-noun", 16_439);
    assertThat(audit.features().agreementPartOfSpeech())
        .containsEntry("adjective", 2_864)
        .containsEntry("noun", 716_465);
    assertThat(audit.features().grammaticalCase())
        .containsEntry("<missing>", 11_013)
        .containsEntry("nominative", 98_733)
        .containsEntry("accusative", 102_845)
        .containsEntry("sociative", 100_049)
        .containsEntry("vocative", 7_788);
    assertThat(audit.features().gender())
        .containsEntry("<missing>", 730_744)
        .containsEntry("feminine", 1_869);
    assertThat(audit.features().animacy())
        .containsEntry("<missing>", 732_947)
        .containsEntry("animate", 30)
        .containsEntry("human", 15);
    assertThat(audit.features().ambiguityReasons())
        .containsEntry("multiple-parts-of-speech", 2_899)
        .containsEntry("multiple-inflections", 717)
        .containsEntry("multiple-cases", 73);
    assertThat(audit.features().unknownGrammemes()).hasSizeGreaterThan(200);

    assertThat(audit.patterns().all().caseSlots()).isEqualTo(5_254);
    assertThat(audit.patterns().usedAgreement().caseSlots()).isEqualTo(5_254);
    assertThat(audit.patterns().usedTerms().caseSlots()).isEqualTo(5_254);
    assertThat(audit.patterns().all().slotAttributes().get("case"))
        .containsEntry("instrumental", 955)
        .containsEntry("vocative", 254);
    assertThat(audit.patterns().all().slotAttributes().get("number"))
        .containsEntry("singular", 3_172)
        .containsEntry("plural", 2_296);

    assertThat(audit.packPolicy().recommendation())
        .isEqualTo("closed-world multi-case explicit-form pack first");
    assertThat(audit.packPolicy().termScope()).containsExactly("noun", "proper-noun");
    assertThat(audit.packPolicy().runtimeOptions()).containsExactly("case", "number");
    assertThat(audit.packPolicy().metadataBits())
        .containsExactly("partOfSpeech", "gender", "animacy");
    assertThat(audit.packPolicy().caseMode()).isEqualTo("explicit-form-key");
    assertThat(audit.packPolicy().numberMode()).isEqualTo("explicit-number-option");
    assertThat(audit.packPolicy().pronounScope()).isEqualTo("inventory-only-v0");
    assertThat(audit.packPolicy().pronounAgreementPolicy())
        .isEqualTo("separate-malayalam-pronoun-profile-later");
    assertThat(audit.packPolicy().laterOptimization())
        .isEqualTo("suffix-composition audit after closed-world fixture");
    assertThat(audit.packPolicy().openWorldGeneration()).isFalse();

    assertThat(audit.pronouns().rows()).isEqualTo(75);
    assertThat(audit.pronouns().uniqueValues()).isEqualTo(75);
    assertThat(audit.pronouns().cases())
        .containsEntry("nominative", 12)
        .containsEntry("genitive", 20)
        .containsEntry("sociative", 9);
    assertThat(audit.pronouns().numbers())
        .containsEntry("plural", 29)
        .containsEntry("singular", 46);
    assertThat(audit.pronouns().persons())
        .containsEntry("first", 21)
        .containsEntry("second", 24)
        .containsEntry("third", 30);
    assertThat(audit.pronouns().determination())
        .containsEntry("determination=dependent", 10)
        .containsEntry("determination=independent", 10);

    assertThat(audit.samples().caseTaggedAgreementEntries()).hasSize(8);
    assertThat(audit.samples().caseTaggedAgreementEntries().get(0).line()).isEqualTo(9);
    assertThat(audit.samples().caseTaggedAgreementEntries().get(0).surface()).isNotBlank();
    assertThat(audit.samples().caseTaggedAgreementEntries().get(0).grammaticalCase())
        .containsExactly("nominative");
    assertThat(audit.samples().genderTaggedAgreementEntries()).hasSize(8);
    assertThat(audit.samples().genderTaggedAgreementEntries().get(0).line()).isEqualTo(4_824);
    assertThat(audit.samples().genderTaggedAgreementEntries().get(0).gender())
        .containsExactly("feminine");
    assertThat(audit.samples().ambiguousTermSurfaces()).hasSize(8);
    assertThat(audit.samples().ambiguousTermSurfaces().get(0).reasons())
        .containsExactly("multiple-parts-of-speech");
    assertThat(audit.samples().missingAgreementPatterns()).isEmpty();
  }

  @Test
  public void loadedAuditCollectionsAreImmutable() {
    MalayalamPackAudit audit = loader.load(malayalamAuditFixtureJson());

    assertThatThrownBy(() -> audit.provenance().sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.features().grammaticalCase().put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.patterns().usedTerms().slotAttributes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> audit.patterns().usedTerms().slotAttributes().get("case").put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.packPolicy().runtimeOptions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.pronouns().determination().put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().caseTaggedAgreementEntries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().caseTaggedAgreementEntries().get(0).number().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new MalayalamPackAuditJsonLoader(null))
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
        malayalamAuditFixtureJson()
            .replace("\"dictionaryEntries\": 748662", "\"dictionaryEntries\": 748662.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNegativeCount() {
    String json =
        malayalamAuditFixtureJson()
            .replace("\"dictionaryEntries\": 748662", "\"dictionaryEntries\": -1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dictionaryEntries must be non-negative");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json =
        malayalamAuditFixtureJson().replace("\"nominative\": 98733", "\"nominative\": 98733.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: case.nominative");
  }

  @Test
  public void rejectsUnexpectedLocale() {
    String json = malayalamAuditFixtureJson().replace("\"locale\": \"ml\"", "\"locale\": \"he\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected locale ml");
  }

  @Test
  public void rejectsUnsupportedPatternSlotAttributeValue() {
    String json =
        replaceRequired(
            malayalamAuditFixtureJson(),
            """
                "count": {
                      "countable": 14
                    },\
            """,
            """
                "count": {
                      "uncountable": 14
                    },\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Malayalam slotAttributes.count key");
  }

  @Test
  public void rejectsIncoherentSurfaceCount() {
    String json =
        malayalamAuditFixtureJson().replace("\"termSurfaces\": 730205", "\"termSurfaces\": 730440");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("surface counts exceed entry counts");
  }

  @Test
  public void rejectsIncoherentCaseCoverage() {
    String json =
        malayalamAuditFixtureJson()
            .replace(
                "\"caseTaggedAgreementEntries\": 721993", "\"caseTaggedAgreementEntries\": 721992");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("case coverage does not match counts");
  }

  @Test
  public void rejectsIncoherentPronounRows() {
    String json = malayalamAuditFixtureJson().replace("\"rows\": 75", "\"rows\": 76");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pronoun case counts must sum to rows");
  }

  @Test
  public void rejectsOpenWorldMalayalamPackPolicy() {
    String json =
        malayalamAuditFixtureJson()
            .replace("\"openWorldGeneration\": false", "\"openWorldGeneration\": true");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not enable open-world generation");
  }

  @Test
  public void rejectsSuffixFirstMalayalamPackPolicy() {
    String json =
        malayalamAuditFixtureJson()
            .replace(
                "\"laterOptimization\": \"suffix-composition audit after closed-world fixture\"",
                "\"laterOptimization\": \"suffix-composition-first\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Malayalam later optimization policy");
  }

  @Test
  public void rejectsMismatchedProvenanceLabels() {
    String json =
        replaceRequired(
            malayalamAuditFixtureJson(),
            """
                "sourceLabels": [
                  "inflection/resources/org/unicode/inflection/dictionary/dictionary_ml.lst",\
            """,
            """
                "sourceLabels": [
                  "inflection/resources/org/unicode/inflection/dictionary/dictionary_ml_stale.lst",\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source labels must match sources");
  }

  private static String malayalamAuditFixtureJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/ml_pack_audit_fixture.json");
  }

  private static String replaceRequired(String text, String target, String replacement) {
    assertThat(text).contains(target);
    return text.replace(target, replacement);
  }

  private static String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
