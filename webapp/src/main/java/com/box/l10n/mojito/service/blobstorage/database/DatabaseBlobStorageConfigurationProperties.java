package com.box.l10n.mojito.service.blobstorage.database;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.blob-storage.database")
public class DatabaseBlobStorageConfigurationProperties {

  /**
   * Time to live in seconds for blobs that should be kept at least 1 day, {@link
   * com.box.l10n.mojito.service.blobstorage.Retention.MIN_1_DAY}.
   *
   * <p>Default is 1 day. Explicit configuration overrides this default. Changing the property does
   * not backfill existing rows.
   */
  long min1DayTtl = Duration.ofDays(1).toSeconds();

  /**
   * Threshold in milliseconds for warning about slow expired-blob cleanup runs or batches.
   *
   * <p>Set to 0 or a negative value to disable slow cleanup warnings.
   */
  long cleanupSlowThresholdMs = 5000;

  public long getMin1DayTtl() {
    return min1DayTtl;
  }

  public void setMin1DayTtl(long min1DayTtl) {
    this.min1DayTtl = min1DayTtl;
  }

  public long getCleanupSlowThresholdMs() {
    return cleanupSlowThresholdMs;
  }

  public void setCleanupSlowThresholdMs(long cleanupSlowThresholdMs) {
    this.cleanupSlowThresholdMs = cleanupSlowThresholdMs;
  }
}
