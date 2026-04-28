package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexEntry;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexEntryRepository extends JpaRepository<TermIndexEntry, Long> {

  Optional<TermIndexEntry> findBySourceLocaleTagAndNormalizedKey(
      String sourceLocaleTag, String normalizedKey);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          insert ignore into term_index_entry
            (created_date, last_modified_date, source_locale_tag, normalized_key, display_term,
             occurrence_count, repository_count, first_seen_at, last_seen_at)
          values
            (now(), now(), :sourceLocaleTag, :normalizedKey, :displayTerm, 0, 0, now(), now())
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("sourceLocaleTag") String sourceLocaleTag,
      @Param("normalizedKey") String normalizedKey,
      @Param("displayTerm") String displayTerm);

  @Query(
      """
      select entry.id as id,
             entry.normalizedKey as normalizedKey,
             entry.displayTerm as displayTerm,
             entry.sourceLocaleTag as sourceLocaleTag,
             count(occurrence.id) as occurrenceCount,
             count(distinct occurrence.repository.id) as repositoryCount,
             max(occurrence.createdDate) as lastOccurrenceAt
      from TermIndexOccurrence occurrence
      join occurrence.termIndexEntry entry
      where (:repositoryIdsEmpty = true or occurrence.repository.id in :repositoryIds)
        and (
          :searchQuery is null
          or lower(entry.displayTerm) like lower(concat('%', :searchQuery, '%'))
          or lower(entry.normalizedKey) like lower(concat('%', :searchQuery, '%'))
        )
        and (:extractionMethod is null or occurrence.extractionMethod = :extractionMethod)
      group by entry.id, entry.normalizedKey, entry.displayTerm, entry.sourceLocaleTag
      having count(occurrence.id) >= :minOccurrences
      order by count(occurrence.id) desc, lower(entry.displayTerm) asc
      """)
  List<SearchRow> searchEntries(
      @Param("repositoryIdsEmpty") boolean repositoryIdsEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("searchQuery") String searchQuery,
      @Param("extractionMethod") String extractionMethod,
      @Param("minOccurrences") long minOccurrences,
      Pageable pageable);

  interface SearchRow {
    Long getId();

    String getNormalizedKey();

    String getDisplayTerm();

    String getSourceLocaleTag();

    Long getOccurrenceCount();

    Long getRepositoryCount();

    ZonedDateTime getLastOccurrenceAt();
  }
}
