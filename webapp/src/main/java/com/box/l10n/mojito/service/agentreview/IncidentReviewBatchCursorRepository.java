package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.agentreview.IncidentReviewBatchCursor;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface IncidentReviewBatchCursorRepository
    extends JpaRepository<IncidentReviewBatchCursor, Long> {
  Optional<IncidentReviewBatchCursor> findByTeamIdAndScopeFingerprint(
      Long teamId, String scopeFingerprint);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select c from IncidentReviewBatchCursor c where c.teamId = :teamId and c.scopeFingerprint ="
          + " :scope")
  Optional<IncidentReviewBatchCursor> findForUpdate(
      @Param("teamId") Long teamId, @Param("scope") String scope);
}
