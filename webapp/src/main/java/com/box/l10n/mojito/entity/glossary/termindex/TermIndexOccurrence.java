package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.Repository;
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
    name = "term_index_occurrence",
    indexes = {
      @Index(
          name = "UK__TERM_INDEX_OCCURRENCE__EXTRACTED_TERM_TU_SPAN",
          columnList =
              "term_index_extracted_term_id, tm_text_unit_id, start_index, end_index, extractor_id",
          unique = true),
      @Index(name = "I__TERM_INDEX_OCCURRENCE__TM_TEXT_UNIT", columnList = "tm_text_unit_id"),
      @Index(
          name = "I__TERM_INDEX_OCCURRENCE__REPOSITORY_EXTRACTED_TERM",
          columnList = "repository_id, term_index_extracted_term_id")
    })
public class TermIndexOccurrence extends AuditableEntity {

  public static final String METHOD_LEXICAL_TITLE_CASE = "LEXICAL_TITLE_CASE";
  public static final String METHOD_LEXICAL_CAMEL_CASE = "LEXICAL_CAMEL_CASE";
  public static final String METHOD_LEXICAL_UPPER_CASE = "LEXICAL_UPPER_CASE";
  public static final String METHOD_AI_TERMINOLOGY = "AI_TERMINOLOGY";
  public static final String METHOD_CODE_SYMBOL = "CODE_SYMBOL";
  public static final String METHOD_SCREENSHOT_OCR = "SCREENSHOT_OCR";
  public static final String METHOD_EXTERNAL_GLOSSARY_IMPORT = "EXTERNAL_GLOSSARY_IMPORT";
  public static final String METHOD_MANUAL_SEED = "MANUAL_SEED";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "term_index_extracted_term_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_OCCURRENCE__EXTRACTED_TERM"))
  private TermIndexExtractedTerm termIndexExtractedTerm;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "tm_text_unit_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_OCCURRENCE__TM_TEXT_UNIT"))
  private TMTextUnit tmTextUnit;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_OCCURRENCE__REPOSITORY"))
  private Repository repository;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "asset_id",
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_OCCURRENCE__ASSET"))
  private Asset asset;

  @Column(name = "matched_text", nullable = false, length = 512)
  private String matchedText;

  @Column(name = "start_index", nullable = false)
  private Integer startIndex;

  @Column(name = "end_index", nullable = false)
  private Integer endIndex;

  @Column(name = "source_hash", length = 64)
  private String sourceHash;

  @Column(name = "extractor_id", nullable = false, length = 64)
  private String extractorId;

  @Column(name = "extraction_method", nullable = false, length = 64)
  private String extractionMethod;

  @Column(name = "confidence")
  private Integer confidence;

  @Column(name = "metadata_json", length = Integer.MAX_VALUE)
  private String metadataJson;

  public TermIndexExtractedTerm getTermIndexExtractedTerm() {
    return termIndexExtractedTerm;
  }

  public void setTermIndexExtractedTerm(TermIndexExtractedTerm termIndexExtractedTerm) {
    this.termIndexExtractedTerm = termIndexExtractedTerm;
  }

  public TMTextUnit getTmTextUnit() {
    return tmTextUnit;
  }

  public void setTmTextUnit(TMTextUnit tmTextUnit) {
    this.tmTextUnit = tmTextUnit;
  }

  public Repository getRepository() {
    return repository;
  }

  public void setRepository(Repository repository) {
    this.repository = repository;
  }

  public Asset getAsset() {
    return asset;
  }

  public void setAsset(Asset asset) {
    this.asset = asset;
  }

  public String getMatchedText() {
    return matchedText;
  }

  public void setMatchedText(String matchedText) {
    this.matchedText = matchedText;
  }

  public Integer getStartIndex() {
    return startIndex;
  }

  public void setStartIndex(Integer startIndex) {
    this.startIndex = startIndex;
  }

  public Integer getEndIndex() {
    return endIndex;
  }

  public void setEndIndex(Integer endIndex) {
    this.endIndex = endIndex;
  }

  public String getSourceHash() {
    return sourceHash;
  }

  public void setSourceHash(String sourceHash) {
    this.sourceHash = sourceHash;
  }

  public String getExtractorId() {
    return extractorId;
  }

  public void setExtractorId(String extractorId) {
    this.extractorId = extractorId;
  }

  public String getExtractionMethod() {
    return extractionMethod;
  }

  public void setExtractionMethod(String extractionMethod) {
    this.extractionMethod = extractionMethod;
  }

  public Integer getConfidence() {
    return confidence;
  }

  public void setConfidence(Integer confidence) {
    this.confidence = confidence;
  }

  public String getMetadataJson() {
    return metadataJson;
  }

  public void setMetadataJson(String metadataJson) {
    this.metadataJson = metadataJson;
  }
}
