package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(name = "term_index_automation_run")
public class TermIndexAutomationRun extends AuditableEntity {

  public static final String TYPE_REVIEW_EXTRACTED_TERMS = "REVIEW_EXTRACTED_TERMS";
  public static final String TYPE_GENERATE_CANDIDATES = "GENERATE_CANDIDATES";

  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_SUCCEEDED = "SUCCEEDED";
  public static final String STATUS_FAILED = "FAILED";

  @Column(name = "type", nullable = false, length = 64)
  private String type;

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_RUNNING;

  @Column(name = "requested_repository_ids", length = 2048)
  private String requestedRepositoryIds;

  @Column(name = "pollable_task_id")
  private Long pollableTaskId;

  @Column(name = "message", length = Integer.MAX_VALUE)
  private String message;

  @Column(name = "entry_count", nullable = false)
  private Long entryCount = 0L;

  @Column(name = "processed_entry_count", nullable = false)
  private Long processedEntryCount = 0L;

  @Column(name = "reviewable_entry_count", nullable = false)
  private Long reviewableEntryCount = 0L;

  @Column(name = "reviewed_entry_count", nullable = false)
  private Long reviewedEntryCount = 0L;

  @Column(name = "updated_entry_count", nullable = false)
  private Long updatedEntryCount = 0L;

  @Column(name = "candidate_count", nullable = false)
  private Long candidateCount = 0L;

  @Column(name = "created_candidate_count", nullable = false)
  private Long createdCandidateCount = 0L;

  @Column(name = "updated_candidate_count", nullable = false)
  private Long updatedCandidateCount = 0L;

  @Column(name = "skipped_existing_candidate_count", nullable = false)
  private Long skippedExistingCandidateCount = 0L;

  @Column(name = "accepted_count", nullable = false)
  private Long acceptedCount = 0L;

  @Column(name = "to_review_count", nullable = false)
  private Long toReviewCount = 0L;

  @Column(name = "rejected_count", nullable = false)
  private Long rejectedCount = 0L;

  @Column(name = "skipped_human_reviewed_count", nullable = false)
  private Long skippedHumanReviewedCount = 0L;

  @Column(name = "batch_count", nullable = false)
  private Integer batchCount = 0;

  @Column(name = "completed_batch_count", nullable = false)
  private Integer completedBatchCount = 0;

  @Column(name = "started_at")
  private ZonedDateTime startedAt;

  @Column(name = "completed_at")
  private ZonedDateTime completedAt;

  @Column(name = "error_message", length = 2048)
  private String errorMessage;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getRequestedRepositoryIds() {
    return requestedRepositoryIds;
  }

  public void setRequestedRepositoryIds(String requestedRepositoryIds) {
    this.requestedRepositoryIds = requestedRepositoryIds;
  }

  public Long getPollableTaskId() {
    return pollableTaskId;
  }

  public void setPollableTaskId(Long pollableTaskId) {
    this.pollableTaskId = pollableTaskId;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Long getEntryCount() {
    return entryCount;
  }

  public void setEntryCount(Long entryCount) {
    this.entryCount = entryCount;
  }

  public Long getProcessedEntryCount() {
    return processedEntryCount;
  }

  public void setProcessedEntryCount(Long processedEntryCount) {
    this.processedEntryCount = processedEntryCount;
  }

  public Long getReviewableEntryCount() {
    return reviewableEntryCount;
  }

  public void setReviewableEntryCount(Long reviewableEntryCount) {
    this.reviewableEntryCount = reviewableEntryCount;
  }

  public Long getReviewedEntryCount() {
    return reviewedEntryCount;
  }

  public void setReviewedEntryCount(Long reviewedEntryCount) {
    this.reviewedEntryCount = reviewedEntryCount;
  }

  public Long getUpdatedEntryCount() {
    return updatedEntryCount;
  }

  public void setUpdatedEntryCount(Long updatedEntryCount) {
    this.updatedEntryCount = updatedEntryCount;
  }

  public Long getCandidateCount() {
    return candidateCount;
  }

  public void setCandidateCount(Long candidateCount) {
    this.candidateCount = candidateCount;
  }

  public Long getCreatedCandidateCount() {
    return createdCandidateCount;
  }

  public void setCreatedCandidateCount(Long createdCandidateCount) {
    this.createdCandidateCount = createdCandidateCount;
  }

  public Long getUpdatedCandidateCount() {
    return updatedCandidateCount;
  }

  public void setUpdatedCandidateCount(Long updatedCandidateCount) {
    this.updatedCandidateCount = updatedCandidateCount;
  }

  public Long getSkippedExistingCandidateCount() {
    return skippedExistingCandidateCount;
  }

  public void setSkippedExistingCandidateCount(Long skippedExistingCandidateCount) {
    this.skippedExistingCandidateCount = skippedExistingCandidateCount;
  }

  public Long getAcceptedCount() {
    return acceptedCount;
  }

  public void setAcceptedCount(Long acceptedCount) {
    this.acceptedCount = acceptedCount;
  }

  public Long getToReviewCount() {
    return toReviewCount;
  }

  public void setToReviewCount(Long toReviewCount) {
    this.toReviewCount = toReviewCount;
  }

  public Long getRejectedCount() {
    return rejectedCount;
  }

  public void setRejectedCount(Long rejectedCount) {
    this.rejectedCount = rejectedCount;
  }

  public Long getSkippedHumanReviewedCount() {
    return skippedHumanReviewedCount;
  }

  public void setSkippedHumanReviewedCount(Long skippedHumanReviewedCount) {
    this.skippedHumanReviewedCount = skippedHumanReviewedCount;
  }

  public Integer getBatchCount() {
    return batchCount;
  }

  public void setBatchCount(Integer batchCount) {
    this.batchCount = batchCount;
  }

  public Integer getCompletedBatchCount() {
    return completedBatchCount;
  }

  public void setCompletedBatchCount(Integer completedBatchCount) {
    this.completedBatchCount = completedBatchCount;
  }

  public ZonedDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(ZonedDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public ZonedDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(ZonedDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
