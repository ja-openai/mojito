package com.box.l10n.mojito.service.cms;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.content-cms")
public class CmsContentConfigurationProperties {

  public static final long DEFAULT_MAX_PUBLISH_ARTIFACT_BYTES = 1024L * 1024L;

  private long maxPublishArtifactBytes = DEFAULT_MAX_PUBLISH_ARTIFACT_BYTES;
  private String snapshotSigningKeyId;
  private Map<String, String> snapshotSigningKeys = new LinkedHashMap<>();

  public long getMaxPublishArtifactBytes() {
    return maxPublishArtifactBytes;
  }

  public void setMaxPublishArtifactBytes(long maxPublishArtifactBytes) {
    this.maxPublishArtifactBytes = maxPublishArtifactBytes;
  }

  public String getSnapshotSigningKeyId() {
    return snapshotSigningKeyId;
  }

  public void setSnapshotSigningKeyId(String snapshotSigningKeyId) {
    this.snapshotSigningKeyId = snapshotSigningKeyId;
  }

  public Map<String, String> getSnapshotSigningKeys() {
    return snapshotSigningKeys;
  }

  public void setSnapshotSigningKeys(Map<String, String> snapshotSigningKeys) {
    this.snapshotSigningKeys =
        snapshotSigningKeys == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(snapshotSigningKeys);
  }
}
