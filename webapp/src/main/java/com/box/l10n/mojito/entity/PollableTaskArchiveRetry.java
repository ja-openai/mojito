package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "pollable_task_archive_retry",
    indexes = {
      @Index(
          name = "I__POLLABLE_TASK_ARCHIVE_RETRY__NEXT_ATTEMPT_TASK",
          columnList = "next_attempt_at,task_id")
    })
public class PollableTaskArchiveRetry implements Serializable {

  @Id
  @Column(name = "task_id", nullable = false)
  private Long taskId;

  @Column(name = "finished_date", nullable = false)
  private ZonedDateTime finishedDate;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private ZonedDateTime nextAttemptAt;

  @Column(name = "last_error", length = 2048)
  private String lastError;

  public Long getTaskId() {
    return taskId;
  }

  public void setTaskId(Long taskId) {
    this.taskId = taskId;
  }

  public ZonedDateTime getFinishedDate() {
    return finishedDate;
  }

  public void setFinishedDate(ZonedDateTime finishedDate) {
    this.finishedDate = finishedDate;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public void setAttemptCount(int attemptCount) {
    this.attemptCount = attemptCount;
  }

  public ZonedDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public void setNextAttemptAt(ZonedDateTime nextAttemptAt) {
    this.nextAttemptAt = nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }
}
