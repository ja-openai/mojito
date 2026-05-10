package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexCandidateRepository extends JpaRepository<TermIndexCandidate, Long> {

  Optional<TermIndexCandidate> findBySourceTypeAndSourceNameAndCandidateHash(
      String sourceType, String sourceName, String candidateHash);

  Optional<TermIndexCandidate>
      findFirstBySourceTypeAndSourceNameAndSourceExternalIdOrderByCreatedDateDesc(
          String sourceType, String sourceName, String sourceExternalId);

  List<TermIndexCandidate> findByTermIndexExtractedTermIdInOrderByCreatedDateDesc(
      Collection<Long> termIndexExtractedTermIds);

  @Query(
      """
      select distinct candidate
      from TermIndexCandidate candidate
      left join fetch candidate.termIndexExtractedTerm
      where candidate.sourceLocaleTag = :sourceLocaleTag
        and candidate.normalizedKey in :normalizedKeys
      order by candidate.normalizedKey asc, candidate.createdDate desc
      """)
  List<TermIndexCandidate> findBySourceLocaleTagAndNormalizedKeyIn(
      @Param("sourceLocaleTag") String sourceLocaleTag,
      @Param("normalizedKeys") Collection<String> normalizedKeys);

  @Query(
      """
      select candidate.termIndexExtractedTerm.id as termIndexExtractedTermId,
             max(candidate.id) as termIndexCandidateId
      from TermIndexCandidate candidate
      where candidate.termIndexExtractedTerm.id in :termIndexExtractedTermIds
      group by candidate.termIndexExtractedTerm.id
      """)
  List<ExtractedTermCandidateIdRow> findCandidateIdsByExtractedTermIdIn(
      @Param("termIndexExtractedTermIds") Collection<Long> termIndexExtractedTermIds);

  @Query(
      """
      select distinct candidate
      from TermIndexCandidate candidate
      left join fetch candidate.termIndexExtractedTerm extractedTerm
      left join TermIndexOccurrence occurrence
        on occurrence.termIndexExtractedTerm = extractedTerm
      where (
          candidate.termIndexExtractedTerm is null
          or (:repositoryScopeEmpty = false and occurrence.repository.id in :repositoryIds)
        )
        and (
          :searchQuery is null
          or lower(candidate.term) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.normalizedKey) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.label) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.definition) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.rationale) like lower(concat('%', :searchQuery, '%'))
        )
        and (
          :reviewStatusFilter = 'ALL'
          or (:reviewStatusFilter = 'NON_REJECTED' and candidate.reviewStatus <> 'REJECTED')
          or candidate.reviewStatus = :reviewStatusFilter
        )
      order by candidate.createdDate desc, lower(candidate.term) asc
      """)
  List<TermIndexCandidate> findForGlossaryCandidateExport(
      @Param("repositoryScopeEmpty") boolean repositoryScopeEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("searchQuery") String searchQuery,
      @Param("reviewStatusFilter") String reviewStatusFilter,
      Pageable pageable);

  @Query(
      """
      select candidate.id as id,
             extractedTerm.id as termIndexExtractedTermId,
             candidate.normalizedKey as normalizedKey,
             candidate.term as term,
             candidate.label as label,
             candidate.sourceLocaleTag as sourceLocaleTag,
             candidate.metadataJson as metadataJson,
             candidate.reviewStatus as reviewStatus,
             candidate.reviewAuthority as reviewAuthority,
             candidate.reviewReason as reviewReason,
             candidate.reviewRationale as reviewRationale,
             candidate.reviewConfidence as reviewConfidence,
             count(occurrence.id) as occurrenceCount,
             count(distinct occurrence.repository.id) as repositoryCount,
             max(occurrence.createdDate) as lastOccurrenceAt,
             candidate.createdDate as candidateCreatedDate
      from TermIndexCandidate candidate
      left join candidate.termIndexExtractedTerm extractedTerm
      left join TermIndexOccurrence occurrence
        on occurrence.termIndexExtractedTerm = extractedTerm
        and (:repositoryScopeEmpty = true or occurrence.repository.id in :repositoryIds)
      where (
          candidate.termIndexExtractedTerm is null
          or occurrence.id is not null
        )
        and (
          :searchQuery is null
          or lower(candidate.term) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.normalizedKey) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.label) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.definition) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.rationale) like lower(concat('%', :searchQuery, '%'))
        )
        and (
          :reviewStatusFilter = 'ALL'
          or (:reviewStatusFilter = 'NON_REJECTED' and candidate.reviewStatus <> 'REJECTED')
          or candidate.reviewStatus = :reviewStatusFilter
        )
      group by candidate.id,
               extractedTerm.id,
               candidate.normalizedKey,
               candidate.term,
               candidate.label,
               candidate.sourceLocaleTag,
               candidate.metadataJson,
               candidate.reviewStatus,
               candidate.reviewAuthority,
               candidate.reviewReason,
               candidate.reviewRationale,
               candidate.reviewConfidence,
               candidate.createdDate
      order by count(occurrence.id) desc, candidate.createdDate desc, lower(candidate.term) asc
      """)
  List<CandidateSearchRow> searchForGlossarySuggestions(
      @Param("repositoryScopeEmpty") boolean repositoryScopeEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("searchQuery") String searchQuery,
      @Param("reviewStatusFilter") String reviewStatusFilter,
      Pageable pageable);

  @Query(
      """
      select candidate.id as id,
             extractedTerm.id as termIndexExtractedTermId,
             candidate.normalizedKey as normalizedKey,
             candidate.term as term,
             candidate.label as label,
             candidate.sourceLocaleTag as sourceLocaleTag,
             candidate.metadataJson as metadataJson,
             candidate.definition as definition,
             candidate.rationale as rationale,
             candidate.termType as termType,
             candidate.partOfSpeech as partOfSpeech,
             candidate.enforcement as enforcement,
             candidate.doNotTranslate as doNotTranslate,
             candidate.confidence as confidence,
             candidate.reviewStatus as reviewStatus,
             candidate.reviewAuthority as reviewAuthority,
             candidate.reviewReason as reviewReason,
             candidate.reviewRationale as reviewRationale,
             candidate.reviewConfidence as reviewConfidence,
             candidate.reviewChangedAt as reviewChangedAt,
             reviewChangedByUser.id as reviewChangedByUserId,
             reviewChangedByUser.username as reviewChangedByUsername,
             reviewChangedByUser.commonName as reviewChangedByCommonName,
             count(occurrence.id) as occurrenceCount,
             count(distinct occurrence.repository.id) as repositoryCount,
             max(occurrence.createdDate) as lastOccurrenceAt,
             candidate.createdDate as candidateCreatedDate
      from TermIndexCandidate candidate
      left join candidate.termIndexExtractedTerm extractedTerm
      left join candidate.reviewChangedByUser reviewChangedByUser
      left join TermIndexOccurrence occurrence
        on occurrence.termIndexExtractedTerm = extractedTerm
        and (:repositoryScopeEmpty = true or occurrence.repository.id in :repositoryIds)
      where (
          candidate.termIndexExtractedTerm is null
          or occurrence.id is not null
        )
        and (
          :searchQuery is null
          or lower(candidate.term) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.normalizedKey) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.label) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.definition) like lower(concat('%', :searchQuery, '%'))
          or lower(candidate.rationale) like lower(concat('%', :searchQuery, '%'))
        )
        and (
          :reviewStatusFilter = 'ALL'
          or (:reviewStatusFilter = 'NON_REJECTED' and candidate.reviewStatus <> 'REJECTED')
          or candidate.reviewStatus = :reviewStatusFilter
        )
        and (
          :reviewAuthorityFilter = 'ALL'
          or candidate.reviewAuthority = :reviewAuthorityFilter
        )
        and (:reviewChangedAfter is null or candidate.reviewChangedAt >= :reviewChangedAfter)
        and (:reviewChangedBefore is null or candidate.reviewChangedAt < :reviewChangedBefore)
      group by candidate.id,
               extractedTerm.id,
               candidate.normalizedKey,
               candidate.term,
               candidate.label,
               candidate.sourceLocaleTag,
               candidate.metadataJson,
               candidate.definition,
               candidate.rationale,
               candidate.termType,
               candidate.partOfSpeech,
               candidate.enforcement,
               candidate.doNotTranslate,
               candidate.confidence,
               candidate.reviewStatus,
               candidate.reviewAuthority,
               candidate.reviewReason,
               candidate.reviewRationale,
               candidate.reviewConfidence,
               candidate.reviewChangedAt,
               reviewChangedByUser.id,
               reviewChangedByUser.username,
               reviewChangedByUser.commonName,
               candidate.createdDate
      having count(occurrence.id) >= :minOccurrences
      order by count(occurrence.id) desc, candidate.createdDate desc, lower(candidate.term) asc
      """)
  List<CandidateExplorerRow> searchForExplorer(
      @Param("repositoryScopeEmpty") boolean repositoryScopeEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("searchQuery") String searchQuery,
      @Param("reviewStatusFilter") String reviewStatusFilter,
      @Param("reviewAuthorityFilter") String reviewAuthorityFilter,
      @Param("minOccurrences") long minOccurrences,
      @Param("reviewChangedAfter") ZonedDateTime reviewChangedAfter,
      @Param("reviewChangedBefore") ZonedDateTime reviewChangedBefore,
      Pageable pageable);

  interface CandidateSearchRow {
    Long getId();

    Long getTermIndexExtractedTermId();

    String getNormalizedKey();

    String getTerm();

    String getLabel();

    String getSourceLocaleTag();

    String getMetadataJson();

    String getReviewStatus();

    String getReviewAuthority();

    String getReviewReason();

    String getReviewRationale();

    Integer getReviewConfidence();

    Long getOccurrenceCount();

    Long getRepositoryCount();

    java.time.ZonedDateTime getLastOccurrenceAt();

    java.time.ZonedDateTime getCandidateCreatedDate();
  }

  interface ExtractedTermCandidateIdRow {
    Long getTermIndexExtractedTermId();

    Long getTermIndexCandidateId();
  }

  interface CandidateExplorerRow extends CandidateSearchRow {
    String getDefinition();

    String getRationale();

    String getTermType();

    String getPartOfSpeech();

    String getEnforcement();

    Boolean getDoNotTranslate();

    Integer getConfidence();

    ZonedDateTime getReviewChangedAt();

    Long getReviewChangedByUserId();

    String getReviewChangedByUsername();

    String getReviewChangedByCommonName();
  }
}
