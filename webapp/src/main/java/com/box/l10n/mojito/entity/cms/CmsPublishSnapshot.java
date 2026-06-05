package com.box.l10n.mojito.entity.cms;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.BaseEntity;
import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Check;
import org.springframework.data.annotation.CreatedBy;

@Entity
@Check(name = "CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_VERSION", constraints = "snapshot_version >= 1")
@Check(name = "CK__CMS_PUBLISH_SNAPSHOT__STATUS", constraints = "status = 'PUBLISHED'")
@Check(
    name = "CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_BYTE_SIZE",
    constraints = "artifact_byte_size >= 0")
@Table(
    name = "cms_publish_snapshot",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__CMS_PUBLISH_SNAPSHOT__PROJECT_VERSION",
          columnNames = {"content_project_id", "snapshot_version"}),
      @UniqueConstraint(
          name = "UK__CMS_PUBLISH_SNAPSHOT__PROJECT_REQUEST_KEY",
          columnNames = {"content_project_id", "publish_request_key"})
    },
    indexes = {
      @Index(
          name = "I__CMS_PUBLISH_SNAPSHOT__PROJECT_CREATED",
          columnList = "content_project_id, created_date")
    })
public class CmsPublishSnapshot extends AuditableEntity {

  public enum Status {
    PUBLISHED
  }

  public static final int PUBLISH_REQUEST_KEY_MAX_LENGTH = 128;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_project_id",
      foreignKey = @ForeignKey(name = "FK__CMS_PUBLISH_SNAPSHOT__PROJECT"))
  private CmsContentProject project;

  @CreatedBy
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = BaseEntity.CreatedByUserColumnName,
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__CMS_PUBLISH_SNAPSHOT__CREATED_BY_USER"))
  private User createdByUser;

  @Column(name = "created_by_username", nullable = false, length = User.NAME_MAX_LENGTH)
  private String createdByUsername;

  @Column(name = "published_at", nullable = false, length = 32)
  private String publishedAt;

  @Column(name = "snapshot_version", nullable = false)
  private Integer snapshotVersion;

  @Column(name = "publish_request_key", nullable = false, length = PUBLISH_REQUEST_KEY_MAX_LENGTH)
  private String publishRequestKey;

  @Column(name = "publish_request_locale_tags", nullable = false, length = Integer.MAX_VALUE)
  private String publishRequestLocaleTags;

  @Column(name = "publish_request_authoring_sha256", nullable = false, length = 64)
  private String publishRequestAuthoringSha256;

  @Column(name = "publish_request_package_sha256", nullable = false, length = 64)
  private String publishRequestPackageSha256;

  @Column(name = "status", nullable = false)
  @Enumerated(EnumType.STRING)
  private Status status = Status.PUBLISHED;

  @Column(name = "locale_tags", nullable = false, length = Integer.MAX_VALUE)
  private String localeTags;

  @Column(name = "artifact_json", nullable = false, length = Integer.MAX_VALUE)
  private String artifactJson;

  @Column(name = "artifact_sha256", nullable = false, length = 64)
  private String artifactSha256;

  @Column(name = "artifact_byte_size", nullable = false)
  private Long artifactByteSize;

  @Column(name = "snapshot_signing_key_id", nullable = false, length = 128)
  private String snapshotSigningKeyId;

  @Column(name = "snapshot_signature", nullable = false, length = 64)
  private String snapshotSignature;

  @Column(name = "artifact_signature", nullable = false, length = 64)
  private String artifactSignature;

  @Column(name = "completeness_json", nullable = false, length = Integer.MAX_VALUE)
  private String completenessJson;

  public CmsContentProject getProject() {
    return project;
  }

  public void setProject(CmsContentProject project) {
    this.project = project;
  }

  public User getCreatedByUser() {
    return createdByUser;
  }

  public void setCreatedByUser(User createdByUser) {
    this.createdByUser = createdByUser;
  }

  public String getCreatedByUsername() {
    return createdByUsername;
  }

  public void setCreatedByUsername(String createdByUsername) {
    this.createdByUsername = createdByUsername;
  }

  public String getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(String publishedAt) {
    this.publishedAt = publishedAt;
  }

  public Integer getSnapshotVersion() {
    return snapshotVersion;
  }

  public void setSnapshotVersion(Integer snapshotVersion) {
    this.snapshotVersion = snapshotVersion;
  }

  public String getPublishRequestKey() {
    return publishRequestKey;
  }

  public void setPublishRequestKey(String publishRequestKey) {
    this.publishRequestKey = publishRequestKey;
  }

  public String getPublishRequestLocaleTags() {
    return publishRequestLocaleTags;
  }

  public void setPublishRequestLocaleTags(String publishRequestLocaleTags) {
    this.publishRequestLocaleTags = publishRequestLocaleTags;
  }

  public String getPublishRequestAuthoringSha256() {
    return publishRequestAuthoringSha256;
  }

  public void setPublishRequestAuthoringSha256(String publishRequestAuthoringSha256) {
    this.publishRequestAuthoringSha256 = publishRequestAuthoringSha256;
  }

  public String getPublishRequestPackageSha256() {
    return publishRequestPackageSha256;
  }

  public void setPublishRequestPackageSha256(String publishRequestPackageSha256) {
    this.publishRequestPackageSha256 = publishRequestPackageSha256;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public String getLocaleTags() {
    return localeTags;
  }

  public void setLocaleTags(String localeTags) {
    this.localeTags = localeTags;
  }

  public String getArtifactJson() {
    return artifactJson;
  }

  public void setArtifactJson(String artifactJson) {
    this.artifactJson = artifactJson;
  }

  public String getArtifactSha256() {
    return artifactSha256;
  }

  public void setArtifactSha256(String artifactSha256) {
    this.artifactSha256 = artifactSha256;
  }

  public Long getArtifactByteSize() {
    return artifactByteSize;
  }

  public void setArtifactByteSize(Long artifactByteSize) {
    this.artifactByteSize = artifactByteSize;
  }

  public String getSnapshotSigningKeyId() {
    return snapshotSigningKeyId;
  }

  public void setSnapshotSigningKeyId(String snapshotSigningKeyId) {
    this.snapshotSigningKeyId = snapshotSigningKeyId;
  }

  public String getSnapshotSignature() {
    return snapshotSignature;
  }

  public void setSnapshotSignature(String snapshotSignature) {
    this.snapshotSignature = snapshotSignature;
  }

  public String getArtifactSignature() {
    return artifactSignature;
  }

  public void setArtifactSignature(String artifactSignature) {
    this.artifactSignature = artifactSignature;
  }

  public String getCompletenessJson() {
    return completenessJson;
  }

  public void setCompletenessJson(String completenessJson) {
    this.completenessJson = completenessJson;
  }

  @PreUpdate
  @PreRemove
  private void rejectMutation() {
    throw new IllegalStateException("CMS publish snapshots are immutable");
  }
}
