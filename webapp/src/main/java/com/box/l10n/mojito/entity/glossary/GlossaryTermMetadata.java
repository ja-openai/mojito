package com.box.l10n.mojito.entity.glossary;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.TMTextUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Set;

@Entity
@Table(
    name = "glossary_term_metadata",
    indexes = {
      @Index(
          name = "UK__GLOSSARY_TERM_METADATA__GLOSSARY_TU",
          columnList = "glossary_id, tm_text_unit_id",
          unique = true),
      @Index(name = "I__GLOSSARY_TERM_METADATA__TM_TEXT_UNIT__ID", columnList = "tm_text_unit_id")
    })
public class GlossaryTermMetadata extends AuditableEntity {

  public static final String TERM_TYPE_BRAND = "BRAND";
  public static final String TERM_TYPE_PRODUCT = "PRODUCT";
  public static final String TERM_TYPE_UI_LABEL = "UI_LABEL";
  public static final String TERM_TYPE_LEGAL = "LEGAL";
  public static final String TERM_TYPE_TECHNICAL = "TECHNICAL";
  public static final String TERM_TYPE_GENERAL = "GENERAL";

  public static final String ENFORCEMENT_HARD = "HARD";
  public static final String ENFORCEMENT_SOFT = "SOFT";
  public static final String ENFORCEMENT_REVIEW_ONLY = "REVIEW_ONLY";

  public static final String STATUS_CANDIDATE = "CANDIDATE";
  public static final String STATUS_APPROVED = "APPROVED";
  public static final String STATUS_DEPRECATED = "DEPRECATED";
  public static final String STATUS_REJECTED = "REJECTED";

  public static final String PROVENANCE_MANUAL = "MANUAL";
  public static final String PROVENANCE_IMPORTED = "IMPORTED";
  public static final String PROVENANCE_AUTOMATED = "AUTOMATED";
  public static final String PROVENANCE_AI_EXTRACTED = "AI_EXTRACTED";

  public static final Set<String> TERM_TYPES =
      Set.of(
          TERM_TYPE_BRAND,
          TERM_TYPE_PRODUCT,
          TERM_TYPE_UI_LABEL,
          TERM_TYPE_LEGAL,
          TERM_TYPE_TECHNICAL,
          TERM_TYPE_GENERAL);
  public static final Set<String> ENFORCEMENTS =
      Set.of(ENFORCEMENT_HARD, ENFORCEMENT_SOFT, ENFORCEMENT_REVIEW_ONLY);
  public static final Set<String> STATUSES =
      Set.of(STATUS_CANDIDATE, STATUS_APPROVED, STATUS_DEPRECATED, STATUS_REJECTED);
  public static final Set<String> PROVENANCES =
      Set.of(PROVENANCE_MANUAL, PROVENANCE_IMPORTED, PROVENANCE_AUTOMATED, PROVENANCE_AI_EXTRACTED);

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "glossary_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_METADATA__GLOSSARY"))
  private Glossary glossary;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "tm_text_unit_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_METADATA__TM_TEXT_UNIT"))
  private TMTextUnit tmTextUnit;

  @Column(name = "part_of_speech", length = 64)
  private String partOfSpeech;

  @Column(name = "term_type", length = 32)
  private String termType;

  @Column(name = "enforcement", length = 32)
  private String enforcement = ENFORCEMENT_SOFT;

  @Column(name = "status", length = 32)
  private String status = STATUS_CANDIDATE;

  @Column(name = "provenance", length = 32)
  private String provenance = PROVENANCE_MANUAL;

  @Column(name = "case_sensitive", nullable = false)
  private Boolean caseSensitive = false;

  @Column(name = "do_not_translate", nullable = false)
  private Boolean doNotTranslate = false;

  public Glossary getGlossary() {
    return glossary;
  }

  public void setGlossary(Glossary glossary) {
    this.glossary = glossary;
  }

  public TMTextUnit getTmTextUnit() {
    return tmTextUnit;
  }

  public void setTmTextUnit(TMTextUnit tmTextUnit) {
    this.tmTextUnit = tmTextUnit;
  }

  public String getPartOfSpeech() {
    return partOfSpeech;
  }

  public void setPartOfSpeech(String partOfSpeech) {
    this.partOfSpeech = partOfSpeech;
  }

  public String getTermType() {
    return termType;
  }

  public void setTermType(String termType) {
    this.termType = termType;
  }

  public String getEnforcement() {
    return enforcement;
  }

  public void setEnforcement(String enforcement) {
    this.enforcement = enforcement;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getProvenance() {
    return provenance;
  }

  public void setProvenance(String provenance) {
    this.provenance = provenance;
  }

  public Boolean getCaseSensitive() {
    return caseSensitive;
  }

  public void setCaseSensitive(Boolean caseSensitive) {
    this.caseSensitive = caseSensitive;
  }

  public Boolean getDoNotTranslate() {
    return doNotTranslate;
  }

  public void setDoNotTranslate(Boolean doNotTranslate) {
    this.doNotTranslate = doNotTranslate;
  }
}
