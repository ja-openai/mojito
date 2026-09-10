package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.ClaimRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.RunView;
import com.box.l10n.mojito.service.agentreview.AgentReviewProjectService;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ClaimAgentReviewRunMcpTool
    extends AgentReviewMcpTool<ClaimAgentReviewRunMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.claim_run",
          "Claim or renew shared review work",
          "Claim/resume a run or renew its coordinator lease using the last observed generation. Choose an opaque session owner id and reuse it within the coordinator. An active other owner cannot be displaced; expired takeover advances generation and fences old writes. Returns run state plus independent routing results. Requires PM/admin with run access.",
          false,
          false,
          List.of(
              runId(),
              new McpToolParameter(
                  "owner",
                  "Stable opaque identifier for this coordinator, for example a UUID.",
                  true,
                  string(128)),
              new McpToolParameter(
                  "expectedGeneration",
                  "Generation from inspect_run; 0 for an unclaimed new run.",
                  true,
                  Map.of("type", "integer", "minimum", 0)),
              new McpToolParameter(
                  "leaseSeconds",
                  "Lease duration, 30-1800 seconds; default 300. Renew before expiry.",
                  false,
                  integer(30, 1800))));

  private final AgentReviewService service;
  private final AgentReviewProjectService projectService;

  public ClaimAgentReviewRunMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service,
      AgentReviewProjectService projectService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
    this.projectService = projectService;
  }

  public record Input(Long runId, String owner, Long expectedGeneration, Integer leaseSeconds) {}

  public record Result(RunView run, AgentReviewProjectService.RouteResult routing) {}

  @Override
  protected Object executeAuthorized(Input input) {
    long runId = requireId(input.runId(), "runId");
    RunView run =
        service.claimRun(
            runId,
            new ClaimRequest(input.owner(), input.expectedGeneration(), input.leaseSeconds()));
    return new Result(run, projectService.routeRun(runId));
  }
}
