package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.ZonedDateTime;

@Entity
@Table(name = "pollable_task_archive_checkpoint")
public class PollableTaskArchiveCheckpoint implements Serializable {

  @Id
  @Column(name = "id", nullable = false)
  private Integer id;

  @Column(name = "last_finished_date", nullable = false)
  private ZonedDateTime lastFinishedDate;

  @Column(name = "last_task_id", nullable = false)
  private Long lastTaskId;

  @Column(name = "cutoff_finished_before")
  private ZonedDateTime cutoffFinishedBefore;

  @Column(name = "high_water_finished_date")
  private ZonedDateTime highWaterFinishedDate;

  @Column(name = "high_water_task_id")
  private Long highWaterTaskId;

  @Column(name = "delete_source_mode", nullable = false)
  private boolean deleteSourceMode;

  @Column(name = "retention_days", nullable = false)
  private int retentionDays = 90;

  @Column(name = "lease_token", length = 64)
  private String leaseToken;

  @Column(name = "lease_expires_at")
  private ZonedDateTime leaseExpiresAt;

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public ZonedDateTime getLastFinishedDate() {
    return lastFinishedDate;
  }

  public void setLastFinishedDate(ZonedDateTime lastFinishedDate) {
    this.lastFinishedDate = lastFinishedDate;
  }

  public Long getLastTaskId() {
    return lastTaskId;
  }

  public void setLastTaskId(Long lastTaskId) {
    this.lastTaskId = lastTaskId;
  }

  public ZonedDateTime getCutoffFinishedBefore() {
    return cutoffFinishedBefore;
  }

  public void setCutoffFinishedBefore(ZonedDateTime cutoffFinishedBefore) {
    this.cutoffFinishedBefore = cutoffFinishedBefore;
  }

  public ZonedDateTime getHighWaterFinishedDate() {
    return highWaterFinishedDate;
  }

  public void setHighWaterFinishedDate(ZonedDateTime highWaterFinishedDate) {
    this.highWaterFinishedDate = highWaterFinishedDate;
  }

  public Long getHighWaterTaskId() {
    return highWaterTaskId;
  }

  public void setHighWaterTaskId(Long highWaterTaskId) {
    this.highWaterTaskId = highWaterTaskId;
  }

  public boolean isDeleteSourceMode() {
    return deleteSourceMode;
  }

  public void setDeleteSourceMode(boolean deleteSourceMode) {
    this.deleteSourceMode = deleteSourceMode;
  }

  public int getRetentionDays() {
    return retentionDays;
  }

  public void setRetentionDays(int retentionDays) {
    this.retentionDays = retentionDays;
  }

  public String getLeaseToken() {
    return leaseToken;
  }

  public void setLeaseToken(String leaseToken) {
    this.leaseToken = leaseToken;
  }

  public ZonedDateTime getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public void setLeaseExpiresAt(ZonedDateTime leaseExpiresAt) {
    this.leaseExpiresAt = leaseExpiresAt;
  }
}
