package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class InspectAgentReviewRunMcpTool
    extends AgentReviewMcpTool<InspectAgentReviewRunMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.inspect_run",
          "Inspect shared translation review run",
          "Read scope, input/checkpoint artifact references, coverage, ownership generation, lease, and revision. Requires PM or admin with run access. This read does not claim or resume execution.",
          true,
          true,
          List.of(runId()));

  private final AgentReviewService service;

  public InspectAgentReviewRunMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long runId) {}

  @Override
  protected Object executeAuthorized(Input input) {
    return service.getRun(requireId(input.runId(), "runId"));
  }
}
