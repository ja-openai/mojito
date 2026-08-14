package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.HebrewPackAuditJsonLoader.HebrewPackAudit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HebrewPackAuditJsonLoaderTest {

  private final HebrewPackAuditJsonLoader loader = new HebrewPackAuditJsonLoader();

  @Test
  public void loadsGeneratedFixtureAndValidatesAuditContract() {
    HebrewPackAudit audit = loader.load(hebrewAuditFixtureJson());

    assertThat(audit.schema()).isEqualTo(HebrewPackAuditJsonLoader.EXPECTED_SCHEMA);
    assertThat(audit.locale()).isEqualTo("he");
    assertThat(audit.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(audit.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/he_pack_audit.py");
    assertThat(audit.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_he.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_he.xml",
            "inflection/resources/org/unicode/inflection/inflection/pronoun_he.csv");
    assertThat(audit.provenance().sources()).hasSize(3);
    assertThat(audit.provenance().sources().get(0).byteSize()).isEqualTo(9_728_222L);
    assertThat(audit.provenance().sources().get(0).sha256())
        .isEqualTo("abece63d2c0f688d156e79860c9a3c97c51a30e07268616be67e4c7dc2d557cd");
    assertThat(audit.provenance().sources().get(1).byteSize()).isEqualTo(19_306_220L);
    assertThat(audit.provenance().sources().get(1).sha256())
        .isEqualTo("991f04b14eda1986ad7521dc76d9ade201fd01989d8c66c50944608d5c19c037");
    assertThat(audit.provenance().sources().get(2).byteSize()).isEqualTo(1_230L);
    assertThat(audit.provenance().sources().get(2).sha256())
        .isEqualTo("d2ebdc0e7004e8b20ab15bc6421dac53fe4e9e5aad967994af45855c10f108b3");

    assertThat(audit.counts().dictionaryEntries()).isEqualTo(147_765);
    assertThat(audit.counts().skippedDictionaryLines()).isEqualTo(74);
    assertThat(audit.counts().termEntries()).isEqualTo(31_039);
    assertThat(audit.counts().termSurfaces()).isEqualTo(31_039);
    assertThat(audit.counts().agreementEntries()).isEqualTo(49_836);
    assertThat(audit.counts().agreementSurfaces()).isEqualTo(49_836);
    assertThat(audit.counts().constructAgreementEntries()).isEqualTo(35_005);
    assertThat(audit.counts().dualAgreementEntries()).isEqualTo(19);
    assertThat(audit.counts().caseTaggedAgreementEntries()).isEqualTo(3);
    assertThat(audit.counts().inflectionPatterns()).isEqualTo(5_299);
    assertThat(audit.counts().usedAgreementPatterns()).isEqualTo(2_674);
    assertThat(audit.counts().usedTermPatterns()).isEqualTo(2_377);
    assertThat(audit.counts().missingAgreementPatterns()).isZero();
    assertThat(audit.counts().ambiguousTermSurfaces()).isEqualTo(6_902);

    assertThat(audit.features().partOfSpeech())
        .containsEntry("verb", 103_001)
        .containsEntry("noun", 30_980)
        .containsEntry("proper-noun", 299);
    assertThat(audit.features().termPartOfSpeech())
        .containsEntry("noun", 30_980)
        .containsEntry("verb", 4_250);
    assertThat(audit.features().agreementPartOfSpeech())
        .containsEntry("adjective", 21_142)
        .containsEntry("noun", 30_980);
    assertThat(audit.features().grammaticalCase())
        .containsEntry("<missing>", 49_833)
        .containsEntry("nominative", 3);
    assertThat(audit.features().number())
        .containsEntry("singular", 24_770)
        .containsEntry("plural", 27_189)
        .containsEntry("dual", 19);
    assertThat(audit.features().definiteness())
        .containsEntry("construct", 35_005)
        .containsEntry("<missing>", 14_822);
    assertThat(audit.features().ambiguityReasons())
        .containsEntry("multiple-inflections", 6_530)
        .containsEntry("multiple-parts-of-speech", 6_453);

    assertThat(audit.patterns().all().constructSlots()).isEqualTo(19_917);
    assertThat(audit.patterns().all().dualSlots()).isEqualTo(16);
    assertThat(audit.patterns().all().caseSlots()).isEqualTo(4);
    assertThat(audit.patterns().usedAgreement().constructSlots()).isEqualTo(9_600);
    assertThat(audit.patterns().usedAgreement().dualSlots()).isEqualTo(16);
    assertThat(audit.patterns().usedTerms().constructSlots()).isEqualTo(8_420);
    assertThat(audit.patterns().usedTerms().slotAttributes().get("verb-type"))
        .containsEntry("infinitive", 3);

    assertThat(audit.packPolicy().recommendation())
        .isEqualTo("closed-world construct-state explicit-form pack");
    assertThat(audit.packPolicy().termScope()).containsExactly("noun", "proper-noun");
    assertThat(audit.packPolicy().runtimeOptions()).containsExactly("number", "definiteness");
    assertThat(audit.packPolicy().metadataBits()).containsExactly("gender", "partOfSpeech");
    assertThat(audit.packPolicy().caseMode()).isEqualTo("unsupported-for-nouns-v0");
    assertThat(audit.packPolicy().constructMode()).isEqualTo("explicit-form-key");
    assertThat(audit.packPolicy().articleMode()).isEqualTo("not-derived-from-dictionary-v0");
    assertThat(audit.packPolicy().numberMode()).isEqualTo("explicit-number-option");
    assertThat(audit.packPolicy().countMode()).isEqualTo("singular-plural-only-by-product-policy");
    assertThat(audit.packPolicy().pronounScope()).isEqualTo("inventory-only-v0");
    assertThat(audit.packPolicy().pronounAttachmentPolicy())
        .isEqualTo("separate-hebrew-attachment-profile-later");
    assertThat(audit.packPolicy().openWorldGeneration()).isFalse();

    assertThat(audit.approvedFixtureCandidateSearch().requiredFormKeys())
        .containsExactly(
            "bare.singular",
            "bare.plural",
            "construct.singular",
            "construct.plural",
            "construct.dual");
    assertThat(audit.approvedFixtureCandidateSearch().cleanPartOfSpeech())
        .containsExactly("noun", "proper-noun");
    assertThat(audit.approvedFixtureCandidateSearch().excludedPartOfSpeech())
        .containsExactly("adjective", "verb");
    assertThat(audit.approvedFixtureCandidateSearch().singleGenderRequired()).isTrue();
    assertThat(audit.approvedFixtureCandidateSearch().singlePartOfSpeechRequired()).isTrue();
    assertThat(audit.approvedFixtureCandidateSearch().completeCleanGroups()).isZero();
    assertThat(audit.approvedFixtureCandidateSearch().constructDualCleanGroups()).isEqualTo(1);
    assertThat(audit.approvedFixtureCandidateSearch().nearCompleteCleanGroups()).isEqualTo(181);
    assertThat(audit.approvedFixtureCandidateSearch().maxObservedCleanFormKeys()).isEqualTo(4);
    assertThat(audit.approvedFixtureCandidateSearch().constructDualCleanGroupSamples()).hasSize(1);
    var constructDualSample =
        audit.approvedFixtureCandidateSearch().constructDualCleanGroupSamples().get(0);
    assertThat(constructDualSample.inflections()).containsExactly("11d3");
    assertThat(constructDualSample.gender()).containsExactly("masculine");
    assertThat(constructDualSample.partOfSpeech()).containsExactly("noun");
    assertThat(constructDualSample.formKeys()).containsExactly("construct.dual");
    assertThat(constructDualSample.missingFormKeys())
        .containsExactly("bare.singular", "bare.plural", "construct.singular", "construct.plural");
    assertThat(constructDualSample.sampleEntriesByFormKey().get("construct.dual"))
        .extracting("surface")
        .containsExactly("שיפוליי");

    assertThat(audit.pronouns().rows()).isEqualTo(32);
    assertThat(audit.pronouns().uniqueValues()).isEqualTo(32);
    assertThat(audit.pronouns().cases())
        .containsEntry("nominative", 10)
        .containsEntry("genitive", 11)
        .containsEntry("reflexive", 11);
    assertThat(audit.pronouns().numbers())
        .containsEntry("plural", 15)
        .containsEntry("singular", 17);
    assertThat(audit.pronouns().persons())
        .containsEntry("first", 6)
        .containsEntry("second", 14)
        .containsEntry("third", 12);

    assertThat(audit.samples().constructAgreementEntries()).hasSize(8);
    assertThat(audit.samples().constructAgreementEntries().get(0).surface()).isEqualTo("אב");
    assertThat(audit.samples().constructAgreementEntries().get(0).definiteness())
        .containsExactly("construct");
    assertThat(audit.samples().dualAgreementEntries()).hasSize(8);
    assertThat(audit.samples().dualAgreementEntries().get(0).surface()).isEqualTo("אפי");
    assertThat(audit.samples().dualAgreementEntries().get(0).number())
        .containsExactly("singular", "dual");
    assertThat(audit.samples().caseTaggedAgreementEntries()).hasSize(3);
    assertThat(audit.samples().caseTaggedAgreementEntries().get(0).surface()).isEqualTo("כנען");
    assertThat(audit.samples().caseTaggedAgreementEntries().get(0).grammaticalCase())
        .containsExactly("nominative");
    assertThat(audit.samples().ambiguousTermSurfaces()).hasSize(8);
    assertThat(audit.samples().ambiguousTermSurfaces().get(0).surface()).isEqualTo("אבדה");
    assertThat(audit.samples().ambiguousTermSurfaces().get(0).reasons())
        .containsExactly("multiple-inflections", "multiple-parts-of-speech");
    assertThat(audit.samples().missingAgreementPatterns()).isEmpty();
  }

  @Test
  public void loadedAuditCollectionsAreImmutable() {
    HebrewPackAudit audit = loader.load(hebrewAuditFixtureJson());

    assertThatThrownBy(() -> audit.provenance().sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.features().number().put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.patterns().usedTerms().slotAttributes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> audit.patterns().usedTerms().slotAttributes().get("number").put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.approvedFixtureCandidateSearch().requiredFormKeys().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                audit
                    .approvedFixtureCandidateSearch()
                    .constructDualCleanGroupSamples()
                    .get(0)
                    .sampleEntriesByFormKey()
                    .get("construct.dual")
                    .clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.packPolicy().runtimeOptions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().constructAgreementEntries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().constructAgreementEntries().get(0).number().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new HebrewPackAuditJsonLoader(null))
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
        hebrewAuditFixtureJson()
            .replace("\"dictionaryEntries\": 147765", "\"dictionaryEntries\": 147765.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNegativeCount() {
    String json =
        hebrewAuditFixtureJson()
            .replace("\"dictionaryEntries\": 147765", "\"dictionaryEntries\": -1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dictionaryEntries must be non-negative");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json = hebrewAuditFixtureJson().replace("\"nominative\": 3", "\"nominative\": 3.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: case.nominative");
  }

  @Test
  public void rejectsUnexpectedLocale() {
    String json = hebrewAuditFixtureJson().replace("\"locale\": \"he\"", "\"locale\": \"ar\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected locale he");
  }

  @Test
  public void rejectsUnsupportedPatternSlotAttributeValue() {
    String json =
        replaceRequired(
            hebrewAuditFixtureJson(),
            """
                "verb-type": {
                      "infinitive": 5
                    }\
            """,
            """
                "verb-type": {
                      "participle": 5
                    }\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Hebrew slotAttributes.verb-type key");
  }

  @Test
  public void rejectsIncoherentSurfaceCount() {
    String json =
        hebrewAuditFixtureJson().replace("\"termSurfaces\": 31039", "\"termSurfaces\": 31040");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("surface counts exceed entry counts");
  }

  @Test
  public void rejectsIncoherentPronounRows() {
    String json = hebrewAuditFixtureJson().replace("\"rows\": 32", "\"rows\": 33");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pronoun case counts must sum to rows");
  }

  @Test
  public void rejectsOpenWorldHebrewPackPolicy() {
    String json =
        hebrewAuditFixtureJson()
            .replace("\"openWorldGeneration\": false", "\"openWorldGeneration\": true");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not enable open-world generation");
  }

  @Test
  public void rejectsCaseEnabledHebrewPackPolicy() {
    String json =
        hebrewAuditFixtureJson()
            .replace(
                "\"caseMode\": \"unsupported-for-nouns-v0\"",
                "\"caseMode\": \"explicit-form-key\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Hebrew option policy");
  }

  @Test
  public void rejectsUnexpectedCompleteHebrewApprovedFixtureCandidates() {
    String json =
        hebrewAuditFixtureJson()
            .replace("\"completeCleanGroups\": 0", "\"completeCleanGroups\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("complete approved-fixture candidate");
  }

  @Test
  public void rejectsMismatchedProvenanceLabels() {
    String json =
        replaceRequired(
            hebrewAuditFixtureJson(),
            """
                "sourceLabels": [
                  "inflection/resources/org/unicode/inflection/dictionary/dictionary_he.lst",\
            """,
            """
                "sourceLabels": [
                  "inflection/resources/org/unicode/inflection/dictionary/dictionary_he_stale.lst",\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source labels must match sources");
  }

  private static String hebrewAuditFixtureJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/he_pack_audit_fixture.json");
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
