package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRunEntry;
import com.box.l10n.mojito.service.DBUtils;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class TermIndexRefreshRunEntryRepositoryImpl
    implements TermIndexRefreshRunEntryRepositoryCustom {

  private final EntityManager entityManager;
  private final DBUtils dbUtils;
  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

  public TermIndexRefreshRunEntryRepositoryImpl(
      EntityManager entityManager,
      DBUtils dbUtils,
      NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
    this.entityManager = entityManager;
    this.dbUtils = dbUtils;
    this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
  }

  @Override
  public int insertEntries(Long refreshRunId, Collection<Long> termIndexExtractedTermIds) {
    if (termIndexExtractedTermIds == null || termIndexExtractedTermIds.isEmpty()) {
      return 0;
    }

    if (dbUtils.isPostgres()) {
      return namedParameterJdbcTemplate.update(
          """
          insert into term_index_refresh_run_entry
            (created_date, last_modified_date, refresh_run_id, term_index_extracted_term_id)
          select current_timestamp, current_timestamp, :refreshRunId, term_index_extracted_term.id
          from term_index_extracted_term
          where term_index_extracted_term.id in (:termIndexExtractedTermIds)
          on conflict (refresh_run_id, term_index_extracted_term_id) do nothing
          """,
          insertEntriesParameters(refreshRunId, termIndexExtractedTermIds));
    }

    if (dbUtils.isMysql()) {
      return namedParameterJdbcTemplate.update(
          """
          insert ignore into term_index_refresh_run_entry
            (created_date, last_modified_date, refresh_run_id, term_index_extracted_term_id)
          select now(), now(), :refreshRunId, term_index_extracted_term.id
          from term_index_extracted_term
          where term_index_extracted_term.id in (:termIndexExtractedTermIds)
          """,
          insertEntriesParameters(refreshRunId, termIndexExtractedTermIds));
    }

    return insertEntriesWithJpa(refreshRunId, termIndexExtractedTermIds);
  }

  @Override
  public int insertExistingRepositoryEntries(Long refreshRunId, Long repositoryId) {
    if (dbUtils.isPostgres()) {
      return namedParameterJdbcTemplate.update(
          """
          insert into term_index_refresh_run_entry
            (created_date, last_modified_date, refresh_run_id, term_index_extracted_term_id)
          select distinct current_timestamp, current_timestamp, :refreshRunId,
            occurrence.term_index_extracted_term_id
          from term_index_occurrence occurrence
          where occurrence.repository_id = :repositoryId
          on conflict (refresh_run_id, term_index_extracted_term_id) do nothing
          """,
          new MapSqlParameterSource()
              .addValue("refreshRunId", refreshRunId)
              .addValue("repositoryId", repositoryId));
    }

    if (dbUtils.isMysql()) {
      return namedParameterJdbcTemplate.update(
          """
          insert ignore into term_index_refresh_run_entry
            (created_date, last_modified_date, refresh_run_id, term_index_extracted_term_id)
          select distinct now(), now(), :refreshRunId, occurrence.term_index_extracted_term_id
          from term_index_occurrence occurrence
          where occurrence.repository_id = :repositoryId
          """,
          new MapSqlParameterSource()
              .addValue("refreshRunId", refreshRunId)
              .addValue("repositoryId", repositoryId));
    }

    List<Long> existingRepositoryTermIds =
        entityManager
            .createQuery(
                """
                select distinct occurrence.termIndexExtractedTerm.id
                from TermIndexOccurrence occurrence
                where occurrence.repository.id = :repositoryId
                """,
                Long.class)
            .setParameter("repositoryId", repositoryId)
            .getResultList();
    return insertEntriesWithJpa(refreshRunId, existingRepositoryTermIds);
  }

  private MapSqlParameterSource insertEntriesParameters(
      Long refreshRunId, Collection<Long> termIndexExtractedTermIds) {
    return new MapSqlParameterSource()
        .addValue("refreshRunId", refreshRunId)
        .addValue("termIndexExtractedTermIds", termIndexExtractedTermIds);
  }

  private int insertEntriesWithJpa(Long refreshRunId, Collection<Long> termIndexExtractedTermIds) {
    int insertCount = 0;
    for (Long termIndexExtractedTermId : new LinkedHashSet<>(termIndexExtractedTermIds)) {
      Long existingCount =
          entityManager
              .createQuery(
                  """
                  select count(runEntry.id)
                  from TermIndexRefreshRunEntry runEntry
                  where runEntry.refreshRun.id = :refreshRunId
                    and runEntry.termIndexExtractedTerm.id = :termIndexExtractedTermId
                  """,
                  Long.class)
              .setParameter("refreshRunId", refreshRunId)
              .setParameter("termIndexExtractedTermId", termIndexExtractedTermId)
              .getSingleResult();
      if (existingCount > 0) {
        continue;
      }

      TermIndexRefreshRunEntry runEntry = new TermIndexRefreshRunEntry();
      runEntry.setRefreshRun(entityManager.getReference(TermIndexRefreshRun.class, refreshRunId));
      runEntry.setTermIndexExtractedTerm(
          entityManager.getReference(TermIndexExtractedTerm.class, termIndexExtractedTermId));
      entityManager.persist(runEntry);
      insertCount++;
    }
    entityManager.flush();
    return insertCount;
  }
}
