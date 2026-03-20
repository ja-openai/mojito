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
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "review_automation_run",
    indexes = {
      @Index(name = "I__REVIEW_AUTOMATION_RUN__CREATED_DATE", columnList = "created_date"),
      @Index(
          name = "I__REVIEW_AUTOMATION_RUN__AUTOMATION__ID",
          columnList = "review_automation_id"),
      @Index(
          name = "I__REVIEW_AUTOMATION_RUN__REQUESTED_BY_USER__ID",
          columnList = "requested_by_user_id")
    })
public class ReviewAutomationRun extends AuditableEntity {

  public enum TriggerSource {
    MANUAL,
    CRON
  }

  public enum Status {
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED
  }

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "review_automation_id",
      foreignKey = @ForeignKey(name = "FK__REVIEW_AUTOMATION_RUN__AUTOMATION"))
  private ReviewAutomation reviewAutomation;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_source", nullable = false, length = 32)
  private TriggerSource triggerSource;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "requested_by_user_id",
      foreignKey = @ForeignKey(name = "FK__REVIEW_AUTOMATION_RUN__USER"))
  private User requestedByUser;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private Status status;

  @Column(name = "started_at")
  private ZonedDateTime startedAt;

  @Column(name = "finished_at")
  private ZonedDateTime finishedAt;

  @Column(name = "feature_count", nullable = false)
  private int featureCount;

  @Column(name = "created_project_request_count", nullable = false)
  private int createdProjectRequestCount;

  @Column(name = "created_project_count", nullable = false)
  private int createdProjectCount;

  @Column(name = "created_locale_count", nullable = false)
  private int createdLocaleCount;

  @Column(name = "skipped_locale_count", nullable = false)
  private int skippedLocaleCount;

  @Column(name = "errored_locale_count", nullable = false)
  private int erroredLocaleCount;

  @Column(name = "error_message", length = 4000)
  private String errorMessage;

  public ReviewAutomation getReviewAutomation() {
    return reviewAutomation;
  }

  public void setReviewAutomation(ReviewAutomation reviewAutomation) {
    this.reviewAutomation = reviewAutomation;
  }

  public TriggerSource getTriggerSource() {
    return triggerSource;
  }

  public void setTriggerSource(TriggerSource triggerSource) {
    this.triggerSource = triggerSource;
  }

  public User getRequestedByUser() {
    return requestedByUser;
  }

  public void setRequestedByUser(User requestedByUser) {
    this.requestedByUser = requestedByUser;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public ZonedDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(ZonedDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public ZonedDateTime getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(ZonedDateTime finishedAt) {
    this.finishedAt = finishedAt;
  }

  public int getFeatureCount() {
    return featureCount;
  }

  public void setFeatureCount(int featureCount) {
    this.featureCount = featureCount;
  }

  public int getCreatedProjectRequestCount() {
    return createdProjectRequestCount;
  }

  public void setCreatedProjectRequestCount(int createdProjectRequestCount) {
    this.createdProjectRequestCount = createdProjectRequestCount;
  }

  public int getCreatedProjectCount() {
    return createdProjectCount;
  }

  public void setCreatedProjectCount(int createdProjectCount) {
    this.createdProjectCount = createdProjectCount;
  }

  public int getCreatedLocaleCount() {
    return createdLocaleCount;
  }

  public void setCreatedLocaleCount(int createdLocaleCount) {
    this.createdLocaleCount = createdLocaleCount;
  }

  public int getSkippedLocaleCount() {
    return skippedLocaleCount;
  }

  public void setSkippedLocaleCount(int skippedLocaleCount) {
    this.skippedLocaleCount = skippedLocaleCount;
  }

  public int getErroredLocaleCount() {
    return erroredLocaleCount;
  }

  public void setErroredLocaleCount(int erroredLocaleCount) {
    this.erroredLocaleCount = erroredLocaleCount;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
