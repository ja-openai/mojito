package com.box.l10n.mojito.service.mcp.task;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.service.pollableTask.PollableTaskInspectionService;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class InspectTaskMcpTool extends TypedMcpToolHandler<InspectTaskMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "task.inspect",
          "Inspect Mojito task",
          "Look up a Mojito pollable task by id and return its status, repository context, failure details, timestamps, and related API links.",
          true,
          true,
          List.of(new McpToolParameter("taskId", "Pollable task id to inspect.", true)));

  private final PollableTaskInspectionService pollableTaskInspectionService;

  public InspectTaskMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      PollableTaskInspectionService pollableTaskInspectionService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.pollableTaskInspectionService = pollableTaskInspectionService;
  }

  public record Input(Long taskId) {}

  @Override
  protected Object execute(Input input) {
    if (input.taskId() == null) {
      throw new IllegalArgumentException("taskId is required");
    }

    return pollableTaskInspectionService.inspectTask(input.taskId());
  }
}
