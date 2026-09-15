package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/** Immutable historical evidence; scalar identifiers survive project deletion. */
@Entity
@Immutable
@Table(
    name = "review_feedback_event",
    indexes = {
      @Index(name = "UK__REVIEW_FEEDBACK_EVENT__KEY", columnList = "event_key", unique = true),
      @Index(name = "I__REVIEW_FEEDBACK_EVENT__AI", columnList = "ai_baseline,id")
    })
public class ReviewFeedbackEvent extends BaseEntity {
  @Column(name = "event_key", nullable = false, updatable = false, length = 64)
  private String eventKey;

  @Column(name = "project_id", updatable = false)
  private Long projectId;

  @Column(name = "text_unit_id", nullable = false, updatable = false)
  private Long textUnitId;

  @Column(name = "reviewer_id", nullable = false, updatable = false)
  private Long reviewerId;

  @Column(name = "locale_tag", nullable = false, updatable = false, length = 64)
  private String locale;

  @Column(name = "model", nullable = false, updatable = false, length = 255)
  private String model;

  @Column(name = "prompt_version", nullable = false, updatable = false, length = 255)
  private String promptVersion;

  @Column(name = "category", nullable = false, updatable = false, length = 64)
  private String category;

  @Column(name = "pattern_key", nullable = false, updatable = false, length = 64)
  private String patternKey;

  @Column(name = "source_hash", nullable = false, updatable = false, length = 64)
  private String sourceHash;

  @Column(name = "baseline_hash", nullable = false, updatable = false, length = 64)
  private String baselineHash;

  @Column(name = "final_hash", nullable = false, updatable = false, length = 64)
  private String finalHash;

  @Column(name = "ai_baseline", nullable = false, updatable = false)
  private boolean aiBaseline;

  @Column(name = "payload_json", nullable = false, updatable = false, length = Integer.MAX_VALUE)
  private String payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private java.time.Instant createdAt;

  protected ReviewFeedbackEvent() {}

  public ReviewFeedbackEvent(
      String eventKey,
      Long projectId,
      Long textUnitId,
      Long reviewerId,
      String locale,
      String model,
      String promptVersion,
      String category,
      String patternKey,
      String sourceHash,
      String baselineHash,
      String finalHash,
      boolean aiBaseline,
      String payload) {
    this.eventKey = eventKey;
    this.projectId = projectId;
    this.textUnitId = textUnitId;
    this.reviewerId = reviewerId;
    this.locale = locale;
    this.model = model;
    this.promptVersion = promptVersion;
    this.category = category;
    this.patternKey = patternKey;
    this.sourceHash = sourceHash;
    this.baselineHash = baselineHash;
    this.finalHash = finalHash;
    this.aiBaseline = aiBaseline;
    this.payload = payload;
    this.createdAt = java.time.Instant.now();
  }

  public String getEventKey() {
    return eventKey;
  }

  public Long getProjectId() {
    return projectId;
  }

  public Long getTextUnitId() {
    return textUnitId;
  }

  public Long getReviewerId() {
    return reviewerId;
  }

  public String getLocale() {
    return locale;
  }

  public String getModel() {
    return model;
  }

  public String getPromptVersion() {
    return promptVersion;
  }

  public String getCategory() {
    return category;
  }

  public String getPatternKey() {
    return patternKey;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public String getBaselineHash() {
    return baselineHash;
  }

  public String getFinalHash() {
    return finalHash;
  }

  public boolean getAiBaseline() {
    return aiBaseline;
  }

  public String getPayload() {
    return payload;
  }

  public java.time.Instant getCreatedAt() {
    return createdAt;
  }
}
