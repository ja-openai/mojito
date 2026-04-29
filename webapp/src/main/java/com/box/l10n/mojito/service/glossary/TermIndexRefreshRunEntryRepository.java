package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRunEntry;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexRefreshRunEntryRepository
    extends JpaRepository<TermIndexRefreshRunEntry, Long> {

  long countByRefreshRunId(Long refreshRunId);

  @Query(
      """
      select runEntry.termIndexExtractedTerm.id
      from TermIndexRefreshRunEntry runEntry
      where runEntry.refreshRun.id = :refreshRunId
        and runEntry.termIndexExtractedTerm.id > :afterTermIndexExtractedTermId
      order by runEntry.termIndexExtractedTerm.id asc
      """)
  List<Long> findTermIndexExtractedTermIdsByRefreshRunIdAfter(
      @Param("refreshRunId") Long refreshRunId,
      @Param("afterTermIndexExtractedTermId") Long afterTermIndexExtractedTermId,
      Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          insert ignore into term_index_refresh_run_entry
            (created_date, last_modified_date, refresh_run_id, term_index_extracted_term_id)
          select now(), now(), :refreshRunId, term_index_extracted_term.id
          from term_index_extracted_term
          where term_index_extracted_term.id in (:termIndexExtractedTermIds)
          """,
      nativeQuery = true)
  int insertEntries(
      @Param("refreshRunId") Long refreshRunId,
      @Param("termIndexExtractedTermIds") Collection<Long> termIndexExtractedTermIds);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          insert ignore into term_index_refresh_run_entry
            (created_date, last_modified_date, refresh_run_id, term_index_extracted_term_id)
          select distinct now(), now(), :refreshRunId, occurrence.term_index_extracted_term_id
          from term_index_occurrence occurrence
          where occurrence.repository_id = :repositoryId
          """,
      nativeQuery = true)
  int insertExistingRepositoryEntries(
      @Param("refreshRunId") Long refreshRunId, @Param("repositoryId") Long repositoryId);
}
