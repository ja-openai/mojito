package com.box.l10n.mojito.entity;

import com.box.l10n.mojito.rest.View;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "tm_text_unit_variant_leveraging")
public class TMTextUnitVariantLeveraging extends BaseEntity {

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "tm_text_unit_variant_id",
      foreignKey = @ForeignKey(name = "FK__TM_TEXT_UNIT_VARIANT_LEVERAGING__TM_TEXT_UNIT_VARIANT"))
  @JsonIgnore
  private TMTextUnitVariant tmTextUnitVariant;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "source_tm_text_unit_id",
      foreignKey = @ForeignKey(name = "FK__TM_TEXT_UNIT_VARIANT_LEVERAGING__SOURCE_TM_TEXT_UNIT"))
  @JsonIgnore
  private TMTextUnit sourceTmTextUnit;

  @Column(name = "source_tm_text_unit_id", insertable = false, updatable = false)
  @JsonView(View.TranslationHistorySummary.class)
  private Long sourceTmTextUnitId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "source_tm_text_unit_variant_id",
      foreignKey =
          @ForeignKey(name = "FK__TM_TEXT_UNIT_VARIANT_LEVERAGING__SOURCE_TM_TEXT_UNIT_VARIANT"))
  @JsonIgnore
  private TMTextUnitVariant sourceTmTextUnitVariant;

  @Column(name = "source_tm_text_unit_variant_id", insertable = false, updatable = false)
  @JsonView(View.TranslationHistorySummary.class)
  private Long sourceTmTextUnitVariantId;

  @Column(name = "leveraging_type", length = 64)
  @JsonView(View.TranslationHistorySummary.class)
  private String leveragingType;

  @Column(name = "unique_match")
  @JsonView(View.TranslationHistorySummary.class)
  private boolean uniqueMatch;

  public TMTextUnitVariant getTmTextUnitVariant() {
    return tmTextUnitVariant;
  }

  public void setTmTextUnitVariant(TMTextUnitVariant tmTextUnitVariant) {
    this.tmTextUnitVariant = tmTextUnitVariant;
  }

  public TMTextUnit getSourceTmTextUnit() {
    return sourceTmTextUnit;
  }

  public void setSourceTmTextUnit(TMTextUnit sourceTmTextUnit) {
    this.sourceTmTextUnit = sourceTmTextUnit;
  }

  public Long getSourceTmTextUnitId() {
    return sourceTmTextUnitId;
  }

  public TMTextUnitVariant getSourceTmTextUnitVariant() {
    return sourceTmTextUnitVariant;
  }

  public void setSourceTmTextUnitVariant(TMTextUnitVariant sourceTmTextUnitVariant) {
    this.sourceTmTextUnitVariant = sourceTmTextUnitVariant;
  }

  public Long getSourceTmTextUnitVariantId() {
    return sourceTmTextUnitVariantId;
  }

  public String getLeveragingType() {
    return leveragingType;
  }

  public void setLeveragingType(String leveragingType) {
    this.leveragingType = leveragingType;
  }

  public boolean isUniqueMatch() {
    return uniqueMatch;
  }

  public void setUniqueMatch(boolean uniqueMatch) {
    this.uniqueMatch = uniqueMatch;
  }
}
