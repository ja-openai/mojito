package com.box.l10n.mojito.service.mcp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskInspectionService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class InspectTaskMcpToolTest {

  @Test
  public void executeReturnsTaskInspectionFromService() {
    RecordingInspectionService pollableTaskInspectionService = new RecordingInspectionService();
    pollableTaskInspectionService.inspection =
        new PollableTaskInspectionService.TaskInspection(
            50255159L,
            PollableTaskInspectionService.TaskStatus.FAILED,
            "SmartlingPullLocaleFileJob",
            "com.box.l10n.mojito.service.thirdparty.smartling.quartz.SmartlingPullLocaleFileJob",
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

    InspectTaskMcpTool tool =
        new InspectTaskMcpTool(
            ObjectMapper.withNoFailOnUnknownProperties(), pollableTaskInspectionService);

    Object result = tool.execute(new InspectTaskMcpTool.Input(50255159L));

    assertThat(result).isEqualTo(pollableTaskInspectionService.inspection);
    assertThat(pollableTaskInspectionService.seenTaskId).isEqualTo(50255159L);
  }

  @Test
  public void executeRequiresTaskId() {
    InspectTaskMcpTool tool =
        new InspectTaskMcpTool(
            ObjectMapper.withNoFailOnUnknownProperties(), new RecordingInspectionService());

    assertThatThrownBy(() -> tool.execute(new InspectTaskMcpTool.Input(null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("taskId is required");
  }

  private static class RecordingInspectionService extends PollableTaskInspectionService {
    private Long seenTaskId;
    private TaskInspection inspection;

    private RecordingInspectionService() {
      super(
          new PollableTaskService(),
          new PollableTaskBlobStorage(),
          emptyRepositoryRepository(),
          ObjectMapper.withNoFailOnUnknownProperties());
    }

    @Override
    public TaskInspection inspectTask(long pollableTaskId) {
      seenTaskId = pollableTaskId;
      return inspection;
    }

    private static RepositoryRepository emptyRepositoryRepository() {
      return (RepositoryRepository)
          Proxy.newProxyInstance(
              RepositoryRepository.class.getClassLoader(),
              new Class<?>[] {RepositoryRepository.class},
              (proxy, method, args) -> {
                if (method.getName().equals("findNoGraphById")) {
                  return Optional.empty();
                }
                if (method.getName().equals("findByName")) {
                  return null;
                }
                if (method.getDeclaringClass().equals(Object.class)) {
                  return method.invoke(proxy, args);
                }
                throw new UnsupportedOperationException(method.getName());
              });
    }
  }
}
