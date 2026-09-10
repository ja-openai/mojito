package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.agentreview.OriginalAssessment;
import com.box.l10n.mojito.entity.agentreview.SuggestionAssessment;

/** Human judgment submitted with the ordinary guarded Review Project decision. */
public record AgentReviewDecisionRequest(
    Long proposalId,
    Integer proposalRevision,
    Long proposalVersion,
    String requestId,
    Action action,
    OriginalAssessment originalAssessment,
    SuggestionAssessment suggestionAssessment,
    String explanation) {
  public enum Action {
    ACCEPT,
    KEEP_CURRENT,
    DEFER,
    REQUEST_REVISION
  }
}
