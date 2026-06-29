package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.GermanicNordicPackAuditJsonLoader.GermanicNordicPackAudit;
import com.box.l10n.mojito.mf2.inflection.GermanicNordicPackAuditJsonLoader.LocaleAudit;
import com.box.l10n.mojito.mf2.inflection.GermanicNordicPackAuditJsonLoader.Source;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class GermanicNordicPackAuditJsonLoaderTest {

  private final GermanicNordicPackAuditJsonLoader loader = new GermanicNordicPackAuditJsonLoader();

  @Test
  public void loadsGeneratedFixtureAndValidatesAuditContract() {
    GermanicNordicPackAudit audit = loader.load(germanicNordicAuditFixtureJson());

    assertThat(audit.schema()).isEqualTo(GermanicNordicPackAuditJsonLoader.EXPECTED_SCHEMA);
    assertThat(audit.summary().locales()).containsExactly("da", "nb", "nl", "sv");
    assertThat(audit.summary().caseRuntimeCandidateLocales()).containsExactly("da", "sv");
    assertThat(audit.summary().metadataFirstLocales()).containsExactly("nb", "nl");
    assertThat(audit.locales())
        .extracting(LocaleAudit::locale)
        .containsExactly("da", "nb", "nl", "sv");

    LocaleAudit danish = audit.locale("da");
    assertThat(danish.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_da.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_da.xml",
            "inflection/resources/org/unicode/inflection/inflection/pronoun_da.csv");
    assertThat(danish.provenance().sources())
        .extracting(Source::byteSize)
        .containsExactly(38_557_469L, 2_149_623L, 1_562L);
    assertThat(danish.provenance().sources())
        .extracting(Source::sha256)
        .containsExactly(
            "6226384ce104886c32e6a90432f8b41d96be0f9925eb10896095250d048b6c8b",
            "795f73dcbc1eea9db20fe77063952a023ed499c746d8ae3e003be001a8633851",
            "f92077f94747e3a9aca5db1da6d44e11dc5ac8e85a8f45b85e9c9e4d6d92a714");
    assertThat(danish.counts().dictionaryEntries()).isEqualTo(586_265);
    assertThat(danish.counts().caseTaggedAgreementEntries()).isEqualTo(478_561);
    assertThat(danish.counts().definiteAgreementEntries()).isEqualTo(267_600);
    assertThat(danish.counts().genitiveAgreementEntries()).isEqualTo(234_045);
    assertThat(danish.features().grammaticalCase())
        .containsEntry("genitive", 234_045)
        .containsEntry("nominative", 245_232);
    assertThat(danish.patterns().all().caseSlots()).isEqualTo(12_131);
    assertThat(danish.patterns().all().definitenessSlots()).isEqualTo(13_929);
    assertThat(danish.packPolicy().recommendation())
        .isEqualTo("closed-world explicit genitive/definiteness pack first");
    assertThat(danish.packPolicy().runtimeOptions())
        .containsExactly("case", "number", "definiteness");
    assertThat(danish.samples().caseTaggedAgreementEntries()).hasSize(8);

    LocaleAudit bokmal = audit.locale("nb");
    assertThat(bokmal.provenance().sources())
        .extracting(Source::byteSize)
        .containsExactly(9_470_180L, 249_252L, 2_904L);
    assertThat(bokmal.counts().dictionaryEntries()).isEqualTo(162_215);
    assertThat(bokmal.counts().caseTaggedAgreementEntries()).isEqualTo(5);
    assertThat(bokmal.counts().definiteAgreementEntries()).isEqualTo(72_343);
    assertThat(bokmal.packPolicy().recommendation())
        .isEqualTo("closed-world definiteness/gender metadata pack first");
    assertThat(bokmal.packPolicy().runtimeOptions()).containsExactly("number", "definiteness");
    assertThat(bokmal.packPolicy().caseMode()).isEqualTo("unsupported-for-nouns-v0");
    assertThat(bokmal.samples().caseTaggedAgreementEntries()).hasSize(5);

    LocaleAudit dutch = audit.locale("nl");
    assertThat(dutch.provenance().sources())
        .extracting(Source::byteSize)
        .containsExactly(780_817L, 2_381_180L, 2_772L);
    assertThat(dutch.counts().dictionaryEntries()).isEqualTo(13_093);
    assertThat(dutch.counts().diminutiveEntries()).isEqualTo(2_783);
    assertThat(dutch.counts().definiteAgreementEntries()).isZero();
    assertThat(dutch.patterns().all().slotAttributes().get("case")).containsEntry("partitive", 71);
    assertThat(dutch.packPolicy().recommendation())
        .isEqualTo("metadata-and-diminutive audit before case runtime");
    assertThat(dutch.packPolicy().metadataBits())
        .containsExactly("gender", "partOfSpeech", "sizeness");
    assertThat(dutch.samples().diminutiveEntries()).hasSize(8);

    LocaleAudit swedish = audit.locale("sv");
    assertThat(swedish.provenance().sources())
        .extracting(Source::byteSize)
        .containsExactly(19_379_822L, 855_601L, 2_234L);
    assertThat(swedish.counts().dictionaryEntries()).isEqualTo(296_437);
    assertThat(swedish.counts().caseTaggedAgreementEntries()).isEqualTo(240_031);
    assertThat(swedish.counts().definiteAgreementEntries()).isEqualTo(135_914);
    assertThat(swedish.counts().genitiveAgreementEntries()).isEqualTo(120_385);
    assertThat(swedish.patterns().all().slotAttributes().get("case")).containsEntry("oblique", 1);
    assertThat(swedish.packPolicy().caseMode()).isEqualTo("nominative-genitive-explicit-form-key");
    assertThat(swedish.pronouns().rows()).isEqualTo(46);
  }

  @Test
  public void loadedAuditCollectionsAreImmutable() {
    GermanicNordicPackAudit audit = loader.load(germanicNordicAuditFixtureJson());

    assertThatThrownBy(() -> audit.locales().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.summary().locales().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.locale("da").features().grammaticalCase().put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.locale("sv").patterns().all().slotAttributes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> audit.locale("nl").patterns().all().slotAttributes().get("case").put("trial", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.locale("nb").packPolicy().runtimeOptions().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.locale("da").samples().caseTaggedAgreementEntries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> audit.locale("da").samples().caseTaggedAgreementEntries().get(0).number().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new GermanicNordicPackAuditJsonLoader(null))
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
        germanicNordicAuditFixtureJson()
            .replace("\"dictionaryEntries\": 586265", "\"dictionaryEntries\": 586265.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNegativeCount() {
    String json =
        germanicNordicAuditFixtureJson()
            .replace("\"dictionaryEntries\": 586265", "\"dictionaryEntries\": -1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dictionaryEntries must be non-negative");
  }

  @Test
  public void rejectsUnexpectedSummaryLocaleCount() {
    String json =
        germanicNordicAuditFixtureJson().replace("\"localeCount\": 4", "\"localeCount\": 3");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("locale count does not match locales");
  }

  @Test
  public void rejectsUnexpectedLocaleOrder() {
    String json =
        replaceRequired(
            germanicNordicAuditFixtureJson(),
            """
                "locales": [
                  "da",
                  "nb",
                  "nl",
                  "sv"
                ],\
            """,
            """
                "locales": [
                  "da",
                  "sv",
                  "nl",
                  "nb"
                ],\
            """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unexpected Germanic/Nordic locale order");
  }

  @Test
  public void rejectsUnsupportedPatternSlotAttributeValue() {
    String json = germanicNordicAuditFixtureJson().replace("\"oblique\": 1", "\"ergative\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Germanic/Nordic slotAttributes.case key");
  }

  @Test
  public void rejectsIncoherentPatternSlotCount() {
    String json =
        germanicNordicAuditFixtureJson().replace("\"caseSlots\": 12131", "\"caseSlots\": 12132");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("case slot count is incoherent");
  }

  @Test
  public void rejectsOpenWorldPackPolicy() {
    String json =
        germanicNordicAuditFixtureJson()
            .replace("\"openWorldGeneration\": false", "\"openWorldGeneration\": true");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not enable open-world generation");
  }

  @Test
  public void rejectsDanishPolicyDrift() {
    String json =
        germanicNordicAuditFixtureJson()
            .replace(
                "\"caseMode\": \"nominative-genitive-explicit-form-key\"",
                "\"caseMode\": \"unsupported-for-nouns-v0\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("explicit genitive policy");
  }

  @Test
  public void rejectsBokmalCaseEvidenceDrift() {
    String json =
        germanicNordicAuditFixtureJson()
            .replace("\"caseTaggedAgreementEntries\": 5", "\"caseTaggedAgreementEntries\": 5000");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Bokmal policy requires sparse case data");
  }

  @Test
  public void rejectsMismatchedProvenanceLabels() {
    String json =
        replaceFirstAfter(
            germanicNordicAuditFixtureJson(),
            "\"sourceLabels\"",
            "dictionary_da.lst",
            "dictionary_da_stale.lst");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source labels must match sources");
  }

  private static String germanicNordicAuditFixtureJson() {
    return readResource(
        "com/box/l10n/mojito/mf2/inflection/germanic_nordic_pack_audit_fixture.json");
  }

  private static String replaceRequired(String text, String target, String replacement) {
    assertThat(text).contains(target);
    return text.replace(target, replacement);
  }

  private static String replaceFirstAfter(
      String text, String anchor, String target, String replacement) {
    int anchorIndex = text.indexOf(anchor);
    assertThat(anchorIndex).as("anchor %s", anchor).isNotNegative();
    int targetIndex = text.indexOf(target, anchorIndex);
    assertThat(targetIndex).as("target %s", target).isNotNegative();
    return text.substring(0, targetIndex)
        + replacement
        + text.substring(targetIndex + target.length());
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
