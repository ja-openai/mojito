package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.GlossaryTermInflectionProfile;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.Mf2TermRenderer;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader.TermInflectionProfilePack;
import com.box.l10n.mojito.service.security.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryTermInflectionProfileServiceTest {

  private static final Long GLOSSARY_ID = 1L;
  private static final Long TM_TEXT_UNIT_ID = 2L;
  private static final Long GLOSSARY_TERM_METADATA_ID = 3L;

  @Mock GlossaryTermInflectionProfileRepository profileRepository;
  @Mock GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  @Mock UserService userService;

  private ObjectMapper objectMapper;
  private GlossaryTermInflectionProfileService service;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
    service =
        new GlossaryTermInflectionProfileService(
            profileRepository, glossaryTermMetadataRepository, userService, objectMapper);
  }

  @Test
  public void upsertProfileCanonicalizesAndValidatesApprovedProfile() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(
            GLOSSARY_ID, TM_TEXT_UNIT_ID))
        .thenReturn(Optional.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "fr"))
        .thenReturn(Optional.empty());
    when(profileRepository.save(any(GlossaryTermInflectionProfile.class)))
        .thenAnswer(
            invocation -> {
              GlossaryTermInflectionProfile profile = invocation.getArgument(0);
              profile.setId(4L);
              return profile;
            });

    GlossaryTermInflectionProfileService.InflectionProfileView view =
        service.upsertProfileForSystem(
            GLOSSARY_ID,
            TM_TEXT_UNIT_ID,
            new GlossaryTermInflectionProfileService.InflectionProfileInput(
                "fr",
                "approved",
                """
                {"partOfSpeech": "noun", "gender": "feminine", "number": "singular"}
                """,
                """
                {"bare.singular": "epee de fer", "definite.singular": "l'epee de fer"}
                """,
                null,
                """
                {"source": "manual"}
                """));

    JsonNode morphology = objectMapper.readTreeUnchecked(view.morphologyJson());
    JsonNode forms = objectMapper.readTreeUnchecked(view.formsJson());
    JsonNode diagnostics = objectMapper.readTreeUnchecked(view.diagnosticsJson());

    assertThat(view.id()).isEqualTo(4L);
    assertThat(view.glossaryTermMetadataId()).isEqualTo(GLOSSARY_TERM_METADATA_ID);
    assertThat(view.tmTextUnitId()).isEqualTo(TM_TEXT_UNIT_ID);
    assertThat(view.termId()).isEqualTo("item.iron_sword");
    assertThat(view.source()).isEqualTo("iron sword");
    assertThat(view.localeTag()).isEqualTo("fr");
    assertThat(view.schema()).isEqualTo(TermInflectionProfilePackJsonLoader.EXPECTED_SCHEMA);
    assertThat(view.status()).isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_APPROVED);
    assertThat(morphology.get("gender").asText()).isEqualTo("feminine");
    assertThat(forms.get("definite.singular").asText()).isEqualTo("l'epee de fer");
    assertThat(diagnostics).isEmpty();
  }

  @Test
  public void upsertProfileRejectsApprovedDiagnosticsBeforeSave() {
    GlossaryTermMetadata metadata = metadata("item.warning", "warning");
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(
            GLOSSARY_ID, TM_TEXT_UNIT_ID))
        .thenReturn(Optional.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "fr"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.upsertProfileForSystem(
                    GLOSSARY_ID,
                    TM_TEXT_UNIT_ID,
                    new GlossaryTermInflectionProfileService.InflectionProfileInput(
                        "fr",
                        "APPROVED",
                        """
                        {"partOfSpeech": "noun"}
                        """,
                        """
                        {"bare.singular": "warning"}
                        """,
                        """
                        [{"code": "ambiguous", "message": "Needs review"}]
                        """,
                        """
                        {"source": "manual"}
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Approved inflection profile cannot include diagnostics");

    verify(profileRepository, never()).save(any(GlossaryTermInflectionProfile.class));
  }

  @Test
  public void reviewProfileForSystemApprovesGeneratedProfileWithoutRepostingForms() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermInflectionProfile profile =
        profile(
            metadata,
            TermInflectionProfilePackJsonLoader.STATUS_GENERATED,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {"bare.singular": "epee de fer"}
            """,
            "[]",
            "{\"source\":\"dictionary-prefill\"}");
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(
            GLOSSARY_ID, TM_TEXT_UNIT_ID))
        .thenReturn(Optional.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "fr"))
        .thenReturn(Optional.of(profile));
    when(profileRepository.save(any(GlossaryTermInflectionProfile.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    GlossaryTermInflectionProfileService.InflectionProfileView view =
        service.reviewProfileForSystem(
            GLOSSARY_ID,
            TM_TEXT_UNIT_ID,
            new GlossaryTermInflectionProfileService.InflectionProfileReviewInput(
                "fr", "approved", null, null, null, null));

    ArgumentCaptor<GlossaryTermInflectionProfile> profileCaptor =
        ArgumentCaptor.forClass(GlossaryTermInflectionProfile.class);
    verify(profileRepository).save(profileCaptor.capture());

    assertThat(view.status()).isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_APPROVED);
    assertThat(view.formsJson()).contains("epee de fer");
    assertThat(profileCaptor.getValue().getStatus())
        .isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_APPROVED);
    assertThat(profileCaptor.getValue().getProvenanceJson()).contains("dictionary-prefill");
  }

  @Test
  public void reviewProfileForSystemCanReplaceFormsAndApproveReviewedHebrewRows() {
    GlossaryTermMetadata metadata = metadata("he.reviewed.hand", "יד");
    GlossaryTermInflectionProfile profile =
        profile(
            metadata,
            TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED,
            """
            {"partOfSpeech": "noun", "gender": "feminine", "number": "singular"}
            """,
            """
            {
              "bare.singular": "יד",
              "bare.plural": "ידיים",
              "construct.singular": "יד",
              "construct.plural": "ידי"
            }
            """,
            """
            [{
              "reason": "missing-form-cell",
              "formKey": "construct.dual"
            }]
            """,
            "{\"source\":\"dictionary-prefill\"}");
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(
            GLOSSARY_ID, TM_TEXT_UNIT_ID))
        .thenReturn(Optional.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "he"))
        .thenReturn(Optional.of(profile));
    when(profileRepository.save(any(GlossaryTermInflectionProfile.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    GlossaryTermInflectionProfileService.InflectionProfileView view =
        service.reviewProfileForSystem(
            GLOSSARY_ID,
            TM_TEXT_UNIT_ID,
            new GlossaryTermInflectionProfileService.InflectionProfileReviewInput(
                "he",
                "APPROVED",
                """
                {"partOfSpeech": "noun", "gender": "feminine", "number": "singular"}
                """,
                """
                {
                  "bare.singular": "יד",
                  "bare.plural": "ידיים",
                  "construct.singular": "יד",
                  "construct.plural": "ידי",
                  "construct.dual": "ידי"
                }
                """,
                "[]",
                "{\"reviewedBy\":\"translator\"}"));

    ArgumentCaptor<GlossaryTermInflectionProfile> profileCaptor =
        ArgumentCaptor.forClass(GlossaryTermInflectionProfile.class);
    verify(profileRepository).save(profileCaptor.capture());

    assertThat(view.status()).isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_APPROVED);
    assertThat(view.formsJson()).contains("\"construct.dual\":\"ידי\"");
    assertThat(view.diagnosticsJson()).isEqualTo("[]");
    assertThat(profileCaptor.getValue().getFormsJson()).contains("\"construct.dual\":\"ידי\"");
    assertThat(profileCaptor.getValue().getProvenanceJson()).contains("translator");
  }

  @Test
  public void reviewProfileForSystemRejectsApprovalWithRemainingDiagnostics() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermInflectionProfile profile =
        profile(
            metadata,
            TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {"bare.singular": "epee de fer"}
            """,
            """
            [{"code": "ambiguous", "message": "Needs review"}]
            """,
            "{\"source\":\"dictionary-prefill\"}");
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(
            GLOSSARY_ID, TM_TEXT_UNIT_ID))
        .thenReturn(Optional.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "fr"))
        .thenReturn(Optional.of(profile));

    assertThatThrownBy(
            () ->
                service.reviewProfileForSystem(
                    GLOSSARY_ID,
                    TM_TEXT_UNIT_ID,
                    new GlossaryTermInflectionProfileService.InflectionProfileReviewInput(
                        "fr", "APPROVED", null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Approved inflection profile cannot include diagnostics");

    verify(profileRepository, never()).save(any(GlossaryTermInflectionProfile.class));
  }

  @Test
  public void compileProfilePackForSystemReturnsRendererReadyPack() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermInflectionProfile profile =
        approvedProfile(
            metadata,
            """
            {"partOfSpeech": "noun", "gender": "feminine", "number": "singular"}
            """,
            """
            {
              "bare.singular": "epee de fer",
              "count.one": "epee de fer",
              "count.other": "epees de fer"
            }
            """);
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "fr"))
        .thenReturn(List.of(profile));

    Mf2TermRenderer renderer =
        Mf2TermRenderer.forCompiledTerms(service.compileProfilePackForSystem(GLOSSARY_ID, "fr"));

    assertThat(renderer.renderTerm("item.iron_sword", Map.of(), Map.of())).isEqualTo("epee de fer");
    assertThat(
            renderer.renderMessage(
                "Total: {$item :term count=$count}.",
                Map.of("item", "item.iron_sword"),
                Map.of("count", "2")))
        .isEqualTo("Total: epees de fer.");
  }

  @Test
  public void compileProfilePackExportForSystemRendersApprovedTurkishExplicitTemplateRows() {
    GlossaryTermMetadata metadata = metadata("tr.explicit.çakmak", "çakmak");
    GlossaryTermInflectionProfile profile =
        approvedProfile(
            metadata,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {
              "bare.singular": "çakmak",
              "accusative.singular": "çakmağı",
              "dative.singular": "çakmağa",
              "count.other": "çakmaklar"
            }
            """);
    profile.setLocaleTag("tr");
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "tr"))
        .thenReturn(List.of(profile));

    GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport export =
        service.compileProfilePackExportForSystem(GLOSSARY_ID, "tr");

    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(export.pack());
    assertThat(export.approvedProfileCount()).isEqualTo(1);
    assertThat(export.skippedProfileCount()).isZero();
    assertGlossaryApprovedExportPolicy(export, 1);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative}.",
                Map.of("item", "tr.explicit.çakmak"),
                Map.of()))
        .isEqualTo("Silindi çakmağı.");
    assertThat(
            renderer.renderMessage(
                "Gönderildi {$item :term case=dative}.",
                Map.of("item", "tr.explicit.çakmak"),
                Map.of()))
        .isEqualTo("Gönderildi çakmağa.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.çakmak"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi çakmaklar.");
  }

  @Test
  public void compileProfilePackExportForSystemRendersApprovedHebrewConstructDualRows() {
    GlossaryTermMetadata metadata = metadata("he.reviewed.hand", "יד");
    GlossaryTermInflectionProfile profile =
        approvedProfile(
            metadata,
            """
            {"partOfSpeech": "noun", "gender": "feminine", "number": "singular"}
            """,
            """
            {
              "bare.singular": "יד",
              "bare.plural": "ידיים",
              "construct.singular": "יד",
              "construct.plural": "ידי",
              "construct.dual": "ידי"
            }
            """);
    profile.setLocaleTag("he");
    profile.setProvenanceJson(
        """
        {"reviewedBy": "translator", "reason": "product-reviewed-complete-construct-state"}
        """);
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "he"))
        .thenReturn(List.of(profile));

    GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport export =
        service.compileProfilePackExportForSystem(GLOSSARY_ID, "he");

    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(export.pack());
    assertThat(export.approvedProfileCount()).isEqualTo(1);
    assertThat(export.skippedProfileCount()).isZero();
    assertGlossaryApprovedExportPolicy(export, 1);
    assertThat(
            renderer.renderMessage(
                "נבחרו {$item :term definiteness=construct number=dual}.",
                Map.of("item", "he.reviewed.hand"),
                Map.of()))
        .isEqualTo("נבחרו ידי.");
  }

  @Test
  public void compileProfilePackExportForSystemRendersApprovedArabicExplicitRows() {
    GlossaryTermMetadata metadata = metadata("ar.explicit.message", "رسالة");
    GlossaryTermInflectionProfile profile =
        approvedProfile(
            metadata,
            """
            {"partOfSpeech": "noun", "gender": "feminine", "number": "singular"}
            """,
            """
            {
              "indefinite.nominative.singular": "رسالة",
              "indefinite.accusative.singular": "رسالة",
              "indefinite.genitive.singular": "رسالة",
              "construct.nominative.singular": "رسالة",
              "construct.accusative.singular": "رسالة",
              "construct.genitive.singular": "رسالة",
              "indefinite.nominative.dual": "رسالتان",
              "indefinite.accusative.dual": "رسالتين",
              "indefinite.genitive.dual": "رسالتين",
              "construct.nominative.dual": "رسالتا",
              "construct.accusative.dual": "رسالتي",
              "construct.genitive.dual": "رسالتي",
              "indefinite.nominative.plural": "رسائل",
              "indefinite.accusative.plural": "رسائل",
              "indefinite.genitive.plural": "رسائل",
              "construct.nominative.plural": "رسائل",
              "construct.accusative.plural": "رسائل",
              "construct.genitive.plural": "رسائل"
            }
            """);
    profile.setLocaleTag("ar");
    profile.setProvenanceJson(
        """
        {"reviewedBy": "translator", "reason": "product-reviewed-complete-explicit-forms"}
        """);
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "ar"))
        .thenReturn(List.of(profile));

    GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport export =
        service.compileProfilePackExportForSystem(GLOSSARY_ID, "ar");

    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(export.pack());
    assertThat(export.approvedProfileCount()).isEqualTo(1);
    assertThat(export.skippedProfileCount()).isZero();
    assertGlossaryApprovedExportPolicy(export, 1);
    assertThat(
            renderer.renderMessage(
                "اختيرت {$item :term definiteness=construct case=genitive number=dual}.",
                Map.of("item", "ar.explicit.message"),
                Map.of()))
        .isEqualTo("اختيرت رسالتي.");
    assertThat(
            renderer.renderMessage(
                "حُذفت {$item :term definiteness=indefinite case=genitive number=plural}.",
                Map.of("item", "ar.explicit.message"),
                Map.of()))
        .isEqualTo("حُذفت رسائل.");
  }

  @Test
  public void compileProfilePackExportForSystemRendersApprovedMalayalamCaseRows() {
    GlossaryTermMetadata metadata = metadata("ml.case.father", "പിതാവ്");
    GlossaryTermInflectionProfile profile =
        approvedProfile(
            metadata,
            """
            {"partOfSpeech": "noun", "gender": "masculine", "number": "singular"}
            """,
            """
            {
              "nominative.singular": "പിതാവ്",
              "nominative.plural": "പിതാക്കന്മാർ",
              "accusative.singular": "പിതാവിനെ",
              "accusative.plural": "പിതാവുകളെ",
              "dative.singular": "പിതാവിനു്",
              "dative.plural": "പിതാവുകൾക്കു്",
              "genitive.singular": "പിതാവിന്റെ",
              "genitive.plural": "പിതാവുകളുടെ",
              "instrumental.singular": "പിതാവാൽ",
              "instrumental.plural": "പിതാവുകളാൽ",
              "locative.singular": "പിതാവിങ്കൽ",
              "locative.plural": "പിതാവുകളിങ്കൽ",
              "sociative.singular": "പിതാവിനോടു്",
              "sociative.plural": "പിതാക്കന്മാരോട്",
              "vocative.singular": "പിതാവേ",
              "vocative.plural": "പിതാക്കന്മാരേ"
            }
            """);
    profile.setLocaleTag("ml");
    profile.setProvenanceJson(
        """
        {"reviewedBy": "translator", "reason": "product-reviewed-complete-case-forms"}
        """);
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "ml"))
        .thenReturn(List.of(profile));

    GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport export =
        service.compileProfilePackExportForSystem(GLOSSARY_ID, "ml");

    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(export.pack());
    assertThat(export.approvedProfileCount()).isEqualTo(1);
    assertThat(export.skippedProfileCount()).isZero();
    assertGlossaryApprovedExportPolicy(export, 1);
    assertThat(
            renderer.renderMessage(
                "വിളിച്ചത് {$item :term case=vocative}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("വിളിച്ചത് പിതാവേ.");
    assertThat(
            renderer.renderMessage(
                "കൂടെ {$item :term case=sociative number=plural}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("കൂടെ പിതാക്കന്മാരോട്.");
  }

  @Test
  public void compileProfilePackExportForSystemReportsDisabledProfilesSkipped() {
    GlossaryTermMetadata approvedMetadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermMetadata disabledMetadata = metadata("item.warning", "warning");
    GlossaryTermInflectionProfile approvedProfile =
        approvedProfile(
            approvedMetadata,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {"bare.singular": "epee de fer"}
            """);
    GlossaryTermInflectionProfile disabledProfile =
        profile(
            disabledMetadata,
            TermInflectionProfilePackJsonLoader.STATUS_DISABLED,
            """
            {"partOfSpeech": "noun"}
            """,
            "{}",
            """
            [{
              "termId": "item.warning",
              "code": "missing-form-cell",
              "reason": "missing-form-cell",
              "formKey": "construct.genitive.dual",
              "message": "Needs translator-approved forms"
            }]
            """,
            "{\"source\":\"dictionary-prefill\"}");
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "fr"))
        .thenReturn(List.of(disabledProfile, approvedProfile));

    GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport export =
        service.compileProfilePackExportForSystem(GLOSSARY_ID, "fr");

    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(export.pack());
    assertThat(export.approvedProfileCount()).isEqualTo(1);
    assertThat(export.skippedProfileCount()).isEqualTo(1);
    assertThat(export.exportPolicy()).isEqualTo(export.pack().exportPolicy());
    assertThat(export.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-glossary-approved-profile-forms");
    assertThat(export.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(export.exportPolicy().automaticExportTerms()).isEqualTo(1);
    assertThat(export.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(export.exportPolicy().blockedTerms()).isEqualTo(1);
    assertThat(export.exportPolicy().blockedReasons()).containsEntry("disabled-profile", 1);
    assertThat(export.skippedProfiles().getFirst().termId()).isEqualTo("item.warning");
    assertThat(export.skippedProfiles().getFirst().status())
        .isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_DISABLED);
    assertThat(export.skippedProfiles().getFirst().diagnosticCount()).isEqualTo(1);
    assertThat(export.skippedProfiles().getFirst().missingFormKeys())
        .containsExactly("construct.genitive.dual");
    assertThat(export.skippedProfiles().getFirst().diagnosticSummaries()).hasSize(1);
    assertThat(export.skippedProfiles().getFirst().diagnosticSummaries().getFirst().termId())
        .isEqualTo("item.warning");
    assertThat(export.skippedProfiles().getFirst().diagnosticSummaries().getFirst().formKey())
        .isEqualTo("construct.genitive.dual");
    assertThat(renderer.renderTerm("item.iron_sword", Map.of(), Map.of())).isEqualTo("epee de fer");
    assertThatThrownBy(() -> renderer.renderTerm("item.warning", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing compiled term: item.warning");
  }

  @Test
  public void compileProfilePackForSystemRejectsGeneratedProfilesWithPolicyDiagnostic() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermInflectionProfile profile =
        profile(
            metadata,
            TermInflectionProfilePackJsonLoader.STATUS_GENERATED,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {"bare.singular": "epee de fer"}
            """,
            "[]",
            "{}");
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "fr"))
        .thenReturn(List.of(profile));

    assertThatThrownBy(() -> service.compileProfilePackForSystem(GLOSSARY_ID, "fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot compile inflection profile pack for locale fr")
        .hasMessageContaining("item.iron_sword=GENERATED")
        .hasMessageContaining("Approve or disable generated/review-needed profiles");
  }

  @Test
  public void profilePackForSystemPreservesProfileProvenanceAndRollsUpSources() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermInflectionProfile profile =
        profile(
            metadata,
            TermInflectionProfilePackJsonLoader.STATUS_APPROVED,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {"bare.singular": "epee de fer"}
            """,
            "[]",
            """
            {
              "source": "dictionary-prefill",
              "sourceLabels": ["unicode-fr"],
              "sources": [{
                "path": "dictionary_fr.lst",
                "byteSize": 12,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "gitLfsPointer": false
              }]
            }
            """);
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "fr"))
        .thenReturn(List.of(profile));

    TermInflectionProfilePack pack = service.profilePackForSystem(GLOSSARY_ID, "fr");

    assertThat(pack.provenance().sourceLabels()).containsExactly("unicode-fr");
    assertThat(pack.provenance().sources().getFirst().path()).isEqualTo("dictionary_fr.lst");
    assertThat(pack.profiles().getFirst().provenance().get("source").asText())
        .isEqualTo("dictionary-prefill");
  }

  @Test
  public void profilePackForSystemPreservesButDoesNotRollUpMalformedProfileProvenanceSources() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermInflectionProfile profile =
        profile(
            metadata,
            TermInflectionProfilePackJsonLoader.STATUS_APPROVED,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {"bare.singular": "epee de fer"}
            """,
            "[]",
            """
            {
              "source": "dictionary-prefill",
              "sourceLabels": ["unicode-fr", "extra"],
              "sources": [{
                "path": "dictionary_fr.lst",
                "byteSize": 12,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "gitLfsPointer": false
              }]
            }
            """);
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "fr"))
        .thenReturn(List.of(profile));

    TermInflectionProfilePack pack = service.profilePackForSystem(GLOSSARY_ID, "fr");
    JsonNode rowProvenance = pack.profiles().getFirst().provenance();

    assertThat(pack.provenance().sourceLabels()).isEmpty();
    assertThat(pack.provenance().sources()).isEmpty();
    assertThat(rowProvenance.get("source").asText()).isEqualTo("dictionary-prefill");
    assertThat(rowProvenance.get("sourceLabels")).hasSize(2);
    assertThat(rowProvenance.get("sources")).hasSize(1);
  }

  @Test
  public void profilePackForSystemPreservesButDoesNotRollUpInvalidProfileProvenanceSourceShape() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    GlossaryTermInflectionProfile profile =
        profile(
            metadata,
            TermInflectionProfilePackJsonLoader.STATUS_APPROVED,
            """
            {"partOfSpeech": "noun"}
            """,
            """
            {"bare.singular": "epee de fer"}
            """,
            "[]",
            """
            {
              "source": "dictionary-prefill",
              "sourceLabels": ["unicode-fr", "blank-path"],
              "sources": [{
                "path": "dictionary_fr.lst",
                "byteSize": 12,
                "sha256": "not-a-sha256",
                "gitLfsPointer": false
              }, {
                "path": " ",
                "byteSize": 12,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "gitLfsPointer": false
              }]
            }
            """);
    when(profileRepository.findByGlossaryIdAndLocaleTag(GLOSSARY_ID, "fr"))
        .thenReturn(List.of(profile));

    TermInflectionProfilePack pack = service.profilePackForSystem(GLOSSARY_ID, "fr");
    JsonNode rowProvenance = pack.profiles().getFirst().provenance();

    assertThat(pack.provenance().sourceLabels()).isEmpty();
    assertThat(pack.provenance().sources()).isEmpty();
    assertThat(rowProvenance.get("sourceLabels")).hasSize(2);
    assertThat(rowProvenance.get("sources")).hasSize(2);
  }

  @Test
  public void importProfilePackForSystemCreatesProfilesFromTermIds() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    when(glossaryTermMetadataRepository.findByGlossaryId(GLOSSARY_ID))
        .thenReturn(List.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "fr"))
        .thenReturn(Optional.empty());
    when(profileRepository.save(any(GlossaryTermInflectionProfile.class)))
        .thenAnswer(
            invocation -> {
              GlossaryTermInflectionProfile profile = invocation.getArgument(0);
              profile.setId(4L);
              return profile;
            });

    GlossaryTermInflectionProfileService.InflectionProfileImportResult result =
        service.importProfilePackForSystem(
            GLOSSARY_ID,
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "fr",
              "provenance": {
                "license": "Mojito-authored",
                "generator": "unit-test",
                "sourceLabels": [],
                "sources": []
              },
              "profiles": [{
                "termId": "item.iron_sword",
                "source": "iron sword",
                "status": "APPROVED",
                "morphology": {
                  "partOfSpeech": "noun",
                  "gender": "feminine"
                },
                "forms": {"bare.singular": "epee de fer"},
                "diagnostics": [],
                "provenance": {"source": "dictionary-prefill"}
              }]
            }
            """);

    assertThat(result.localeTag()).isEqualTo("fr");
    assertThat(result.profileCount()).isEqualTo(1);
    assertThat(result.createdProfileCount()).isEqualTo(1);
    assertThat(result.updatedProfileCount()).isZero();
    assertThat(result.profiles().getFirst().termId()).isEqualTo("item.iron_sword");
    assertThat(
            objectMapper
                .readTreeUnchecked(result.profiles().getFirst().provenanceJson())
                .get("source")
                .asText())
        .isEqualTo("dictionary-prefill");
  }

  @Test
  public void importProfilePackForSystemPreservesStructuredMissingFormDiagnostics() {
    GlossaryTermMetadata metadata = metadata("ar.explicit.mother", "أم");
    when(glossaryTermMetadataRepository.findByGlossaryId(GLOSSARY_ID))
        .thenReturn(List.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "ar"))
        .thenReturn(Optional.empty());
    when(profileRepository.save(any(GlossaryTermInflectionProfile.class)))
        .thenAnswer(
            invocation -> {
              GlossaryTermInflectionProfile profile = invocation.getArgument(0);
              profile.setId(4L);
              return profile;
            });

    GlossaryTermInflectionProfileService.InflectionProfileImportResult result =
        service.importProfilePackForSystem(
            GLOSSARY_ID,
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "ar",
              "provenance": {"sourceLabels": [], "sources": []},
              "profiles": [{
                "termId": "ar.explicit.mother",
                "source": "أم",
                "status": "REVIEW_NEEDED",
                "morphology": {"partOfSpeech": "noun", "gender": "feminine"},
                "forms": {"construct.genitive.singular": "أُمِّ"},
                "diagnostics": [{
                  "termId": "ar.explicit.mother",
                  "reason": "missing-form-cell",
                  "formKey": "construct.genitive.dual"
                }]
              }]
            }
            """);

    JsonNode diagnostic =
        objectMapper.readTreeUnchecked(result.profiles().getFirst().diagnosticsJson()).get(0);

    assertThat(result.localeTag()).isEqualTo("ar");
    assertThat(result.profiles().getFirst().status())
        .isEqualTo(TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED);
    assertThat(diagnostic.get("code").asText()).isEqualTo("missing-form-cell");
    assertThat(diagnostic.get("reason").asText()).isEqualTo("missing-form-cell");
    assertThat(diagnostic.get("formKey").asText()).isEqualTo("construct.genitive.dual");
    assertThat(diagnostic.get("termId").asText()).isEqualTo("ar.explicit.mother");
  }

  @Test
  public void importProfilePackForSystemUsesPackProvenanceWhenProfileProvenanceIsMissing() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    when(glossaryTermMetadataRepository.findByGlossaryId(GLOSSARY_ID))
        .thenReturn(List.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "fr"))
        .thenReturn(Optional.empty());
    when(profileRepository.save(any(GlossaryTermInflectionProfile.class)))
        .thenAnswer(
            invocation -> {
              GlossaryTermInflectionProfile profile = invocation.getArgument(0);
              profile.setId(4L);
              return profile;
            });

    GlossaryTermInflectionProfileService.InflectionProfileImportResult result =
        service.importProfilePackForSystem(
            GLOSSARY_ID,
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "fr",
              "provenance": {
                "license": "Unicode-3.0",
                "generator": "dev-docs/experiments/mf2-inflection/fr_profile_prefill.py",
                "sourceLabels": ["unicode-fr"],
                "sources": [{
                  "path": "dictionary_fr.lst",
                  "byteSize": 12,
                    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "gitLfsPointer": false
                }]
              },
              "profiles": [{
                "termId": "item.iron_sword",
                "source": "iron sword",
                "status": "APPROVED",
                "morphology": {"partOfSpeech": "noun"},
                "forms": {"bare.singular": "epee de fer"},
                "diagnostics": []
              }]
            }
            """);
    JsonNode provenance =
        objectMapper.readTreeUnchecked(result.profiles().getFirst().provenanceJson());

    assertThat(provenance.get("license").asText()).isEqualTo("Unicode-3.0");
    assertThat(provenance.get("generator").asText())
        .isEqualTo("dev-docs/experiments/mf2-inflection/fr_profile_prefill.py");
    assertThat(provenance.get("sourceLabels").get(0).asText()).isEqualTo("unicode-fr");
    assertThat(provenance.get("sources").get(0).get("path").asText())
        .isEqualTo("dictionary_fr.lst");
  }

  @Test
  public void importProfilePackForSystemRejectsUnknownTermsBeforeSave() {
    when(glossaryTermMetadataRepository.findByGlossaryId(GLOSSARY_ID))
        .thenReturn(List.of(metadata("item.shield", "shield")));

    assertThatThrownBy(
            () ->
                service.importProfilePackForSystem(
                    GLOSSARY_ID,
                    """
                    {
                      "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
                      "locale": "fr",
                      "provenance": {"sourceLabels": [], "sources": []},
                      "profiles": [{
                        "termId": "item.iron_sword",
                        "source": "iron sword",
                        "status": "APPROVED",
                        "morphology": {"partOfSpeech": "noun"},
                        "forms": {"bare.singular": "epee de fer"},
                        "diagnostics": []
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Glossary term metadata not found for imported inflection profile: item.iron_sword");

    verify(profileRepository, never()).save(any(GlossaryTermInflectionProfile.class));
  }

  @Test
  public void importProfilePackForSystemValidatesEntirePackBeforeSavingAnyProfile() {
    GlossaryTermMetadata metadata = metadata("item.iron_sword", "iron sword");
    when(glossaryTermMetadataRepository.findByGlossaryId(GLOSSARY_ID))
        .thenReturn(List.of(metadata));
    when(profileRepository.findByGlossaryTermMetadataIdAndLocaleTag(
            GLOSSARY_TERM_METADATA_ID, "fr"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.importProfilePackForSystem(
                    GLOSSARY_ID,
                    """
                    {
                      "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
                      "locale": "fr",
                      "provenance": {"sourceLabels": [], "sources": []},
                      "profiles": [
                        {
                          "termId": "item.iron_sword",
                          "source": "iron sword",
                          "status": "APPROVED",
                          "morphology": {"partOfSpeech": "noun"},
                          "forms": {"bare.singular": "epee de fer"},
                          "diagnostics": []
                        },
                        {
                          "termId": "item.missing",
                          "source": "missing",
                          "status": "APPROVED",
                          "morphology": {"partOfSpeech": "noun"},
                          "forms": {"bare.singular": "manquant"},
                          "diagnostics": []
                        }
                      ]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Glossary term metadata not found for imported inflection profile: item.missing");

    verify(profileRepository, never()).save(any(GlossaryTermInflectionProfile.class));
  }

  @Test
  public void importProfilePackForSystemRejectsSourceMismatchBeforeSave() {
    when(glossaryTermMetadataRepository.findByGlossaryId(GLOSSARY_ID))
        .thenReturn(List.of(metadata("item.iron_sword", "iron sword")));

    assertThatThrownBy(
            () ->
                service.importProfilePackForSystem(
                    GLOSSARY_ID,
                    """
                    {
                      "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
                      "locale": "fr",
                      "provenance": {"sourceLabels": [], "sources": []},
                      "profiles": [{
                        "termId": "item.iron_sword",
                        "source": "stale sword",
                        "status": "APPROVED",
                        "morphology": {"partOfSpeech": "noun"},
                        "forms": {"bare.singular": "epee de fer"},
                        "diagnostics": []
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Imported inflection profile source mismatch for term item.iron_sword");

    verify(profileRepository, never()).save(any(GlossaryTermInflectionProfile.class));
  }

  private GlossaryTermMetadata metadata(String termId, String source) {
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(TM_TEXT_UNIT_ID);
    tmTextUnit.setName(termId);
    tmTextUnit.setContent(source);

    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setId(GLOSSARY_TERM_METADATA_ID);
    metadata.setTmTextUnit(tmTextUnit);
    return metadata;
  }

  private GlossaryTermInflectionProfile approvedProfile(
      GlossaryTermMetadata metadata, String morphologyJson, String formsJson) {
    return profile(
        metadata,
        TermInflectionProfilePackJsonLoader.STATUS_APPROVED,
        morphologyJson,
        formsJson,
        "[]",
        "{\"source\":\"manual\"}");
  }

  private void assertGlossaryApprovedExportPolicy(
      GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport export,
      int automaticExportTerms) {
    assertThat(export.exportPolicy()).isEqualTo(export.pack().exportPolicy());
    assertThat(export.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-glossary-approved-profile-forms");
    assertThat(export.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(export.exportPolicy().automaticExportTerms()).isEqualTo(automaticExportTerms);
    assertThat(export.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(export.exportPolicy().blockedTerms()).isZero();
  }

  private GlossaryTermInflectionProfile profile(
      GlossaryTermMetadata metadata,
      String status,
      String morphologyJson,
      String formsJson,
      String diagnosticsJson,
      String provenanceJson) {
    GlossaryTermInflectionProfile profile = new GlossaryTermInflectionProfile();
    profile.setId(4L);
    profile.setGlossaryTermMetadata(metadata);
    profile.setLocaleTag("fr");
    profile.setSchema(TermInflectionProfilePackJsonLoader.EXPECTED_SCHEMA);
    profile.setStatus(status);
    profile.setMorphologyJson(morphologyJson);
    profile.setFormsJson(formsJson);
    profile.setDiagnosticsJson(diagnosticsJson);
    profile.setProvenanceJson(provenanceJson);
    return profile;
  }
}
