package com.box.l10n.mojito.entity.cms;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
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
@Check(
    name = "CK__CMS_CONTENT_TYPE_FIELD__FIELD_TYPE",
    constraints = "field_type in ('TEXT', 'ICU_MESSAGE')")
@Check(name = "CK__CMS_CONTENT_TYPE_FIELD__LOCALIZABLE", constraints = "localizable = true")
@Check(name = "CK__CMS_CONTENT_TYPE_FIELD__SORT_ORDER", constraints = "sort_order >= 0")
@Check(name = "CK__CMS_CONTENT_TYPE_FIELD__ENTITY_VERSION", constraints = "entity_version >= 0")
@Table(
    name = "cms_content_type_field",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_TYPE_FIELD__TYPE_FIELD_KEY",
          columnNames = {"content_type_id", "field_key"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_TYPE_FIELD__ID_TYPE",
          columnNames = {"id", "content_type_id"})
    },
    indexes = {@Index(name = "I__CMS_CONTENT_TYPE_FIELD__TYPE", columnList = "content_type_id")})
public class CmsContentTypeField extends CmsAuditableEntity {

  public enum FieldType {
    TEXT,
    ICU_MESSAGE
  }

  public static final int KEY_MAX_LENGTH = 128;
  public static final int NAME_MAX_LENGTH = 255;
  public static final int DESCRIPTION_MAX_LENGTH = 1024;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_type_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_TYPE_FIELD__TYPE"))
  private CmsContentType contentType;

  @Column(name = "content_type_id", insertable = false, updatable = false)
  @NotAudited
  private Long contentTypeId;

  @Column(name = "field_key", nullable = false, length = KEY_MAX_LENGTH)
  private String fieldKey;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
  private String description;

  @Column(name = "field_type", nullable = false)
  @Enumerated(EnumType.STRING)
  private FieldType fieldType = FieldType.TEXT;

  @Column(name = "localizable", nullable = false)
  private Boolean localizable = true;

  @Column(name = "required", nullable = false)
  private Boolean required = false;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  @Column(name = "entity_version")
  @Version
  private Long entityVersion = 0L;

  public CmsContentType getContentType() {
    return contentType;
  }

  public void setContentType(CmsContentType contentType) {
    this.contentType = contentType;
  }

  public String getFieldKey() {
    return fieldKey;
  }

  public void setFieldKey(String fieldKey) {
    this.fieldKey = fieldKey;
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

  public FieldType getFieldType() {
    return fieldType;
  }

  public void setFieldType(FieldType fieldType) {
    this.fieldType = fieldType;
  }

  public Boolean getLocalizable() {
    return localizable;
  }

  public void setLocalizable(Boolean localizable) {
    this.localizable = localizable;
  }

  public Boolean getRequired() {
    return required;
  }

  public void setRequired(Boolean required) {
    this.required = required;
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
