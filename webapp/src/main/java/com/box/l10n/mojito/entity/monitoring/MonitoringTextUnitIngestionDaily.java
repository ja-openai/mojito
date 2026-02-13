package com.box.l10n.mojito.entity.monitoring;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.Repository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "monitoring_text_unit_ingestion_daily",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__MTUID__DAY__REPOSITORY__ID",
          columnNames = {"day_utc", "repository_id"})
    },
    indexes = {
      @Index(name = "I__MTUID__DAY_UTC", columnList = "day_utc"),
      @Index(name = "I__MTUID__REPOSITORY__ID", columnList = "repository_id")
    })
public class MonitoringTextUnitIngestionDaily extends AuditableEntity {

  @Column(name = "day_utc", nullable = false)
  LocalDate dayUtc;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__MTUID__REPOSITORY__ID"))
  Repository repository;

  @Column(name = "string_count", nullable = false)
  Long stringCount;

  @Column(name = "word_count", nullable = false)
  Long wordCount;

  @Column(name = "computed_at", nullable = false)
  ZonedDateTime computedAt;

  public LocalDate getDayUtc() {
    return dayUtc;
  }

  public void setDayUtc(LocalDate dayUtc) {
    this.dayUtc = dayUtc;
  }

  public Repository getRepository() {
    return repository;
  }

  public void setRepository(Repository repository) {
    this.repository = repository;
  }

  public Long getStringCount() {
    return stringCount;
  }

  public void setStringCount(Long stringCount) {
    this.stringCount = stringCount;
  }

  public Long getWordCount() {
    return wordCount;
  }

  public void setWordCount(Long wordCount) {
    this.wordCount = wordCount;
  }

  public ZonedDateTime getComputedAt() {
    return computedAt;
  }

  public void setComputedAt(ZonedDateTime computedAt) {
    this.computedAt = computedAt;
  }
}
