package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.entity.agentreview.AgentReviewFeedback;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AgentReviewFeedbackHistoryMcpTool
    extends AgentReviewMcpTool<AgentReviewFeedbackHistoryMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.feedback_history",
          "Read preserved review feedback",
          "Read append-only human judgments and agent responses for one exact proposal revision, including already consumed rounds. Use proposal_history to discover other revisions. Requires PM/admin with access; reading never changes feedback state.",
          true,
          true,
          List.of(
              new McpToolParameter(
                  "proposalId",
                  "Exact proposal revision whose feedback is requested.",
                  true,
                  positiveId()),
              afterId(),
              pageLimit()));

  private final AgentReviewService service;

  public AgentReviewFeedbackHistoryMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long proposalId, Long afterId, Integer limit) {}

  public record Result(List<AgentReviewFeedback> feedback, Long nextAfterId) {}

  @Override
  protected Object executeAuthorized(Input input) {
    int limit = limit(input.limit());
    List<AgentReviewFeedback> feedback =
        service.feedbackHistory(
            requireId(input.proposalId(), "proposalId"), cursor(input.afterId()), limit);
    return new Result(
        feedback, feedback.size() == limit ? feedback.get(feedback.size() - 1).getId() : null);
  }
}
