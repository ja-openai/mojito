package com.box.l10n.mojito.entity;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "ai_translate_run",
    indexes = {
      @Index(name = "I__AI_TRANSLATE_RUN__CREATED_DATE", columnList = "created_date"),
      @Index(name = "I__AI_TRANSLATE_RUN__REPOSITORY__ID", columnList = "repository_id"),
      @Index(
          name = "UK__AI_TRANSLATE_RUN__POLLABLE_TASK__ID",
          columnList = "pollable_task_id",
          unique = true)
    })
public class AiTranslateRun extends AuditableEntity {

  public enum TriggerSource {
    MANUAL,
    CRON
  }

  public enum Status {
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
    PARTIAL
  }

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_source", nullable = false, length = 32)
  private TriggerSource triggerSource;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_id",
      foreignKey = @ForeignKey(name = "FK__AI_TRANSLATE_RUN__REPOSITORY__ID"))
  private Repository repository;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "requested_by_user_id",
      foreignKey = @ForeignKey(name = "FK__AI_TRANSLATE_RUN__USER__ID"))
  private User requestedByUser;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "pollable_task_id",
      foreignKey = @ForeignKey(name = "FK__AI_TRANSLATE_RUN__POLLABLE_TASK__ID"))
  private PollableTask pollableTask;

  @Column(name = "model", nullable = false, length = 255)
  private String model;

  @Column(name = "translate_type", nullable = false, length = 64)
  private String translateType;

  @Column(name = "related_strings_type", nullable = false, length = 64)
  private String relatedStringsType;

  @Column(name = "source_text_max_count_per_locale", nullable = false)
  private int sourceTextMaxCountPerLocale;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private Status status;

  @Column(name = "started_at")
  private ZonedDateTime startedAt;

  @Column(name = "finished_at")
  private ZonedDateTime finishedAt;

  @Column(name = "input_tokens", nullable = false)
  private long inputTokens;

  @Column(name = "cached_input_tokens", nullable = false)
  private long cachedInputTokens;

  @Column(name = "output_tokens", nullable = false)
  private long outputTokens;

  @Column(name = "reasoning_tokens", nullable = false)
  private long reasoningTokens;

  @Column(name = "estimated_cost_usd", precision = 18, scale = 6)
  private BigDecimal estimatedCostUsd;

  public TriggerSource getTriggerSource() {
    return triggerSource;
  }

  public void setTriggerSource(TriggerSource triggerSource) {
    this.triggerSource = triggerSource;
  }

  public Repository getRepository() {
    return repository;
  }

  public void setRepository(Repository repository) {
    this.repository = repository;
  }

  public User getRequestedByUser() {
    return requestedByUser;
  }

  public void setRequestedByUser(User requestedByUser) {
    this.requestedByUser = requestedByUser;
  }

  public PollableTask getPollableTask() {
    return pollableTask;
  }

  public void setPollableTask(PollableTask pollableTask) {
    this.pollableTask = pollableTask;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getTranslateType() {
    return translateType;
  }

  public void setTranslateType(String translateType) {
    this.translateType = translateType;
  }

  public String getRelatedStringsType() {
    return relatedStringsType;
  }

  public void setRelatedStringsType(String relatedStringsType) {
    this.relatedStringsType = relatedStringsType;
  }

  public int getSourceTextMaxCountPerLocale() {
    return sourceTextMaxCountPerLocale;
  }

  public void setSourceTextMaxCountPerLocale(int sourceTextMaxCountPerLocale) {
    this.sourceTextMaxCountPerLocale = sourceTextMaxCountPerLocale;
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

  public long getInputTokens() {
    return inputTokens;
  }

  public void setInputTokens(long inputTokens) {
    this.inputTokens = inputTokens;
  }

  public long getCachedInputTokens() {
    return cachedInputTokens;
  }

  public void setCachedInputTokens(long cachedInputTokens) {
    this.cachedInputTokens = cachedInputTokens;
  }

  public long getOutputTokens() {
    return outputTokens;
  }

  public void setOutputTokens(long outputTokens) {
    this.outputTokens = outputTokens;
  }

  public long getReasoningTokens() {
    return reasoningTokens;
  }

  public void setReasoningTokens(long reasoningTokens) {
    this.reasoningTokens = reasoningTokens;
  }

  public BigDecimal getEstimatedCostUsd() {
    return estimatedCostUsd;
  }

  public void setEstimatedCostUsd(BigDecimal estimatedCostUsd) {
    this.estimatedCostUsd = estimatedCostUsd;
  }
}
