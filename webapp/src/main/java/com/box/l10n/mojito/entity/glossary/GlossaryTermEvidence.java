package com.box.l10n.mojito.entity.glossary;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.Screenshot;
import com.box.l10n.mojito.entity.TMTextUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "glossary_term_evidence",
    indexes = {
      @Index(
          name = "I__GLOSSARY_TERM_EVIDENCE__TERM_METADATA__ID",
          columnList = "glossary_term_metadata_id"),
      @Index(name = "I__GLOSSARY_TERM_EVIDENCE__SCREENSHOT__ID", columnList = "screenshot_id"),
      @Index(name = "I__GLOSSARY_TERM_EVIDENCE__TM_TEXT_UNIT__ID", columnList = "tm_text_unit_id")
    })
public class GlossaryTermEvidence extends AuditableEntity {

  public static final String EVIDENCE_TYPE_SCREENSHOT = "SCREENSHOT";
  public static final String EVIDENCE_TYPE_STRING_USAGE = "STRING_USAGE";
  public static final String EVIDENCE_TYPE_CODE_REF = "CODE_REF";
  public static final String EVIDENCE_TYPE_NOTE = "NOTE";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "glossary_term_metadata_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_EVIDENCE__TERM_METADATA"))
  private GlossaryTermMetadata glossaryTermMetadata;

  @Column(name = "evidence_type", nullable = false, length = 32)
  private String evidenceType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "screenshot_id",
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_EVIDENCE__SCREENSHOT"))
  private Screenshot screenshot;

  @Column(name = "image_key", length = 512)
  private String imageKey;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "tm_text_unit_id",
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_EVIDENCE__TM_TEXT_UNIT"))
  private TMTextUnit tmTextUnit;

  @Column(name = "caption", length = 1024)
  private String caption;

  @Column(name = "crop_x")
  private Integer cropX;

  @Column(name = "crop_y")
  private Integer cropY;

  @Column(name = "crop_width")
  private Integer cropWidth;

  @Column(name = "crop_height")
  private Integer cropHeight;

  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;

  public GlossaryTermMetadata getGlossaryTermMetadata() {
    return glossaryTermMetadata;
  }

  public void setGlossaryTermMetadata(GlossaryTermMetadata glossaryTermMetadata) {
    this.glossaryTermMetadata = glossaryTermMetadata;
  }

  public String getEvidenceType() {
    return evidenceType;
  }

  public void setEvidenceType(String evidenceType) {
    this.evidenceType = evidenceType;
  }

  public Screenshot getScreenshot() {
    return screenshot;
  }

  public void setScreenshot(Screenshot screenshot) {
    this.screenshot = screenshot;
  }

  public String getImageKey() {
    return imageKey;
  }

  public void setImageKey(String imageKey) {
    this.imageKey = imageKey;
  }

  public TMTextUnit getTmTextUnit() {
    return tmTextUnit;
  }

  public void setTmTextUnit(TMTextUnit tmTextUnit) {
    this.tmTextUnit = tmTextUnit;
  }

  public String getCaption() {
    return caption;
  }

  public void setCaption(String caption) {
    this.caption = caption;
  }

  public Integer getCropX() {
    return cropX;
  }

  public void setCropX(Integer cropX) {
    this.cropX = cropX;
  }

  public Integer getCropY() {
    return cropY;
  }

  public void setCropY(Integer cropY) {
    this.cropY = cropY;
  }

  public Integer getCropWidth() {
    return cropWidth;
  }

  public void setCropWidth(Integer cropWidth) {
    this.cropWidth = cropWidth;
  }

  public Integer getCropHeight() {
    return cropHeight;
  }

  public void setCropHeight(Integer cropHeight) {
    this.cropHeight = cropHeight;
  }

  public Integer getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(Integer sortOrder) {
    this.sortOrder = sortOrder;
  }
}
