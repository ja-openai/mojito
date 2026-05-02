package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "term_index_extracted_term",
    indexes = {
      @Index(
          name = "UK__TERM_INDEX_EXTRACTED_TERM__LOCALE_KEY",
          columnList = "source_locale_tag, normalized_key",
          unique = true)
    })
public class TermIndexExtractedTerm extends AuditableEntity {

  public static final String SOURCE_LOCALE_ROOT = "root";

  @Column(name = "normalized_key", nullable = false, length = 255)
  private String normalizedKey;

  @Column(name = "display_term", nullable = false, length = 512)
  private String displayTerm;

  @Column(name = "source_locale_tag", nullable = false, length = 64)
  private String sourceLocaleTag = SOURCE_LOCALE_ROOT;

  @Column(name = "occurrence_count", nullable = false)
  private Long occurrenceCount = 0L;

  @Column(name = "repository_count", nullable = false)
  private Integer repositoryCount = 0;

  @Column(name = "first_seen_at")
  private ZonedDateTime firstSeenAt;

  @Column(name = "last_seen_at")
  private ZonedDateTime lastSeenAt;

  @Column(name = "review_status", nullable = false, length = 32)
  private String reviewStatus = TermIndexReview.STATUS_TO_REVIEW;

  @Column(name = "review_authority", nullable = false, length = 32)
  private String reviewAuthority = TermIndexReview.AUTHORITY_DEFAULT;

  @Column(name = "review_reason", length = 64)
  private String reviewReason;

  @Column(name = "review_rationale", length = 2048)
  private String reviewRationale;

  @Column(name = "review_confidence")
  private Integer reviewConfidence;

  @Column(name = "review_changed_at")
  private ZonedDateTime reviewChangedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "review_changed_by_user_id",
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_EXTRACTED_TERM__REVIEW_CHANGED_BY_USER"))
  private User reviewChangedByUser;

  public String getNormalizedKey() {
    return normalizedKey;
  }

  public void setNormalizedKey(String normalizedKey) {
    this.normalizedKey = normalizedKey;
  }

  public String getDisplayTerm() {
    return displayTerm;
  }

  public void setDisplayTerm(String displayTerm) {
    this.displayTerm = displayTerm;
  }

  public String getSourceLocaleTag() {
    return sourceLocaleTag;
  }

  public void setSourceLocaleTag(String sourceLocaleTag) {
    this.sourceLocaleTag = sourceLocaleTag;
  }

  public Long getOccurrenceCount() {
    return occurrenceCount;
  }

  public void setOccurrenceCount(Long occurrenceCount) {
    this.occurrenceCount = occurrenceCount;
  }

  public Integer getRepositoryCount() {
    return repositoryCount;
  }

  public void setRepositoryCount(Integer repositoryCount) {
    this.repositoryCount = repositoryCount;
  }

  public ZonedDateTime getFirstSeenAt() {
    return firstSeenAt;
  }

  public void setFirstSeenAt(ZonedDateTime firstSeenAt) {
    this.firstSeenAt = firstSeenAt;
  }

  public ZonedDateTime getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(ZonedDateTime lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }

  public String getReviewStatus() {
    return reviewStatus;
  }

  public void setReviewStatus(String reviewStatus) {
    this.reviewStatus = reviewStatus;
  }

  public String getReviewAuthority() {
    return reviewAuthority;
  }

  public void setReviewAuthority(String reviewAuthority) {
    this.reviewAuthority = reviewAuthority;
  }

  public String getReviewReason() {
    return reviewReason;
  }

  public void setReviewReason(String reviewReason) {
    this.reviewReason = reviewReason;
  }

  public String getReviewRationale() {
    return reviewRationale;
  }

  public void setReviewRationale(String reviewRationale) {
    this.reviewRationale = reviewRationale;
  }

  public Integer getReviewConfidence() {
    return reviewConfidence;
  }

  public void setReviewConfidence(Integer reviewConfidence) {
    this.reviewConfidence = reviewConfidence;
  }

  public ZonedDateTime getReviewChangedAt() {
    return reviewChangedAt;
  }

  public void setReviewChangedAt(ZonedDateTime reviewChangedAt) {
    this.reviewChangedAt = reviewChangedAt;
  }

  public User getReviewChangedByUser() {
    return reviewChangedByUser;
  }

  public void setReviewChangedByUser(User reviewChangedByUser) {
    this.reviewChangedByUser = reviewChangedByUser;
  }
}
