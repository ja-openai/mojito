package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.RussianCasePackAuditJsonLoader.RussianCasePackAudit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class RussianCasePackAuditJsonLoaderTest {

  private final RussianCasePackAuditJsonLoader loader = new RussianCasePackAuditJsonLoader();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new RussianCasePackAuditJsonLoader(null))
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
  public void loadsGeneratedFixtureAndValidatesCountsAndConflictPolicy() {
    RussianCasePackAudit audit = loader.load(russianCasePackAuditFixtureJson());

    assertThat(audit.schema()).isEqualTo("mojito-mf2-inflection/ru-case-pack-audit/v0");
    assertThat(audit.locale()).isEqualTo("ru");
    assertThat(audit.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(audit.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/ru_case_pack_audit.py");
    assertThat(audit.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_ru.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_ru.xml");
    assertThat(audit.sources()).hasSize(2);
    assertThat(audit.sources().get(0).byteSize()).isEqualTo(76320564L);
    assertThat(audit.sources().get(0).sha256())
        .isEqualTo("0cec5900694f7a7e1361ab95b3e47bca9114079b6ab8b48531c355eb8862bfeb");
    assertThat(audit.sources().get(1).byteSize()).isEqualTo(1500300L);
    assertThat(audit.sources().get(1).sha256())
        .isEqualTo("8fade258e582840840daa29bd6971a61a090feb33268b303582c10bd4b08b00a");

    assertThat(audit.counts().dictionaryEntries()).isEqualTo(914690);
    assertThat(audit.counts().supportedEntries()).isEqualTo(911004);
    assertThat(audit.counts().uniqueSupportedSurfaces()).isEqualTo(910896);
    assertThat(audit.counts().ambiguousSupportedSurfaces()).isEqualTo(212141);
    assertThat(audit.counts().nominativeSingularCandidates()).isEqualTo(150023);
    assertThat(audit.counts().completeCaseFormCandidates()).isEqualTo(68171);
    assertThat(audit.counts().missingInflectionPatterns()).isZero();
    assertThat(audit.counts().usedInflectionalPatterns()).isEqualTo(749);

    assertThat(audit.features().grammaticalCase()).containsEntry("prepositional", 193515);
    assertThat(audit.features().animacy()).containsEntry("animate", 215358);
    assertThat(audit.features().partOfSpeech()).containsEntry("proper-noun", 419);
    assertThat(audit.features().caseFormCandidateSkipReasons())
        .containsEntry("conflicting-form-key", 26995)
        .containsEntry("suffix-mismatch", 49554);
    assertThat(audit.features().conflictingFormKeys())
        .containsEntry("instrumental.singular", 26981)
        .containsEntry("prepositional.singular", 482);
    assertThat(audit.features().conflictingKeysPerTerm())
        .containsEntry("1", 26264)
        .containsEntry("6", 483);
    assertThat(audit.features().patternSlotCases()).containsEntry("partitive", 38);
    assertThat(audit.features().patternSlotAnimacy()).containsEntry("human", 12);

    assertThat(audit.reviewPolicy().runtimeExport()).isEqualTo("closed-world-case-forms");
    assertThat(audit.reviewPolicy().automaticExportTerms()).isEqualTo(68169);
    assertThat(audit.reviewPolicy().reviewRequiredSurfaces()).isEqualTo(212141);
    assertThat(audit.reviewPolicy().blockedDictionaryEntries()).isEqualTo(846521);
    assertThat(audit.reviewPolicy().reviewRequiredReasons())
        .containsEntry("multiple-cases", 198758);
    assertThat(audit.reviewPolicy().blockedReasons())
        .containsEntry("duplicate-term-id", 2)
        .containsEntry("not-nominative", 702128);

    assertThat(audit.sizeEstimates().metadataStringPoolBytes()).isEqualTo(3158445);
    assertThat(audit.sizeEstimates().metadataRowBytes()).isEqualTo(1800276);
    assertThat(audit.sizeEstimates().metadataLowerBoundBytes()).isEqualTo(4958721);
    assertThat(audit.sizeEstimates().caseFormRowsIfEager()).isEqualTo(818052);
    assertThat(audit.sizeEstimates().caseFormRowBytesIfEager()).isEqualTo(9816624);

    assertThat(audit.samples().ambiguousSurfaces()).hasSize(8);
    assertThat(audit.samples().nominativeSingularCandidates()).hasSize(8);
    assertThat(audit.samples().conflictingCaseFormCandidates()).hasSize(8);
    assertThat(audit.samples().missingInflectionPatterns()).isEmpty();
    assertThat(audit.samples().conflictingCaseFormCandidates().get(0).surface())
        .isEqualTo("Аляска");
    assertThat(audit.samples().conflictingCaseFormCandidates().get(0).inflectionPattern())
        .isEqualTo("1e0");
    assertThat(audit.samples().conflictingCaseFormCandidates().get(0).conflicts().get(0).formKey())
        .isEqualTo("instrumental.singular");
    assertThat(
            audit.samples().conflictingCaseFormCandidates().get(0).conflicts().get(0).firstValue())
        .isEqualTo("Аляской");
    assertThat(
            audit
                .samples()
                .conflictingCaseFormCandidates()
                .get(0)
                .conflicts()
                .get(0)
                .variantValue())
        .isEqualTo("Аляскою");
  }

  @Test
  public void loadedAuditCollectionsAreImmutable() {
    RussianCasePackAudit audit = loader.load(russianCasePackAuditFixtureJson());

    assertThatThrownBy(() -> audit.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.features().grammaticalCase().put("dative", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.reviewPolicy().blockedReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().ambiguousSurfaces().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().ambiguousSurfaces().get(0).entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.samples().conflictingCaseFormCandidates().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> audit.samples().conflictingCaseFormCandidates().get(0).conflicts().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsUnexpectedLocale() {
    String json =
        russianCasePackAuditFixtureJson().replace("\"locale\": \"ru\"", "\"locale\": \"sr\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected locale ru");
  }

  @Test
  public void rejectsUnsupportedPatternSlotCaseValue() {
    String json =
        russianCasePackAuditFixtureJson().replace("\"partitive\": 38", "\"ablative\": 38");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Russian patternSlotCases key");
  }

  @Test
  public void rejectsUnsupportedConflictFormKey() {
    String json =
        russianCasePackAuditFixtureJson()
            .replace(
                "\"formKey\": \"instrumental.singular\"", "\"formKey\": \"ablative.singular\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Russian case-form key");
  }

  @Test
  public void rejectsNonIntegralCount() {
    String json =
        russianCasePackAuditFixtureJson()
            .replace("\"dictionaryEntries\": 914690", "\"dictionaryEntries\": 914690.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json =
        russianCasePackAuditFixtureJson()
            .replace("\"prepositional\": 193515", "\"prepositional\": 193515.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: case.prepositional");
  }

  @Test
  public void rejectsNonIntegralSampleLine() {
    String json = russianCasePackAuditFixtureJson().replace("\"line\": 482", "\"line\": 482.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json =
        russianCasePackAuditFixtureJson().replace("\"byteSize\": 76320564", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsIncoherentSkipCounts() {
    String json =
        russianCasePackAuditFixtureJson()
            .replace(
                "\"completeCaseFormCandidates\": 68171", "\"completeCaseFormCandidates\": 68170");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("skip counts do not close");
  }

  @Test
  public void rejectsIncoherentConflictDistribution() {
    String json = russianCasePackAuditFixtureJson().replace("\"1\": 26264", "\"1\": 26263");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting-key distribution");
  }

  @Test
  public void rejectsIncoherentSizeEstimate() {
    String json =
        russianCasePackAuditFixtureJson()
            .replace("\"metadataRowBytes\": 1800276", "\"metadataRowBytes\": 1800277");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metadata row byte estimate");
  }

  @Test
  public void rejectsIncoherentReviewPolicy() {
    String json =
        russianCasePackAuditFixtureJson()
            .replace("\"automaticExportTerms\": 68169", "\"automaticExportTerms\": 68170");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate term-id count");
  }

  private String russianCasePackAuditFixtureJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/ru_case_pack_audit_fixture.json");
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
