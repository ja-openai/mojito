package com.box.l10n.mojito.service.pollableTask;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.pollable-task.archive")
public class PollableTaskArchiveProperties {

  private int retentionDays = 90;

  private int batchSize = 100;

  private int leaseSeconds = 300;

  private boolean deleteSource;

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

  public int getLeaseSeconds() {
    return leaseSeconds;
  }

  public void setLeaseSeconds(int leaseSeconds) {
    this.leaseSeconds = leaseSeconds;
  }

  public boolean isDeleteSource() {
    return deleteSource;
  }

  public void setDeleteSource(boolean deleteSource) {
    this.deleteSource = deleteSource;
  }
}
