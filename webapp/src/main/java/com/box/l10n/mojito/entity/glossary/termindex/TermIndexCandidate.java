package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "term_index_candidate",
    indexes = {
      @Index(
          name = "UK__TERM_INDEX_CANDIDATE__SOURCE_HASH",
          columnList = "source_type, source_name, candidate_hash",
          unique = true),
      @Index(
          name = "I__TERM_INDEX_CANDIDATE__EXTRACTED_TERM",
          columnList = "term_index_extracted_term_id"),
      @Index(
          name = "I__TERM_INDEX_CANDIDATE__KEY",
          columnList = "source_locale_tag, normalized_key"),
      @Index(
          name = "I__TERM_INDEX_CANDIDATE__SOURCE_EXTERNAL_ID",
          columnList = "source_type, source_name, source_external_id")
    })
public class TermIndexCandidate extends AuditableEntity {

  public static final String SOURCE_TYPE_EXTRACTION = "EXTRACTION";
  public static final String SOURCE_TYPE_AI = "AI";
  public static final String SOURCE_TYPE_EXTERNAL = "EXTERNAL";
  public static final String SOURCE_TYPE_CODEX = "CODEX";
  public static final String SOURCE_TYPE_HUMAN = "HUMAN";

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "term_index_extracted_term_id",
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_CANDIDATE__EXTRACTED_TERM"))
  private TermIndexExtractedTerm termIndexExtractedTerm;

  @Column(name = "source_locale_tag", nullable = false, length = 64)
  private String sourceLocaleTag = TermIndexExtractedTerm.SOURCE_LOCALE_ROOT;

  @Column(name = "normalized_key", nullable = false, length = 255)
  private String normalizedKey;

  @Column(name = "term", nullable = false, length = 512)
  private String term;

  @Column(name = "label", length = 512)
  private String label;

  @Column(name = "definition", length = 2048)
  private String definition;

  @Column(name = "rationale", length = 2048)
  private String rationale;

  @Column(name = "confidence")
  private Integer confidence;

  @Column(name = "source_type", nullable = false, length = 64)
  private String sourceType;

  @Column(name = "source_name", nullable = false, length = 255)
  private String sourceName = "";

  @Column(name = "source_external_id", length = 255)
  private String sourceExternalId;

  @Column(name = "candidate_hash", nullable = false, length = 64)
  private String candidateHash;

  @Column(name = "term_type", length = 32)
  private String termType;

  @Column(name = "part_of_speech", length = 64)
  private String partOfSpeech;

  @Column(name = "enforcement", length = 32)
  private String enforcement;

  @Column(name = "do_not_translate")
  private Boolean doNotTranslate;

  @Column(name = "metadata_json", length = Integer.MAX_VALUE)
  private String metadataJson;

  @Column(name = "review_status", nullable = false, length = 32)
  private String reviewStatus = TermIndexReview.STATUS_TO_REVIEW;

  @Column(name = "review_authority", nullable = false, length = 32)
  private String reviewAuthority = TermIndexReview.AUTHORITY_DEFAULT;

  @Column(name = "review_reason", length = 64)
  private String reviewReason;

  @Column(name = "review_rationale", length = 2048)
  private String reviewRationale;

  @Column(name = "review_confidence")
  private Integer reviewConfidence;

  @Column(name = "review_changed_at")
  private ZonedDateTime reviewChangedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "review_changed_by_user_id",
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_CANDIDATE__REVIEW_CHANGED_BY_USER"))
  private User reviewChangedByUser;

  public TermIndexExtractedTerm getTermIndexExtractedTerm() {
    return termIndexExtractedTerm;
  }

  public void setTermIndexExtractedTerm(TermIndexExtractedTerm termIndexExtractedTerm) {
    this.termIndexExtractedTerm = termIndexExtractedTerm;
  }

  public String getSourceLocaleTag() {
    return sourceLocaleTag;
  }

  public void setSourceLocaleTag(String sourceLocaleTag) {
    this.sourceLocaleTag = sourceLocaleTag;
  }

  public String getNormalizedKey() {
    return normalizedKey;
  }

  public void setNormalizedKey(String normalizedKey) {
    this.normalizedKey = normalizedKey;
  }

  public String getTerm() {
    return term;
  }

  public void setTerm(String term) {
    this.term = term;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public String getDefinition() {
    return definition;
  }

  public void setDefinition(String definition) {
    this.definition = definition;
  }

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String rationale) {
    this.rationale = rationale;
  }

  public Integer getConfidence() {
    return confidence;
  }

  public void setConfidence(Integer confidence) {
    this.confidence = confidence;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceName() {
    return sourceName;
  }

  public void setSourceName(String sourceName) {
    this.sourceName = sourceName;
  }

  public String getSourceExternalId() {
    return sourceExternalId;
  }

  public void setSourceExternalId(String sourceExternalId) {
    this.sourceExternalId = sourceExternalId;
  }

  public String getCandidateHash() {
    return candidateHash;
  }

  public void setCandidateHash(String candidateHash) {
    this.candidateHash = candidateHash;
  }

  public String getTermType() {
    return termType;
  }

  public void setTermType(String termType) {
    this.termType = termType;
  }

  public String getPartOfSpeech() {
    return partOfSpeech;
  }

  public void setPartOfSpeech(String partOfSpeech) {
    this.partOfSpeech = partOfSpeech;
  }

  public String getEnforcement() {
    return enforcement;
  }

  public void setEnforcement(String enforcement) {
    this.enforcement = enforcement;
  }

  public Boolean getDoNotTranslate() {
    return doNotTranslate;
  }

  public void setDoNotTranslate(Boolean doNotTranslate) {
    this.doNotTranslate = doNotTranslate;
  }

  public String getMetadataJson() {
    return metadataJson;
  }

  public void setMetadataJson(String metadataJson) {
    this.metadataJson = metadataJson;
  }

  public String getReviewStatus() {
    return reviewStatus;
  }

  public void setReviewStatus(String reviewStatus) {
    this.reviewStatus = reviewStatus;
  }

  public String getReviewAuthority() {
    return reviewAuthority;
  }

  public void setReviewAuthority(String reviewAuthority) {
    this.reviewAuthority = reviewAuthority;
  }

  public String getReviewReason() {
    return reviewReason;
  }

  public void setReviewReason(String reviewReason) {
    this.reviewReason = reviewReason;
  }

  public String getReviewRationale() {
    return reviewRationale;
  }

  public void setReviewRationale(String reviewRationale) {
    this.reviewRationale = reviewRationale;
  }

  public Integer getReviewConfidence() {
    return reviewConfidence;
  }

  public void setReviewConfidence(Integer reviewConfidence) {
    this.reviewConfidence = reviewConfidence;
  }

  public ZonedDateTime getReviewChangedAt() {
    return reviewChangedAt;
  }

  public void setReviewChangedAt(ZonedDateTime reviewChangedAt) {
    this.reviewChangedAt = reviewChangedAt;
  }

  public User getReviewChangedByUser() {
    return reviewChangedByUser;
  }

  public void setReviewChangedByUser(User reviewChangedByUser) {
    this.reviewChangedByUser = reviewChangedByUser;
  }
}
