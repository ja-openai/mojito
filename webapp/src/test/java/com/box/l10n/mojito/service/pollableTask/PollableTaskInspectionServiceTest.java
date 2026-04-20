package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public class PollableTaskInspectionServiceTest {

  @Test
  public void inspectTaskResolvesRepositoryAndParsesUnexpectedFailureDetails() {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(50255159L);
    pollableTask.setName(
        "com.box.l10n.mojito.service.thirdparty.smartling.quartz.SmartlingPullLocaleFileJob");
    pollableTask.setCreatedDate(ZonedDateTime.parse("2026-04-20T10:15:30Z"));
    pollableTask.setErrorMessage(
        "{\"expected\":false,\"type\":\"unexpected\",\"message\":\"An unexpected error happened, task=50255159\"}");
    pollableTask.setErrorStack(
        "java.lang.IllegalStateException: privacy-transcend pull blew up\n\tat test.Line");
    pollableTask.setFinishedDate(ZonedDateTime.parse("2026-04-20T10:16:00Z"));

    Repository repository = new Repository();
    repository.setId(7L);
    repository.setName("privacy-transcend");

    StubPollableTaskService pollableTaskService =
        new StubPollableTaskService(pollableTask, List.of(pollableTask));
    StubPollableTaskBlobStorage pollableTaskBlobStorage = new StubPollableTaskBlobStorage();
    pollableTaskBlobStorage.putInputJson(50255159L, "{\"repositoryId\":7,\"job\":\"pull\"}");

    PollableTaskInspectionService service =
        new PollableTaskInspectionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            repositoryRepository(Optional.of(repository), null),
            ObjectMapper.withNoFailOnUnknownProperties());

    PollableTaskInspectionService.TaskInspection inspection = service.inspectTask(50255159L);

    assertThat(inspection.status()).isEqualTo(PollableTaskInspectionService.TaskStatus.FAILED);
    assertThat(inspection.operation()).isEqualTo("SmartlingPullLocaleFileJob");
    assertThat(inspection.repository().id()).isEqualTo(7L);
    assertThat(inspection.repository().name()).isEqualTo("privacy-transcend");
    assertThat(inspection.repository().resolvedFrom()).isEqualTo("input");
    assertThat(inspection.error().reportedType()).isEqualTo("unexpected");
    assertThat(inspection.error().reportedMessage())
        .isEqualTo("An unexpected error happened, task=50255159");
    assertThat(inspection.error().exceptionType()).isEqualTo("java.lang.IllegalStateException");
    assertThat(inspection.error().exceptionMessage()).isEqualTo("privacy-transcend pull blew up");
    assertThat(inspection.links().inspection()).isEqualTo("/api/pollableTasks/50255159/inspection");
  }

  @Test
  public void inspectTaskThrowsWhenTaskDoesNotExist() {
    PollableTaskInspectionService service =
        new PollableTaskInspectionService(
            new StubPollableTaskService(null, List.of()),
            new StubPollableTaskBlobStorage(),
            repositoryRepository(Optional.empty(), null),
            ObjectMapper.withNoFailOnUnknownProperties());

    assertThatThrownBy(() -> service.inspectTask(404L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Pollable task not found: 404");
  }

  private RepositoryRepository repositoryRepository(
      Optional<Repository> repositoryById, Repository repositoryByName) {
    return (RepositoryRepository)
        Proxy.newProxyInstance(
            RepositoryRepository.class.getClassLoader(),
            new Class<?>[] {RepositoryRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("findNoGraphById")) {
                return repositoryById;
              }
              if (method.getName().equals("findByName")) {
                return repositoryByName;
              }
              if (method.getDeclaringClass().equals(Object.class)) {
                return method.invoke(this, args);
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static class StubPollableTaskService extends PollableTaskService {
    private final PollableTask pollableTask;
    private final List<PollableTask> failures;

    private StubPollableTaskService(PollableTask pollableTask, List<PollableTask> failures) {
      this.pollableTask = pollableTask;
      this.failures = failures;
    }

    @Override
    public PollableTask getPollableTask(long id) {
      return pollableTask;
    }

    @Override
    public List<PollableTask> getAllPollableTasksWithError(PollableTask pollableTask) {
      return failures;
    }
  }

  private static class StubPollableTaskBlobStorage extends PollableTaskBlobStorage {
    private final Map<Long, String> inputJsonById = new HashMap<>();
    private final Map<Long, String> outputJsonById = new HashMap<>();

    @Override
    public Optional<String> findInputJson(Long pollableTaskId) {
      return Optional.ofNullable(inputJsonById.get(pollableTaskId));
    }

    @Override
    public Optional<String> findOutputJson(Long pollableTaskId) {
      return Optional.ofNullable(outputJsonById.get(pollableTaskId));
    }

    private void putInputJson(Long pollableTaskId, String value) {
      inputJsonById.put(pollableTaskId, value);
    }
  }
}
