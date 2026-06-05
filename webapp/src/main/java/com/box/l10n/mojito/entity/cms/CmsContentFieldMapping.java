package com.box.l10n.mojito.entity.cms;

import com.box.l10n.mojito.entity.TMTextUnit;
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
@Check(name = "CK__CMS_CONTENT_FIELD_MAPPING__ENTITY_VERSION", constraints = "entity_version >= 0")
@Table(
    name = "cms_content_field_mapping",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_FIELD_MAPPING__VARIANT_FIELD",
          columnNames = {"content_entry_variant_id", "content_type_field_id"}),
      @UniqueConstraint(
          name = "UK__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT",
          columnNames = {"tm_text_unit_id"})
    },
    indexes = {
      @Index(
          name = "I__CMS_CONTENT_FIELD_MAPPING__VARIANT_TYPE",
          columnList = "content_entry_variant_id, content_type_id"),
      @Index(
          name = "I__CMS_CONTENT_FIELD_MAPPING__VARIANT_TYPE_PROJECT",
          columnList = "content_entry_variant_id, content_type_id, content_project_id"),
      @Index(
          name = "I__CMS_CONTENT_FIELD_MAPPING__FIELD_TYPE",
          columnList = "content_type_field_id, content_type_id"),
      @Index(
          name = "I__CMS_CONTENT_FIELD_MAPPING__PROJECT_ASSET",
          columnList = "content_project_id, asset_id"),
      @Index(
          name = "I__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT_ASSET",
          columnList = "tm_text_unit_id, asset_id")
    })
public class CmsContentFieldMapping extends CmsAuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_entry_variant_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__VARIANT"))
  private CmsContentEntryVariant variant;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_type_field_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__TYPE_FIELD"))
  private CmsContentTypeField field;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "content_type_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__TYPE"))
  private CmsContentType contentType;

  @Column(name = "content_project_id", nullable = false)
  private Long contentProjectId;

  @Column(name = "asset_id", nullable = false)
  private Long assetId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(
      value = {
        @JoinColumn(
            name = "content_entry_variant_id",
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
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__VARIANT_TYPE_PROJECT"))
  @NotAudited
  private CmsContentEntryVariant scopedVariant;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(
      value = {
        @JoinColumn(
            name = "content_type_field_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "content_type_id",
            referencedColumnName = "content_type_id",
            insertable = false,
            updatable = false)
      },
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__FIELD_TYPE"))
  @NotAudited
  private CmsContentTypeField typedField;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "tm_text_unit_id",
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT"))
  private TMTextUnit tmTextUnit;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(
      value = {
        @JoinColumn(
            name = "content_project_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "asset_id",
            referencedColumnName = "asset_id",
            insertable = false,
            updatable = false)
      },
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__PROJECT_ASSET"))
  @NotAudited
  private CmsContentProject scopedProjectAsset;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumns(
      value = {
        @JoinColumn(
            name = "tm_text_unit_id",
            referencedColumnName = "id",
            insertable = false,
            updatable = false),
        @JoinColumn(
            name = "asset_id",
            referencedColumnName = "asset_id",
            insertable = false,
            updatable = false)
      },
      foreignKey = @ForeignKey(name = "FK__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT_ASSET"))
  @NotAudited
  private TMTextUnit scopedAssetTextUnit;

  @Column(name = "entity_version")
  @Version
  private Long entityVersion = 0L;

  public CmsContentEntryVariant getVariant() {
    return variant;
  }

  public void setVariant(CmsContentEntryVariant variant) {
    this.variant = variant;
  }

  public CmsContentTypeField getField() {
    return field;
  }

  public void setField(CmsContentTypeField field) {
    this.field = field;
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

  public Long getAssetId() {
    return assetId;
  }

  public void setAssetId(Long assetId) {
    this.assetId = assetId;
  }

  public TMTextUnit getTmTextUnit() {
    return tmTextUnit;
  }

  public void setTmTextUnit(TMTextUnit tmTextUnit) {
    this.tmTextUnit = tmTextUnit;
  }

  public Long getEntityVersion() {
    return entityVersion;
  }

  public void setEntityVersion(Long entityVersion) {
    this.entityVersion = entityVersion;
  }
}
