package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
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

  List<TermIndexCandidate> findByTermIndexExtractedTermIdInOrderByCreatedDateDesc(
      Collection<Long> termIndexExtractedTermIds);

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
      order by candidate.createdDate desc, lower(candidate.term) asc
      """)
  List<TermIndexCandidate> findForGlossaryCandidateExport(
      @Param("repositoryScopeEmpty") boolean repositoryScopeEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("searchQuery") String searchQuery,
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
      group by candidate.id,
               extractedTerm.id,
               candidate.normalizedKey,
               candidate.term,
               candidate.label,
               candidate.sourceLocaleTag,
               candidate.metadataJson,
               candidate.createdDate
      order by count(occurrence.id) desc, candidate.createdDate desc, lower(candidate.term) asc
      """)
  List<CandidateSearchRow> searchForGlossarySuggestions(
      @Param("repositoryScopeEmpty") boolean repositoryScopeEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("searchQuery") String searchQuery,
      Pageable pageable);

  interface CandidateSearchRow {
    Long getId();

    Long getTermIndexExtractedTermId();

    String getNormalizedKey();

    String getTerm();

    String getLabel();

    String getSourceLocaleTag();

    String getMetadataJson();

    Long getOccurrenceCount();

    Long getRepositoryCount();

    java.time.ZonedDateTime getLastOccurrenceAt();

    java.time.ZonedDateTime getCandidateCreatedDate();
  }
}
