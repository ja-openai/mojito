package com.box.l10n.mojito.service.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.utils.ServerConfig;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class TranslationIncidentServiceTest {

  private final TranslationIncidentRepository translationIncidentRepository =
      Mockito.mock(TranslationIncidentRepository.class);
  private final BadTranslationLookupService badTranslationLookupService =
      Mockito.mock(BadTranslationLookupService.class);
  private final BadTranslationReviewProjectService badTranslationReviewProjectService =
      Mockito.mock(BadTranslationReviewProjectService.class);
  private final BadTranslationSlackService badTranslationSlackService =
      Mockito.mock(BadTranslationSlackService.class);
  private final BadTranslationSlackMessageComposer badTranslationSlackMessageComposer =
      new BadTranslationSlackMessageComposer();
  private final BadTranslationMutationService badTranslationMutationService =
      Mockito.mock(BadTranslationMutationService.class);
  private final TMTextUnitVariantRepository tmTextUnitVariantRepository =
      Mockito.mock(TMTextUnitVariantRepository.class);
  private final UserService userService = Mockito.mock(UserService.class);
  private final AuditorAwareImpl auditorAwareImpl = Mockito.mock(AuditorAwareImpl.class);
  private final ServerConfig serverConfig = Mockito.mock(ServerConfig.class);

  private final TranslationIncidentService translationIncidentService =
      new TranslationIncidentService(
          translationIncidentRepository,
          badTranslationLookupService,
          badTranslationReviewProjectService,
          badTranslationSlackService,
          badTranslationSlackMessageComposer,
          badTranslationMutationService,
          tmTextUnitVariantRepository,
          userService,
          auditorAwareImpl,
          serverConfig,
          ObjectMapper.withNoFailOnUnknownProperties());

  @Before
  public void setUp() {
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(serverConfig.getUrl()).thenReturn("https://mojito.example/");
    User currentUser = new User();
    currentUser.setUsername("oncall");
    when(auditorAwareImpl.getCurrentAuditor()).thenReturn(Optional.of(currentUser));
    when(translationIncidentRepository.save(any(TranslationIncident.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  public void rejectIncidentUsesStoredCandidateAndUpdatesAuditFields() {
    TranslationIncident incident = new TranslationIncident();
    incident.setId(91L);
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incident.setResolution(TranslationIncidentResolution.READY_TO_REJECT);
    incident.setRepositoryName("chatgpt-web");
    incident.setStringId("string.id");
    incident.setObservedLocale("fr-ca");
    incident.setResolvedLocale("fr-CA");
    incident.setResolvedLocaleId(21L);
    incident.setReason("Malformed ICU");
    incident.setSourceReference("https://buildkite.example/123");
    incident.setLookupResolutionStatus("UNIQUE_MATCH");
    incident.setLocaleResolutionStrategy("NORMALIZED");
    incident.setLookupCandidateCount(1);
    incident.setSelectedTmTextUnitId(11L);
    incident.setSelectedTmTextUnitCurrentVariantId(12L);
    incident.setSelectedTmTextUnitVariantId(13L);
    incident.setSelectedAssetPath("/src/a.ts");
    incident.setSelectedSource("source");
    incident.setSelectedTarget("target");
    incident.setSelectedTargetComment("comment");
    incident.setSelectedTranslationStatus("APPROVED");
    incident.setSelectedIncludedInLocalizedFile(true);
    incident.setSelectedCanReject(true);
    when(translationIncidentRepository.findById(91L)).thenReturn(Optional.of(incident));
    when(badTranslationMutationService.rejectTranslation(any(), any(), any()))
        .thenReturn(
            new BadTranslationMutationService.RejectMutationResult(
                12L, 13L, 112L, 113L, "TRANSLATION_NEEDED", false, 221L, true));

    TranslationIncidentService.IncidentDetail detail =
        translationIncidentService.rejectIncident(
            91L, new TranslationIncidentService.RejectIncidentRequest("duplicate other clause"));

    ArgumentCaptor<TranslationIncident> incidentCaptor =
        ArgumentCaptor.forClass(TranslationIncident.class);
    verify(translationIncidentRepository).save(incidentCaptor.capture());
    assertThat(detail.status()).isEqualTo(TranslationIncidentStatus.CLOSED.name());
    assertThat(detail.resolution()).isEqualTo(TranslationIncidentResolution.REJECTED.name());
    assertThat(detail.rejectAuditCommentId()).isEqualTo(221L);
    assertThat(detail.rejectedByUsername()).isEqualTo("oncall");
    assertThat(detail.closedByUsername()).isEqualTo("oncall");
    assertThat(detail.closedAt()).isNotNull();
    assertThat(detail.incidentLink())
        .isEqualTo("https://mojito.example/translation-incidents?incidentId=91");
    assertThat(detail.selectedTextUnitLink())
        .isEqualTo("https://mojito.example/text-units/11?locale=fr-CA");
    assertThat(incidentCaptor.getValue().getRejectAuditComment())
        .contains("Bad translation incident #91")
        .contains("duplicate other clause");
  }

  @Test
  public void reopenRejectedIncidentClearsClosedFieldsAndKeepsRejectedStatus() {
    TranslationIncident incident = new TranslationIncident();
    incident.setId(92L);
    incident.setStatus(TranslationIncidentStatus.CLOSED);
    incident.setResolution(TranslationIncidentResolution.REJECTED);
    incident.setClosedByUsername("admin");
    incident.setClosedAt(java.time.ZonedDateTime.now());
    when(translationIncidentRepository.findById(92L)).thenReturn(Optional.of(incident));

    TranslationIncidentService.IncidentDetail detail =
        translationIncidentService.updateStatus(
            92L,
            new TranslationIncidentService.UpdateStatusRequest(TranslationIncidentStatus.OPEN));

    ArgumentCaptor<TranslationIncident> incidentCaptor =
        ArgumentCaptor.forClass(TranslationIncident.class);
    verify(translationIncidentRepository, Mockito.atLeastOnce()).save(incidentCaptor.capture());
    assertThat(detail.status()).isEqualTo(TranslationIncidentStatus.OPEN.name());
    assertThat(detail.resolution()).isEqualTo(TranslationIncidentResolution.REJECTED.name());
    assertThat(detail.closedAt()).isNull();
    assertThat(detail.closedByUsername()).isNull();
    assertThat(incidentCaptor.getValue().getStatus()).isEqualTo(TranslationIncidentStatus.OPEN);
    assertThat(incidentCaptor.getValue().getClosedAt()).isNull();
    assertThat(incidentCaptor.getValue().getClosedByUsername()).isNull();
  }

  @Test
  public void sendSlackDraftUsesStoredDestinationAndPersistsThread() {
    TranslationIncident incident = new TranslationIncident();
    incident.setId(93L);
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incident.setResolution(TranslationIncidentResolution.READY_TO_REJECT);
    incident.setSlackDestinationSource("TEAM_CHANNEL");
    incident.setSlackClientId("ops");
    incident.setSlackChannelId("C123");
    incident.setSlackCanSend(true);
    incident.setSlackDraft("Please re-review this translation.");
    incident.setReviewProjectRequestId(301L);
    when(translationIncidentRepository.findById(93L)).thenReturn(Optional.of(incident));
    when(badTranslationSlackService.sendMessage(any(), any()))
        .thenReturn(
            new BadTranslationSlackDispatch(
                true,
                true,
                "1744780000.1000",
                "1744780000.1000",
                "Slack message posted to channel"));

    TranslationIncidentService.IncidentDetail detail =
        translationIncidentService.sendSlackDraft(93L);

    ArgumentCaptor<TranslationIncident> incidentCaptor =
        ArgumentCaptor.forClass(TranslationIncident.class);
    verify(translationIncidentRepository, Mockito.atLeastOnce()).save(incidentCaptor.capture());
    verify(badTranslationSlackService)
        .sendMessage(
            any(BadTranslationSlackService.SlackContext.class),
            Mockito.eq("Please re-review this translation."));
    assertThat(detail.slackThreadTs()).isEqualTo("1744780000.1000");
    assertThat(detail.slackNote()).isEqualTo("Slack message posted to channel");
    assertThat(incidentCaptor.getValue().getSlackThreadTs()).isEqualTo("1744780000.1000");
    assertThat(incidentCaptor.getValue().getSlackNote())
        .isEqualTo("Slack message posted to channel");
  }
}
