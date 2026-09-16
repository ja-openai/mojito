package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import jakarta.persistence.LockModeType;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/**
 * @author jaurambault
 */
@RepositoryRestResource(exported = false)
public interface PollableTaskRepository extends JpaRepository<PollableTask, Long> {

  interface ArchiveCandidate {

    Long getId();

    ZonedDateTime getFinishedDate();
  }

  @Override
  @EntityGraph(value = "PollableTask.legacy", type = EntityGraphType.FETCH)
  Optional<PollableTask> findById(Long aLong);

  @EntityGraph(value = "PollableTask.legacy", type = EntityGraphType.FETCH)
  List<PollableTask> findByNameAndFinishedDateIsNullOrderByCreatedDateDesc(String name);

  /**
   * Retrieves pollable tasks that have not finished yet and have exceeded the maximum execution
   * time.
   *
   * <p>Must pass "now" as parameter due to HSQL persisting ZonedDateTime without TZ info. Comparing
   * ZonedDateTime against unix_timestamp() then fails because of the TZ difference.
   *
   * <p>This does not show if test are running in UTC like on CI
   */
  @Query(
      """
	      select pt from #{#entityName} pt
	      where pt.finishedDate is null
	      and pt.name not in ('com.box.l10n.mojito.service.oaireview.AiReviewChatJob',
	                          'com.box.l10n.mojito.service.oaireview.AiReviewConfiguredChatJob')
	      and (cast(unix_timestamp(pt.createdDate) as long) + pt.timeout) < cast(unix_timestamp(:now) as long)
	      """)
  List<PollableTask> findZombiePollableTasks(@Param("now") ZonedDateTime now, Pageable pageable);

  @Query(
      """
      select pt.id as id, pt.finishedDate as finishedDate
      from PollableTask pt
      where pt.finishedDate >= :lastFinishedDate
        and pt.finishedDate < :finishedBefore
        and (
          pt.finishedDate > :lastFinishedDate
          or (pt.finishedDate = :lastFinishedDate and pt.id > :lastTaskId)
        )
      order by pt.finishedDate desc, pt.id desc
      """)
  List<ArchiveCandidate> findArchiveHighWater(
      @Param("lastFinishedDate") ZonedDateTime lastFinishedDate,
      @Param("lastTaskId") long lastTaskId,
      @Param("finishedBefore") ZonedDateTime finishedBefore,
      Pageable pageable);

  @Query(
      """
      select pt.id as id, pt.finishedDate as finishedDate
      from PollableTask pt
      where pt.finishedDate >= :lastFinishedDate
        and pt.finishedDate <= :highWaterFinishedDate
        and pt.finishedDate < :finishedBefore
        and (
          pt.finishedDate > :lastFinishedDate
          or (pt.finishedDate = :lastFinishedDate and pt.id > :lastTaskId)
        )
        and (
          pt.finishedDate < :highWaterFinishedDate
          or (pt.finishedDate = :highWaterFinishedDate and pt.id <= :highWaterTaskId)
        )
      order by pt.finishedDate asc, pt.id asc
      """)
  List<ArchiveCandidate> findArchiveCandidates(
      @Param("lastFinishedDate") ZonedDateTime lastFinishedDate,
      @Param("lastTaskId") long lastTaskId,
      @Param("finishedBefore") ZonedDateTime finishedBefore,
      @Param("highWaterFinishedDate") ZonedDateTime highWaterFinishedDate,
      @Param("highWaterTaskId") long highWaterTaskId,
      Pageable pageable);

  boolean existsByParentTask_Id(Long parentTaskId);

  // Avoid the public lookup's eager graph when scanning a parent that must remain in MySQL.
  @Query("select pt from PollableTask pt where pt.id = :id")
  Optional<PollableTask> findForArchiveRead(@Param("id") long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select pt from PollableTask pt where pt.id = :id")
  Optional<PollableTask> findForArchiveUpdate(@Param("id") long id);
}
