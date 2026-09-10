package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.SubmissionResult;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.SubmitProposalRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SubmitAgentReviewProposalsMcpTool
    extends AgentReviewMcpTool<SubmitAgentReviewProposalsMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.submit_proposals",
          "Stage translation review proposals",
          "Persist 1-50 independently idempotent findings outside TM. Each item retains its frozen baseline and verifier evidence; inspect every ordered result for errors. Reuse submissionKey and identical payload on retry. A revision uses previousProposalId and respondsToFeedbackId. READY requires independent verification; optional wording stays OPTIONAL. Requires PM/admin and active run claim; never applies translations.",
          false,
          false,
          List.of(
              runId(),
              new McpToolParameter(
                  "proposals",
                  "Independent findings or revisions. Exact source/baseline strings must not be trimmed or normalized; null baseline means no current translation. Batch boundaries do not determine project grouping.",
                  true,
                  proposals())));

  private final AgentReviewService service;

  public SubmitAgentReviewProposalsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long runId, List<SubmitProposalRequest> proposals) {}

  public record Result(List<SubmissionResult> results) {}

  @Override
  protected Object executeAuthorized(Input input) {
    return new Result(
        service.submitProposals(requireId(input.runId(), "runId"), input.proposals()));
  }
}
