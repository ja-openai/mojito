package com.box.l10n.mojito.entity.cms;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
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
@Check(name = "CK__CMS_CONTENT_TYPE__SCHEMA_VERSION", constraints = "schema_version >= 1")
@Check(name = "CK__CMS_CONTENT_TYPE__ENTITY_VERSION", constraints = "entity_version >= 0")
@Table(
    name = "cms_content_type",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_TYPE__PROJECT_TYPE_KEY",
          columnNames = {"content_project_id", "type_key"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_TYPE__ID_PROJECT",
          columnNames = {"id", "content_project_id"})
    })
public class CmsContentType extends CmsAuditableEntity {

  public static final int KEY_MAX_LENGTH = 128;
  public static final int NAME_MAX_LENGTH = 255;
  public static final int DESCRIPTION_MAX_LENGTH = 1024;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_project_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_TYPE__PROJECT"))
  private CmsContentProject project;

  @Column(name = "content_project_id", insertable = false, updatable = false)
  @NotAudited
  private Long contentProjectId;

  @Column(name = "type_key", nullable = false, length = KEY_MAX_LENGTH)
  private String typeKey;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
  private String description;

  @Column(name = "schema_version", nullable = false)
  private Integer schemaVersion = 1;

  @Column(name = "metadata_schema_json", length = Integer.MAX_VALUE)
  private String metadataSchemaJson;

  @Column(name = "entity_version")
  @Version
  private Long entityVersion = 0L;

  public CmsContentProject getProject() {
    return project;
  }

  public void setProject(CmsContentProject project) {
    this.project = project;
  }

  public String getTypeKey() {
    return typeKey;
  }

  public void setTypeKey(String typeKey) {
    this.typeKey = typeKey;
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

  public Integer getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Integer schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getMetadataSchemaJson() {
    return metadataSchemaJson;
  }

  public void setMetadataSchemaJson(String metadataSchemaJson) {
    this.metadataSchemaJson = metadataSchemaJson;
  }

  public Long getEntityVersion() {
    return entityVersion;
  }

  public void setEntityVersion(Long entityVersion) {
    this.entityVersion = entityVersion;
  }
}
