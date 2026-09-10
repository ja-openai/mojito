package com.box.l10n.mojito.entity.agentreview;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Durable review history; scalar historical links intentionally do not cascade on project deletion.
 */
@org.hibernate.annotations.Immutable
@Entity
@Table(
    name = "agent_review_feedback",
    indexes = {
      @Index(
          name = "UK__AGENT_REVIEW_FEEDBACK__REQUEST",
          columnList = "proposal_id,actor_type,request_key",
          unique = true),
      @Index(
          name = "UK__AGENT_REVIEW_FEEDBACK__RESPONSE",
          columnList = "responds_to_feedback_id",
          unique = true),
      @Index(
          name = "I__AGENT_REVIEW_FEEDBACK__PENDING",
          columnList = "proposal_id,follow_up_requested",
          unique = false)
    })
public class AgentReviewFeedback extends AuditableEntity {
  @Column(name = "response_run_id")
  private Long responseRunId;

  public Long getResponseRunId() {
    return responseRunId;
  }

  public void setResponseRunId(Long responseRunId) {
    this.responseRunId = responseRunId;
  }

  @Column(name = "proposal_id", nullable = false)
  private Long proposalId;

  @Column(name = "request_key", nullable = false, length = 128)
  private String requestKey;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false, length = 16)
  private ActorType actorType;

  @Column(name = "actor_user_id", nullable = false)
  private Long actorUserId;

  @Column(name = "actor_identity", nullable = false, length = 255)
  private String actorIdentity;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 32)
  private FeedbackAction action;

  @Enumerated(EnumType.STRING)
  @Column(name = "original_assessment", nullable = true, length = 32)
  private OriginalAssessment originalAssessment;

  @Enumerated(EnumType.STRING)
  @Column(name = "suggestion_assessment", nullable = true, length = 32)
  private SuggestionAssessment suggestionAssessment;

  @Column(name = "explanation", nullable = true, length = Integer.MAX_VALUE)
  private String explanation;

  @Column(name = "evidence_json", nullable = true, length = Integer.MAX_VALUE)
  private String evidenceJson;

  @Column(name = "follow_up_requested", nullable = false)
  private boolean followUpRequested;

  @Column(name = "responds_to_feedback_id", nullable = true)
  private Long respondsToFeedbackId;

  @Column(name = "response_proposal_id", nullable = true)
  private Long responseProposalId;

  @Column(name = "final_target", nullable = true, length = Integer.MAX_VALUE)
  private String finalTarget;

  @Column(name = "applied_variant_id", nullable = true)
  private Long appliedVariantId;

  public Long getProposalId() {
    return proposalId;
  }

  public void setProposalId(Long proposalId) {
    this.proposalId = proposalId;
  }

  public String getRequestKey() {
    return requestKey;
  }

  public void setRequestKey(String requestKey) {
    this.requestKey = requestKey;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public void setRequestFingerprint(String requestFingerprint) {
    this.requestFingerprint = requestFingerprint;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public void setActorType(ActorType actorType) {
    this.actorType = actorType;
  }

  public Long getActorUserId() {
    return actorUserId;
  }

  public void setActorUserId(Long actorUserId) {
    this.actorUserId = actorUserId;
  }

  public String getActorIdentity() {
    return actorIdentity;
  }

  public void setActorIdentity(String actorIdentity) {
    this.actorIdentity = actorIdentity;
  }

  public FeedbackAction getAction() {
    return action;
  }

  public void setAction(FeedbackAction action) {
    this.action = action;
  }

  public OriginalAssessment getOriginalAssessment() {
    return originalAssessment;
  }

  public void setOriginalAssessment(OriginalAssessment originalAssessment) {
    this.originalAssessment = originalAssessment;
  }

  public SuggestionAssessment getSuggestionAssessment() {
    return suggestionAssessment;
  }

  public void setSuggestionAssessment(SuggestionAssessment suggestionAssessment) {
    this.suggestionAssessment = suggestionAssessment;
  }

  public String getExplanation() {
    return explanation;
  }

  public void setExplanation(String explanation) {
    this.explanation = explanation;
  }

  public String getEvidenceJson() {
    return evidenceJson;
  }

  public void setEvidenceJson(String evidenceJson) {
    this.evidenceJson = evidenceJson;
  }

  public boolean getFollowUpRequested() {
    return followUpRequested;
  }

  public void setFollowUpRequested(boolean followUpRequested) {
    this.followUpRequested = followUpRequested;
  }

  public Long getRespondsToFeedbackId() {
    return respondsToFeedbackId;
  }

  public void setRespondsToFeedbackId(Long respondsToFeedbackId) {
    this.respondsToFeedbackId = respondsToFeedbackId;
  }

  public Long getResponseProposalId() {
    return responseProposalId;
  }

  public void setResponseProposalId(Long responseProposalId) {
    this.responseProposalId = responseProposalId;
  }

  public String getFinalTarget() {
    return finalTarget;
  }

  public void setFinalTarget(String finalTarget) {
    this.finalTarget = finalTarget;
  }

  public Long getAppliedVariantId() {
    return appliedVariantId;
  }

  public void setAppliedVariantId(Long appliedVariantId) {
    this.appliedVariantId = appliedVariantId;
  }
}
