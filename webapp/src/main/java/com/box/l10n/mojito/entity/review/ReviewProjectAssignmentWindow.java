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
    name = "review_project_assignment_window",
    indexes = {
      @Index(name = "I__RP_ASSIGNMENT_WINDOW__PROJECT", columnList = "review_project_id"),
      @Index(
          name = "I__RP_ASSIGNMENT_WINDOW__TRANSLATOR",
          columnList = "assigned_translator_user_id"),
      @Index(name = "I__RP_ASSIGNMENT_WINDOW__ASSIGNED_AT", columnList = "assigned_at"),
      @Index(name = "I__RP_ASSIGNMENT_WINDOW__ACCEPTED_AT", columnList = "accepted_at"),
      @Index(name = "I__RP_ASSIGNMENT_WINDOW__ENDED_AT", columnList = "ended_at")
    })
public class ReviewProjectAssignmentWindow extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "review_project_id",
      foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_WINDOW__PROJECT"))
  private ReviewProject reviewProject;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "assigned_translator_user_id",
      foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_WINDOW__TRANSLATOR"))
  private User assignedTranslatorUser;

  @Column(name = "assigned_translator_username_snapshot")
  private String assignedTranslatorUsernameSnapshot;

  @Column(name = "assigned_at", nullable = false)
  private ZonedDateTime assignedAt;

  @Column(name = "accepted_at")
  private ZonedDateTime acceptedAt;

  @Column(name = "ended_at")
  private ZonedDateTime endedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "end_reason", length = 32)
  private ReviewProjectAssignmentWindowEndReason endReason;

  @Column(name = "self_reported_minutes")
  private Integer selfReportedMinutes;

  @Column(name = "self_reported_note", length = 512)
  private String selfReportedNote;

  @Column(name = "self_reported_at")
  private ZonedDateTime selfReportedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "self_reported_by_user_id",
      foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_WINDOW__REPORTED_BY"))
  private User selfReportedByUser;

  public ReviewProject getReviewProject() {
    return reviewProject;
  }

  public void setReviewProject(ReviewProject reviewProject) {
    this.reviewProject = reviewProject;
  }

  public User getAssignedTranslatorUser() {
    return assignedTranslatorUser;
  }

  public void setAssignedTranslatorUser(User assignedTranslatorUser) {
    this.assignedTranslatorUser = assignedTranslatorUser;
  }

  public String getAssignedTranslatorUsernameSnapshot() {
    return assignedTranslatorUsernameSnapshot;
  }

  public void setAssignedTranslatorUsernameSnapshot(String assignedTranslatorUsernameSnapshot) {
    this.assignedTranslatorUsernameSnapshot = assignedTranslatorUsernameSnapshot;
  }

  public ZonedDateTime getAssignedAt() {
    return assignedAt;
  }

  public void setAssignedAt(ZonedDateTime assignedAt) {
    this.assignedAt = assignedAt;
  }

  public ZonedDateTime getAcceptedAt() {
    return acceptedAt;
  }

  public void setAcceptedAt(ZonedDateTime acceptedAt) {
    this.acceptedAt = acceptedAt;
  }

  public ZonedDateTime getEndedAt() {
    return endedAt;
  }

  public void setEndedAt(ZonedDateTime endedAt) {
    this.endedAt = endedAt;
  }

  public ReviewProjectAssignmentWindowEndReason getEndReason() {
    return endReason;
  }

  public void setEndReason(ReviewProjectAssignmentWindowEndReason endReason) {
    this.endReason = endReason;
  }

  public Integer getSelfReportedMinutes() {
    return selfReportedMinutes;
  }

  public void setSelfReportedMinutes(Integer selfReportedMinutes) {
    this.selfReportedMinutes = selfReportedMinutes;
  }

  public String getSelfReportedNote() {
    return selfReportedNote;
  }

  public void setSelfReportedNote(String selfReportedNote) {
    this.selfReportedNote = selfReportedNote;
  }

  public ZonedDateTime getSelfReportedAt() {
    return selfReportedAt;
  }

  public void setSelfReportedAt(ZonedDateTime selfReportedAt) {
    this.selfReportedAt = selfReportedAt;
  }

  public User getSelfReportedByUser() {
    return selfReportedByUser;
  }

  public void setSelfReportedByUser(User selfReportedByUser) {
    this.selfReportedByUser = selfReportedByUser;
  }
}
