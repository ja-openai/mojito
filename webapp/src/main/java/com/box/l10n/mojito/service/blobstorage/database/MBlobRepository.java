package com.box.l10n.mojito.service.blobstorage.database;

import com.box.l10n.mojito.entity.MBlob;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author jaurambault
 */
@Repository
public interface MBlobRepository
    extends JpaRepository<MBlob, Long>, JpaSpecificationExecutor<MBlob> {

  Optional<MBlob> findByName(@Param("name") String name);

  /**
   * Task payloads can outlive their TTL while work is still running. Only completed standalone
   * tasks are eligible: a completed child may still be needed by its parent, and a parent can
   * finish its own work before its children finish. Keep unknown names and missing task metadata
   * until their retention has been reconciled. The task and child checks use their ID indexes; they
   * do not load payloads or traverse a task graph.
   *
   * <p>The guarded decimal cast accepts the full positive bigint range without throwing on unknown
   * names or larger 19-digit numbers. Both MySQL and HSQL support these native SQL functions.
   */
  String CLEANUP_TASK_SAFETY_PREDICATE =
      """
      and (
        mblob.name < 'pollable_task/' or mblob.name >= 'pollable_task0'
        or exists (
          select 1 from pollable_task task
          where task.id = cast(
            case when regexp_like(mblob.name, '^pollable_task/[1-9][0-9]{0,18}/(input|output)$')
              then substring(mblob.name, 15, locate('/', mblob.name, 15) - 15)
              else null end as decimal(19,0))
            and task.finished_date is not null
            and task.parent_task_id is null
            and task.expected_sub_task_number = 0
            and not exists (
              select 1 from pollable_task child where child.parent_task_id = task.id
            )
        )
      )
      """;

  /** Pass the cutoff explicitly so tests and both cleanup paths use the same timestamp binding. */
  @Query(
      value =
          """
          select id from mblob
          where timestampadd(second, expire_after_seconds, created_date) < :now
          """
              + CLEANUP_TASK_SAFETY_PREDICATE,
      nativeQuery = true)
  List<Long> findExpiredBlobIdsWithNow(@Param("now") ZonedDateTime now, Pageable pageable);

  /**
   * Recheck the same expiry cutoff while deleting; selected IDs alone are not deletion authority.
   */
  @Transactional
  @Modifying
  @Query(
      value =
          """
          delete from mblob where id in :ids
          and timestampadd(second, expire_after_seconds, created_date) < :now
          """
              + CLEANUP_TASK_SAFETY_PREDICATE,
      nativeQuery = true)
  int deleteExpiredByIds(@Param("ids") List<Long> ids, @Param("now") ZonedDateTime now);

  @Transactional
  @Modifying
  @Query("delete from #{#entityName} mb where mb.id in ?1")
  int deleteByIds(List<Long> ids);

  @Query("select mb.id from  #{#entityName} mb where mb.name = ?1")
  Optional<Long> findIdByName(String name);
}
