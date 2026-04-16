package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "translation_incident")
public class TranslationIncident extends AuditableEntity {

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 64)
  private TranslationIncidentStatus status;

  @Column(name = "lookup_resolution_status", nullable = false, length = 64)
  private String lookupResolutionStatus;

  @Column(name = "locale_resolution_strategy", nullable = false, length = 64)
  private String localeResolutionStrategy;

  @Column(name = "locale_used_fallback", nullable = false)
  private boolean localeUsedFallback;

  @Column(name = "repository_name")
  private String repositoryName;

  @Column(name = "string_id", nullable = false, length = 1024)
  private String stringId;

  @Column(name = "observed_locale", nullable = false, length = 64)
  private String observedLocale;

  @Column(name = "resolved_locale", length = 64)
  private String resolvedLocale;

  @Column(name = "resolved_locale_id")
  private Long resolvedLocaleId;

  @Column(name = "reason", length = Integer.MAX_VALUE, nullable = false)
  private String reason;

  @Column(name = "source_reference", length = 2048)
  private String sourceReference;

  @Column(name = "lookup_candidate_count", nullable = false)
  private int lookupCandidateCount;

  @Column(name = "selected_tm_text_unit_id")
  private Long selectedTmTextUnitId;

  @Column(name = "selected_tm_text_unit_current_variant_id")
  private Long selectedTmTextUnitCurrentVariantId;

  @Column(name = "selected_tm_text_unit_variant_id")
  private Long selectedTmTextUnitVariantId;

  @Column(name = "selected_asset_path", length = 1024)
  private String selectedAssetPath;

  @Column(name = "selected_source", length = Integer.MAX_VALUE)
  private String selectedSource;

  @Column(name = "selected_target", length = Integer.MAX_VALUE)
  private String selectedTarget;

  @Column(name = "selected_target_comment", length = Integer.MAX_VALUE)
  private String selectedTargetComment;

  @Column(name = "selected_translation_status", length = 64)
  private String selectedTranslationStatus;

  @Column(name = "selected_included_in_localized_file")
  private Boolean selectedIncludedInLocalizedFile;

  @Column(name = "selected_can_reject")
  private Boolean selectedCanReject;

  @Column(name = "review_project_id")
  private Long reviewProjectId;

  @Column(name = "review_project_request_id")
  private Long reviewProjectRequestId;

  @Column(name = "review_project_name")
  private String reviewProjectName;

  @Column(name = "review_project_link", length = 1024)
  private String reviewProjectLink;

  @Column(name = "review_project_confidence", length = 32)
  private String reviewProjectConfidence;

  @Column(name = "review_project_confidence_score")
  private Integer reviewProjectConfidenceScore;

  @Column(name = "translation_author_username")
  private String translationAuthorUsername;

  @Column(name = "reviewer_username")
  private String reviewerUsername;

  @Column(name = "owner_username")
  private String ownerUsername;

  @Column(name = "translation_author_slack_mention")
  private String translationAuthorSlackMention;

  @Column(name = "reviewer_slack_mention")
  private String reviewerSlackMention;

  @Column(name = "owner_slack_mention")
  private String ownerSlackMention;

  @Column(name = "slack_destination_source", length = 64)
  private String slackDestinationSource;

  @Column(name = "slack_client_id")
  private String slackClientId;

  @Column(name = "slack_channel_id")
  private String slackChannelId;

  @Column(name = "slack_thread_ts")
  private String slackThreadTs;

  @Column(name = "slack_can_send")
  private Boolean slackCanSend;

  @Column(name = "slack_note", length = Integer.MAX_VALUE)
  private String slackNote;

  @Column(name = "slack_draft", length = Integer.MAX_VALUE)
  private String slackDraft;

  @Column(name = "lookup_candidates_json", length = Integer.MAX_VALUE)
  private String lookupCandidatesJson;

  @Column(name = "review_project_candidates_json", length = Integer.MAX_VALUE)
  private String reviewProjectCandidatesJson;

  @Column(name = "reject_audit_comment", length = Integer.MAX_VALUE)
  private String rejectAuditComment;

  @Column(name = "reject_audit_comment_id")
  private Long rejectAuditCommentId;

  @Column(name = "rejected_by_username")
  private String rejectedByUsername;

  @Column(name = "rejected_at")
  private java.time.ZonedDateTime rejectedAt;

  public TranslationIncidentStatus getStatus() {
    return status;
  }

  public void setStatus(TranslationIncidentStatus status) {
    this.status = status;
  }

  public String getLookupResolutionStatus() {
    return lookupResolutionStatus;
  }

  public void setLookupResolutionStatus(String lookupResolutionStatus) {
    this.lookupResolutionStatus = lookupResolutionStatus;
  }

  public String getLocaleResolutionStrategy() {
    return localeResolutionStrategy;
  }

  public void setLocaleResolutionStrategy(String localeResolutionStrategy) {
    this.localeResolutionStrategy = localeResolutionStrategy;
  }

  public boolean isLocaleUsedFallback() {
    return localeUsedFallback;
  }

  public void setLocaleUsedFallback(boolean localeUsedFallback) {
    this.localeUsedFallback = localeUsedFallback;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public void setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
  }

  public String getStringId() {
    return stringId;
  }

  public void setStringId(String stringId) {
    this.stringId = stringId;
  }

  public String getObservedLocale() {
    return observedLocale;
  }

  public void setObservedLocale(String observedLocale) {
    this.observedLocale = observedLocale;
  }

  public String getResolvedLocale() {
    return resolvedLocale;
  }

  public void setResolvedLocale(String resolvedLocale) {
    this.resolvedLocale = resolvedLocale;
  }

  public Long getResolvedLocaleId() {
    return resolvedLocaleId;
  }

  public void setResolvedLocaleId(Long resolvedLocaleId) {
    this.resolvedLocaleId = resolvedLocaleId;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getSourceReference() {
    return sourceReference;
  }

  public void setSourceReference(String sourceReference) {
    this.sourceReference = sourceReference;
  }

  public int getLookupCandidateCount() {
    return lookupCandidateCount;
  }

  public void setLookupCandidateCount(int lookupCandidateCount) {
    this.lookupCandidateCount = lookupCandidateCount;
  }

  public Long getSelectedTmTextUnitId() {
    return selectedTmTextUnitId;
  }

  public void setSelectedTmTextUnitId(Long selectedTmTextUnitId) {
    this.selectedTmTextUnitId = selectedTmTextUnitId;
  }

  public Long getSelectedTmTextUnitCurrentVariantId() {
    return selectedTmTextUnitCurrentVariantId;
  }

  public void setSelectedTmTextUnitCurrentVariantId(Long selectedTmTextUnitCurrentVariantId) {
    this.selectedTmTextUnitCurrentVariantId = selectedTmTextUnitCurrentVariantId;
  }

  public Long getSelectedTmTextUnitVariantId() {
    return selectedTmTextUnitVariantId;
  }

  public void setSelectedTmTextUnitVariantId(Long selectedTmTextUnitVariantId) {
    this.selectedTmTextUnitVariantId = selectedTmTextUnitVariantId;
  }

  public String getSelectedAssetPath() {
    return selectedAssetPath;
  }

  public void setSelectedAssetPath(String selectedAssetPath) {
    this.selectedAssetPath = selectedAssetPath;
  }

  public String getSelectedSource() {
    return selectedSource;
  }

  public void setSelectedSource(String selectedSource) {
    this.selectedSource = selectedSource;
  }

  public String getSelectedTarget() {
    return selectedTarget;
  }

  public void setSelectedTarget(String selectedTarget) {
    this.selectedTarget = selectedTarget;
  }

  public String getSelectedTargetComment() {
    return selectedTargetComment;
  }

  public void setSelectedTargetComment(String selectedTargetComment) {
    this.selectedTargetComment = selectedTargetComment;
  }

  public String getSelectedTranslationStatus() {
    return selectedTranslationStatus;
  }

  public void setSelectedTranslationStatus(String selectedTranslationStatus) {
    this.selectedTranslationStatus = selectedTranslationStatus;
  }

  public Boolean getSelectedIncludedInLocalizedFile() {
    return selectedIncludedInLocalizedFile;
  }

  public void setSelectedIncludedInLocalizedFile(Boolean selectedIncludedInLocalizedFile) {
    this.selectedIncludedInLocalizedFile = selectedIncludedInLocalizedFile;
  }

  public Boolean getSelectedCanReject() {
    return selectedCanReject;
  }

  public void setSelectedCanReject(Boolean selectedCanReject) {
    this.selectedCanReject = selectedCanReject;
  }

  public Long getReviewProjectId() {
    return reviewProjectId;
  }

  public void setReviewProjectId(Long reviewProjectId) {
    this.reviewProjectId = reviewProjectId;
  }

  public Long getReviewProjectRequestId() {
    return reviewProjectRequestId;
  }

  public void setReviewProjectRequestId(Long reviewProjectRequestId) {
    this.reviewProjectRequestId = reviewProjectRequestId;
  }

  public String getReviewProjectName() {
    return reviewProjectName;
  }

  public void setReviewProjectName(String reviewProjectName) {
    this.reviewProjectName = reviewProjectName;
  }

  public String getReviewProjectLink() {
    return reviewProjectLink;
  }

  public void setReviewProjectLink(String reviewProjectLink) {
    this.reviewProjectLink = reviewProjectLink;
  }

  public String getReviewProjectConfidence() {
    return reviewProjectConfidence;
  }

  public void setReviewProjectConfidence(String reviewProjectConfidence) {
    this.reviewProjectConfidence = reviewProjectConfidence;
  }

  public Integer getReviewProjectConfidenceScore() {
    return reviewProjectConfidenceScore;
  }

  public void setReviewProjectConfidenceScore(Integer reviewProjectConfidenceScore) {
    this.reviewProjectConfidenceScore = reviewProjectConfidenceScore;
  }

  public String getTranslationAuthorUsername() {
    return translationAuthorUsername;
  }

  public void setTranslationAuthorUsername(String translationAuthorUsername) {
    this.translationAuthorUsername = translationAuthorUsername;
  }

  public String getReviewerUsername() {
    return reviewerUsername;
  }

  public void setReviewerUsername(String reviewerUsername) {
    this.reviewerUsername = reviewerUsername;
  }

  public String getOwnerUsername() {
    return ownerUsername;
  }

  public void setOwnerUsername(String ownerUsername) {
    this.ownerUsername = ownerUsername;
  }

  public String getTranslationAuthorSlackMention() {
    return translationAuthorSlackMention;
  }

  public void setTranslationAuthorSlackMention(String translationAuthorSlackMention) {
    this.translationAuthorSlackMention = translationAuthorSlackMention;
  }

  public String getReviewerSlackMention() {
    return reviewerSlackMention;
  }

  public void setReviewerSlackMention(String reviewerSlackMention) {
    this.reviewerSlackMention = reviewerSlackMention;
  }

  public String getOwnerSlackMention() {
    return ownerSlackMention;
  }

  public void setOwnerSlackMention(String ownerSlackMention) {
    this.ownerSlackMention = ownerSlackMention;
  }

  public String getSlackDestinationSource() {
    return slackDestinationSource;
  }

  public void setSlackDestinationSource(String slackDestinationSource) {
    this.slackDestinationSource = slackDestinationSource;
  }

  public String getSlackChannelId() {
    return slackChannelId;
  }

  public String getSlackClientId() {
    return slackClientId;
  }

  public void setSlackClientId(String slackClientId) {
    this.slackClientId = slackClientId;
  }

  public void setSlackChannelId(String slackChannelId) {
    this.slackChannelId = slackChannelId;
  }

  public String getSlackThreadTs() {
    return slackThreadTs;
  }

  public void setSlackThreadTs(String slackThreadTs) {
    this.slackThreadTs = slackThreadTs;
  }

  public Boolean getSlackCanSend() {
    return slackCanSend;
  }

  public void setSlackCanSend(Boolean slackCanSend) {
    this.slackCanSend = slackCanSend;
  }

  public String getSlackNote() {
    return slackNote;
  }

  public void setSlackNote(String slackNote) {
    this.slackNote = slackNote;
  }

  public String getSlackDraft() {
    return slackDraft;
  }

  public void setSlackDraft(String slackDraft) {
    this.slackDraft = slackDraft;
  }

  public String getLookupCandidatesJson() {
    return lookupCandidatesJson;
  }

  public void setLookupCandidatesJson(String lookupCandidatesJson) {
    this.lookupCandidatesJson = lookupCandidatesJson;
  }

  public String getReviewProjectCandidatesJson() {
    return reviewProjectCandidatesJson;
  }

  public void setReviewProjectCandidatesJson(String reviewProjectCandidatesJson) {
    this.reviewProjectCandidatesJson = reviewProjectCandidatesJson;
  }

  public String getRejectAuditComment() {
    return rejectAuditComment;
  }

  public void setRejectAuditComment(String rejectAuditComment) {
    this.rejectAuditComment = rejectAuditComment;
  }

  public Long getRejectAuditCommentId() {
    return rejectAuditCommentId;
  }

  public void setRejectAuditCommentId(Long rejectAuditCommentId) {
    this.rejectAuditCommentId = rejectAuditCommentId;
  }

  public String getRejectedByUsername() {
    return rejectedByUsername;
  }

  public void setRejectedByUsername(String rejectedByUsername) {
    this.rejectedByUsername = rejectedByUsername;
  }

  public java.time.ZonedDateTime getRejectedAt() {
    return rejectedAt;
  }

  public void setRejectedAt(java.time.ZonedDateTime rejectedAt) {
    this.rejectedAt = rejectedAt;
  }
}
