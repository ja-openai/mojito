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

  String ROUTING_CANDIDATES =
      " from AgentReviewProposal p where p.runId = :runId and p.readiness in"
          + " (com.box.l10n.mojito.entity.agentreview.Readiness.READY,"
          + " com.box.l10n.mojito.entity.agentreview.Readiness.SUSPECTED,"
          + " com.box.l10n.mojito.entity.agentreview.Readiness.HUMAN_REVIEW) and p.disposition ="
          + " com.box.l10n.mojito.entity.agentreview.Disposition.OPEN"
          + " and p.category <> com.box.l10n.mojito.entity.agentreview.Category.OPTIONAL_IMPROVEMENT"
          + " and p.reviewProjectTextUnitId is null and p.groupKey in :groups"
          + " and (:queued = false or p.incidentId is null or not exists (select i.id"
          + " from TranslationIncident i where i.id = p.incidentId and i.reviewFindingId = p.findingId))";

  @Query("select distinct p.tmTextUnitId" + ROUTING_CANDIDATES + " order by p.tmTextUnitId")
  List<Long> findRoutingTextUnitIds(
      @Param("runId") Long runId,
      @Param("groups") List<String> completedGroups,
      @Param("queued") boolean queued);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p" + ROUTING_CANDIDATES + " and p.tmTextUnitId in :textUnitIds order by p.id")
  List<AgentReviewProposal> findRoutingForUpdate(
      @Param("runId") Long runId,
      @Param("groups") List<String> completedGroups,
      @Param("queued") boolean queued,
      @Param("textUnitIds") List<Long> textUnitIds);
}
