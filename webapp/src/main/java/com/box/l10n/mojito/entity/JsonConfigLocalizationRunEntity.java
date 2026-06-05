package com.box.l10n.mojito.entity;

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
    name = "json_config_localization_run",
    indexes = {
      @Index(name = "I__JSON_CONFIG_LOCALIZATION_RUN__CREATED_DATE", columnList = "created_date"),
      @Index(
          name = "I__JSON_CONFIG_LOCALIZATION_RUN__SETUP",
          columnList = "json_config_localization_id")
    })
public class JsonConfigLocalizationRunEntity extends AuditableEntity {

  public enum TriggerSource {
    CRON
  }

  public enum Status {
    RUNNING,
    COMPLETED,
    FAILED
  }

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "json_config_localization_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__JSON_CONFIG_LOCALIZATION_RUN__SETUP"))
  private JsonConfigLocalizationEntity jsonConfigLocalization;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_source", nullable = false, length = 32)
  private TriggerSource triggerSource;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private Status status;

  @Column(name = "started_at")
  private ZonedDateTime startedAt;

  @Column(name = "finished_at")
  private ZonedDateTime finishedAt;

  @Column(name = "pull_enabled", nullable = false)
  private boolean pullEnabled;

  @Column(name = "extract_enabled", nullable = false)
  private boolean extractEnabled;

  @Column(name = "translate_enabled", nullable = false)
  private boolean translateEnabled;

  @Column(name = "merge_enabled", nullable = false)
  private boolean mergeEnabled;

  @Column(name = "save_config_enabled", nullable = false)
  private boolean saveConfigEnabled;

  @Column(name = "push_enabled", nullable = false)
  private boolean pushEnabled;

  @Column(name = "pulled", nullable = false)
  private boolean pulled;

  @Column(name = "extracted", nullable = false)
  private boolean extracted;

  @Column(name = "translated", nullable = false)
  private boolean translated;

  @Column(name = "merged", nullable = false)
  private boolean merged;

  @Column(name = "saved_config", nullable = false)
  private boolean savedConfig;

  @Column(name = "pushed", nullable = false)
  private boolean pushed;

  @Column(name = "push_skipped", nullable = false)
  private boolean pushSkipped;

  @Column(name = "summary", length = 1024)
  private String summary;

  @Column(name = "error_message", length = 4000)
  private String errorMessage;

  public JsonConfigLocalizationEntity getJsonConfigLocalization() {
    return jsonConfigLocalization;
  }

  public void setJsonConfigLocalization(JsonConfigLocalizationEntity jsonConfigLocalization) {
    this.jsonConfigLocalization = jsonConfigLocalization;
  }

  public TriggerSource getTriggerSource() {
    return triggerSource;
  }

  public void setTriggerSource(TriggerSource triggerSource) {
    this.triggerSource = triggerSource;
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

  public boolean isPullEnabled() {
    return pullEnabled;
  }

  public void setPullEnabled(boolean pullEnabled) {
    this.pullEnabled = pullEnabled;
  }

  public boolean isExtractEnabled() {
    return extractEnabled;
  }

  public void setExtractEnabled(boolean extractEnabled) {
    this.extractEnabled = extractEnabled;
  }

  public boolean isTranslateEnabled() {
    return translateEnabled;
  }

  public void setTranslateEnabled(boolean translateEnabled) {
    this.translateEnabled = translateEnabled;
  }

  public boolean isMergeEnabled() {
    return mergeEnabled;
  }

  public void setMergeEnabled(boolean mergeEnabled) {
    this.mergeEnabled = mergeEnabled;
  }

  public boolean isSaveConfigEnabled() {
    return saveConfigEnabled;
  }

  public void setSaveConfigEnabled(boolean saveConfigEnabled) {
    this.saveConfigEnabled = saveConfigEnabled;
  }

  public boolean isPushEnabled() {
    return pushEnabled;
  }

  public void setPushEnabled(boolean pushEnabled) {
    this.pushEnabled = pushEnabled;
  }

  public boolean isPulled() {
    return pulled;
  }

  public void setPulled(boolean pulled) {
    this.pulled = pulled;
  }

  public boolean isExtracted() {
    return extracted;
  }

  public void setExtracted(boolean extracted) {
    this.extracted = extracted;
  }

  public boolean isTranslated() {
    return translated;
  }

  public void setTranslated(boolean translated) {
    this.translated = translated;
  }

  public boolean isMerged() {
    return merged;
  }

  public void setMerged(boolean merged) {
    this.merged = merged;
  }

  public boolean isSavedConfig() {
    return savedConfig;
  }

  public void setSavedConfig(boolean savedConfig) {
    this.savedConfig = savedConfig;
  }

  public boolean isPushed() {
    return pushed;
  }

  public void setPushed(boolean pushed) {
    this.pushed = pushed;
  }

  public boolean isPushSkipped() {
    return pushSkipped;
  }

  public void setPushSkipped(boolean pushSkipped) {
    this.pushSkipped = pushSkipped;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
