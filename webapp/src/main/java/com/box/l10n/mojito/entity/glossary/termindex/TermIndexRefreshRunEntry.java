package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "term_index_refresh_run_entry",
    indexes = {
      @Index(
          name = "UK__TERM_INDEX_REFRESH_RUN_ENTRY__RUN_ENTRY",
          columnList = "refresh_run_id, term_index_entry_id",
          unique = true),
      @Index(
          name = "I__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_ENTRY",
          columnList = "term_index_entry_id")
    })
public class TermIndexRefreshRunEntry extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "refresh_run_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_REFRESH_RUN_ENTRY__REFRESH_RUN"))
  private TermIndexRefreshRun refreshRun;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "term_index_entry_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_ENTRY"))
  private TermIndexEntry termIndexEntry;

  public TermIndexRefreshRun getRefreshRun() {
    return refreshRun;
  }

  public void setRefreshRun(TermIndexRefreshRun refreshRun) {
    this.refreshRun = refreshRun;
  }

  public TermIndexEntry getTermIndexEntry() {
    return termIndexEntry;
  }

  public void setTermIndexEntry(TermIndexEntry termIndexEntry) {
    this.termIndexEntry = termIndexEntry;
  }
}
