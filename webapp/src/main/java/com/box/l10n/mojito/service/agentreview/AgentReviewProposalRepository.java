package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
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
public interface AgentReviewProposalRepository extends JpaRepository<AgentReviewProposal, Long> {
  Optional<AgentReviewProposal> findFirstByReviewProjectTextUnitIdOrderByIdDesc(Long rowId);

  Optional<AgentReviewProposal> findByActiveIntakeFingerprint(String fingerprint);

  @Query(
      "select p from AgentReviewProposal p where p.tmTextUnitId = :unitId and p.localeId ="
          + " :localeId and p.disposition = :disposition order by p.id desc")
  List<AgentReviewProposal> findRecentPendingForString(
      @Param("unitId") Long unitId,
      @Param("localeId") Long localeId,
      @Param("disposition") com.box.l10n.mojito.entity.agentreview.Disposition disposition,
      Pageable pageable);

  List<AgentReviewProposal> findByReviewProjectId(Long projectId);

  List<AgentReviewProposal> findByRunIdAndIdGreaterThanOrderByIdAsc(
      Long runId, Long afterId, Pageable pageable);

  List<AgentReviewProposal> findByFindingIdAndIdGreaterThanOrderByProposalRevisionAsc(
      String findingId, Long afterId, Pageable pageable);

  Optional<AgentReviewProposal> findByRunIdAndSubmissionKey(Long runId, String submissionKey);

  List<AgentReviewProposal> findByRunIdOrderByIdAsc(Long runId);

  List<AgentReviewProposal> findByFindingIdOrderByProposalRevisionAsc(String findingId);

  List<AgentReviewProposal> findByReviewProjectTextUnitIdOrderByProposalRevisionDesc(Long rowId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from AgentReviewProposal p where p.id = :id")
  Optional<AgentReviewProposal> findForUpdateById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select p from AgentReviewProposal p where p.runId = :runId and p.readiness in"
          + " (com.box.l10n.mojito.entity.agentreview.Readiness.READY,"
          + " com.box.l10n.mojito.entity.agentreview.Readiness.HUMAN_REVIEW) and p.disposition ="
          + " com.box.l10n.mojito.entity.agentreview.Disposition.OPEN order by p.id")
  List<AgentReviewProposal> findReadyForUpdateByRunId(@Param("runId") Long runId);
}
