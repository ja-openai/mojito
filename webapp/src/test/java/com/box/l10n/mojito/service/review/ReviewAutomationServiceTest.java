package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.review.ReviewAutomationWS;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

public class ReviewAutomationServiceTest {

  private final ReviewAutomationRepository reviewAutomationRepository =
      Mockito.mock(ReviewAutomationRepository.class);
  private final ReviewFeatureRepository reviewFeatureRepository =
      Mockito.mock(ReviewFeatureRepository.class);
  private final ReviewAutomationRunRepository reviewAutomationRunRepository =
      Mockito.mock(ReviewAutomationRunRepository.class);
  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
  private final UserService userService = Mockito.mock(UserService.class);
  private final LocaleService localeService = Mockito.mock(LocaleService.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<ReviewAutomationCronSchedulerService>
      reviewAutomationCronSchedulerServiceProvider = Mockito.mock(ObjectProvider.class);

  private ReviewAutomationService reviewAutomationService;

  @Before
  public void setUp() {
    reviewAutomationService =
        new ReviewAutomationService(
            reviewAutomationRepository,
            reviewFeatureRepository,
            reviewAutomationRunRepository,
            teamRepository,
            userService,
            localeService,
            reviewAutomationCronSchedulerServiceProvider);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
  }

  @Test
  public void deleteReviewAutomationDeletesRunHistoryBeforeAutomation() {
    ReviewAutomation automation = new ReviewAutomation();
    automation.setId(7L);
    when(reviewAutomationRepository.findById(7L)).thenReturn(Optional.of(automation));

    reviewAutomationService.deleteReviewAutomation(7L);

    InOrder inOrder = inOrder(reviewAutomationRunRepository, reviewAutomationRepository);
    inOrder.verify(reviewAutomationRunRepository).deleteByReviewAutomationId(7L);
    inOrder.verify(reviewAutomationRepository).delete(automation);
  }

  @Test
  public void createReviewAutomationAllowsSharedReviewFeatures() {
    Team team = new Team();
    team.setId(2L);
    ReviewFeature reviewFeature = new ReviewFeature();
    reviewFeature.setId(5L);
    reviewFeature.setName("Product UI");
    AtomicReference<ReviewAutomation> savedAutomationReference = new AtomicReference<>();

    when(reviewAutomationRepository.findByNameIgnoreCase("Daily review"))
        .thenReturn(Optional.empty());
    when(teamRepository.findById(2L)).thenReturn(Optional.of(team));
    when(reviewFeatureRepository.findByIdInOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(reviewFeature));
    when(reviewAutomationRunRepository.findLatestRunTimestampsByAutomationIds(List.of(9L)))
        .thenReturn(List.of());
    when(reviewAutomationRunRepository.findLatestSuccessfulRunTimestampsByAutomationIds(
            Mockito.eq(List.of(9L)), Mockito.anyList()))
        .thenReturn(List.of());
    when(reviewAutomationRepository.save(Mockito.any(ReviewAutomation.class)))
        .thenAnswer(
            invocation -> {
              ReviewAutomation automation = invocation.getArgument(0);
              automation.setId(9L);
              savedAutomationReference.set(automation);
              return automation;
            });
    when(reviewAutomationRepository.findByIdWithFeatures(9L))
        .thenAnswer(invocation -> Optional.of(savedAutomationReference.get()));

    reviewAutomationService.createReviewAutomation(
        "Daily review", true, "0 0 9 ? * MON-FRI", "UTC", 2L, 1, 2000, true, List.of(5L), null);

    ArgumentCaptor<ReviewAutomation> automationCaptor =
        ArgumentCaptor.forClass(ReviewAutomation.class);
    verify(reviewAutomationRepository).save(automationCaptor.capture());
    ReviewAutomation savedAutomation = automationCaptor.getValue();
    org.junit.Assert.assertEquals("Daily review", savedAutomation.getName());
    org.junit.Assert.assertEquals(1, savedAutomation.getFeatures().size());
    assertEquals(List.of(), savedAutomation.getExcludedLocaleTags());
  }

  @Test
  public void batchUpsertReplaceModeDisablesEnabledAutomationsNotInBatch() {
    Team team = new Team();
    team.setId(2L);
    ReviewAutomation keptAutomation = new ReviewAutomation();
    keptAutomation.setId(7L);
    keptAutomation.setName("Kept");
    keptAutomation.setEnabled(true);
    keptAutomation.setAssignTranslator(true);
    ReviewAutomation omittedAutomation = new ReviewAutomation();
    omittedAutomation.setId(8L);
    omittedAutomation.setName("Omitted");
    omittedAutomation.setEnabled(true);

    when(reviewAutomationRepository.findByIdWithFeatures(7L))
        .thenReturn(Optional.of(keptAutomation));
    when(reviewAutomationRepository.findByNameIgnoreCase("Kept"))
        .thenReturn(Optional.of(keptAutomation));
    when(teamRepository.findById(2L)).thenReturn(Optional.of(team));
    when(reviewAutomationRepository.findAllEnabledWithTeam())
        .thenReturn(List.of(keptAutomation, omittedAutomation));
    when(reviewAutomationRepository.save(Mockito.any(ReviewAutomation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReviewAutomationService.BatchUpsertResult result =
        reviewAutomationService.batchUpsert(
            List.of(
                new ReviewAutomationService.BatchUpsertRow(
                    7L,
                    "Kept",
                    true,
                    "0 0 9 ? * MON-FRI",
                    "UTC",
                    2L,
                    1,
                    2000,
                    true,
                    List.of(),
                    null)),
            ReviewAutomationService.BatchUpsertMode.REPLACE_DISABLE_OMITTED);

    org.junit.Assert.assertEquals(0, result.createdCount());
    org.junit.Assert.assertEquals(1, result.updatedCount());
    org.junit.Assert.assertEquals(1, result.disabledCount());
    org.junit.Assert.assertTrue(keptAutomation.getEnabled());
    org.junit.Assert.assertFalse(omittedAutomation.getEnabled());
    verify(reviewAutomationRepository).save(omittedAutomation);
  }

  @Test
  public void createNormalizesExcludedLocalesFromGlobalCatalogBeforeRepositoryOnboarding() {
    Team team = new Team();
    team.setId(2L);
    when(teamRepository.findById(2L)).thenReturn(Optional.of(team));
    Locale hebrew = locale("he");
    Locale french = locale("fr-FR");
    when(localeService.findByBcp47Tag("HE")).thenReturn(hebrew);
    when(localeService.findByBcp47Tag("he")).thenReturn(hebrew);
    when(localeService.findByBcp47Tag("fr-fr")).thenReturn(french);
    AtomicReference<ReviewAutomation> saved = new AtomicReference<>();
    when(reviewAutomationRepository.save(Mockito.any(ReviewAutomation.class)))
        .thenAnswer(
            invocation -> {
              ReviewAutomation automation = invocation.getArgument(0);
              automation.setId(9L);
              saved.set(automation);
              return automation;
            });
    when(reviewAutomationRepository.findByIdWithFeatures(9L))
        .thenAnswer(invocation -> Optional.of(saved.get()));

    ReviewAutomationService.ReviewAutomationDetail result =
        reviewAutomationService.createReviewAutomation(
            "Daily",
            true,
            "0 0 9 ? * MON-FRI",
            "UTC",
            2L,
            1,
            2000,
            true,
            List.of(),
            List.of(" HE ", "fr-fr", "he"));

    assertEquals(List.of("he", "fr-FR"), saved.get().getExcludedLocaleTags());
    assertEquals(List.of("he", "fr-FR"), result.excludedLocaleTags());
  }

  @Test
  public void oldUpdatePayloadPreservesExclusionsAndExplicitEmptyListClearsThem() {
    ReviewAutomation automation = existingAutomation();
    ReviewAutomationWS webService = webService();
    ObjectMapper mapper = new ObjectMapper();
    String oldPayload =
        """
        {"name":"Daily","cronExpression":"0 0 9 ? * MON-FRI","teamId":2}
        """;

    ReviewAutomationWS.ReviewAutomationResponse preserved =
        webService.updateReviewAutomation(
            7L,
            mapper.readValueUnchecked(
                oldPayload, ReviewAutomationWS.UpsertReviewAutomationRequest.class));
    assertEquals(List.of("he"), preserved.excludedLocaleTags());
    assertEquals(List.of("he"), automation.getExcludedLocaleTags());

    ReviewAutomationWS.ReviewAutomationResponse cleared =
        webService.updateReviewAutomation(
            7L,
            mapper.readValueUnchecked(
                oldPayload.replace("}", ",\"excludedLocaleTags\":[]}"),
                ReviewAutomationWS.UpsertReviewAutomationRequest.class));
    assertEquals(List.of(), cleared.excludedLocaleTags());
    assertEquals(List.of(), automation.getExcludedLocaleTags());
  }

  @Test
  public void oldBatchPayloadPreservesExclusionsAndExplicitEmptyListClearsThem() {
    ReviewAutomation automation = existingAutomation();
    ReviewAutomationWS webService = webService();
    ObjectMapper mapper = new ObjectMapper();
    String oldRow =
        """
        {"id":7,"name":"Daily","cronExpression":"0 0 9 ? * MON-FRI","teamId":2}
        """;

    webService.batchUpsertReviewAutomations(
        mapper.readValueUnchecked(
            "{\"rows\":[" + oldRow + "]}",
            ReviewAutomationWS.BatchUpsertReviewAutomationsRequest.class));
    assertEquals(List.of("he"), automation.getExcludedLocaleTags());

    webService.batchUpsertReviewAutomations(
        mapper.readValueUnchecked(
            "{\"rows\":[" + oldRow.replace("}", ",\"excludedLocaleTags\":[]}") + "]}",
            ReviewAutomationWS.BatchUpsertReviewAutomationsRequest.class));
    assertEquals(List.of(), automation.getExcludedLocaleTags());
  }

  @Test
  public void unknownOrBlankExcludedLocaleIsRejectedWithoutSaving() {
    existingAutomation();
    for (List<String> localeTags : List.of(List.of("not-a-locale"), List.of(" "))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              reviewAutomationService.updateReviewAutomation(
                  7L,
                  "Daily",
                  true,
                  "0 0 9 ? * MON-FRI",
                  "UTC",
                  2L,
                  1,
                  2000,
                  true,
                  List.of(),
                  localeTags));
    }
    Mockito.verify(reviewAutomationRepository, Mockito.never()).save(Mockito.any());
  }

  @Test
  public void batchExportIncludesSavedExclusions() {
    ReviewAutomation automation = existingAutomation();
    when(reviewAutomationRepository.findAllOptionRows())
        .thenReturn(List.of(new ReviewAutomationOptionRow(7L, "Daily", true)));
    when(reviewAutomationRepository.findAllById(List.of(7L))).thenReturn(List.of(automation));

    assertEquals(
        List.of("he"),
        webService().getReviewAutomationBatchExport().getFirst().excludedLocaleTags());
  }

  @Test
  public void incidentSourceRoundTripsAndLegacyUpdatesPreserveIt() {
    ReviewAutomation automation = existingAutomation();
    ReviewAutomationWS webService = webService();
    ObjectMapper mapper = new ObjectMapper();
    String payload =
        """
        {"name":"Daily","cronExpression":"0 0 9 ? * MON-FRI","teamId":2,
         "reviewSource":"INCIDENTS","incidentReviewType":"TERMINOLOGY"}
        """;
    ReviewAutomationWS.ReviewAutomationResponse saved =
        webService.updateReviewAutomation(
            7L,
            mapper.readValueUnchecked(
                payload, ReviewAutomationWS.UpsertReviewAutomationRequest.class));
    assertEquals(ReviewAutomation.ReviewSource.INCIDENTS, saved.reviewSource());
    assertEquals("TERMINOLOGY", saved.incidentReviewType());
    assertEquals(List.of("he"), saved.excludedLocaleTags());

    webService.updateReviewAutomation(
        7L,
        mapper.readValueUnchecked(
            "{\"name\":\"Daily\",\"cronExpression\":\"0 0 9 ? * MON-FRI\",\"teamId\":2}",
            ReviewAutomationWS.UpsertReviewAutomationRequest.class));
    assertEquals(ReviewAutomation.ReviewSource.INCIDENTS, automation.getReviewSource());
    assertEquals("TERMINOLOGY", automation.getIncidentReviewType());

    when(reviewAutomationRepository.findAllOptionRows())
        .thenReturn(List.of(new ReviewAutomationOptionRow(7L, "Daily", true)));
    when(reviewAutomationRepository.findAllById(List.of(7L))).thenReturn(List.of(automation));
    assertEquals(
        "TERMINOLOGY", webService.getReviewAutomationBatchExport().getFirst().incidentReviewType());
  }

  @Test
  public void selectingAllIncidentTypesClearsTypeAndCurrentSourceClearsIncidentFilter() {
    ReviewAutomation automation = existingAutomation();
    automation.setReviewSource(ReviewAutomation.ReviewSource.INCIDENTS);
    automation.setIncidentReviewType("TERMINOLOGY");
    reviewAutomationService.updateReviewAutomation(
        7L,
        "Daily",
        true,
        "0 0 9 ? * MON-FRI",
        "UTC",
        2L,
        1,
        2000,
        true,
        List.of(),
        null,
        ReviewAutomation.ReviewSource.INCIDENTS,
        " ");
    assertEquals(null, automation.getIncidentReviewType());
    reviewAutomationService.updateReviewAutomation(
        7L,
        "Daily",
        true,
        "0 0 9 ? * MON-FRI",
        "UTC",
        2L,
        1,
        2000,
        true,
        List.of(),
        null,
        ReviewAutomation.ReviewSource.CURRENT_TRANSLATIONS,
        "TERMINOLOGY");
    assertEquals(ReviewAutomation.ReviewSource.CURRENT_TRANSLATIONS, automation.getReviewSource());
    assertEquals(null, automation.getIncidentReviewType());
  }

  @Test
  public void incidentWordLimitIsValidatedWhileCurrentTranslationLimitIsUnchanged() {
    ReviewAutomation automation = existingAutomation();
    reviewAutomationService.updateReviewAutomation(
        7L,
        "Daily",
        true,
        "0 0 9 ? * MON-FRI",
        "UTC",
        2L,
        1,
        100000,
        true,
        List.of(),
        null,
        ReviewAutomation.ReviewSource.INCIDENTS,
        null);
    assertEquals(Integer.valueOf(100000), automation.getMaxWordCountPerProject());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            reviewAutomationService.updateReviewAutomation(
                7L,
                "Daily",
                true,
                "0 0 9 ? * MON-FRI",
                "UTC",
                2L,
                1,
                100001,
                true,
                List.of(),
                null,
                ReviewAutomation.ReviewSource.INCIDENTS,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reviewAutomationService.batchUpsert(
                List.of(
                    new ReviewAutomationService.BatchUpsertRow(
                        7L,
                        "Daily",
                        true,
                        "0 0 9 ? * MON-FRI",
                        "UTC",
                        2L,
                        1,
                        100001,
                        true,
                        List.of(),
                        null)),
                ReviewAutomationService.BatchUpsertMode.MERGE));

    reviewAutomationService.updateReviewAutomation(
        7L,
        "Daily",
        true,
        "0 0 9 ? * MON-FRI",
        "UTC",
        2L,
        1,
        100001,
        true,
        List.of(),
        null,
        ReviewAutomation.ReviewSource.CURRENT_TRANSLATIONS,
        null);
    assertEquals(Integer.valueOf(100001), automation.getMaxWordCountPerProject());
    assertEquals(ReviewAutomation.ReviewSource.CURRENT_TRANSLATIONS, automation.getReviewSource());
  }

  @Test
  public void newIncidentAutomationDefaultsToAllWhileLegacyUpdatesRetainTheirScope() {
    Team team = new Team();
    team.setId(2L);
    when(teamRepository.findById(2L)).thenReturn(Optional.of(team));
    when(reviewAutomationRepository.save(Mockito.any(ReviewAutomation.class)))
        .thenAnswer(
            invocation -> {
              ReviewAutomation automation = invocation.getArgument(0);
              automation.setId(42L);
              return automation;
            });
    reviewAutomationService.batchUpsert(
        List.of(
            new ReviewAutomationService.BatchUpsertRow(
                null,
                "New incidents",
                true,
                "0 0 9 ? * MON-FRI",
                "UTC",
                2L,
                1,
                2000,
                true,
                List.of(),
                List.of(),
                ReviewAutomation.ReviewSource.INCIDENTS,
                null,
                null)),
        ReviewAutomationService.BatchUpsertMode.MERGE);
    ArgumentCaptor<ReviewAutomation> captor = ArgumentCaptor.forClass(ReviewAutomation.class);
    verify(reviewAutomationRepository).save(captor.capture());
    assertEquals(ReviewAutomation.IncidentScope.ALL, captor.getValue().getIncidentScope());

    ReviewAutomation legacy = existingAutomation();
    legacy.setReviewSource(ReviewAutomation.ReviewSource.INCIDENTS);
    reviewAutomationService.updateReviewAutomation(
        7L, "Daily", true, "0 0 9 ? * MON-FRI", "UTC", 2L, 1, 2000, true, List.of(), null);
    assertEquals(ReviewAutomation.IncidentScope.REVIEW_FEATURES, legacy.getIncidentScope());
    reviewAutomationService.updateReviewAutomation(
        7L,
        "Daily",
        true,
        "0 0 9 ? * MON-FRI",
        "UTC",
        2L,
        1,
        2000,
        true,
        List.of(),
        null,
        ReviewAutomation.ReviewSource.INCIDENTS,
        null,
        ReviewAutomation.IncidentScope.ALL);
    reviewAutomationService.updateReviewAutomation(
        7L, "Daily", true, "0 0 9 ? * MON-FRI", "UTC", 2L, 1, 2000, true, List.of(), null);
    assertEquals(ReviewAutomation.IncidentScope.ALL, legacy.getIncidentScope());
  }

  private ReviewAutomation existingAutomation() {
    ReviewAutomation automation = new ReviewAutomation();
    automation.setId(7L);
    automation.setName("Daily");
    automation.setExcludedLocaleTags(List.of("he"));
    Team team = new Team();
    team.setId(2L);
    when(teamRepository.findById(2L)).thenReturn(Optional.of(team));
    when(reviewAutomationRepository.findByIdWithFeatures(7L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRepository.findByNameIgnoreCase("Daily"))
        .thenReturn(Optional.of(automation));
    when(reviewAutomationRepository.save(Mockito.any(ReviewAutomation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    return automation;
  }

  private ReviewAutomationWS webService() {
    return new ReviewAutomationWS(
        reviewAutomationService,
        Mockito.mock(ReviewAutomationSchedulerService.class),
        Mockito.mock(ReviewAutomationRunService.class),
        Mockito.mock(TeamService.class));
  }

  private Locale locale(String tag) {
    Locale locale = new Locale();
    locale.setBcp47Tag(tag);
    return locale;
  }
}
