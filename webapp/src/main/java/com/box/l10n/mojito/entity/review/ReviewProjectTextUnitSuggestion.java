package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.BaseEntity;
import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

@Entity
@Table(
    name = "review_project_text_unit_suggestion",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__REVIEW_PROJECT_TEXT_UNIT_SUGGESTION__TEXT_UNIT",
          columnNames = {"review_project_text_unit_id"})
    })
public class ReviewProjectTextUnitSuggestion extends AuditableEntity {

  public enum Source {
    FIND_REPLACE,
    AI_REVIEW
  }

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "review_project_text_unit_id",
      foreignKey = @ForeignKey(name = "FK__RPTUS__REVIEW_PROJECT_TEXT_UNIT"))
  private ReviewProjectTextUnit reviewProjectTextUnit;

  @Column(name = "target", nullable = false, length = Integer.MAX_VALUE)
  private String target;

  @Column(name = "previous_target", length = Integer.MAX_VALUE)
  private String previousTarget;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 64)
  private Source source = Source.FIND_REPLACE;

  @Column(name = "notes", length = 4000)
  private String notes;

  @Version
  @Column(name = "version")
  private Long version = 0L;

  @CreatedBy
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = BaseEntity.CreatedByUserColumnName,
      foreignKey = @ForeignKey(name = "FK__RPTUS__CREATED_BY_USER"))
  private User createdByUser;

  @LastModifiedBy
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "last_modified_by_user_id",
      foreignKey = @ForeignKey(name = "FK__RPTUS__LAST_MODIFIED_BY_USER"))
  private User lastModifiedByUser;

  public ReviewProjectTextUnit getReviewProjectTextUnit() {
    return reviewProjectTextUnit;
  }

  public void setReviewProjectTextUnit(ReviewProjectTextUnit reviewProjectTextUnit) {
    this.reviewProjectTextUnit = reviewProjectTextUnit;
  }

  public String getTarget() {
    return target;
  }

  public void setTarget(String target) {
    this.target = target;
  }

  public String getPreviousTarget() {
    return previousTarget;
  }

  public void setPreviousTarget(String previousTarget) {
    this.previousTarget = previousTarget;
  }

  public Source getSource() {
    return source;
  }

  public void setSource(Source source) {
    this.source = source;
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

  public User getCreatedByUser() {
    return createdByUser;
  }

  public User getLastModifiedByUser() {
    return lastModifiedByUser;
  }
}
