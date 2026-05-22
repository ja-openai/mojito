package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRunEntry;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexRefreshRunEntryRepository
    extends JpaRepository<TermIndexRefreshRunEntry, Long>,
        TermIndexRefreshRunEntryRepositoryCustom {

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
}
