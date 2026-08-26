package com.box.l10n.mojito.entity;

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

@Entity
@Table(
    name = "bulk_import_run_item",
    indexes = {
      @Index(name = "I__BULK_IMPORT_RUN_ITEM__RUN", columnList = "bulk_import_run_id"),
      @Index(
          name = "I__BULK_IMPORT_RUN_ITEM__TEXT_UNIT_LOCALE",
          columnList = "tm_text_unit_id, locale_id"),
      @Index(
          name = "I__BULK_IMPORT_RUN_ITEM__PREVIOUS_VARIANT",
          columnList = "previous_tm_text_unit_variant_id"),
      @Index(
          name = "I__BULK_IMPORT_RUN_ITEM__RESULTING_VARIANT",
          columnList = "resulting_tm_text_unit_variant_id")
    })
public class BulkImportRunItem extends AuditableEntity {

  public enum Status {
    IMPORTED,
    SKIPPED,
    UNMATCHED
  }

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "bulk_import_run_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN_ITEM__RUN"))
  private BulkImportRun run;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "tm_text_unit_id",
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN_ITEM__TEXT_UNIT"))
  private TMTextUnit tmTextUnit;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "locale_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN_ITEM__LOCALE"))
  private Locale locale;

  @Column(name = "previous_tm_text_unit_variant_id")
  private Long previousTmTextUnitVariantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "resulting_tm_text_unit_variant_id",
      foreignKey = @ForeignKey(name = "FK__BULK_IMPORT_RUN_ITEM__RESULTING_VARIANT"))
  private TMTextUnitVariant resultingTmTextUnitVariant;

  @Column(name = "text_unit_name", length = 1024)
  private String textUnitName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status;

  @Column(name = "translator_identity", nullable = false, length = 255)
  private String translatorIdentity;

  @Column(name = "reviewer_identity", nullable = false, length = 255)
  private String reviewerIdentity;

  public BulkImportRun getRun() {
    return run;
  }

  public void setRun(BulkImportRun run) {
    this.run = run;
  }

  public TMTextUnit getTmTextUnit() {
    return tmTextUnit;
  }

  public void setTmTextUnit(TMTextUnit tmTextUnit) {
    this.tmTextUnit = tmTextUnit;
  }

  public Locale getLocale() {
    return locale;
  }

  public void setLocale(Locale locale) {
    this.locale = locale;
  }

  public Long getPreviousTmTextUnitVariantId() {
    return previousTmTextUnitVariantId;
  }

  public void setPreviousTmTextUnitVariantId(Long previousTmTextUnitVariantId) {
    this.previousTmTextUnitVariantId = previousTmTextUnitVariantId;
  }

  public TMTextUnitVariant getResultingTmTextUnitVariant() {
    return resultingTmTextUnitVariant;
  }

  public void setResultingTmTextUnitVariant(TMTextUnitVariant resultingTmTextUnitVariant) {
    this.resultingTmTextUnitVariant = resultingTmTextUnitVariant;
  }

  public String getTextUnitName() {
    return textUnitName;
  }

  public void setTextUnitName(String textUnitName) {
    this.textUnitName = textUnitName;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public String getTranslatorIdentity() {
    return translatorIdentity;
  }

  public void setTranslatorIdentity(String translatorIdentity) {
    this.translatorIdentity = translatorIdentity;
  }

  public String getReviewerIdentity() {
    return reviewerIdentity;
  }

  public void setReviewerIdentity(String reviewerIdentity) {
    this.reviewerIdentity = reviewerIdentity;
  }
}
