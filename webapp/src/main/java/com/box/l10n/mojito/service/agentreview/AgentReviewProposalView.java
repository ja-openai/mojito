package com.box.l10n.mojito.service.agentreview;

import java.util.List;

/** Immutable proposal context alongside the current editable Review Project row. */
public record AgentReviewProposalView(
    Long proposalId,
    int proposalRevision,
    long proposalVersion,
    String findingId,
    Long runId,
    String reviewType,
    String reviewedSource,
    String reviewedTarget,
    String proposedTarget,
    String rationale,
    String category,
    String verificationStatus,
    String verificationNotes,
    String integrityDiagnostics,
    List<Evidence> evidence,
    String disposition,
    boolean stale,
    String lastFeedbackRequestId,
    boolean canReconsider) {
  public record Evidence(String label, String url) {}
}
