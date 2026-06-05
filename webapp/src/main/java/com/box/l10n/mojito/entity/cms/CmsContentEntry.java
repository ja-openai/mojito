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
    name = "CK__CMS_CONTENT_ENTRY__STATUS",
    constraints = "status in ('DRAFT', 'READY', 'ARCHIVED')")
@Check(name = "CK__CMS_CONTENT_ENTRY__ENTITY_VERSION", constraints = "entity_version >= 0")
@Table(
    name = "cms_content_entry",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_ENTRY__PROJECT_ENTRY_KEY",
          columnNames = {"content_project_id", "entry_key"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_ENTRY__ID_TYPE",
          columnNames = {"id", "content_type_id"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_ENTRY__ID_TYPE_PROJECT",
          columnNames = {"id", "content_type_id", "content_project_id"})
    },
    indexes = {
      @Index(
          name = "I__CMS_CONTENT_ENTRY__TYPE_PROJECT",
          columnList = "content_type_id, content_project_id")
    })
public class CmsContentEntry extends CmsAuditableEntity {

  public enum Status {
    DRAFT,
    READY,
    ARCHIVED
  }

  public static final int KEY_MAX_LENGTH = 128;
  public static final int NAME_MAX_LENGTH = 255;
  public static final int DESCRIPTION_MAX_LENGTH = 1024;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_project_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_ENTRY__PROJECT"))
  private CmsContentProject project;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_type_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_ENTRY__TYPE"))
  private CmsContentType contentType;

  @Column(name = "content_type_id", insertable = false, updatable = false)
  @NotAudited
  private Long contentTypeId;

  @Column(name = "content_project_id", insertable = false, updatable = false)
  @NotAudited
  private Long contentProjectId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(
      value = {
        @JoinColumn(
            name = "content_type_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "content_project_id",
            referencedColumnName = "content_project_id",
            insertable = false,
            updatable = false)
      },
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_ENTRY__TYPE_PROJECT"))
  @NotAudited
  private CmsContentType projectContentType;

  @Column(name = "entry_key", nullable = false, length = KEY_MAX_LENGTH)
  private String entryKey;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
  private String description;

  @Column(name = "status", nullable = false)
  @Enumerated(EnumType.STRING)
  private Status status = Status.DRAFT;

  @Column(name = "metadata_json", length = Integer.MAX_VALUE)
  private String metadataJson;

  @Column(name = "entity_version")
  @Version
  private Long entityVersion = 0L;

  public CmsContentProject getProject() {
    return project;
  }

  public void setProject(CmsContentProject project) {
    this.project = project;
  }

  public CmsContentType getContentType() {
    return contentType;
  }

  public void setContentType(CmsContentType contentType) {
    this.contentType = contentType;
  }

  public String getEntryKey() {
    return entryKey;
  }

  public void setEntryKey(String entryKey) {
    this.entryKey = entryKey;
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

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public String getMetadataJson() {
    return metadataJson;
  }

  public void setMetadataJson(String metadataJson) {
    this.metadataJson = metadataJson;
  }

  public Long getEntityVersion() {
    return entityVersion;
  }

  public void setEntityVersion(Long entityVersion) {
    this.entityVersion = entityVersion;
  }
}
