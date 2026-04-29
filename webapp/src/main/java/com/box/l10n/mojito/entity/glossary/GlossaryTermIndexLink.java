package com.box.l10n.mojito.entity.glossary;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
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
    name = "glossary_term_index_link",
    indexes = {
      @Index(
          name = "UK__GLOSSARY_TERM_INDEX_LINK__TERM_CANDIDATE_RELATION",
          columnList = "glossary_term_metadata_id, term_index_candidate_id, relation_type",
          unique = true),
      @Index(
          name = "I__GLOSSARY_TERM_INDEX_LINK__CANDIDATE",
          columnList = "term_index_candidate_id")
    })
public class GlossaryTermIndexLink extends AuditableEntity {

  public static final String RELATION_TYPE_PRIMARY = "PRIMARY";
  public static final String RELATION_TYPE_ALIAS = "ALIAS";
  public static final String RELATION_TYPE_RELATED = "RELATED";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "glossary_term_metadata_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_INDEX_LINK__TERM_METADATA"))
  private GlossaryTermMetadata glossaryTermMetadata;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "term_index_candidate_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_INDEX_LINK__CANDIDATE"))
  private TermIndexCandidate termIndexCandidate;

  @Column(name = "relation_type", nullable = false, length = 32)
  private String relationType = RELATION_TYPE_PRIMARY;

  @Column(name = "confidence")
  private Integer confidence;

  @Column(name = "rationale", length = 2048)
  private String rationale;

  public GlossaryTermMetadata getGlossaryTermMetadata() {
    return glossaryTermMetadata;
  }

  public void setGlossaryTermMetadata(GlossaryTermMetadata glossaryTermMetadata) {
    this.glossaryTermMetadata = glossaryTermMetadata;
  }

  public TermIndexCandidate getTermIndexCandidate() {
    return termIndexCandidate;
  }

  public void setTermIndexCandidate(TermIndexCandidate termIndexCandidate) {
    this.termIndexCandidate = termIndexCandidate;
  }

  public String getRelationType() {
    return relationType;
  }

  public void setRelationType(String relationType) {
    this.relationType = relationType;
  }

  public Integer getConfidence() {
    return confidence;
  }

  public void setConfidence(Integer confidence) {
    this.confidence = confidence;
  }

  public String getRationale() {
    return rationale;
  }

  public void setRationale(String rationale) {
    this.rationale = rationale;
  }
}
