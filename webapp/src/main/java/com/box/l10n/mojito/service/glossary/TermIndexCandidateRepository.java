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

  List<TermIndexCandidate> findByTermIndexExtractedTermIdInOrderByCreatedDateDesc(
      Collection<Long> termIndexExtractedTermIds);

  @Query(
      """
      select candidate.id as id,
             candidate.normalizedKey as normalizedKey,
             candidate.term as term,
             candidate.label as label,
             candidate.sourceLocaleTag as sourceLocaleTag,
             candidate.confidence as confidence,
             candidate.sourceType as sourceType,
             candidate.sourceName as sourceName,
             candidate.createdDate as lastSignalAt
      from TermIndexCandidate candidate
      where candidate.termIndexExtractedTerm is null
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
  List<UnattachedCandidateRow> searchUnattachedCandidates(
      @Param("searchQuery") String searchQuery, Pageable pageable);

  interface UnattachedCandidateRow {
    Long getId();

    String getNormalizedKey();

    String getTerm();

    String getLabel();

    String getSourceLocaleTag();

    Integer getConfidence();

    String getSourceType();

    String getSourceName();

    ZonedDateTime getLastSignalAt();
  }
}
