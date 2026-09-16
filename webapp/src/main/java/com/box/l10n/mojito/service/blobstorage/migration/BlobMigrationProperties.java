package com.box.l10n.mojito.service.blobstorage.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.blob-storage.migration")
public class BlobMigrationProperties {
  private boolean enabled;
  private boolean promotionEnabled;
  private String fenceVerifierExecutable;
  private int leaseSeconds = 120;

  public boolean isPromotionEnabled() {
    return promotionEnabled;
  }

  public void setPromotionEnabled(boolean value) {
    promotionEnabled = value;
  }

  public String getFenceVerifierExecutable() {
    return fenceVerifierExecutable;
  }

  public void setFenceVerifierExecutable(String value) {
    fenceVerifierExecutable = value;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getLeaseSeconds() {
    return leaseSeconds;
  }

  public void setLeaseSeconds(int seconds) {
    if (seconds < 30 || seconds > 3600)
      throw new IllegalArgumentException("Lease must be 30-3600 seconds");
    leaseSeconds = seconds;
  }
}
