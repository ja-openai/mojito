package com.box.l10n.mojito.rest.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.IncidentReviewCreateJob;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobAccess;
import com.box.l10n.mojito.service.agentreview.IncidentReviewPreviewJob;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskInspectionService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class IncidentReviewTaskAccessTest {

  private final PollableTaskService tasks = mock(PollableTaskService.class);
  private final PollableTaskBlobStorage storage = mock(PollableTaskBlobStorage.class);
  private final UserService users = mock(UserService.class);
  private final IncidentReviewJobAccess access = new IncidentReviewJobAccess(tasks, users);
  private final PollableTaskWS ws = new PollableTaskWS();

  public IncidentReviewTaskAccessTest() {
    ws.pollableTaskService = tasks;
    ws.pollableTaskBlobStorage = storage;
    ws.aiReviewChatJobAccess = mock(AiReviewChatJobAccess.class);
    ws.incidentReviewJobAccess = access;
    ws.pollableTaskInspectionService =
        new PollableTaskInspectionService(
            tasks,
            storage,
            mock(RepositoryRepository.class),
            ObjectMapper.withNoFailOnUnknownProperties(),
            ws.aiReviewChatJobAccess,
            access);
    when(tasks.getCreatedByUserIdWithAncestorFallback(anyLong())).thenReturn(7L);
    authenticate(7L, true);
  }

  @Test
  public void requesterCanReadStatusInputOutputAndInspection() {
    when(storage.getInputJson(91L)).thenReturn("{\"request\":{}}");
    when(storage.getOutputJson(91L)).thenReturn("{\"incidentBatches\":[[12]]}");
    when(storage.findInputJson(91L)).thenReturn(Optional.of("{\"request\":{}}"));
    when(storage.findOutputJson(91L)).thenReturn(Optional.of("{\"incidentBatches\":[[12]]}"));
    for (String job : jobNames()) {
      PollableTask task = task(91L, job);
      when(tasks.getPollableTask(91L)).thenReturn(task);
      assertThat(ws.getPollableTaskById(91L)).isSameAs(task);
      assertThat(ws.getPollableTaskInput(91L)).contains("request");
      assertThat(ws.getPollableTaskOutput(91L)).contains("incidentBatches");
      assertThat(ws.getPollableTaskInspection(91L).output().at("/incidentBatches/0/0").asInt())
          .isEqualTo(12);
    }
  }

  @Test
  public void anotherPrivilegedUserCannotReadAnyRoute() {
    authenticate(8L, true);
    assertAllIncidentReadsDenied();
  }

  @Test
  public void anonymousUserCannotReadAnyRoute() {
    when(users.getCurrentUser()).thenReturn(Optional.empty());
    assertAllIncidentReadsDenied();
  }

  @Test
  public void requesterWithoutCurrentPmOrAdminRoleCannotReadAnyRoute() {
    authenticate(7L, false);
    assertAllIncidentReadsDenied();
  }

  @Test
  public void missingOwnershipCannotReadAnyRoute() {
    when(tasks.getCreatedByUserIdWithAncestorFallback(91L)).thenReturn(null);
    assertAllIncidentReadsDenied();
  }

  @Test
  public void parentStatusCannotExposeAnotherUsersIncidentChild() {
    authenticate(8L, true);
    PollableTask parent = task(90L, "ordinary parent");
    parent.getSubTasks().add(task(91L, IncidentReviewPreviewJob.class.getCanonicalName()));
    when(tasks.getPollableTask(90L)).thenReturn(parent);

    assertNotFound(() -> ws.getPollableTaskById(90L));
    verifyNoInteractions(storage);
  }

  @Test
  public void parentInspectionCannotExposeAnotherUsersIncidentFailure() {
    authenticate(8L, true);
    PollableTask parent = task(90L, "ordinary parent");
    when(tasks.getPollableTask(90L)).thenReturn(parent);
    when(tasks.getAllPollableTasksWithError(parent))
        .thenReturn(List.of(task(91L, IncidentReviewCreateJob.class.getCanonicalName())));

    assertNotFound(() -> ws.getPollableTaskInspection(90L));
    verifyNoInteractions(storage);
  }

  @Test
  public void failureRepositoryFallbackCannotReadAnotherUsersIncidentParent() {
    authenticate(8L, true);
    PollableTask failure = task(92L, "ordinary child");
    failure.setParentTask(task(91L, IncidentReviewCreateJob.class.getCanonicalName()));
    when(tasks.getPollableTask(92L)).thenReturn(failure);
    when(tasks.getAllPollableTasksWithError(failure)).thenReturn(List.of(failure));

    assertNotFound(() -> ws.getPollableTaskInspection(92L));
    verify(storage, never()).findInputJson(91L);
    verify(storage, never()).findOutputJson(91L);
  }

  @Test
  public void unrelatedTasksKeepExistingAccess() {
    access.assertCanReadTree(task(1L, "ordinary job"));
    access.assertCanRead(null);
    verifyNoInteractions(tasks, users);
  }

  private void assertAllIncidentReadsDenied() {
    for (String job : jobNames()) {
      when(tasks.getPollableTask(91L)).thenReturn(task(91L, job));
      assertNotFound(() -> ws.getPollableTaskById(91L));
      assertNotFound(() -> ws.getPollableTaskInput(91L));
      assertNotFound(() -> ws.getPollableTaskOutput(91L));
      assertNotFound(() -> ws.getPollableTaskInspection(91L));
    }
    verifyNoInteractions(storage);
  }

  private void assertNotFound(Runnable read) {
    assertThatThrownBy(read::run)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  private void authenticate(long userId, boolean privileged) {
    User user = new User();
    user.setId(userId);
    when(users.getCurrentUser()).thenReturn(Optional.of(user));
    when(users.isCurrentUserAdminOrPm()).thenReturn(privileged);
  }

  private PollableTask task(long id, String name) {
    PollableTask task = new PollableTask();
    task.setId(id);
    task.setName(name);
    return task;
  }

  private List<String> jobNames() {
    return List.of(
        IncidentReviewPreviewJob.class.getCanonicalName(),
        IncidentReviewCreateJob.class.getCanonicalName());
  }
}
