package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "review_project_time_spent_stat",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__RP_TIME_SPENT_STAT__WINDOW",
          columnNames = {"assignment_window_id"})
    },
    indexes = {
      @Index(name = "I__RP_TIME_SPENT_STAT__PROJECT", columnList = "review_project_id"),
      @Index(name = "I__RP_TIME_SPENT_STAT__REQUEST", columnList = "review_project_request_id"),
      @Index(
          name = "I__RP_TIME_SPENT_STAT__TRANSLATOR",
          columnList = "assigned_translator_user_id"),
      @Index(name = "I__RP_TIME_SPENT_STAT__LOCALE", columnList = "locale_bcp47_tag"),
      @Index(name = "I__RP_TIME_SPENT_STAT__LAST_DECISION", columnList = "last_decision_at"),
      @Index(name = "I__RP_TIME_SPENT_STAT__FINALIZED", columnList = "finalized_at")
    })
public class ReviewProjectTimeSpentStat extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "assignment_window_id",
      foreignKey = @ForeignKey(name = "FK__RP_TIME_SPENT_STAT__WINDOW"))
  private ReviewProjectAssignmentWindow assignmentWindow;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "review_project_id",
      foreignKey = @ForeignKey(name = "FK__RP_TIME_SPENT_STAT__PROJECT"))
  private ReviewProject reviewProject;

  @Column(name = "review_project_request_id")
  private Long reviewProjectRequestId;

  @Column(name = "review_project_request_name")
  private String reviewProjectRequestName;

  @Column(name = "review_project_type", length = 32)
  private String reviewProjectType;

  @Column(name = "review_project_status", length = 32)
  private String reviewProjectStatus;

  @Column(name = "locale_bcp47_tag")
  private String localeBcp47Tag;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "assigned_translator_user_id",
      foreignKey = @ForeignKey(name = "FK__RP_TIME_SPENT_STAT__TRANSLATOR"))
  private User assignedTranslatorUser;

  @Column(name = "assigned_translator_username")
  private String assignedTranslatorUsername;

  @Column(name = "assignment_window_started_at")
  private ZonedDateTime assignmentWindowStartedAt;

  @Column(name = "assignment_accepted_at")
  private ZonedDateTime assignmentAcceptedAt;

  @Column(name = "assignment_window_ended_at")
  private ZonedDateTime assignmentWindowEndedAt;

  @Column(name = "assignment_window_end_reason", length = 32)
  private String assignmentWindowEndReason;

  @Column(name = "project_created_date")
  private ZonedDateTime projectCreatedDate;

  @Column(name = "project_due_date")
  private ZonedDateTime projectDueDate;

  @Column(name = "text_unit_count", nullable = false)
  private long textUnitCount;

  @Column(name = "word_count", nullable = false)
  private long wordCount;

  @Column(name = "decided_count", nullable = false)
  private long decidedCount;

  @Column(name = "decided_word_count", nullable = false)
  private long decidedWordCount;

  @Column(name = "first_decision_at")
  private ZonedDateTime firstDecisionAt;

  @Column(name = "last_decision_at")
  private ZonedDateTime lastDecisionAt;

  @Column(name = "raw_decision_span_seconds", nullable = false)
  private long rawDecisionSpanSeconds;

  @Column(name = "project_span_seconds", nullable = false)
  private long projectSpanSeconds;

  @Column(name = "accepted_to_first_decision_seconds")
  private Long acceptedToFirstDecisionSeconds;

  @Column(name = "assigned_to_accepted_seconds")
  private Long assignedToAcceptedSeconds;

  @Column(name = "estimated_active_seconds", nullable = false)
  private long estimatedActiveSeconds;

  @Column(name = "pause_seconds", nullable = false)
  private long pauseSeconds;

  @Column(name = "pause_count", nullable = false)
  private long pauseCount;

  @Column(name = "pause_ratio", nullable = false)
  private double pauseRatio;

  @Column(name = "self_reported_seconds")
  private Long selfReportedSeconds;

  @Column(name = "reported_computed_delta_seconds")
  private Long reportedComputedDeltaSeconds;

  @Column(name = "reported_computed_ratio")
  private Double reportedComputedRatio;

  @Column(name = "reported_missing", nullable = false)
  private boolean reportedMissing;

  @Enumerated(EnumType.STRING)
  @Column(name = "review_flag", nullable = false, length = 32)
  private ReviewProjectTimeSpentReviewFlag reviewFlag = ReviewProjectTimeSpentReviewFlag.OK;

  @Column(name = "closed_with_pending", nullable = false)
  private boolean closedWithPending;

  @Column(name = "pending_count_at_close")
  private Long pendingCountAtClose;

  @Column(name = "pending_word_count_at_close")
  private Long pendingWordCountAtClose;

  @Column(name = "close_reason", length = 512)
  private String closeReason;

  @Column(name = "finalized_at")
  private ZonedDateTime finalizedAt;

  @Column(name = "computed_at", nullable = false)
  private ZonedDateTime computedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "attribution_confidence", nullable = false, length = 32)
  private ReviewProjectTimeSpentAttributionConfidence attributionConfidence =
      ReviewProjectTimeSpentAttributionConfidence.ASSIGNMENT_WINDOW;

  public ReviewProjectAssignmentWindow getAssignmentWindow() {
    return assignmentWindow;
  }

  public void setAssignmentWindow(ReviewProjectAssignmentWindow assignmentWindow) {
    this.assignmentWindow = assignmentWindow;
  }

  public ReviewProject getReviewProject() {
    return reviewProject;
  }

  public void setReviewProject(ReviewProject reviewProject) {
    this.reviewProject = reviewProject;
  }

  public Long getReviewProjectRequestId() {
    return reviewProjectRequestId;
  }

  public void setReviewProjectRequestId(Long reviewProjectRequestId) {
    this.reviewProjectRequestId = reviewProjectRequestId;
  }

  public String getReviewProjectRequestName() {
    return reviewProjectRequestName;
  }

  public void setReviewProjectRequestName(String reviewProjectRequestName) {
    this.reviewProjectRequestName = reviewProjectRequestName;
  }

  public String getReviewProjectType() {
    return reviewProjectType;
  }

  public void setReviewProjectType(String reviewProjectType) {
    this.reviewProjectType = reviewProjectType;
  }

  public String getReviewProjectStatus() {
    return reviewProjectStatus;
  }

  public void setReviewProjectStatus(String reviewProjectStatus) {
    this.reviewProjectStatus = reviewProjectStatus;
  }

  public String getLocaleBcp47Tag() {
    return localeBcp47Tag;
  }

  public void setLocaleBcp47Tag(String localeBcp47Tag) {
    this.localeBcp47Tag = localeBcp47Tag;
  }

  public User getAssignedTranslatorUser() {
    return assignedTranslatorUser;
  }

  public void setAssignedTranslatorUser(User assignedTranslatorUser) {
    this.assignedTranslatorUser = assignedTranslatorUser;
  }

  public String getAssignedTranslatorUsername() {
    return assignedTranslatorUsername;
  }

  public void setAssignedTranslatorUsername(String assignedTranslatorUsername) {
    this.assignedTranslatorUsername = assignedTranslatorUsername;
  }

  public ZonedDateTime getAssignmentWindowStartedAt() {
    return assignmentWindowStartedAt;
  }

  public void setAssignmentWindowStartedAt(ZonedDateTime assignmentWindowStartedAt) {
    this.assignmentWindowStartedAt = assignmentWindowStartedAt;
  }

  public ZonedDateTime getAssignmentAcceptedAt() {
    return assignmentAcceptedAt;
  }

  public void setAssignmentAcceptedAt(ZonedDateTime assignmentAcceptedAt) {
    this.assignmentAcceptedAt = assignmentAcceptedAt;
  }

  public ZonedDateTime getAssignmentWindowEndedAt() {
    return assignmentWindowEndedAt;
  }

  public void setAssignmentWindowEndedAt(ZonedDateTime assignmentWindowEndedAt) {
    this.assignmentWindowEndedAt = assignmentWindowEndedAt;
  }

  public String getAssignmentWindowEndReason() {
    return assignmentWindowEndReason;
  }

  public void setAssignmentWindowEndReason(String assignmentWindowEndReason) {
    this.assignmentWindowEndReason = assignmentWindowEndReason;
  }

  public ZonedDateTime getProjectCreatedDate() {
    return projectCreatedDate;
  }

  public void setProjectCreatedDate(ZonedDateTime projectCreatedDate) {
    this.projectCreatedDate = projectCreatedDate;
  }

  public ZonedDateTime getProjectDueDate() {
    return projectDueDate;
  }

  public void setProjectDueDate(ZonedDateTime projectDueDate) {
    this.projectDueDate = projectDueDate;
  }

  public long getTextUnitCount() {
    return textUnitCount;
  }

  public void setTextUnitCount(long textUnitCount) {
    this.textUnitCount = textUnitCount;
  }

  public long getWordCount() {
    return wordCount;
  }

  public void setWordCount(long wordCount) {
    this.wordCount = wordCount;
  }

  public long getDecidedCount() {
    return decidedCount;
  }

  public void setDecidedCount(long decidedCount) {
    this.decidedCount = decidedCount;
  }

  public long getDecidedWordCount() {
    return decidedWordCount;
  }

  public void setDecidedWordCount(long decidedWordCount) {
    this.decidedWordCount = decidedWordCount;
  }

  public ZonedDateTime getFirstDecisionAt() {
    return firstDecisionAt;
  }

  public void setFirstDecisionAt(ZonedDateTime firstDecisionAt) {
    this.firstDecisionAt = firstDecisionAt;
  }

  public ZonedDateTime getLastDecisionAt() {
    return lastDecisionAt;
  }

  public void setLastDecisionAt(ZonedDateTime lastDecisionAt) {
    this.lastDecisionAt = lastDecisionAt;
  }

  public long getRawDecisionSpanSeconds() {
    return rawDecisionSpanSeconds;
  }

  public void setRawDecisionSpanSeconds(long rawDecisionSpanSeconds) {
    this.rawDecisionSpanSeconds = rawDecisionSpanSeconds;
  }

  public long getProjectSpanSeconds() {
    return projectSpanSeconds;
  }

  public void setProjectSpanSeconds(long projectSpanSeconds) {
    this.projectSpanSeconds = projectSpanSeconds;
  }

  public Long getAcceptedToFirstDecisionSeconds() {
    return acceptedToFirstDecisionSeconds;
  }

  public void setAcceptedToFirstDecisionSeconds(Long acceptedToFirstDecisionSeconds) {
    this.acceptedToFirstDecisionSeconds = acceptedToFirstDecisionSeconds;
  }

  public Long getAssignedToAcceptedSeconds() {
    return assignedToAcceptedSeconds;
  }

  public void setAssignedToAcceptedSeconds(Long assignedToAcceptedSeconds) {
    this.assignedToAcceptedSeconds = assignedToAcceptedSeconds;
  }

  public long getEstimatedActiveSeconds() {
    return estimatedActiveSeconds;
  }

  public void setEstimatedActiveSeconds(long estimatedActiveSeconds) {
    this.estimatedActiveSeconds = estimatedActiveSeconds;
  }

  public long getPauseSeconds() {
    return pauseSeconds;
  }

  public void setPauseSeconds(long pauseSeconds) {
    this.pauseSeconds = pauseSeconds;
  }

  public long getPauseCount() {
    return pauseCount;
  }

  public void setPauseCount(long pauseCount) {
    this.pauseCount = pauseCount;
  }

  public double getPauseRatio() {
    return pauseRatio;
  }

  public void setPauseRatio(double pauseRatio) {
    this.pauseRatio = pauseRatio;
  }

  public Long getSelfReportedSeconds() {
    return selfReportedSeconds;
  }

  public void setSelfReportedSeconds(Long selfReportedSeconds) {
    this.selfReportedSeconds = selfReportedSeconds;
  }

  public Long getReportedComputedDeltaSeconds() {
    return reportedComputedDeltaSeconds;
  }

  public void setReportedComputedDeltaSeconds(Long reportedComputedDeltaSeconds) {
    this.reportedComputedDeltaSeconds = reportedComputedDeltaSeconds;
  }

  public Double getReportedComputedRatio() {
    return reportedComputedRatio;
  }

  public void setReportedComputedRatio(Double reportedComputedRatio) {
    this.reportedComputedRatio = reportedComputedRatio;
  }

  public boolean isReportedMissing() {
    return reportedMissing;
  }

  public void setReportedMissing(boolean reportedMissing) {
    this.reportedMissing = reportedMissing;
  }

  public ReviewProjectTimeSpentReviewFlag getReviewFlag() {
    return reviewFlag;
  }

  public void setReviewFlag(ReviewProjectTimeSpentReviewFlag reviewFlag) {
    this.reviewFlag = reviewFlag;
  }

  public boolean isClosedWithPending() {
    return closedWithPending;
  }

  public void setClosedWithPending(boolean closedWithPending) {
    this.closedWithPending = closedWithPending;
  }

  public Long getPendingCountAtClose() {
    return pendingCountAtClose;
  }

  public void setPendingCountAtClose(Long pendingCountAtClose) {
    this.pendingCountAtClose = pendingCountAtClose;
  }

  public Long getPendingWordCountAtClose() {
    return pendingWordCountAtClose;
  }

  public void setPendingWordCountAtClose(Long pendingWordCountAtClose) {
    this.pendingWordCountAtClose = pendingWordCountAtClose;
  }

  public String getCloseReason() {
    return closeReason;
  }

  public void setCloseReason(String closeReason) {
    this.closeReason = closeReason;
  }

  public ZonedDateTime getFinalizedAt() {
    return finalizedAt;
  }

  public void setFinalizedAt(ZonedDateTime finalizedAt) {
    this.finalizedAt = finalizedAt;
  }

  public ZonedDateTime getComputedAt() {
    return computedAt;
  }

  public void setComputedAt(ZonedDateTime computedAt) {
    this.computedAt = computedAt;
  }

  public ReviewProjectTimeSpentAttributionConfidence getAttributionConfidence() {
    return attributionConfidence;
  }

  public void setAttributionConfidence(
      ReviewProjectTimeSpentAttributionConfidence attributionConfidence) {
    this.attributionConfidence = attributionConfidence;
  }
}
