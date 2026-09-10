package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.entity.agentreview.AgentReviewFeedback;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class PendingAgentReviewFeedbackMcpTool
    extends AgentReviewMcpTool<PendingAgentReviewFeedbackMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.pending_feedback",
          "Read pending human review feedback",
          "Read unconsumed human feedback requesting agent follow-up, including separate assessments of the original and suggestion. Works after the original run completes. Reading never consumes feedback or changes a decision. Requires PM/admin with run access.",
          true,
          true,
          List.of(runId(), afterId(), pageLimit()));

  private final AgentReviewService service;

  public PendingAgentReviewFeedbackMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long runId, Long afterId, Integer limit) {}

  public record Result(List<AgentReviewFeedback> feedback, Long nextAfterId) {}

  @Override
  protected Object executeAuthorized(Input input) {
    int limit = limit(input.limit());
    List<AgentReviewFeedback> feedback =
        service.pendingFeedback(requireId(input.runId(), "runId"), cursor(input.afterId()), limit);
    return new Result(
        feedback, feedback.size() == limit ? feedback.get(feedback.size() - 1).getId() : null);
  }
}
