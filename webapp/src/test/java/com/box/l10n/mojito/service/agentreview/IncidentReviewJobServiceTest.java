package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.security.UserDetailsImpl;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Request;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Result;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobService.Command;
import com.box.l10n.mojito.service.agentreview.ManualIncidentReviewService.Preview;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

public class IncidentReviewJobServiceTest {
  private final QuartzPollableTaskScheduler scheduler = mock(QuartzPollableTaskScheduler.class);
  private final IncidentReviewBatchService batches = mock(IncidentReviewBatchService.class);
  private final ManualIncidentReviewService manual = mock(ManualIncidentReviewService.class);
  private final UserService users = mock(UserService.class);
  private final TeamService teams = mock(TeamService.class);
  private final PollableTaskService tasks = mock(PollableTaskService.class);
  private final PollableTaskBlobStorage outputs = mock(PollableTaskBlobStorage.class);
  private final IncidentReviewJobService service =
      new IncidentReviewJobService(scheduler, batches, manual, users, teams, tasks, outputs);
  private final Request request = ManualIncidentReviewServiceTest.request(null);
  private final Command command = new Command(request, 7L);
  private final Preview preview =
      new Preview(0, 0, 0, List.of(), List.of(), List.of(), List.of(), 0, false, List.of(), false);
  private SecurityContext previous;
  private User user;

  @Before
  public void setup() {
    previous = SecurityContextHolder.createEmptyContext();
    SecurityContextHolder.setContext(previous);
    user = new User();
    user.setId(7L);
    user.setUsername("requester");
    user.setEnabled(true);
    when(users.getUserById(7L)).thenReturn(Optional.of(user));
    when(teams.getCurrentUserIdOrThrow()).thenReturn(7L);
  }

  @After
  public void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void startsBothJobsWithoutPlanningOrWaiting() throws Exception {
    service.preview(request);
    service.create(request);

    ArgumentCaptor<QuartzJobInfo> jobs = ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(scheduler, times(2)).scheduleJob(jobs.capture());
    assertThat(jobs.getAllValues())
        .extracting(QuartzJobInfo::getClazz)
        .containsExactly(IncidentReviewPreviewJob.class, IncidentReviewCreateJob.class);
    ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
    for (QuartzJobInfo job : jobs.getAllValues()) {
      assertThat(job.isInlineInput()).isFalse();
      assertThat(job.getRequestRecovery()).isFalse();
      Command decoded = mapper.readValue(mapper.writeValueAsString(job.getInput()), Command.class);
      assertThat(decoded.requestedByUserId()).isEqualTo(7L);
      assertThat(decoded.request().incidentIds()).isNull();
      assertThat(decoded.request().dueDate().toInstant()).isEqualTo(request.dueDate().toInstant());
      assertThat(decoded.request().maxIncidentsPerProject()).isEqualTo(2);
    }
    verify(batches, times(2)).validateRequest(request, 7L);
    verifyNoMoreInteractions(batches);
    verifyNoInteractions(manual, tasks, outputs);
  }

  @Test
  public void startValidationNeedsNoIncidentOrLocaleQueries() {
    var validation =
        new IncidentReviewBatchService(
            null, users, teams, null, null, null, null, null, null, null, null, null, null, null,
            null);
    when(users.isCurrentUserAdminOrPm()).thenReturn(true);
    validation.validateRequest(request, 7L);
    verify(teams).assertCurrentUserCanAccessTeam(request.teamId());
    verify(users, never()).checkUserCanEditLocale(anyLong());
  }

  @Test
  public void deniedRequestNeverSchedules() {
    doThrow(new AccessDeniedException("denied")).when(batches).validateRequest(request, 7L);
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(scheduler, manual, tasks, outputs);
  }

  @Test
  public void previewRestoresRequesterAndPreviousContext() {
    when(manual.preview(eq(request), eq(7L), any()))
        .thenAnswer(
            invocation -> {
              assertRequester();
              Consumer<String> progress = invocation.getArgument(2);
              progress.accept("scanned 500");
              return preview;
            });

    assertThat(service.preview(command, 31L)).isEqualTo(preview);

    verify(batches).validateRequest(request, 7L);
    verify(tasks).updateMessage(31L, "scanned 500");
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
  }

  @Test
  public void partialOutputAndContextSurviveLaterFailure() throws Exception {
    Result partial =
        new Result(2, 1, 1, List.of("fr"), List.of(12L), List.of(13L), List.of(), 500, true);
    when(manual.create(eq(request), eq(7L), any(), any()))
        .thenAnswer(
            invocation -> {
              assertRequester();
              Consumer<Result> publish = invocation.getArgument(3);
              publish.accept(partial);
              throw new IllegalStateException("later batch failed");
            });

    assertThatThrownBy(() -> service.create(command, 31L)).hasMessage("later batch failed");

    verify(outputs).saveOutput(31L, partial);
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
    assertThat(mapper.readValue(mapper.writeValueAsString(partial), Result.class))
        .isEqualTo(partial);
    assertThat(mapper.readValue(mapper.writeValueAsString(preview), Preview.class))
        .isEqualTo(preview);
  }

  @Test
  public void revokedAccessIsRecheckedInJobAndContextIsRestored() {
    doAnswer(
            invocation -> {
              assertRequester();
              throw new AccessDeniedException("team access revoked");
            })
        .when(batches)
        .validateRequest(request, 7L);
    assertThatThrownBy(() -> service.create(command, 31L)).hasMessage("team access revoked");
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    verifyNoInteractions(manual, outputs);
  }

  @Test
  public void disabledRequesterCannotRunJob() {
    user.setEnabled(false);
    assertThatThrownBy(() -> service.preview(command, 31L))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
    verifyNoInteractions(batches, manual, outputs);
  }

  private void assertRequester() {
    var principal =
        (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    assertThat(principal.getUser().getId()).isEqualTo(7L);
  }
}
