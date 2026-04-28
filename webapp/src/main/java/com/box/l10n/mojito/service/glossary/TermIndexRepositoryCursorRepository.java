package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRepositoryCursor;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexRepositoryCursorRepository
    extends JpaRepository<TermIndexRepositoryCursor, Long> {

  Optional<TermIndexRepositoryCursor> findByRepositoryId(Long repositoryId);

  @Query(
      """
      select cursor
      from TermIndexRepositoryCursor cursor
      join fetch cursor.repository repository
      left join fetch cursor.currentRefreshRun
      where (:repositoryIdsEmpty = true or repository.id in :repositoryIds)
      order by repository.name asc
      """)
  List<TermIndexRepositoryCursor> findForExplorer(
      @Param("repositoryIdsEmpty") boolean repositoryIdsEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update TermIndexRepositoryCursor cursor
      set cursor.status = 'RUNNING',
          cursor.errorMessage = null,
          cursor.leaseOwner = :leaseOwner,
          cursor.leaseToken = :leaseToken,
          cursor.leaseExpiresAt = :leaseExpiresAt,
          cursor.currentRefreshRun = :refreshRun
      where cursor.repository.id = :repositoryId
        and (
          cursor.status <> 'RUNNING'
          or cursor.leaseExpiresAt is null
          or cursor.leaseExpiresAt < :now
        )
      """)
  int acquireLease(
      @Param("repositoryId") Long repositoryId,
      @Param("leaseOwner") String leaseOwner,
      @Param("leaseToken") String leaseToken,
      @Param("leaseExpiresAt") ZonedDateTime leaseExpiresAt,
      @Param("refreshRun") TermIndexRefreshRun refreshRun,
      @Param("now") ZonedDateTime now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update TermIndexRepositoryCursor cursor
      set cursor.lastProcessedCreatedAt = null,
          cursor.lastProcessedTmTextUnitId = null,
          cursor.leaseExpiresAt = :leaseExpiresAt
      where cursor.repository.id = :repositoryId
        and cursor.leaseToken = :leaseToken
      """)
  int resetCheckpointForLease(
      @Param("repositoryId") Long repositoryId,
      @Param("leaseToken") String leaseToken,
      @Param("leaseExpiresAt") ZonedDateTime leaseExpiresAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update TermIndexRepositoryCursor cursor
      set cursor.lastProcessedCreatedAt = :lastProcessedCreatedAt,
          cursor.lastProcessedTmTextUnitId = :lastProcessedTmTextUnitId,
          cursor.leaseExpiresAt = :leaseExpiresAt
      where cursor.repository.id = :repositoryId
        and cursor.leaseToken = :leaseToken
      """)
  int checkpointLease(
      @Param("repositoryId") Long repositoryId,
      @Param("leaseToken") String leaseToken,
      @Param("lastProcessedCreatedAt") ZonedDateTime lastProcessedCreatedAt,
      @Param("lastProcessedTmTextUnitId") Long lastProcessedTmTextUnitId,
      @Param("leaseExpiresAt") ZonedDateTime leaseExpiresAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update TermIndexRepositoryCursor cursor
      set cursor.status = 'IDLE',
          cursor.lastSuccessfulScanAt = :lastSuccessfulScanAt,
          cursor.errorMessage = null,
          cursor.leaseOwner = null,
          cursor.leaseToken = null,
          cursor.leaseExpiresAt = null,
          cursor.currentRefreshRun = null
      where cursor.repository.id = :repositoryId
        and cursor.leaseToken = :leaseToken
      """)
  int completeLease(
      @Param("repositoryId") Long repositoryId,
      @Param("leaseToken") String leaseToken,
      @Param("lastSuccessfulScanAt") ZonedDateTime lastSuccessfulScanAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update TermIndexRepositoryCursor cursor
      set cursor.status = 'FAILED',
          cursor.errorMessage = :errorMessage,
          cursor.leaseOwner = null,
          cursor.leaseToken = null,
          cursor.leaseExpiresAt = null,
          cursor.currentRefreshRun = null
      where cursor.repository.id = :repositoryId
        and cursor.leaseToken = :leaseToken
      """)
  int failLease(
      @Param("repositoryId") Long repositoryId,
      @Param("leaseToken") String leaseToken,
      @Param("errorMessage") String errorMessage);
}
