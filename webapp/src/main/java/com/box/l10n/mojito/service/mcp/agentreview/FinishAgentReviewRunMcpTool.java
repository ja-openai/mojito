package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.FinishRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.RunView;
import com.box.l10n.mojito.service.agentreview.AgentReviewProjectService;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class FinishAgentReviewRunMcpTool
    extends AgentReviewMcpTool<FinishAgentReviewRunMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.finish_run",
          "Finish shared translation review run",
          "Finish agent execution after saving coverage, or explicitly cancel. Completion preserves failures/missing-input coverage and never resolves human review work. Returns committed run plus separate project routing results. Requires PM/admin with the current claim and expected revision; does not apply translations.",
          false,
          false,
          List.of(
              runId(),
              new McpToolParameter(
                  "completion",
                  "Active claim, expected run revision, and explicit cancel flag (normally false).",
                  true,
                  completion())));

  private final AgentReviewService service;
  private final AgentReviewProjectService projectService;

  public FinishAgentReviewRunMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service,
      AgentReviewProjectService projectService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
    this.projectService = projectService;
  }

  public record Input(Long runId, FinishRequest completion) {}

  public record Result(RunView run, AgentReviewProjectService.RouteResult routing) {}

  @Override
  protected Object executeAuthorized(Input input) {
    long runId = requireId(input.runId(), "runId");
    RunView run = service.finishRun(runId, input.completion());
    return new Result(run, projectService.routeRun(runId));
  }
}
