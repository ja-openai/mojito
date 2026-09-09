package com.box.l10n.mojito.entity;

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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Request metadata for review adoption and latency reporting; never stores conversation text. */
@Entity
@Table(
    name = "ai_review_request_usage",
    indexes = {
      @Index(name = "I__AIRRU__USER_STARTED", columnList = "user_id, started_at"),
      @Index(
          name = "I__AIRRU__PROFILE_TYPE_STARTED",
          columnList = "profile_id, request_type, started_at"),
      @Index(name = "I__AIRRU__POLLABLE_TASK", columnList = "pollable_task_id")
    })
public class AiReviewRequestUsage extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "FK__AIRRU__USER__ID"))
  @OnDelete(action = OnDeleteAction.SET_NULL)
  private User user;

  // Keep correlation IDs after normal task or text-unit cleanup without retaining those records.
  @Column(name = "pollable_task_id")
  private Long pollableTaskId;

  @Column(name = "tm_text_unit_id")
  private Long tmTextUnitId;

  @Column(name = "locale", length = 64)
  private String locale;

  @Column(name = "surface", nullable = false, length = 32)
  private String surface;

  @Column(name = "request_type", nullable = false, length = 32)
  private String requestType;

  @Column(name = "profile_id", nullable = false, length = 32)
  private String profileId;

  @Column(name = "model_name", nullable = false, length = 255)
  private String modelName;

  @Column(name = "reasoning_effort", length = 32)
  private String reasoningEffort;

  @Column(name = "requested_service_tier", length = 32)
  private String requestedServiceTier;

  @Column(name = "returned_model", length = 255)
  private String returnedModel;

  @Column(name = "returned_service_tier", length = 32)
  private String returnedServiceTier;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "started_at", nullable = false)
  private ZonedDateTime startedAt;

  @Column(name = "finished_at")
  private ZonedDateTime finishedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public Long getPollableTaskId() {
    return pollableTaskId;
  }

  public void setPollableTaskId(Long pollableTaskId) {
    this.pollableTaskId = pollableTaskId;
  }

  public Long getTmTextUnitId() {
    return tmTextUnitId;
  }

  public void setTmTextUnitId(Long tmTextUnitId) {
    this.tmTextUnitId = tmTextUnitId;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  public String getSurface() {
    return surface;
  }

  public void setSurface(String surface) {
    this.surface = surface;
  }

  public String getRequestType() {
    return requestType;
  }

  public void setRequestType(String requestType) {
    this.requestType = requestType;
  }

  public String getProfileId() {
    return profileId;
  }

  public void setProfileId(String profileId) {
    this.profileId = profileId;
  }

  public String getModelName() {
    return modelName;
  }

  public void setModelName(String modelName) {
    this.modelName = modelName;
  }

  public String getReasoningEffort() {
    return reasoningEffort;
  }

  public void setReasoningEffort(String reasoningEffort) {
    this.reasoningEffort = reasoningEffort;
  }

  public String getRequestedServiceTier() {
    return requestedServiceTier;
  }

  public void setRequestedServiceTier(String requestedServiceTier) {
    this.requestedServiceTier = requestedServiceTier;
  }

  public String getReturnedModel() {
    return returnedModel;
  }

  public void setReturnedModel(String returnedModel) {
    this.returnedModel = returnedModel;
  }

  public String getReturnedServiceTier() {
    return returnedServiceTier;
  }

  public void setReturnedServiceTier(String returnedServiceTier) {
    this.returnedServiceTier = returnedServiceTier;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
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

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }
}
