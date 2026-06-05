package com.box.l10n.mojito.entity.cms;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "cms_publish_snapshot_seal")
public class CmsPublishSnapshotSeal {

  @Id
  @Column(name = "publish_snapshot_id")
  private Long publishSnapshotId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "publish_snapshot_id",
      insertable = false,
      updatable = false,
      foreignKey = @ForeignKey(name = "FK__CMS_PUBLISH_SNAPSHOT_SEAL__SNAPSHOT"))
  private CmsPublishSnapshot snapshot;

  public Long getPublishSnapshotId() {
    return publishSnapshotId;
  }

  public void setPublishSnapshotId(Long publishSnapshotId) {
    this.publishSnapshotId = publishSnapshotId;
  }

  public CmsPublishSnapshot getSnapshot() {
    return snapshot;
  }

  @PreUpdate
  @PreRemove
  private void rejectMutation() {
    throw new IllegalStateException("CMS publish snapshot seals are immutable");
  }
}
