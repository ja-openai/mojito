package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewAutomationRun;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService;
import com.box.l10n.mojito.service.security.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class ReviewAutomationSchedulerServiceTest {

  private final ReviewAutomationRepository reviewAutomationRepository =
      Mockito.mock(ReviewAutomationRepository.class);
  private final ReviewProjectService reviewProjectService =
      Mockito.mock(ReviewProjectService.class);
  private final ReviewAutomationRunService reviewAutomationRunService =
      Mockito.mock(ReviewAutomationRunService.class);
  private final IncidentReviewBatchService incidentReviewBatchService =
      Mockito.mock(IncidentReviewBatchService.class);
  private final UserService userService = Mockito.mock(UserService.class);

  private ReviewAutomationSchedulerService reviewAutomationSchedulerService;

  @Before
  public void setUp() {
    reviewAutomationSchedulerService =
        new ReviewAutomationSchedulerService(
            reviewAutomationRepository,
            reviewProjectService,
            incidentReviewBatchService,
            reviewAutomationRunService,
            userService,
            new SimpleMeterRegistry());
  }

  @Test
  public void incidentSourceUsesSameSavedPolicyForCronAndRunNow() {
    ReviewAutomation automation = automation(19L, "Incident review", "UTC");
    automation.setReviewSource(ReviewAutomation.ReviewSource.INCIDENTS);
    automation.setIncidentReviewType("TERMINOLOGY");
    automation.setExcludedLocaleTags(List.of("he"));
    automation.setAssignTranslator(false);
    automation.setDueDateOffsetDays(3);
    automation.setMaxWordCountPerProject(700);
    automation.setFeatures(
        new LinkedHashSet<>(List.of(feature(25L, "Billing"), feature(26L, "Checkout"))));
    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(33L);
    User systemUser = new User();
    systemUser.setId(99L);
    when(userService.findSystemUser()).thenReturn(systemUser);
    when(reviewAutomationRepository.findByIdWithFeatures(19L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);
    when(incidentReviewBatchService.create(any(), anyLong()))
        .thenReturn(
            new IncidentReviewBatchService.Result(
                2, 1, 2, List.of("fr", "de"), List.of(41L, 42L), List.of(51L), List.of()));

    ReviewAutomationSchedulerService.RunResult cron =
        reviewAutomationSchedulerService.runAutomationFromCron(19L);
    ReviewAutomationSchedulerService.RunResult manual =
        reviewAutomationSchedulerService.runAutomationNow(19L, 99L);

    ArgumentCaptor<IncidentReviewBatchService.Request> captor =
        ArgumentCaptor.forClass(IncidentReviewBatchService.Request.class);
    verify(incidentReviewBatchService, Mockito.times(2)).create(captor.capture(), Mockito.eq(99L));
    for (IncidentReviewBatchService.Request request : captor.getAllValues()) {
      assertEquals(List.of(25L, 26L), request.reviewFeatureIds());
      assertEquals(Boolean.FALSE, request.allRepositories());
      assertEquals(List.of("he"), request.excludedLocaleTags());
      assertEquals("TERMINOLOGY", request.reviewType());
      assertEquals(automation.getTeam().getId(), request.teamId());
      assertEquals(Integer.valueOf(700), request.maxWordCountPerProject());
      assertEquals(Boolean.FALSE, request.assignTranslator());
      assertEquals(
          java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(3),
          request.dueDate().toLocalDate());
    }
    assertEquals(2, cron.createdProjectCount());
    assertEquals(2, manual.createdLocaleCount());
    assertEquals(1, manual.createdProjectRequestCount());
    Mockito.verifyNoInteractions(reviewProjectService);
  }

  @Test
  public void incidentAutomationContinuesPastSkippedPagesAndCombinesCommittedResults() {
    prepareIncidentAutomation();
    when(incidentReviewBatchService.create(any(), anyLong()))
        .thenReturn(
            new IncidentReviewBatchService.Result(
                0, 500, 0, List.of(), List.of(), List.of(), List.of(), 500, true),
            new IncidentReviewBatchService.Result(
                2, 0, 1, List.of("fr"), List.of(41L), List.of(51L), List.of(), 2, true),
            new IncidentReviewBatchService.Result(
                1, 0, 1, List.of("fr"), List.of(42L), List.of(52L), List.of(), 1, false));
    var result = reviewAutomationSchedulerService.runAutomationNow(24L, 99L);
    assertEquals(2, result.createdProjectCount());
    assertEquals(2, result.createdProjectRequestCount());
    assertEquals(1, result.createdLocaleCount());
    verify(incidentReviewBatchService, Mockito.times(3)).create(any(), Mockito.eq(99L));
  }

  @Test
  public void incidentAutomationYieldsAfterItsBatchBudget() {
    prepareIncidentAutomation();
    when(incidentReviewBatchService.create(any(), anyLong()))
        .thenReturn(
            new IncidentReviewBatchService.Result(
                0, 500, 0, List.of(), List.of(), List.of(), List.of(), 500, true));
    reviewAutomationSchedulerService.runAutomationNow(24L, 99L);
    verify(
            incidentReviewBatchService,
            Mockito.times(ReviewAutomationSchedulerService.MAX_INCIDENT_BATCHES_PER_RUN))
        .create(any(), Mockito.eq(99L));
  }

  @Test
  public void laterIncidentBatchFailureRetainsAlreadyCommittedCounts() {
    prepareIncidentAutomation();
    when(incidentReviewBatchService.create(any(), anyLong()))
        .thenReturn(
            new IncidentReviewBatchService.Result(
                2, 0, 1, List.of("fr"), List.of(41L), List.of(51L), List.of(), 2, true))
        .thenThrow(new IllegalStateException("next batch failed"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> reviewAutomationSchedulerService.runAutomationNow(24L, 99L))
        .hasMessage("next batch failed");
    verify(reviewAutomationRunService).markFailed(34L, 1, 1, 1, 0, 0, "next batch failed");
  }

  private void prepareIncidentAutomation() {
    ReviewAutomation automation = automation(24L, "All incidents", "UTC");
    automation.setReviewSource(ReviewAutomation.ReviewSource.INCIDENTS);
    automation.setIncidentScope(ReviewAutomation.IncidentScope.ALL);
    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(34L);
    when(reviewAutomationRepository.findByIdWithFeatures(24L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);
  }

  @Test
  public void incidentRequestNameRetainsDateSuffixWithinMaximumLength() {
    ReviewAutomation automation = automation(22L, "A".repeat(235) + "😀" + "B".repeat(18), "UTC");
    automation.setReviewSource(ReviewAutomation.ReviewSource.INCIDENTS);
    automation.setFeatures(new LinkedHashSet<>(List.of(feature(25L, "Billing"))));
    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(35L);
    when(reviewAutomationRepository.findByIdWithFeatures(22L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);
    when(incidentReviewBatchService.create(any(), anyLong()))
        .thenReturn(
            new IncidentReviewBatchService.Result(
                0, 0, 0, List.of(), List.of(), List.of(), List.of()));

    reviewAutomationSchedulerService.runAutomationNow(22L, 99L);

    ArgumentCaptor<IncidentReviewBatchService.Request> captor =
        ArgumentCaptor.forClass(IncidentReviewBatchService.Request.class);
    verify(incidentReviewBatchService).create(captor.capture(), Mockito.eq(99L));
    String name = captor.getValue().name();
    assertTrue(name.length() <= 255);
    assertEquals(
        "A".repeat(235) + " review - " + java.time.LocalDate.now(java.time.ZoneOffset.UTC), name);
  }

  @Test
  public void allIncidentScopeExplicitlyIgnoresSavedFeatureSelection() {
    ReviewAutomation automation = automation(24L, "All incidents", "UTC");
    automation.setReviewSource(ReviewAutomation.ReviewSource.INCIDENTS);
    automation.setIncidentScope(ReviewAutomation.IncidentScope.ALL);
    automation.setExcludedLocaleTags(List.of("he"));
    automation.setFeatures(new LinkedHashSet<>(List.of(feature(25L, "Billing"))));
    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(36L);
    when(reviewAutomationRepository.findByIdWithFeatures(24L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);
    when(incidentReviewBatchService.create(any(), anyLong()))
        .thenReturn(
            new IncidentReviewBatchService.Result(
                0, 0, 0, List.of(), List.of(), List.of(), List.of()));

    ReviewAutomationSchedulerService.RunResult result =
        reviewAutomationSchedulerService.runAutomationNow(24L, 99L);

    ArgumentCaptor<IncidentReviewBatchService.Request> captor =
        ArgumentCaptor.forClass(IncidentReviewBatchService.Request.class);
    verify(incidentReviewBatchService).create(captor.capture(), Mockito.eq(99L));
    assertEquals(Boolean.TRUE, captor.getValue().allRepositories());
    assertEquals(List.of(), captor.getValue().repositoryIds());
    assertEquals(List.of(), captor.getValue().reviewFeatureIds());
    assertEquals(List.of("he"), captor.getValue().excludedLocaleTags());
    assertEquals(0, result.featureCount());
    Mockito.verifyNoInteractions(reviewProjectService);
  }

  @Test
  public void incidentAutomationWithoutFeaturesDoesNotSelectAllIncidents() {
    ReviewAutomation automation = automation(20L, "Empty", "UTC");
    automation.setReviewSource(ReviewAutomation.ReviewSource.INCIDENTS);
    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(34L);
    when(reviewAutomationRepository.findByIdWithFeatures(20L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);

    assertEquals(
        0, reviewAutomationSchedulerService.runAutomationNow(20L, 99L).createdProjectCount());
    Mockito.verifyNoInteractions(incidentReviewBatchService, reviewProjectService);
  }

  @Test
  public void cronRestoresSecurityContextWhenRunFails() {
    User systemUser = new User();
    systemUser.setId(99L);
    systemUser.setUsername(UserService.SYSTEM_USERNAME);
    when(userService.findSystemUser()).thenReturn(systemUser);
    org.springframework.security.core.context.SecurityContext original =
        org.springframework.security.core.context.SecurityContextHolder.getContext();
    when(reviewAutomationRepository.findByIdWithFeatures(21L))
        .thenAnswer(
            invocation -> {
              assertEquals(
                  UserService.SYSTEM_USERNAME,
                  org.springframework.security.core.context.SecurityContextHolder.getContext()
                      .getAuthentication()
                      .getName());
              throw new IllegalStateException("failed");
            });

    org.junit.Assert.assertThrows(
        IllegalStateException.class,
        () -> reviewAutomationSchedulerService.runAutomationFromCron(21L));
    org.junit.Assert.assertSame(
        original, org.springframework.security.core.context.SecurityContextHolder.getContext());
  }

  @Test
  public void runAutomationUsesDateOnlyRequestName() {
    ReviewAutomation automation = automation(17L, "Morning automation", "America/Los_Angeles");
    ReviewFeature feature = feature(23L, "Checkout");
    automation.setFeatures(new LinkedHashSet<>(List.of(feature)));

    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(31L);

    when(reviewAutomationRepository.findByIdWithFeatures(17L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);
    when(reviewProjectService.createAutomatedReviewProjectRequest(any()))
        .thenReturn(
            new CreateReviewProjectRequestResult(
                51L, "ignored", List.of(), null, List.of(), 0, 0, 0, 0, List.of()));

    reviewAutomationSchedulerService.runAutomation(
        17L, ReviewAutomationRun.TriggerSource.MANUAL, 99L, false);

    ArgumentCaptor<CreateAutomatedReviewProjectRequestCommand> commandCaptor =
        ArgumentCaptor.forClass(CreateAutomatedReviewProjectRequestCommand.class);
    verify(reviewProjectService).createAutomatedReviewProjectRequest(commandCaptor.capture());

    CreateAutomatedReviewProjectRequestCommand command = commandCaptor.getValue();
    assertTrue(command.name().matches("Checkout review - \\d{4}-\\d{2}-\\d{2}"));
    assertEquals(-1, command.name().indexOf(':'));
    assertEquals(-1, command.name().indexOf("PST"));
    assertEquals(-1, command.name().indexOf("PDT"));
    assertEquals(Boolean.TRUE, command.assignTranslator());
    assertEquals(List.of(), command.excludedLocaleTags());
  }

  @Test
  public void runAutomationCanSkipTranslatorAssignment() {
    ReviewAutomation automation = automation(18L, "Morning automation", "UTC");
    automation.setAssignTranslator(false);
    automation.setFeatures(new LinkedHashSet<>(List.of(feature(24L, "Billing"))));

    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(32L);

    when(reviewAutomationRepository.findByIdWithFeatures(18L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);
    when(reviewProjectService.createAutomatedReviewProjectRequest(any()))
        .thenReturn(
            new CreateReviewProjectRequestResult(
                52L, "ignored", List.of(), null, List.of(), 0, 0, 0, 0, List.of()));

    reviewAutomationSchedulerService.runAutomation(
        18L, ReviewAutomationRun.TriggerSource.MANUAL, 99L, false);

    ArgumentCaptor<CreateAutomatedReviewProjectRequestCommand> commandCaptor =
        ArgumentCaptor.forClass(CreateAutomatedReviewProjectRequestCommand.class);
    verify(reviewProjectService).createAutomatedReviewProjectRequest(commandCaptor.capture());

    assertEquals(Boolean.FALSE, commandCaptor.getValue().assignTranslator());
  }

  @Test
  public void cronAndRunNowPassSavedExclusionsToEveryFeature() {
    ReviewAutomation automation = automation(18L, "Daily", "UTC");
    automation.setExcludedLocaleTags(List.of("he"));
    automation.setFeatures(
        new LinkedHashSet<>(List.of(feature(24L, "Billing"), feature(25L, "Checkout"))));
    ReviewAutomationRun run = new ReviewAutomationRun();
    run.setId(32L);
    User systemUser = new User();
    systemUser.setId(100L);
    when(userService.findSystemUser()).thenReturn(systemUser);
    when(reviewAutomationRepository.findByIdWithFeatures(18L)).thenReturn(Optional.of(automation));
    when(reviewAutomationRunService.createRunningRun(any(), any(), anyLong(), anyInt(), any()))
        .thenReturn(run);
    when(reviewProjectService.createAutomatedReviewProjectRequest(any()))
        .thenReturn(
            new CreateReviewProjectRequestResult(
                null, "ignored", List.of(), null, List.of(), 1, 0, 1, 0, List.of()));

    reviewAutomationSchedulerService.runAutomationFromCron(18L);
    reviewAutomationSchedulerService.runAutomationNow(18L, 99L);

    ArgumentCaptor<CreateAutomatedReviewProjectRequestCommand> commands =
        ArgumentCaptor.forClass(CreateAutomatedReviewProjectRequestCommand.class);
    verify(reviewProjectService, Mockito.times(4))
        .createAutomatedReviewProjectRequest(commands.capture());
    assertEquals(
        List.of(24L, 25L, 24L, 25L),
        commands.getAllValues().stream()
            .map(CreateAutomatedReviewProjectRequestCommand::reviewFeatureId)
            .toList());
    assertEquals(
        List.of(100L, 100L, 99L, 99L),
        commands.getAllValues().stream()
            .map(CreateAutomatedReviewProjectRequestCommand::requestedByUserId)
            .toList());
    for (CreateAutomatedReviewProjectRequestCommand command : commands.getAllValues()) {
      assertEquals(List.of("he"), command.excludedLocaleTags());
    }
  }

  private ReviewAutomation automation(Long id, String name, String timeZone) {
    ReviewAutomation automation = new ReviewAutomation();
    automation.setId(id);
    automation.setName(name);
    automation.setEnabled(true);
    automation.setTimeZone(timeZone);
    automation.setDueDateOffsetDays(2);
    automation.setMaxWordCountPerProject(1000);
    automation.setTeam(team(41L));
    return automation;
  }

  private ReviewFeature feature(Long id, String name) {
    ReviewFeature feature = new ReviewFeature();
    feature.setId(id);
    feature.setName(name);
    return feature;
  }

  private Team team(Long id) {
    Team team = new Team();
    team.setId(id);
    return team;
  }
}
