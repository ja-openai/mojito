package com.box.l10n.mojito.service.commit;

import com.box.l10n.mojito.entity.CommitToPullRun;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author garion
 */
@RepositoryRestResource(exported = false)
public interface CommitToPullRunRepository extends JpaRepository<CommitToPullRun, Long> {
  Optional<CommitToPullRun> findByCommitId(Long id);

  @Transactional
  @Modifying
  @Query(
      """
      delete from CommitToPullRun ctpr
      where ctpr.pullRun.createdDate < :beforeDate
      """)
  void deleteAllByPullRunWithCreatedDateBefore(@Param("beforeDate") ZonedDateTime beforeDate);
}
