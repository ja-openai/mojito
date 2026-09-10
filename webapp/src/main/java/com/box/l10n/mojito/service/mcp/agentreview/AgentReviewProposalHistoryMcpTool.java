package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AgentReviewProposalHistoryMcpTool
    extends AgentReviewMcpTool<AgentReviewProposalHistoryMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.proposal_history",
          "Read proposal revision history",
          "Read preserved revisions for the finding containing this proposal. Requires PM/admin with access. Source/baseline and evidence belong to each exact revision; later revisions do not overwrite earlier human judgments.",
          true,
          true,
          List.of(
              new McpToolParameter(
                  "proposalId", "Any proposal revision in the finding.", true, positiveId()),
              afterId(),
              pageLimit()));

  private final AgentReviewService service;

  public AgentReviewProposalHistoryMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long proposalId, Long afterId, Integer limit) {}

  public record Result(List<AgentReviewProposal> proposals, Long nextAfterId) {}

  @Override
  protected Object executeAuthorized(Input input) {
    int limit = limit(input.limit());
    List<AgentReviewProposal> proposals =
        service.proposalHistory(
            requireId(input.proposalId(), "proposalId"), cursor(input.afterId()), limit);
    return new Result(
        proposals, proposals.size() == limit ? proposals.get(proposals.size() - 1).getId() : null);
  }
}
