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
  private final UserService userService = Mockito.mock(UserService.class);

  private ReviewAutomationSchedulerService reviewAutomationSchedulerService;

  @Before
  public void setUp() {
    reviewAutomationSchedulerService =
        new ReviewAutomationSchedulerService(
            reviewAutomationRepository,
            reviewProjectService,
            reviewAutomationRunService,
            userService,
            new SimpleMeterRegistry());
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
