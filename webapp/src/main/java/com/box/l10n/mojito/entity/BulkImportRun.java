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
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "bulk_import_run",
    indexes = {
      @Index(name = "UK__BULK_IMPORT_RUN__RUN_ID", columnList = "run_id", unique = true),
      @Index(
          name = "I__BULK_IMPORT_RUN__REPOSITORY_LOCALE",
          columnList = "repository_id, locale_id, created_date"),
      @Index(name = "I__BULK_IMPORT_RUN__POLLABLE_TASK", columnList = "pollable_task_id")
    })
public class BulkImportRun extends AuditableEntity {

  public enum ActorType {
    HUMAN,
    SERVICE,
    SYSTEM,
    UNKNOWN
  }

  public enum Status {
    RUNNING,
    COMPLETED,
    FAILED
  }

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN__REPOSITORY"))
  private Repository repository;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "asset_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN__ASSET"))
  private Asset asset;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "locale_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN__LOCALE"))
  private Locale locale;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "pollable_task_id",
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN__POLLABLE_TASK"))
  private PollableTask pollableTask;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "initiating_user_id",
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN__INITIATING_USER"))
  private User initiatingUser;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false, length = 16)
  private ActorType actorType;

  @Column(name = "actor_identity", length = 255)
  private String actorIdentity;

  @Column(name = "source", nullable = false, length = 128)
  private String source;

  @Column(name = "import_mode", nullable = false, length = 32)
  private String importMode;

  @Column(name = "integrity_checks_type", nullable = false, length = 64)
  private String integrityChecksType;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status;

  @Column(name = "requested_count", nullable = false)
  private int requestedCount;

  @Column(name = "imported_count", nullable = false)
  private int importedCount;

  @Column(name = "skipped_count", nullable = false)
  private int skippedCount;

  @Column(name = "input_payload_blob_name", length = 1024)
  private String inputPayloadBlobName;

  @Column(name = "output_payload_blob_name", length = 1024)
  private String outputPayloadBlobName;

  @Column(name = "error_message", length = 1024)
  private String errorMessage;

  @Column(name = "completed_date")
  private ZonedDateTime completedDate;

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public Repository getRepository() {
    return repository;
  }

  public void setRepository(Repository repository) {
    this.repository = repository;
  }

  public Asset getAsset() {
    return asset;
  }

  public void setAsset(Asset asset) {
    this.asset = asset;
  }

  public Locale getLocale() {
    return locale;
  }

  public void setLocale(Locale locale) {
    this.locale = locale;
  }

  public PollableTask getPollableTask() {
    return pollableTask;
  }

  public void setPollableTask(PollableTask pollableTask) {
    this.pollableTask = pollableTask;
  }

  public User getInitiatingUser() {
    return initiatingUser;
  }

  public void setInitiatingUser(User initiatingUser) {
    this.initiatingUser = initiatingUser;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public void setActorType(ActorType actorType) {
    this.actorType = actorType;
  }

  public String getActorIdentity() {
    return actorIdentity;
  }

  public void setActorIdentity(String actorIdentity) {
    this.actorIdentity = actorIdentity;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getImportMode() {
    return importMode;
  }

  public void setImportMode(String importMode) {
    this.importMode = importMode;
  }

  public String getIntegrityChecksType() {
    return integrityChecksType;
  }

  public void setIntegrityChecksType(String integrityChecksType) {
    this.integrityChecksType = integrityChecksType;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public int getRequestedCount() {
    return requestedCount;
  }

  public void setRequestedCount(int requestedCount) {
    this.requestedCount = requestedCount;
  }

  public int getImportedCount() {
    return importedCount;
  }

  public void setImportedCount(int importedCount) {
    this.importedCount = importedCount;
  }

  public int getSkippedCount() {
    return skippedCount;
  }

  public void setSkippedCount(int skippedCount) {
    this.skippedCount = skippedCount;
  }

  public String getInputPayloadBlobName() {
    return inputPayloadBlobName;
  }

  public void setInputPayloadBlobName(String inputPayloadBlobName) {
    this.inputPayloadBlobName = inputPayloadBlobName;
  }

  public String getOutputPayloadBlobName() {
    return outputPayloadBlobName;
  }

  public void setOutputPayloadBlobName(String outputPayloadBlobName) {
    this.outputPayloadBlobName = outputPayloadBlobName;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public ZonedDateTime getCompletedDate() {
    return completedDate;
  }

  public void setCompletedDate(ZonedDateTime completedDate) {
    this.completedDate = completedDate;
  }
}
