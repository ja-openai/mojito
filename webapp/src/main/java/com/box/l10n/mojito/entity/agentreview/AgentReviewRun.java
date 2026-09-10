package com.box.l10n.mojito.entity.agentreview;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

/**
 * Durable review history; scalar historical links intentionally do not cascade on project deletion.
 */
@Entity
@Table(
    name = "agent_review_run",
    indexes = {
      @Index(
          name = "UK__AGENT_REVIEW_RUN__REQUEST",
          columnList = "requested_by_user_id,request_key",
          unique = true),
      @Index(
          name = "I__AGENT_REVIEW_RUN__TEAM",
          columnList = "team_id,created_date",
          unique = false)
    })
public class AgentReviewRun extends AuditableEntity {
  @Column(name = "request_key", nullable = false, length = 128)
  private String requestKey;

  @Column(name = "input_fingerprint", nullable = false, length = 64)
  private String inputFingerprint;

  public String getInputFingerprint() {
    return inputFingerprint;
  }

  public void setInputFingerprint(String inputFingerprint) {
    this.inputFingerprint = inputFingerprint;
  }

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "requested_by_user_id", nullable = false)
  private Long requestedByUserId;

  @Column(name = "review_type", nullable = false, length = 64)
  private String reviewType;

  @Column(name = "team_id", nullable = false)
  private Long teamId;

  @Column(name = "repository_ids_json", nullable = false, length = Integer.MAX_VALUE)
  private String repositoryIdsJson;

  @Column(name = "locale_ids_json", nullable = false, length = Integer.MAX_VALUE)
  private String localeIdsJson;

  @Column(name = "method_version", nullable = false, length = 128)
  private String methodVersion;

  @Column(name = "configuration_version", nullable = false, length = 128)
  private String configurationVersion;

  @Column(name = "manifest_sha256", nullable = false, length = 64)
  private String manifestSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private RunStatus status;

  @Column(name = "planned_group_count", nullable = false)
  private int plannedGroupCount;

  @Column(name = "completed_group_count", nullable = false)
  private int completedGroupCount;

  @Column(name = "failed_group_count", nullable = false)
  private int failedGroupCount;

  @Column(name = "reviewed_item_count", nullable = false)
  private int reviewedItemCount;

  @Column(name = "checkpoint_sha256", nullable = true, length = 64)
  private String checkpointSha256;

  @Column(name = "checkpoint_request_fingerprint", nullable = true, length = 64)
  private String checkpointRequestFingerprint;

  @Column(name = "revision", nullable = false)
  private long revision;

  @Column(name = "claim_owner", nullable = true, length = 128)
  private String claimOwner;

  @Column(name = "claimed_by_user_id", nullable = true)
  private Long claimedByUserId;

  @Column(name = "claim_generation", nullable = false)
  private long claimGeneration;

  @Column(name = "lease_expires_at", nullable = true)
  private ZonedDateTime leaseExpiresAt;

  @Column(name = "completed_at", nullable = true)
  private ZonedDateTime completedAt;

  @Column(name = "due_date_offset_days", nullable = false)
  private int dueDateOffsetDays;

  @Column(name = "max_word_count_per_project", nullable = false)
  private int maxWordCountPerProject;

  @Column(name = "assign_translator", nullable = false)
  private boolean assignTranslator;

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

  public Long getRequestedByUserId() {
    return requestedByUserId;
  }

  public void setRequestedByUserId(Long requestedByUserId) {
    this.requestedByUserId = requestedByUserId;
  }

  public String getReviewType() {
    return reviewType;
  }

  public void setReviewType(String reviewType) {
    this.reviewType = reviewType;
  }

  public Long getTeamId() {
    return teamId;
  }

  public void setTeamId(Long teamId) {
    this.teamId = teamId;
  }

  public String getRepositoryIdsJson() {
    return repositoryIdsJson;
  }

  public void setRepositoryIdsJson(String repositoryIdsJson) {
    this.repositoryIdsJson = repositoryIdsJson;
  }

  public String getLocaleIdsJson() {
    return localeIdsJson;
  }

  public void setLocaleIdsJson(String localeIdsJson) {
    this.localeIdsJson = localeIdsJson;
  }

  public String getMethodVersion() {
    return methodVersion;
  }

  public void setMethodVersion(String methodVersion) {
    this.methodVersion = methodVersion;
  }

  public String getConfigurationVersion() {
    return configurationVersion;
  }

  public void setConfigurationVersion(String configurationVersion) {
    this.configurationVersion = configurationVersion;
  }

  public String getManifestSha256() {
    return manifestSha256;
  }

  public void setManifestSha256(String manifestSha256) {
    this.manifestSha256 = manifestSha256;
  }

  public RunStatus getStatus() {
    return status;
  }

  public void setStatus(RunStatus status) {
    this.status = status;
  }

  public int getPlannedGroupCount() {
    return plannedGroupCount;
  }

  public void setPlannedGroupCount(int plannedGroupCount) {
    this.plannedGroupCount = plannedGroupCount;
  }

  public int getCompletedGroupCount() {
    return completedGroupCount;
  }

  public void setCompletedGroupCount(int completedGroupCount) {
    this.completedGroupCount = completedGroupCount;
  }

  public int getFailedGroupCount() {
    return failedGroupCount;
  }

  public void setFailedGroupCount(int failedGroupCount) {
    this.failedGroupCount = failedGroupCount;
  }

  public int getReviewedItemCount() {
    return reviewedItemCount;
  }

  public void setReviewedItemCount(int reviewedItemCount) {
    this.reviewedItemCount = reviewedItemCount;
  }

  public String getCheckpointSha256() {
    return checkpointSha256;
  }

  public void setCheckpointSha256(String checkpointSha256) {
    this.checkpointSha256 = checkpointSha256;
  }

  public String getCheckpointRequestFingerprint() {
    return checkpointRequestFingerprint;
  }

  public void setCheckpointRequestFingerprint(String checkpointRequestFingerprint) {
    this.checkpointRequestFingerprint = checkpointRequestFingerprint;
  }

  public long getRevision() {
    return revision;
  }

  public void setRevision(long revision) {
    this.revision = revision;
  }

  public String getClaimOwner() {
    return claimOwner;
  }

  public void setClaimOwner(String claimOwner) {
    this.claimOwner = claimOwner;
  }

  public Long getClaimedByUserId() {
    return claimedByUserId;
  }

  public void setClaimedByUserId(Long claimedByUserId) {
    this.claimedByUserId = claimedByUserId;
  }

  public long getClaimGeneration() {
    return claimGeneration;
  }

  public void setClaimGeneration(long claimGeneration) {
    this.claimGeneration = claimGeneration;
  }

  public ZonedDateTime getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public void setLeaseExpiresAt(ZonedDateTime leaseExpiresAt) {
    this.leaseExpiresAt = leaseExpiresAt;
  }

  public ZonedDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(ZonedDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public int getDueDateOffsetDays() {
    return dueDateOffsetDays;
  }

  public void setDueDateOffsetDays(int dueDateOffsetDays) {
    this.dueDateOffsetDays = dueDateOffsetDays;
  }

  public int getMaxWordCountPerProject() {
    return maxWordCountPerProject;
  }

  public void setMaxWordCountPerProject(int maxWordCountPerProject) {
    this.maxWordCountPerProject = maxWordCountPerProject;
  }

  public boolean getAssignTranslator() {
    return assignTranslator;
  }

  public void setAssignTranslator(boolean assignTranslator) {
    this.assignTranslator = assignTranslator;
  }
}
