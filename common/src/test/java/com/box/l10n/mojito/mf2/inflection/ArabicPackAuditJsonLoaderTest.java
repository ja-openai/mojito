package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.ArabicPackAuditJsonLoader.ArabicPackAudit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ArabicPackAuditJsonLoaderTest {

  private final ArabicPackAuditJsonLoader loader = new ArabicPackAuditJsonLoader();

  @Test
  public void loadsGeneratedFixtureAndValidatesAuditContract() {
    ArabicPackAudit audit = loader.load(arabicAuditFixtureJson());

    assertThat(audit.schema()).isEqualTo(ArabicPackAuditJsonLoader.EXPECTED_SCHEMA);
    assertThat(audit.locale()).isEqualTo("ar");
    assertThat(audit.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(audit.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/ar_pack_audit.py");
    assertThat(audit.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_ar.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_ar.xml",
            "inflection/resources/org/unicode/inflection/inflection/pronoun_ar.csv");
    assertThat(audit.provenance().sources()).hasSize(3);
    assertThat(audit.provenance().sources().get(0).byteSize()).isEqualTo(893500L);
    assertThat(audit.provenance().sources().get(0).sha256())
        .isEqualTo("7d3ce5339d95517edbdc9c824f002e076351e6ced9bb67c7109ad3d486e3a77e");
    assertThat(audit.provenance().sources().get(1).byteSize()).isEqualTo(1599608L);
    assertThat(audit.provenance().sources().get(1).sha256())
        .isEqualTo("80329a41580409b839a8c768923a80c9e16d20a58dce505beddfa814fe18a3df");
    assertThat(audit.provenance().sources().get(2).byteSize()).isEqualTo(2054L);
    assertThat(audit.provenance().sources().get(2).sha256())
        .isEqualTo("78f84891865d460b9c89a5dcc1a5ef00705bfbe1f627d3d419c1add533d9bc1d");

    assertThat(audit.counts().dictionaryEntries()).isEqualTo(11023);
    assertThat(audit.counts().skippedDictionaryLines()).isEqualTo(84);
    assertThat(audit.counts().termEntries()).isEqualTo(1629);
    assertThat(audit.counts().agreementEntries()).isEqualTo(6080);
    assertThat(audit.counts().baseCandidateEntries()).isEqualTo(130);
    assertThat(audit.counts().dualAgreementEntries()).isEqualTo(1601);
    assertThat(audit.counts().constructAgreementEntries()).isEqualTo(412);
    assertThat(audit.counts().fullySpecifiedAgreementEntries()).isEqualTo(3841);
    assertThat(audit.counts().inflectionPatterns()).isEqualTo(604);
    assertThat(audit.counts().usedAgreementPatterns()).isEqualTo(492);
    assertThat(audit.counts().usedTermPatterns()).isEqualTo(372);
    assertThat(audit.counts().missingAgreementPatterns()).isZero();
    assertThat(audit.counts().ambiguousAgreementSurfaces()).isEqualTo(1222);

    assertThat(audit.features().partOfSpeech())
        .containsEntry("verb", 4886)
        .containsEntry("noun", 1570);
    assertThat(audit.features().termPartOfSpeech())
        .containsEntry("noun", 1570)
        .containsEntry("proper-noun", 63);
    assertThat(audit.features().grammaticalCase())
        .containsEntry("nominative", 2135)
        .containsEntry("accusative", 1609)
        .containsEntry("genitive", 1537);
    assertThat(audit.features().number())
        .containsEntry("dual", 1601)
        .containsEntry("plural", 1794)
        .containsEntry("singular", 2646);
    assertThat(audit.features().definiteness())
        .containsEntry("construct", 412)
        .containsEntry("indefinite", 5387);
    assertThat(audit.features().ambiguityReasons())
        .containsEntry("multiple-cases", 1016)
        .containsEntry("multiple-definiteness", 108);

    assertThat(audit.patterns().all().dualSlots()).isEqualTo(2320);
    assertThat(audit.patterns().all().constructSlots()).isEqualTo(455);
    assertThat(audit.patterns().usedAgreement().dualSlots()).isEqualTo(961);
    assertThat(audit.patterns().usedAgreement().constructSlots()).isEqualTo(455);
    assertThat(audit.patterns().usedTerms().dualSlots()).isEqualTo(310);
    assertThat(audit.patterns().usedTerms().constructSlots()).isEqualTo(393);
    assertThat(audit.patterns().usedTerms().slotAttributes().get("verb-type"))
        .containsEntry("gerund", 13)
        .containsEntry("participle", 5);

    assertThat(audit.packPolicy().recommendation()).isEqualTo("closed-world explicit-form pack");
    assertThat(audit.packPolicy().termScope()).containsExactly("noun", "proper-noun");
    assertThat(audit.packPolicy().runtimeOptions())
        .containsExactly("case", "number", "definiteness");
    assertThat(audit.packPolicy().metadataBits())
        .containsExactly("gender", "animacy", "partOfSpeech");
    assertThat(audit.packPolicy().caseMode()).isEqualTo("explicit-form-key");
    assertThat(audit.packPolicy().numberMode()).isEqualTo("explicit-number-option");
    assertThat(audit.packPolicy().constructMode()).isEqualTo("explicit-form-key");
    assertThat(audit.packPolicy().countMode()).isEqualTo("no-implicit-count-to-dual");
    assertThat(audit.packPolicy().pronounScope()).isEqualTo("inventory-only-v0");
    assertThat(audit.packPolicy().pronounAttachmentPolicy())
        .isEqualTo("separate-arabic-attachment-profile-later");
    assertThat(audit.packPolicy().adjectiveAgreementPolicy()).isEqualTo("out-of-scope-for-v0");
    assertThat(audit.packPolicy().openWorldGeneration()).isFalse();
    assertThat(audit.packPolicy().baseCandidateEntries()).isEqualTo(130);
    assertThat(audit.packPolicy().explicitFormCandidateRows()).isEqualTo(3841);
    assertThat(audit.packPolicy().ambiguousSurfaceRows()).isEqualTo(1222);
    assertThat(audit.packPolicy().verbBearingTermPatternSlots()).isEqualTo(18);
    assertThat(audit.packPolicy().reviewRequiredReasons())
        .containsExactly(
            "ambiguous-surface",
            "construct-state",
            "dual-number",
            "verb-bearing-pattern-slot",
            "missing-or-ambiguous-gender",
            "pronoun-attachment");
    assertThat(audit.packPolicy().reviewRequiredEvidence())
        .containsEntry("ambiguous-surface", 1222)
        .containsEntry("construct-state", 412)
        .containsEntry("dual-number", 1601)
        .containsEntry("verb-bearing-pattern-slot", 18)
        .containsEntry("missing-or-ambiguous-gender", 891)
        .containsEntry("pronoun-attachment", 52);

    assertThat(audit.pronouns().rows()).isEqualTo(52);
    assertThat(audit.pronouns().uniqueValues()).isEqualTo(35);
    assertThat(audit.pronouns().cases())
        .containsEntry("nominative", 13)
        .containsEntry("accusative", 13)
        .containsEntry("genitive", 13)
        .containsEntry("reflexive", 13);
    assertThat(audit.pronouns().numbers())
        .containsEntry("dual", 12)
        .containsEntry("plural", 20)
        .containsEntry("singular", 20);

    assertThat(audit.samples().baseCandidates()).hasSize(8);
    assertThat(audit.samples().baseCandidates().get(0).surface()).isEqualTo("آخِر");
    assertThat(audit.samples().baseCandidates().get(0).partOfSpeech())
        .containsExactly("adjective", "noun");
    assertThat(audit.samples().dualAgreementEntries()).hasSize(8);
    assertThat(audit.samples().dualAgreementEntries().get(0).number()).containsExactly("dual");
    assertThat(audit.samples().constructAgreementEntries()).hasSize(8);
    assertThat(audit.samples().constructAgreementEntries().get(0).definiteness())
        .containsExactly("indefinite", "construct");
    assertThat(audit.samples().ambiguousAgreementSurfaces()).hasSize(8);
    assertThat(audit.samples().ambiguousAgreementSurfaces().get(0).reasons())
        .containsExactly("multiple-cases", "multiple-definiteness");
    assertThat(audit.samples().pronouns()).hasSize(8);
    assertThat(audit.samples().pronouns().get(0).value()).isEqualTo("هُم");
    assertThat(audit.samples().missingAgreementPatterns()).isEmpty();
    assertThat(audit.policyQuestions()).hasSize(5);
  }

  @Test
  public void loadedAuditCollectionsAreImmutable() {
    ArabicPackAudit audit = loader.load(arabicAuditFixtureJson());

    assertThatThrownBy(() -> audit.provenance().sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.features().number().put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.patterns().usedTerms().slotAttributes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> audit.patterns().usedTerms().slotAttributes().get("number").put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.packPolicy().runtimeOptions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.packPolicy().reviewRequiredReasons().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.packPolicy().reviewRequiredEvidence().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().baseCandidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().baseCandidates().get(0).number().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().pronouns().get(0).features().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.policyQuestions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new ArabicPackAuditJsonLoader(null))
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
        arabicAuditFixtureJson()
            .replace("\"dictionaryEntries\": 11023", "\"dictionaryEntries\": 11023.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNegativeCount() {
    String json =
        arabicAuditFixtureJson()
            .replace("\"dictionaryEntries\": 11023", "\"dictionaryEntries\": -1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dictionaryEntries must be non-negative");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json =
        arabicAuditFixtureJson().replace("\"nominative\": 2135", "\"nominative\": 2135.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: case.nominative");
  }

  @Test
  public void rejectsNegativeFeatureCount() {
    String json = arabicAuditFixtureJson().replace("\"nominative\": 2135", "\"nominative\": -1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Feature count must be non-negative for case");
  }

  @Test
  public void rejectsNonIntegralSampleLine() {
    String json = arabicAuditFixtureJson().replace("\"line\": 1,", "\"line\": 1.5,");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json =
        arabicAuditFixtureJson().replace("\"byteSize\": 893500", "\"byteSize\": 893500.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsUnexpectedLocale() {
    String json = arabicAuditFixtureJson().replace("\"locale\": \"ar\"", "\"locale\": \"he\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected locale ar");
  }

  @Test
  public void rejectsUnsupportedPatternSlotAttributeValue() {
    String json =
        replaceRequired(
            arabicAuditFixtureJson(),
            """
                "register": {
                      "informal": 1
                    }\
            """,
            """
                "register": {
                      "formal": 1
                    }\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Arabic slotAttributes.register key");
  }

  @Test
  public void rejectsIncoherentBaseCandidateCount() {
    String json =
        arabicAuditFixtureJson()
            .replace("\"baseCandidateEntries\": 130", "\"baseCandidateEntries\": 2000");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base candidates exceed term entries");
  }

  @Test
  public void rejectsIncoherentPronounRows() {
    String json = arabicAuditFixtureJson().replace("\"rows\": 52", "\"rows\": 53");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pronoun case counts must sum to rows");
  }

  @Test
  public void rejectsOpenWorldArabicPackPolicy() {
    String json =
        arabicAuditFixtureJson()
            .replace("\"openWorldGeneration\": false", "\"openWorldGeneration\": true");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not enable open-world generation");
  }

  @Test
  public void rejectsImplicitCountToDualArabicPackPolicy() {
    String json =
        arabicAuditFixtureJson()
            .replace(
                "\"countMode\": \"no-implicit-count-to-dual\"",
                "\"countMode\": \"implicit-count-to-dual\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Arabic option policy");
  }

  @Test
  public void rejectsStaleArabicPackPolicyEvidence() {
    String json =
        arabicAuditFixtureJson()
            .replace("\"explicitFormCandidateRows\": 3841", "\"explicitFormCandidateRows\": 3840");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pack policy evidence does not match counts");
  }

  @Test
  public void rejectsStaleArabicReviewEvidence() {
    String json =
        arabicAuditFixtureJson().replace("\"dual-number\": 1601", "\"dual-number\": 1600");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("review-required evidence does not match counts");
  }

  @Test
  public void rejectsMismatchedProvenanceLabels() {
    String json =
        replaceRequired(
            arabicAuditFixtureJson(),
            """
                "sourceLabels": [
                  "inflection/resources/org/unicode/inflection/dictionary/dictionary_ar.lst",\
            """,
            """
                "sourceLabels": [
                  "inflection/resources/org/unicode/inflection/dictionary/dictionary_ar_stale.lst",\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source labels must match sources");
  }

  private static String arabicAuditFixtureJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/ar_pack_audit_fixture.json");
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
