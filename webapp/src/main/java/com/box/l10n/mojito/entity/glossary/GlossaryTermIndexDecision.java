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
    name = "glossary_term_index_decision",
    indexes = {
      @Index(
          name = "UK__GLOSSARY_TERM_INDEX_DECISION__GLOSSARY_CANDIDATE",
          columnList = "glossary_id, term_index_candidate_id",
          unique = true),
      @Index(
          name = "I__GLOSSARY_TERM_INDEX_DECISION__CANDIDATE",
          columnList = "term_index_candidate_id")
    })
public class GlossaryTermIndexDecision extends AuditableEntity {

  public static final String DECISION_IGNORED = "IGNORED";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "glossary_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_INDEX_DECISION__GLOSSARY"))
  private Glossary glossary;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "term_index_candidate_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_INDEX_DECISION__CANDIDATE"))
  private TermIndexCandidate termIndexCandidate;

  @Column(name = "decision", nullable = false, length = 32)
  private String decision;

  @Column(name = "reason", length = 2048)
  private String reason;

  public Glossary getGlossary() {
    return glossary;
  }

  public void setGlossary(Glossary glossary) {
    this.glossary = glossary;
  }

  public TermIndexCandidate getTermIndexCandidate() {
    return termIndexCandidate;
  }

  public void setTermIndexCandidate(TermIndexCandidate termIndexCandidate) {
    this.termIndexCandidate = termIndexCandidate;
  }

  public String getDecision() {
    return decision;
  }

  public void setDecision(String decision) {
    this.decision = decision;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
