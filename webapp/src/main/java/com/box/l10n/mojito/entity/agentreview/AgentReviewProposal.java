package com.box.l10n.mojito.entity.agentreview;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Durable review history; scalar historical links intentionally do not cascade on project deletion.
 */
@Entity
@Table(
    name = "agent_review_proposal",
    indexes = {
      @Index(
          name = "UK__AGENT_REVIEW_PROPOSAL__SUBMISSION",
          columnList = "run_id,submission_key",
          unique = true),
      @Index(
          name = "UK__AGENT_REVIEW_PROPOSAL__REVISION",
          columnList = "finding_id,proposal_revision",
          unique = true),
      @Index(
          name = "I__AGENT_REVIEW_PROPOSAL__READY",
          columnList = "run_id,readiness,disposition",
          unique = false),
      @Index(
          name = "I__AGENT_REVIEW_PROPOSAL__PROJECT_ROW",
          columnList = "review_project_text_unit_id",
          unique = false),
      @Index(
          name = "I__AGENT_REVIEW_PROPOSAL__INCIDENT",
          columnList = "incident_id",
          unique = false)
    })
public class AgentReviewProposal extends AuditableEntity {
  @Column(name = "run_id", nullable = false)
  private Long runId;

  @Column(name = "submission_key", nullable = false, length = 128)
  private String submissionKey;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "finding_id", nullable = false, length = 36)
  private String findingId;

  @Column(name = "proposal_revision", nullable = false)
  private int proposalRevision;

  @Column(name = "previous_proposal_id", nullable = true)
  private Long previousProposalId;

  @Column(name = "responds_to_feedback_id", nullable = true)
  private Long respondsToFeedbackId;

  @Column(name = "group_key", nullable = false, length = 128)
  private String groupKey;

  @Column(name = "repository_id", nullable = false)
  private Long repositoryId;

  @Column(name = "locale_id", nullable = false)
  private Long localeId;

  @Column(name = "tm_text_unit_id", nullable = false)
  private Long tmTextUnitId;

  @Column(name = "source", nullable = false, length = Integer.MAX_VALUE)
  private String source;

  @Column(name = "source_comment", nullable = true, length = Integer.MAX_VALUE)
  private String sourceComment;

  @Column(name = "baseline_variant_id", nullable = true)
  private Long baselineVariantId;

  @Column(name = "baseline_target", nullable = true, length = Integer.MAX_VALUE)
  private String baselineTarget;

  @Column(name = "baseline_status", nullable = true, length = 32)
  private String baselineStatus;

  @Column(name = "baseline_included_in_localized_file", nullable = true)
  private Boolean baselineIncludedInLocalizedFile;

  @Column(name = "proposed_target", nullable = true, length = Integer.MAX_VALUE)
  private String proposedTarget;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 32)
  private Category category;

  @Enumerated(EnumType.STRING)
  @Column(name = "readiness", nullable = false, length = 32)
  private Readiness readiness;

  @Enumerated(EnumType.STRING)
  @Column(name = "disposition", nullable = false, length = 32)
  private Disposition disposition;

  @Column(name = "rationale", nullable = false, length = Integer.MAX_VALUE)
  private String rationale;

  @Column(name = "evidence_json", nullable = true, length = Integer.MAX_VALUE)
  private String evidenceJson;

  @Column(name = "producer_identity", nullable = false, length = 255)
  private String producerIdentity;

  @Column(name = "verifier_identity", nullable = true, length = 255)
  private String verifierIdentity;

  @Column(name = "verification_rationale", nullable = true, length = Integer.MAX_VALUE)
  private String verificationRationale;

  @Column(name = "integrity_diagnostics", nullable = true, length = Integer.MAX_VALUE)
  private String integrityDiagnostics;

  @Column(name = "incident_id", nullable = true)
  private Long incidentId;

  @Column(name = "review_project_id", nullable = true)
  private Long reviewProjectId;

  @Column(name = "review_project_text_unit_id", nullable = true)
  private Long reviewProjectTextUnitId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  public Long getRunId() {
    return runId;
  }

  public void setRunId(Long runId) {
    this.runId = runId;
  }

  public String getSubmissionKey() {
    return submissionKey;
  }

  public void setSubmissionKey(String submissionKey) {
    this.submissionKey = submissionKey;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public void setRequestFingerprint(String requestFingerprint) {
    this.requestFingerprint = requestFingerprint;
  }

  public String getFindingId() {
    return findingId;
  }

  public void setFindingId(String findingId) {
    this.findingId = findingId;
  }

  public int getProposalRevision() {
    return proposalRevision;
  }

  public void setProposalRevision(int proposalRevision) {
    this.proposalRevision = proposalRevision;
  }

  public Long getPreviousProposalId() {
    return previousProposalId;
  }

  public void setPreviousProposalId(Long previousProposalId) {
    this.previousProposalId = previousProposalId;
  }

  public Long getRespondsToFeedbackId() {
    return respondsToFeedbackId;
  }

  public void setRespondsToFeedbackId(Long respondsToFeedbackId) {
    this.respondsToFeedbackId = respondsToFeedbackId;
  }

  public String getGroupKey() {
    return groupKey;
  }

  public void setGroupKey(String groupKey) {
    this.groupKey = groupKey;
  }

  public Long getRepositoryId() {
    return repositoryId;
  }

  public void setRepositoryId(Long repositoryId) {
    this.repositoryId = repositoryId;
  }

  public Long getLocaleId() {
    return localeId;
  }

  public void setLocaleId(Long localeId) {
    this.localeId = localeId;
  }

  public Long getTmTextUnitId() {
    return tmTextUnitId;
  }

  public void setTmTextUnitId(Long tmTextUnitId) {
    this.tmTextUnitId = tmTextUnitId;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getSourceComment() {
    return sourceComment;
  }

  public void setSourceComment(String sourceComment) {
    this.sourceComment = sourceComment;
  }

  public Long getBaselineVariantId() {
    return baselineVariantId;
  }

  public void setBaselineVariantId(Long baselineVariantId) {
    this.baselineVariantId = baselineVariantId;
  }

  public String getBaselineTarget() {
    return baselineTarget;
  }

  public void setBaselineTarget(String baselineTarget) {
    this.baselineTarget = baselineTarget;
  }

  public String getBaselineStatus() {
    return baselineStatus;
  }

  public void setBaselineStatus(String baselineStatus) {
    this.baselineStatus = baselineStatus;
  }

  public Boolean getBaselineIncludedInLocalizedFile() {
    return baselineIncludedInLocalizedFile;
  }

  public void setBaselineIncludedInLocalizedFile(Boolean baselineIncludedInLocalizedFile) {
    this.baselineIncludedInLocalizedFile = baselineIncludedInLocalizedFile;
  }

  public String getProposedTarget() {
    return proposedTarget;
  }

  public void setProposedTarget(String proposedTarget) {
    this.proposedTarget = proposedTarget;
  }

  public Category getCategory() {
    return category;
  }

  public void setCategory(Category category) {
    this.category = category;
  }

  public Readiness getReadiness() {
    return readiness;
  }

  public void setReadiness(Readiness readiness) {
    this.readiness = readiness;
  }

  public Disposition getDisposition() {
    return disposition;
  }

  public void setDisposition(Disposition disposition) {
    this.disposition = disposition;
  }

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String rationale) {
    this.rationale = rationale;
  }

  public String getEvidenceJson() {
    return evidenceJson;
  }

  public void setEvidenceJson(String evidenceJson) {
    this.evidenceJson = evidenceJson;
  }

  public String getProducerIdentity() {
    return producerIdentity;
  }

  public void setProducerIdentity(String producerIdentity) {
    this.producerIdentity = producerIdentity;
  }

  public String getVerifierIdentity() {
    return verifierIdentity;
  }

  public void setVerifierIdentity(String verifierIdentity) {
    this.verifierIdentity = verifierIdentity;
  }

  public String getVerificationRationale() {
    return verificationRationale;
  }

  public void setVerificationRationale(String verificationRationale) {
    this.verificationRationale = verificationRationale;
  }

  public String getIntegrityDiagnostics() {
    return integrityDiagnostics;
  }

  public void setIntegrityDiagnostics(String integrityDiagnostics) {
    this.integrityDiagnostics = integrityDiagnostics;
  }

  public Long getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(Long incidentId) {
    this.incidentId = incidentId;
  }

  public Long getReviewProjectId() {
    return reviewProjectId;
  }

  public void setReviewProjectId(Long reviewProjectId) {
    this.reviewProjectId = reviewProjectId;
  }

  public Long getReviewProjectTextUnitId() {
    return reviewProjectTextUnitId;
  }

  public void setReviewProjectTextUnitId(Long reviewProjectTextUnitId) {
    this.reviewProjectTextUnitId = reviewProjectTextUnitId;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }
}
