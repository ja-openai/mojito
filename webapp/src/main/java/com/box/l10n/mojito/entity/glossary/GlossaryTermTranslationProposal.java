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

@Entity
@Table(
    name = "glossary_term_translation_proposal",
    indexes = {
      @Index(
          name = "I__GLOSSARY_TERM_TRANSLATION_PROPOSAL__GLOSSARY__STATUS",
          columnList = "glossary_id, status"),
      @Index(
          name = "I__GLOSSARY_TERM_TRANSLATION_PROPOSAL__TM_TEXT_UNIT__ID",
          columnList = "tm_text_unit_id")
    })
public class GlossaryTermTranslationProposal extends AuditableEntity {

  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_ACCEPTED = "ACCEPTED";
  public static final String STATUS_REJECTED = "REJECTED";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "glossary_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_TRANSLATION_PROPOSAL__GLOSSARY"))
  private Glossary glossary;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "tm_text_unit_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_TRANSLATION_PROPOSAL__TM_TEXT_UNIT"))
  private TMTextUnit tmTextUnit;

  @Column(name = "locale_tag", nullable = false, length = 64)
  private String localeTag;

  @Column(name = "proposed_target", nullable = false, length = Integer.MAX_VALUE)
  private String proposedTarget;

  @Column(name = "proposed_target_comment", length = 1024)
  private String proposedTargetComment;

  @Column(name = "note", length = 2048)
  private String note;

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_PENDING;

  @Column(name = "reviewer_note", length = 2048)
  private String reviewerNote;

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

  public String getLocaleTag() {
    return localeTag;
  }

  public void setLocaleTag(String localeTag) {
    this.localeTag = localeTag;
  }

  public String getProposedTarget() {
    return proposedTarget;
  }

  public void setProposedTarget(String proposedTarget) {
    this.proposedTarget = proposedTarget;
  }

  public String getProposedTargetComment() {
    return proposedTargetComment;
  }

  public void setProposedTargetComment(String proposedTargetComment) {
    this.proposedTargetComment = proposedTargetComment;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getReviewerNote() {
    return reviewerNote;
  }

  public void setReviewerNote(String reviewerNote) {
    this.reviewerNote = reviewerNote;
  }
}
