package com.box.l10n.mojito.rest.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskInspectionService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.TMXliffRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PollableTaskWSTest {

  @Test
  public void missingTaskCannotExposeRetainedInputOrOutput() {
    PollableTaskWS ws = new PollableTaskWS();
    ws.pollableTaskService = new StubPollableTaskService(null);
    ws.aiReviewChatJobAccess = mock(AiReviewChatJobAccess.class);
    ws.pollableTaskBlobStorage = mock(PollableTaskBlobStorage.class);

    for (Runnable read :
        List.<Runnable>of(
            () -> ws.getPollableTaskById(404L),
            () -> ws.getPollableTaskInput(404L),
            () -> ws.getPollableTaskOutput(404L))) {
      assertThatThrownBy(read::run)
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              throwable ->
                  assertThat(((ResponseStatusException) throwable).getStatusCode())
                      .isEqualTo(HttpStatus.NOT_FOUND));
    }
    verifyNoInteractions(ws.aiReviewChatJobAccess, ws.pollableTaskBlobStorage);
  }

  @Test
  public void genericEndpointsEnforceReviewOwnershipBeforeReadingTaskData() {
    PollableTask task = new PollableTask();
    task.setId(91L);
    task.setName(com.box.l10n.mojito.service.oaireview.AiReviewChatJob.class.getCanonicalName());
    PollableTaskWS ws = new PollableTaskWS();
    ws.pollableTaskService = new StubPollableTaskService(task);
    ws.aiReviewChatJobAccess = mock(AiReviewChatJobAccess.class);
    ws.pollableTaskBlobStorage = mock(PollableTaskBlobStorage.class);
    doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
        .when(ws.aiReviewChatJobAccess)
        .assertCanRead(task);

    assertThatThrownBy(() -> ws.getPollableTaskById(91L))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> ws.getPollableTaskInput(91L))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> ws.getPollableTaskOutput(91L))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(ws.pollableTaskBlobStorage);
  }

  @Test
  public void testGetPollableTask() {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(91L);
    pollableTask.setName("testGetPollableTask");

    PollableTaskWS ws = new PollableTaskWS();
    ws.aiReviewChatJobAccess = mock(AiReviewChatJobAccess.class);
    ws.pollableTaskService = new StubPollableTaskService(pollableTask);
    ws.tmXliffRepository = emptyTmxliffRepository();
    ws.pollableTaskBlobStorage = new PollableTaskBlobStorage();
    ws.pollableTaskInspectionService = stubInspectionService(null);

    PollableTask returnedTask = ws.getPollableTaskById(91L);

    assertThat(returnedTask.getName()).isEqualTo("testGetPollableTask");
  }

  @Test
  public void testGetPollableTaskInspection() {
    PollableTaskInspectionService.TaskInspection inspection =
        new PollableTaskInspectionService.TaskInspection(
            50255159L,
            PollableTaskInspectionService.TaskStatus.FAILED,
            "ImportLocalizedAssetJob",
            "com.box.l10n.mojito.service.tm.ImportLocalizedAssetJob",
            null,
            new PollableTaskInspectionService.TaskRepositoryRef(7L, "privacy-transcend", "input"),
            null,
            null,
            null,
            0,
            0,
            List.of(),
            null,
            null,
            null,
            List.of(),
            new PollableTaskInspectionService.TaskLinks(
                "/api/pollableTasks/50255159",
                "/api/pollableTasks/50255159/inspection",
                "/api/pollableTasks/50255159/input",
                "/api/pollableTasks/50255159/output"));

    PollableTaskWS ws = new PollableTaskWS();
    ws.aiReviewChatJobAccess = mock(AiReviewChatJobAccess.class);
    ws.pollableTaskService = new StubPollableTaskService(null);
    ws.tmXliffRepository = emptyTmxliffRepository();
    ws.pollableTaskBlobStorage = new PollableTaskBlobStorage();
    ws.pollableTaskInspectionService = stubInspectionService(inspection);

    PollableTaskInspectionService.TaskInspection returnedInspection =
        ws.getPollableTaskInspection(50255159L);

    assertThat(returnedInspection).isEqualTo(inspection);
  }

  @Test
  public void testGetPollableTaskInspectionReturnsNotFoundForMissingTask() {
    PollableTaskWS ws = new PollableTaskWS();
    ws.aiReviewChatJobAccess = mock(AiReviewChatJobAccess.class);
    ws.pollableTaskService = new StubPollableTaskService(null);
    ws.tmXliffRepository = emptyTmxliffRepository();
    ws.pollableTaskBlobStorage = new PollableTaskBlobStorage();
    ws.pollableTaskInspectionService = stubInspectionService(null);

    assertThatThrownBy(() -> ws.getPollableTaskInspection(404L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            throwable ->
                assertThat(((ResponseStatusException) throwable).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  private PollableTaskInspectionService stubInspectionService(
      PollableTaskInspectionService.TaskInspection inspection) {
    return new PollableTaskInspectionService(
        new StubPollableTaskService(null),
        new PollableTaskBlobStorage(),
        emptyRepositoryRepository(),
        com.box.l10n.mojito.json.ObjectMapper.withNoFailOnUnknownProperties(),
        mock(AiReviewChatJobAccess.class)) {
      @Override
      public TaskInspection inspectTask(long pollableTaskId) {
        if (inspection == null) {
          throw new IllegalArgumentException("Pollable task not found: " + pollableTaskId);
        }
        return inspection;
      }
    };
  }

  private static class StubPollableTaskService extends PollableTaskService {
    private final PollableTask pollableTask;

    private StubPollableTaskService(PollableTask pollableTask) {
      this.pollableTask = pollableTask;
    }

    @Override
    public PollableTask getPollableTask(long id) {
      return pollableTask;
    }
  }

  private com.box.l10n.mojito.service.repository.RepositoryRepository emptyRepositoryRepository() {
    return (com.box.l10n.mojito.service.repository.RepositoryRepository)
        Proxy.newProxyInstance(
            com.box.l10n.mojito.service.repository.RepositoryRepository.class.getClassLoader(),
            new Class<?>[] {com.box.l10n.mojito.service.repository.RepositoryRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("findNoGraphById")) {
                return Optional.empty();
              }
              if (method.getName().equals("findByName")) {
                return null;
              }
              if (method.getDeclaringClass().equals(Object.class)) {
                return method.invoke(this, args);
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private TMXliffRepository emptyTmxliffRepository() {
    return (TMXliffRepository)
        Proxy.newProxyInstance(
            TMXliffRepository.class.getClassLoader(),
            new Class<?>[] {TMXliffRepository.class},
            (proxy, method, args) -> {
              if (method.getDeclaringClass().equals(Object.class)) {
                return method.invoke(this, args);
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
