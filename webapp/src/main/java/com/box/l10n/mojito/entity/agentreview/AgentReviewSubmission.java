package com.box.l10n.mojito.entity.agentreview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A duplicate submission retains its own retry contract without cloning a finding. */
@Entity
@Table(name = "agent_review_submission")
public class AgentReviewSubmission {
  @Id
  @Column(name = "id", length = 64)
  private String id;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "proposal_id", nullable = false)
  private Long proposalId;

  public AgentReviewSubmission() {}

  public AgentReviewSubmission(String id, String requestFingerprint, Long proposalId) {
    this.id = id;
    this.requestFingerprint = requestFingerprint;
    this.proposalId = proposalId;
  }

  public String getId() {
    return id;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public Long getProposalId() {
    return proposalId;
  }
}
