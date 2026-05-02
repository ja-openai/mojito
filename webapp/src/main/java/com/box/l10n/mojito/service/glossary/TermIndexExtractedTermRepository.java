package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
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
public interface TermIndexExtractedTermRepository
    extends JpaRepository<TermIndexExtractedTerm, Long> {

  Optional<TermIndexExtractedTerm> findBySourceLocaleTagAndNormalizedKey(
      String sourceLocaleTag, String normalizedKey);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          insert ignore into term_index_extracted_term
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
             entry.createdDate as createdDate,
             entry.lastModifiedDate as lastModifiedDate,
             entry.reviewStatus as reviewStatus,
             entry.reviewAuthority as reviewAuthority,
             entry.reviewReason as reviewReason,
             entry.reviewRationale as reviewRationale,
             entry.reviewConfidence as reviewConfidence,
             entry.reviewChangedAt as reviewChangedAt,
             reviewChangedByUser.id as reviewChangedByUserId,
             reviewChangedByUser.username as reviewChangedByUsername,
             reviewChangedByUser.commonName as reviewChangedByCommonName,
             count(occurrence.id) as occurrenceCount,
             count(distinct occurrence.repository.id) as repositoryCount,
             max(occurrence.createdDate) as lastOccurrenceAt
      from TermIndexOccurrence occurrence
      join occurrence.termIndexExtractedTerm entry
      left join entry.reviewChangedByUser reviewChangedByUser
      where (:repositoryIdsEmpty = true or occurrence.repository.id in :repositoryIds)
        and (
          :searchQuery is null
          or lower(entry.displayTerm) like lower(concat('%', :searchQuery, '%'))
          or lower(entry.normalizedKey) like lower(concat('%', :searchQuery, '%'))
        )
        and (:extractionMethod is null or occurrence.extractionMethod = :extractionMethod)
        and (
          :reviewStatusFilter = 'ALL'
          or (:reviewStatusFilter = 'NON_REJECTED' and entry.reviewStatus <> 'REJECTED')
          or entry.reviewStatus = :reviewStatusFilter
        )
      group by entry.id,
               entry.normalizedKey,
               entry.displayTerm,
               entry.sourceLocaleTag,
               entry.createdDate,
               entry.lastModifiedDate,
               entry.reviewStatus,
               entry.reviewAuthority,
               entry.reviewReason,
               entry.reviewRationale,
               entry.reviewConfidence,
               entry.reviewChangedAt,
               reviewChangedByUser.id,
               reviewChangedByUser.username,
               reviewChangedByUser.commonName
      having count(occurrence.id) >= :minOccurrences
      order by count(occurrence.id) desc, lower(entry.displayTerm) asc
      """)
  List<SearchRow> searchEntries(
      @Param("repositoryIdsEmpty") boolean repositoryIdsEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("searchQuery") String searchQuery,
      @Param("extractionMethod") String extractionMethod,
      @Param("reviewStatusFilter") String reviewStatusFilter,
      @Param("minOccurrences") long minOccurrences,
      Pageable pageable);

  @Query(
      """
      select entry.id as id,
             entry.normalizedKey as normalizedKey,
             entry.displayTerm as displayTerm,
             entry.sourceLocaleTag as sourceLocaleTag,
             entry.createdDate as createdDate,
             entry.lastModifiedDate as lastModifiedDate,
             entry.reviewStatus as reviewStatus,
             entry.reviewAuthority as reviewAuthority,
             entry.reviewReason as reviewReason,
             entry.reviewRationale as reviewRationale,
             entry.reviewConfidence as reviewConfidence,
             entry.reviewChangedAt as reviewChangedAt,
             reviewChangedByUser.id as reviewChangedByUserId,
             reviewChangedByUser.username as reviewChangedByUsername,
             reviewChangedByUser.commonName as reviewChangedByCommonName,
             count(occurrence.id) as occurrenceCount,
             count(distinct occurrence.repository.id) as repositoryCount,
             max(occurrence.createdDate) as lastOccurrenceAt
      from TermIndexOccurrence occurrence
      join occurrence.termIndexExtractedTerm entry
      left join entry.reviewChangedByUser reviewChangedByUser
      where entry.id in :termIndexExtractedTermIds
        and (:repositoryIdsEmpty = true or occurrence.repository.id in :repositoryIds)
        and entry.reviewStatus <> 'REJECTED'
      group by entry.id,
               entry.normalizedKey,
               entry.displayTerm,
               entry.sourceLocaleTag,
               entry.createdDate,
               entry.lastModifiedDate,
               entry.reviewStatus,
               entry.reviewAuthority,
               entry.reviewReason,
               entry.reviewRationale,
               entry.reviewConfidence,
               entry.reviewChangedAt,
               reviewChangedByUser.id,
               reviewChangedByUser.username,
               reviewChangedByUser.commonName
      order by count(occurrence.id) desc, lower(entry.displayTerm) asc
      """)
  List<SearchRow> findCandidateGenerationRowsByIdIn(
      @Param("termIndexExtractedTermIds") Collection<Long> termIndexExtractedTermIds,
      @Param("repositoryIdsEmpty") boolean repositoryIdsEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds);

  @Query(
      """
      select entry.id as id,
             entry.normalizedKey as normalizedKey,
             entry.displayTerm as displayTerm,
             entry.sourceLocaleTag as sourceLocaleTag,
             entry.createdDate as createdDate,
             entry.lastModifiedDate as lastModifiedDate,
             entry.reviewStatus as reviewStatus,
             entry.reviewAuthority as reviewAuthority,
             entry.reviewReason as reviewReason,
             entry.reviewRationale as reviewRationale,
             entry.reviewConfidence as reviewConfidence,
             entry.reviewChangedAt as reviewChangedAt,
             reviewChangedByUser.id as reviewChangedByUserId,
             reviewChangedByUser.username as reviewChangedByUsername,
             reviewChangedByUser.commonName as reviewChangedByCommonName,
             count(occurrence.id) as occurrenceCount,
             count(distinct occurrence.repository.id) as repositoryCount,
             max(occurrence.createdDate) as lastOccurrenceAt
      from TermIndexOccurrence occurrence
      join occurrence.termIndexExtractedTerm entry
      left join entry.reviewChangedByUser reviewChangedByUser
      where entry.id in :termIndexExtractedTermIds
        and (:repositoryIdsEmpty = true or occurrence.repository.id in :repositoryIds)
      group by entry.id,
               entry.normalizedKey,
               entry.displayTerm,
               entry.sourceLocaleTag,
               entry.createdDate,
               entry.lastModifiedDate,
               entry.reviewStatus,
               entry.reviewAuthority,
               entry.reviewReason,
               entry.reviewRationale,
               entry.reviewConfidence,
               entry.reviewChangedAt,
               reviewChangedByUser.id,
               reviewChangedByUser.username,
               reviewChangedByUser.commonName
      order by count(occurrence.id) desc, lower(entry.displayTerm) asc
      """)
  List<SearchRow> findSearchRowsByIdIn(
      @Param("termIndexExtractedTermIds") Collection<Long> termIndexExtractedTermIds,
      @Param("repositoryIdsEmpty") boolean repositoryIdsEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds);

  @Query(
      """
      select entry.normalizedKey as normalizedKey,
             count(distinct entry.id) as extractedTermMatchCount
      from TermIndexOccurrence occurrence
      join occurrence.termIndexExtractedTerm entry
      where entry.normalizedKey in :normalizedKeys
        and (:repositoryIdsEmpty = true or occurrence.repository.id in :repositoryIds)
      group by entry.normalizedKey
      """)
  List<ExtractedTermMatchCountRow> countScopedMatchesByNormalizedKeyIn(
      @Param("normalizedKeys") Collection<String> normalizedKeys,
      @Param("repositoryIdsEmpty") boolean repositoryIdsEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds);

  interface SearchRow {
    Long getId();

    String getNormalizedKey();

    String getDisplayTerm();

    String getSourceLocaleTag();

    ZonedDateTime getCreatedDate();

    ZonedDateTime getLastModifiedDate();

    String getReviewStatus();

    String getReviewAuthority();

    String getReviewReason();

    String getReviewRationale();

    Integer getReviewConfidence();

    ZonedDateTime getReviewChangedAt();

    Long getReviewChangedByUserId();

    String getReviewChangedByUsername();

    String getReviewChangedByCommonName();

    Long getOccurrenceCount();

    Long getRepositoryCount();

    ZonedDateTime getLastOccurrenceAt();
  }

  interface ExtractedTermMatchCountRow {
    String getNormalizedKey();

    Long getExtractedTermMatchCount();
  }
}
