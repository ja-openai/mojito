package com.box.l10n.mojito.entity.cms;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.Check;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.envers.RelationTargetAuditMode;

@Entity
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
@Check(
    name = "CK__CMS_CONTENT_PROJECT__DELIVERY_HINT",
    constraints = "delivery_hint in ('BLOB_CDN', 'STATSIG_DYNAMIC_CONFIG', 'EXPERIENCE_FRAMEWORK')")
@Check(
    name = "CK__CMS_CONTENT_PROJECT__LAST_PUBLISHED_SNAPSHOT_VERSION",
    constraints = "last_published_snapshot_version >= 0")
@Check(name = "CK__CMS_CONTENT_PROJECT__ENTITY_VERSION", constraints = "entity_version >= 0")
@Check(name = "CK__CMS_CONTENT_PROJECT__ASSET_VIRTUAL", constraints = "asset_virtual = true")
@Check(
    name = "CK__CMS_CONTENT_PROJECT__ASSET_CMS_MANAGED",
    constraints = "asset_cms_managed = true")
@Check(name = "CK__CMS_CONTENT_PROJECT__ASSET_NOT_DELETED", constraints = "asset_deleted = false")
@Table(
    name = "cms_content_project",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_PROJECT__ID_ASSET",
          columnNames = {"id", "asset_id"})
    },
    indexes = {
      @Index(
          name = "UK__CMS_CONTENT_PROJECT__PROJECT_KEY",
          columnList = "project_key",
          unique = true),
      @Index(name = "UK__CMS_CONTENT_PROJECT__ASSET", columnList = "asset_id", unique = true),
      @Index(
          name = "I__CMS_CONTENT_PROJECT__LIVE_CMS_ASSET",
          columnList = "asset_id, repository_id, asset_virtual, asset_cms_managed, asset_deleted")
    })
public class CmsContentProject extends CmsAuditableEntity {

  public static final int KEY_MAX_LENGTH = 128;
  public static final int NAME_MAX_LENGTH = 255;
  public static final int DESCRIPTION_MAX_LENGTH = 1024;
  public static final int DELIVERY_HINT_MAX_LENGTH = 64;

  @Column(name = "project_key", nullable = false, length = KEY_MAX_LENGTH)
  private String projectKey;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
  private String description;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled = true;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_PROJECT__REPOSITORY"))
  private Repository repository;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "asset_id", foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_PROJECT__ASSET"))
  private Asset asset;

  @Column(name = "asset_id", nullable = false, insertable = false, updatable = false)
  @NotAudited
  private Long assetId;

  @Column(name = "asset_virtual", nullable = false)
  private Boolean assetVirtual = true;

  @Column(name = "asset_cms_managed", nullable = false)
  private Boolean assetCmsManaged = true;

  @Column(name = "asset_deleted", nullable = false)
  private Boolean assetDeleted = false;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(
      value = {
        @JoinColumn(
            name = "asset_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "repository_id",
            referencedColumnName = "repository_id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "asset_virtual",
            referencedColumnName = "`virtual`",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "asset_cms_managed",
            referencedColumnName = "cms_managed",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "asset_deleted",
            referencedColumnName = "deleted",
            insertable = false,
            updatable = false)
      },
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_PROJECT__LIVE_CMS_ASSET"))
  @NotAudited
  private Asset cmsVirtualAsset;

  @Column(name = "delivery_hint", nullable = false, length = DELIVERY_HINT_MAX_LENGTH)
  private String deliveryHint = "BLOB_CDN";

  @Column(name = "last_published_snapshot_version", nullable = false)
  @NotAudited
  private Integer lastPublishedSnapshotVersion = 0;

  @Column(name = "entity_version")
  @Version
  private Long entityVersion = 0L;

  public String getProjectKey() {
    return projectKey;
  }

  public void setProjectKey(String projectKey) {
    this.projectKey = projectKey;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
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

  public Long getAssetId() {
    return assetId;
  }

  public String getDeliveryHint() {
    return deliveryHint;
  }

  public void setDeliveryHint(String deliveryHint) {
    this.deliveryHint = deliveryHint;
  }

  public Integer getLastPublishedSnapshotVersion() {
    return lastPublishedSnapshotVersion;
  }

  public void setLastPublishedSnapshotVersion(Integer lastPublishedSnapshotVersion) {
    this.lastPublishedSnapshotVersion = lastPublishedSnapshotVersion;
  }

  public Long getEntityVersion() {
    return entityVersion;
  }

  public void setEntityVersion(Long entityVersion) {
    this.entityVersion = entityVersion;
  }
}
