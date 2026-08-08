package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(name = "mblob_cleanup_policy")
public class DatabaseBlobCleanupPolicy extends AuditableEntity {

  @Column(nullable = false, unique = true)
  private String prefix;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "retention_days", nullable = false)
  private int retentionDays = 3;

  @Column(name = "batch_size", nullable = false)
  private int batchSize = 250;

  @Column(name = "max_batches_per_run", nullable = false)
  private int maxBatchesPerRun;

  @Column(name = "pause_millis", nullable = false)
  private int pauseMillis = 250;

  @Column(name = "max_retries", nullable = false)
  private int maxRetries = 5;

  @Column(name = "stop_requested", nullable = false)
  private boolean stopRequested;

  @Column(nullable = false, length = 32)
  private String status = "IDLE";

  @Column(name = "last_started_date")
  private ZonedDateTime lastStartedDate;

  @Column(name = "last_finished_date")
  private ZonedDateTime lastFinishedDate;

  @Column(name = "last_deleted_count", nullable = false)
  private long lastDeletedCount;

  @Column(name = "total_deleted_count", nullable = false)
  private long totalDeletedCount;

  @Column(name = "last_error", length = 2048)
  private String lastError;

  public String getPrefix() {
    return prefix;
  }

  public void setPrefix(String prefix) {
    this.prefix = prefix;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getRetentionDays() {
    return retentionDays;
  }

  public void setRetentionDays(int retentionDays) {
    this.retentionDays = retentionDays;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getMaxBatchesPerRun() {
    return maxBatchesPerRun;
  }

  public void setMaxBatchesPerRun(int maxBatchesPerRun) {
    this.maxBatchesPerRun = maxBatchesPerRun;
  }

  public int getPauseMillis() {
    return pauseMillis;
  }

  public void setPauseMillis(int pauseMillis) {
    this.pauseMillis = pauseMillis;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public boolean isStopRequested() {
    return stopRequested;
  }

  public void setStopRequested(boolean stopRequested) {
    this.stopRequested = stopRequested;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public ZonedDateTime getLastStartedDate() {
    return lastStartedDate;
  }

  public void setLastStartedDate(ZonedDateTime lastStartedDate) {
    this.lastStartedDate = lastStartedDate;
  }

  public ZonedDateTime getLastFinishedDate() {
    return lastFinishedDate;
  }

  public void setLastFinishedDate(ZonedDateTime lastFinishedDate) {
    this.lastFinishedDate = lastFinishedDate;
  }

  public long getLastDeletedCount() {
    return lastDeletedCount;
  }

  public void setLastDeletedCount(long lastDeletedCount) {
    this.lastDeletedCount = lastDeletedCount;
  }

  public long getTotalDeletedCount() {
    return totalDeletedCount;
  }

  public void setTotalDeletedCount(long totalDeletedCount) {
    this.totalDeletedCount = totalDeletedCount;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }
}
