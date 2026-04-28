package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "ai_translate_text_unit_attempt",
    indexes = {
      @Index(name = "I__AITTA__POLLABLE_GROUP", columnList = "pollable_task_id, request_group_id"),
      @Index(
          name = "I__AITTA__TM_TEXT_UNIT__LOCALE",
          columnList = "tm_text_unit_id, locale_id, created_date")
    })
public class AiTranslateTextUnitAttempt extends AuditableEntity {

  public static final String STATUS_REQUESTED = "REQUESTED";
  public static final String STATUS_RESPONDED = "RESPONDED";
  public static final String STATUS_IMPORTED = "IMPORTED";
  public static final String STATUS_FAILED = "FAILED";

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "ai_translate_run_id",
      foreignKey = @ForeignKey(name = "FK__AITTA__AI_TRANSLATE_RUN__ID"))
  private AiTranslateRun aiTranslateRun;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "pollable_task_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__AITTA__POLLABLE_TASK__ID"))
  private PollableTask pollableTask;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "tm_text_unit_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__AITTA__TM_TEXT_UNIT__ID"))
  private TMTextUnit tmTextUnit;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "locale_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__AITTA__LOCALE__ID"))
  private Locale locale;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "tm_text_unit_variant_id",
      foreignKey = @ForeignKey(name = "FK__AITTA__TM_TEXT_UNIT_VARIANT__ID"))
  private TMTextUnitVariant tmTextUnitVariant;

  @Column(name = "request_group_id", nullable = false, length = 255)
  private String requestGroupId;

  @Column(name = "translate_type", nullable = false, length = 64)
  private String translateType;

  @Column(name = "model", length = 255)
  private String model;

  @Column(name = "status", nullable = false, length = 32)
  private String status;

  @Column(name = "completion_id", length = 255)
  private String completionId;

  @Column(name = "request_payload_blob_name", length = 1024)
  private String requestPayloadBlobName;

  @Column(name = "response_payload_blob_name", length = 1024)
  private String responsePayloadBlobName;

  @Column(name = "error_message", length = Integer.MAX_VALUE)
  private String errorMessage;

  public AiTranslateRun getAiTranslateRun() {
    return aiTranslateRun;
  }

  public void setAiTranslateRun(AiTranslateRun aiTranslateRun) {
    this.aiTranslateRun = aiTranslateRun;
  }

  public PollableTask getPollableTask() {
    return pollableTask;
  }

  public void setPollableTask(PollableTask pollableTask) {
    this.pollableTask = pollableTask;
  }

  public TMTextUnit getTmTextUnit() {
    return tmTextUnit;
  }

  public void setTmTextUnit(TMTextUnit tmTextUnit) {
    this.tmTextUnit = tmTextUnit;
  }

  public Locale getLocale() {
    return locale;
  }

  public void setLocale(Locale locale) {
    this.locale = locale;
  }

  public TMTextUnitVariant getTmTextUnitVariant() {
    return tmTextUnitVariant;
  }

  public void setTmTextUnitVariant(TMTextUnitVariant tmTextUnitVariant) {
    this.tmTextUnitVariant = tmTextUnitVariant;
  }

  public String getRequestGroupId() {
    return requestGroupId;
  }

  public void setRequestGroupId(String requestGroupId) {
    this.requestGroupId = requestGroupId;
  }

  public String getTranslateType() {
    return translateType;
  }

  public void setTranslateType(String translateType) {
    this.translateType = translateType;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getCompletionId() {
    return completionId;
  }

  public void setCompletionId(String completionId) {
    this.completionId = completionId;
  }

  public String getRequestPayloadBlobName() {
    return requestPayloadBlobName;
  }

  public void setRequestPayloadBlobName(String requestPayloadBlobName) {
    this.requestPayloadBlobName = requestPayloadBlobName;
  }

  public String getResponsePayloadBlobName() {
    return responsePayloadBlobName;
  }

  public void setResponsePayloadBlobName(String responsePayloadBlobName) {
    this.responsePayloadBlobName = responsePayloadBlobName;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
