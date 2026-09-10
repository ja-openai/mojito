package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.ResponseRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RespondToAgentReviewFeedbackMcpTool
    extends AgentReviewMcpTool<RespondToAgentReviewFeedbackMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.respond_to_feedback",
          "Respond to human review feedback",
          "Append one evidence-backed CHALLENGE or CONTEXT_REQUEST to a human feedback round. Use submit_proposals with previousProposalId/respondsToFeedbackId for a revised fix instead. Same requestKey and payload acknowledge a retry; no response overwrites a human judgment or applies a translation. Requires PM/admin, active responding-run claim, and access to the original finding.",
          false,
          false,
          List.of(
              runId(),
              new McpToolParameter(
                  "response",
                  "Feedback id, active claim, stable retry key, actor identity, and explanation.",
                  true,
                  response())));

  private final AgentReviewService service;

  public RespondToAgentReviewFeedbackMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long runId, ResponseRequest response) {}

  @Override
  protected Object executeAuthorized(Input input) {
    return service.respondToFeedback(requireId(input.runId(), "runId"), input.response());
  }
}
