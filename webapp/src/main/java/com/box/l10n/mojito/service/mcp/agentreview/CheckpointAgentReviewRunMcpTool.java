package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.CheckpointRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.RunView;
import com.box.l10n.mojito.service.agentreview.AgentReviewProjectService;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class CheckpointAgentReviewRunMcpTool
    extends AgentReviewMcpTool<CheckpointAgentReviewRunMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.checkpoint",
          "Commit translation review progress",
          "Commit one group's coverage and previously uploaded artifact under an active claim and expected run revision. Use IN_PROGRESS to make input/context artifacts reachable before reviewing. COMPLETED includes groups with zero findings; missing inputs and failures stay explicit. Returns committed run state plus routing results; a routing error does not undo the checkpoint. Requires PM/admin; no TM changes.",
          false,
          false,
          List.of(
              runId(),
              new McpToolParameter(
                  "checkpoint",
                  "Group status, reviewed count, evidence digest, expected revision, and active claim.",
                  true,
                  checkpoint())));

  private final AgentReviewService service;
  private final AgentReviewProjectService projectService;

  public CheckpointAgentReviewRunMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service,
      AgentReviewProjectService projectService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
    this.projectService = projectService;
  }

  public record Input(Long runId, CheckpointRequest checkpoint) {}

  public record Result(RunView run, AgentReviewProjectService.RouteResult routing) {}

  @Override
  protected Object executeAuthorized(Input input) {
    long runId = requireId(input.runId(), "runId");
    RunView run = service.checkpoint(runId, input.checkpoint());
    return new Result(run, projectService.routeRun(runId));
  }
}
