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
import jakarta.persistence.Version;

@Entity
@Table(
    name = "review_project_text_unit_feedback",
    indexes = {
      @Index(
          name = "I__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__TEXT_UNIT",
          columnList = "review_project_text_unit_id"),
      @Index(
          name = "I__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__REVIEWER",
          columnList = "reviewer_user_id")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__TEXT_UNIT_REVIEWER",
          columnNames = {"review_project_text_unit_id", "reviewer_user_id"})
    })
public class ReviewProjectTextUnitFeedback extends AuditableEntity {

  public enum Recommendation {
    APPROVE,
    KEEP_CANDIDATE,
    REJECT
  }

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "review_project_text_unit_id",
      nullable = false,
      foreignKey =
          @ForeignKey(name = "FK__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__REVIEW_PROJECT_TEXT_UNIT"))
  private ReviewProjectTextUnit reviewProjectTextUnit;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "reviewer_user_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__REVIEW_PROJECT_TEXT_UNIT_FEEDBACK__REVIEWER"))
  private User reviewerUser;

  @Enumerated(EnumType.STRING)
  @Column(name = "recommendation", nullable = false, length = 32)
  private Recommendation recommendation;

  @Column(name = "confidence")
  private Integer confidence;

  @Column(name = "notes", length = 4000)
  private String notes;

  @Version
  @Column(name = "version")
  private Long version = 0L;

  public ReviewProjectTextUnit getReviewProjectTextUnit() {
    return reviewProjectTextUnit;
  }

  public void setReviewProjectTextUnit(ReviewProjectTextUnit reviewProjectTextUnit) {
    this.reviewProjectTextUnit = reviewProjectTextUnit;
  }

  public User getReviewerUser() {
    return reviewerUser;
  }

  public void setReviewerUser(User reviewerUser) {
    this.reviewerUser = reviewerUser;
  }

  public Recommendation getRecommendation() {
    return recommendation;
  }

  public void setRecommendation(Recommendation recommendation) {
    this.recommendation = recommendation;
  }

  public Integer getConfidence() {
    return confidence;
  }

  public void setConfidence(Integer confidence) {
    this.confidence = confidence;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }
}
