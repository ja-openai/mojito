package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(name = "term_index_refresh_run")
public class TermIndexRefreshRun extends AuditableEntity {

  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_SUCCEEDED = "SUCCEEDED";
  public static final String STATUS_FAILED = "FAILED";

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_RUNNING;

  @Column(name = "requested_repository_ids", length = 2048)
  private String requestedRepositoryIds;

  @Column(name = "processed_text_unit_count", nullable = false)
  private Long processedTextUnitCount = 0L;

  @Column(name = "entry_count", nullable = false)
  private Long entryCount = 0L;

  @Column(name = "occurrence_count", nullable = false)
  private Long occurrenceCount = 0L;

  @Column(name = "started_at")
  private ZonedDateTime startedAt;

  @Column(name = "completed_at")
  private ZonedDateTime completedAt;

  @Column(name = "error_message", length = 2048)
  private String errorMessage;

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getRequestedRepositoryIds() {
    return requestedRepositoryIds;
  }

  public void setRequestedRepositoryIds(String requestedRepositoryIds) {
    this.requestedRepositoryIds = requestedRepositoryIds;
  }

  public Long getProcessedTextUnitCount() {
    return processedTextUnitCount;
  }

  public void setProcessedTextUnitCount(Long processedTextUnitCount) {
    this.processedTextUnitCount = processedTextUnitCount;
  }

  public Long getEntryCount() {
    return entryCount;
  }

  public void setEntryCount(Long entryCount) {
    this.entryCount = entryCount;
  }

  public Long getOccurrenceCount() {
    return occurrenceCount;
  }

  public void setOccurrenceCount(Long occurrenceCount) {
    this.occurrenceCount = occurrenceCount;
  }

  public ZonedDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(ZonedDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public ZonedDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(ZonedDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
