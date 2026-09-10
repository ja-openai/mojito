package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.agentreview.AgentReviewRun;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface AgentReviewRunRepository extends JpaRepository<AgentReviewRun, Long> {
  Optional<AgentReviewRun> findByRequestedByUserIdAndRequestKey(Long userId, String requestKey);

  List<AgentReviewRun> findByTeamIdAndIdLessThanOrderByIdDesc(
      Long teamId, Long beforeId, Pageable pageable);

  List<AgentReviewRun> findByTeamIdOrderByIdDesc(Long teamId, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from AgentReviewRun r where r.id = :id")
  Optional<AgentReviewRun> findForUpdateById(@Param("id") Long id);
}
