package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "term_index_extracted_term",
    indexes = {
      @Index(
          name = "UK__TERM_INDEX_EXTRACTED_TERM__LOCALE_KEY",
          columnList = "source_locale_tag, normalized_key",
          unique = true)
    })
public class TermIndexExtractedTerm extends AuditableEntity {

  public static final String SOURCE_LOCALE_ROOT = "root";

  @Column(name = "normalized_key", nullable = false, length = 255)
  private String normalizedKey;

  @Column(name = "display_term", nullable = false, length = 512)
  private String displayTerm;

  @Column(name = "source_locale_tag", nullable = false, length = 64)
  private String sourceLocaleTag = SOURCE_LOCALE_ROOT;

  @Column(name = "occurrence_count", nullable = false)
  private Long occurrenceCount = 0L;

  @Column(name = "repository_count", nullable = false)
  private Integer repositoryCount = 0;

  @Column(name = "first_seen_at")
  private ZonedDateTime firstSeenAt;

  @Column(name = "last_seen_at")
  private ZonedDateTime lastSeenAt;

  public String getNormalizedKey() {
    return normalizedKey;
  }

  public void setNormalizedKey(String normalizedKey) {
    this.normalizedKey = normalizedKey;
  }

  public String getDisplayTerm() {
    return displayTerm;
  }

  public void setDisplayTerm(String displayTerm) {
    this.displayTerm = displayTerm;
  }

  public String getSourceLocaleTag() {
    return sourceLocaleTag;
  }

  public void setSourceLocaleTag(String sourceLocaleTag) {
    this.sourceLocaleTag = sourceLocaleTag;
  }

  public Long getOccurrenceCount() {
    return occurrenceCount;
  }

  public void setOccurrenceCount(Long occurrenceCount) {
    this.occurrenceCount = occurrenceCount;
  }

  public Integer getRepositoryCount() {
    return repositoryCount;
  }

  public void setRepositoryCount(Integer repositoryCount) {
    this.repositoryCount = repositoryCount;
  }

  public ZonedDateTime getFirstSeenAt() {
    return firstSeenAt;
  }

  public void setFirstSeenAt(ZonedDateTime firstSeenAt) {
    this.firstSeenAt = firstSeenAt;
  }

  public ZonedDateTime getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(ZonedDateTime lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }
}
