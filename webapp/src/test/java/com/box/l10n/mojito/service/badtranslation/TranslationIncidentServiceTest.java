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
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

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
          ObjectMapper.withNoFailOnUnknownProperties(),
          Mockito.mock(TranslationIncidentIntakeService.class),
          Mockito.mock(com.box.l10n.mojito.service.team.TeamService.class));

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
  public void localeFilterUsesDisplayedLocaleBeforePaginationAndCombinesWithOtherFilters() {
    try (var factory =
            new Configuration()
                .addAnnotatedClass(TranslationIncident.class)
                .setProperty(
                    "hibernate.connection.url", "jdbc:hsqldb:mem:incidents-" + UUID.randomUUID())
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        var session = factory.openSession()) {
      var resolved = incident("fr", "fr-CA");
      var unresolved = incident("fr-ca", null);
      unresolved.setCreatedDate(ZonedDateTime.parse("2026-09-20T02:00:00Z"));
      var differentResolvedLocale = incident("fr-CA", "fr");
      var differentRegion = incident("fr", "fr-CAX");
      var closed = incident("fr", "fr-CA");
      closed.setStatus(TranslationIncidentStatus.CLOSED);
      var differentQuery = incident("fr", "fr-CA");
      differentQuery.setStringId("settings.cancel");
      var differentReviewType = incident("fr", "fr-CA");
      differentReviewType.setReviewType("terminology");
      var differentReviewRun = incident("fr", "fr-CA");
      differentReviewRun.setReviewRunId(42L);
      var earlier = incident("fr", "fr-CA");
      earlier.setCreatedDate(ZonedDateTime.parse("2026-09-19T23:59:59Z"));
      var later = incident("fr", "fr-CA");
      later.setCreatedDate(ZonedDateTime.parse("2026-09-21T00:00:00Z"));
      var transaction = session.beginTransaction();
      List.of(
              resolved,
              unresolved,
              differentResolvedLocale,
              differentRegion,
              closed,
              differentQuery,
              differentReviewType,
              differentReviewRun,
              earlier,
              later)
          .forEach(session::persist);
      transaction.commit();
      var repository =
          new SimpleJpaRepository<TranslationIncident, Long>(TranslationIncident.class, session);
      when(translationIncidentRepository.findAll(
              Mockito.<Specification<TranslationIncident>>any(), any(Pageable.class)))
          .thenAnswer(
              invocation ->
                  repository.findAll(
                      invocation.<Specification<TranslationIncident>>getArgument(0),
                      invocation.<Pageable>getArgument(1)));

      var localeOnly =
          translationIncidentService.getIncidents(
              null, null, null, null, 0, 2, null, null, " FR-ca ");
      assertThat(localeOnly.totalElements()).isEqualTo(8);
      assertThat(localeOnly.totalPages()).isEqualTo(4);
      assertThat(localeOnly.items()).hasSize(2);
      var firstPage = filteredIncidents(0);
      assertThat(firstPage.items())
          .extracting(TranslationIncidentService.IncidentSummary::id)
          .containsExactly(unresolved.getId());
      assertThat(firstPage.totalElements()).isEqualTo(2);
      assertThat(firstPage.totalPages()).isEqualTo(2);
      assertThat(firstPage.hasNext()).isTrue();
      var secondPage = filteredIncidents(1);
      assertThat(secondPage.items())
          .extracting(TranslationIncidentService.IncidentSummary::id)
          .containsExactly(resolved.getId());
      assertThat(secondPage.hasPrevious()).isTrue();
      assertThat(secondPage.hasNext()).isFalse();
      assertThat(
              translationIncidentService
                  .getIncidents(null, null, null, null, 0, 25, null, null, "fr")
                  .items())
          .extracting(TranslationIncidentService.IncidentSummary::id)
          .containsExactly(differentResolvedLocale.getId());
      assertThat(
              translationIncidentService
                  .getIncidents(null, null, null, null, 0, 25, null, null, "fr-CA%")
                  .totalElements())
          .isZero();
      assertThat(
              translationIncidentService
                  .getIncidents(null, null, null, null, 0, 25, null, null, " ")
                  .totalElements())
          .isEqualTo(10);
      assertThat(
              translationIncidentService
                  .getIncidents(null, null, null, null, 0, 25, null, null)
                  .totalElements())
          .isEqualTo(10);
    }
  }

  private TranslationIncidentService.IncidentPage filteredIncidents(int page) {
    return translationIncidentService.getIncidents(
        TranslationIncidentStatus.OPEN,
        "save",
        LocalDate.of(2026, 9, 20),
        LocalDate.of(2026, 9, 20),
        page,
        1,
        "linguistic",
        41L,
        "fr-CA");
  }

  private TranslationIncident incident(String observedLocale, String resolvedLocale) {
    TranslationIncident incident = new TranslationIncident();
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incident.setResolution(TranslationIncidentResolution.PENDING_REVIEW);
    incident.setLookupResolutionStatus("UNIQUE_MATCH");
    incident.setLocaleResolutionStrategy("NORMALIZED");
    incident.setStringId("settings.save");
    incident.setObservedLocale(observedLocale);
    incident.setResolvedLocale(resolvedLocale);
    incident.setReason("Incorrect translation");
    incident.setReviewType("linguistic");
    incident.setReviewRunId(41L);
    incident.setCreatedDate(ZonedDateTime.parse("2026-09-20T01:00:00Z"));
    return incident;
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
