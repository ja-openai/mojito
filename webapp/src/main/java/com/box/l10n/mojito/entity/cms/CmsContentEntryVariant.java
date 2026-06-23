package com.box.l10n.mojito.entity.cms;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    name = "CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_REQUIRED",
    constraints = "status <> 'CANDIDATE' or candidate_group_key is not null")
@Check(
    name = "CK__CMS_CONTENT_ENTRY_VARIANT__STATUS",
    constraints = "status in ('CONTROL', 'CANDIDATE', 'ARCHIVED')")
@Check(name = "CK__CMS_CONTENT_ENTRY_VARIANT__SORT_ORDER", constraints = "sort_order >= 0")
@Check(name = "CK__CMS_CONTENT_ENTRY_VARIANT__ENTITY_VERSION", constraints = "entity_version >= 0")
@Check(
    name = "CK__CMS_CONTENT_ENTRY_VARIANT__CONTROL_ENTRY",
    constraints =
        "(status = 'CONTROL' and control_entry_id is not null and control_entry_id ="
            + " content_entry_id) or (status <> 'CONTROL' and control_entry_id is null)")
@Table(
    name = "cms_content_entry_variant",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_ENTRY_VARIANT__ENTRY_VARIANT_KEY",
          columnNames = {"content_entry_id", "variant_key"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_ENTRY_VARIANT__ID_TYPE",
          columnNames = {"id", "content_type_id"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_ENTRY_VARIANT__ID_TYPE_PROJECT",
          columnNames = {"id", "content_type_id", "content_project_id"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_ENTRY_VARIANT__CONTROL_ENTRY",
          columnNames = {"control_entry_id"})
    },
    indexes = {
      @Index(
          name = "I__CMS_CONTENT_ENTRY_VARIANT__ENTRY_TYPE_PROJECT",
          columnList = "content_entry_id, content_type_id, content_project_id")
    })
public class CmsContentEntryVariant extends CmsAuditableEntity {

  public enum Status {
    CONTROL,
    CANDIDATE,
    ARCHIVED
  }

  public static final int KEY_MAX_LENGTH = 128;
  public static final int NAME_MAX_LENGTH = 255;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_entry_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_ENTRY_VARIANT__ENTRY"))
  private CmsContentEntry entry;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_type_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_ENTRY_VARIANT__TYPE"))
  private CmsContentType contentType;

  @Column(name = "content_type_id", insertable = false, updatable = false)
  @NotAudited
  private Long contentTypeId;

  @Column(name = "content_project_id", nullable = false)
  private Long contentProjectId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(
      value = {
        @JoinColumn(
            name = "content_entry_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "content_type_id",
            referencedColumnName = "content_type_id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "content_project_id",
            referencedColumnName = "content_project_id",
            insertable = false,
            updatable = false)
      },
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_ENTRY_VARIANT__ENTRY_TYPE_PROJECT"))
  @NotAudited
  private CmsContentEntry typedEntry;

  @Column(name = "variant_key", nullable = false, length = KEY_MAX_LENGTH)
  private String variantKey;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "candidate_group_key", length = KEY_MAX_LENGTH)
  private String candidateGroupKey;

  @Column(name = "status", nullable = false)
  @Enumerated(EnumType.STRING)
  private Status status = Status.CONTROL;

  @Column(name = "control_entry_id")
  private Long controlEntryId;

  @Column(name = "metadata_json", length = Integer.MAX_VALUE)
  private String metadataJson;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  @Column(name = "entity_version")
  @Version
  private Long entityVersion = 0L;

  public CmsContentEntry getEntry() {
    return entry;
  }

  public void setEntry(CmsContentEntry entry) {
    this.entry = entry;
  }

  public CmsContentType getContentType() {
    return contentType;
  }

  public void setContentType(CmsContentType contentType) {
    this.contentType = contentType;
  }

  public Long getContentProjectId() {
    return contentProjectId;
  }

  public void setContentProjectId(Long contentProjectId) {
    this.contentProjectId = contentProjectId;
  }

  public String getVariantKey() {
    return variantKey;
  }

  public void setVariantKey(String variantKey) {
    this.variantKey = variantKey;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCandidateGroupKey() {
    return candidateGroupKey;
  }

  public void setCandidateGroupKey(String candidateGroupKey) {
    this.candidateGroupKey = candidateGroupKey;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public Long getControlEntryId() {
    return controlEntryId;
  }

  public void setControlEntryId(Long controlEntryId) {
    this.controlEntryId = controlEntryId;
  }

  public String getMetadataJson() {
    return metadataJson;
  }

  public void setMetadataJson(String metadataJson) {
    this.metadataJson = metadataJson;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
  }

  public Long getEntityVersion() {
    return entityVersion;
  }

  public void setEntityVersion(Long entityVersion) {
    this.entityVersion = entityVersion;
  }
}
