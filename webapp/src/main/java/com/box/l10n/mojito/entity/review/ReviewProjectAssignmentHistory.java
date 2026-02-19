package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.BaseEntity;
import com.box.l10n.mojito.entity.Team;
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
import org.springframework.data.annotation.CreatedBy;

@Entity
@Table(
    name = "review_project_assignment_history",
    indexes = {
      @Index(name = "I__RP_ASSIGNMENT_HISTORY__PROJECT_ID", columnList = "review_project_id"),
      @Index(
          name = "I__RP_ASSIGNMENT_HISTORY__PROJECT_CREATED",
          columnList = "review_project_id, created_date")
    })
public class ReviewProjectAssignmentHistory extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "review_project_id",
      foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_HISTORY__PROJECT"))
  private ReviewProject reviewProject;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "team_id", foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_HISTORY__TEAM"))
  private Team team;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "assigned_pm_user_id",
      foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_HISTORY__ASSIGNED_PM"))
  private User assignedPmUser;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "assigned_translator_user_id",
      foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_HISTORY__ASSIGNED_TRANSLATOR"))
  private User assignedTranslatorUser;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private ReviewProjectAssignmentEventType eventType;

  @Column(name = "note", length = 512)
  private String note;

  @CreatedBy
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = BaseEntity.CreatedByUserColumnName,
      foreignKey = @ForeignKey(name = "FK__RP_ASSIGNMENT_HISTORY__CREATED_BY_USER"))
  private User createdByUser;

  public ReviewProject getReviewProject() {
    return reviewProject;
  }

  public void setReviewProject(ReviewProject reviewProject) {
    this.reviewProject = reviewProject;
  }

  public Team getTeam() {
    return team;
  }

  public void setTeam(Team team) {
    this.team = team;
  }

  public User getAssignedPmUser() {
    return assignedPmUser;
  }

  public void setAssignedPmUser(User assignedPmUser) {
    this.assignedPmUser = assignedPmUser;
  }

  public User getAssignedTranslatorUser() {
    return assignedTranslatorUser;
  }

  public void setAssignedTranslatorUser(User assignedTranslatorUser) {
    this.assignedTranslatorUser = assignedTranslatorUser;
  }

  public ReviewProjectAssignmentEventType getEventType() {
    return eventType;
  }

  public void setEventType(ReviewProjectAssignmentEventType eventType) {
    this.eventType = eventType;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public User getCreatedByUser() {
    return createdByUser;
  }

  public void setCreatedByUser(User createdByUser) {
    this.createdByUser = createdByUser;
  }
}
