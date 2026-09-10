package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.agentreview.ActorType;
import com.box.l10n.mojito.entity.agentreview.AgentReviewFeedback;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface AgentReviewFeedbackRepository extends JpaRepository<AgentReviewFeedback, Long> {
  List<AgentReviewFeedback> findByProposalIdAndIdGreaterThanOrderByIdAsc(
      Long proposalId, Long afterId, Pageable pageable);

  Optional<AgentReviewFeedback> findFirstByProposalIdOrderByIdDesc(Long proposalId);

  Optional<AgentReviewFeedback> findByProposalIdAndActorTypeAndRequestKey(
      Long proposalId, ActorType actorType, String requestKey);

  Optional<AgentReviewFeedback> findByRespondsToFeedbackId(Long feedbackId);

  List<AgentReviewFeedback> findByProposalIdOrderByIdAsc(Long proposalId);

  @Query(
      "select f from AgentReviewFeedback f, AgentReviewProposal p where f.proposalId = p.id and p.runId = :runId and p.disposition = com.box.l10n.mojito.entity.agentreview.Disposition.FOLLOW_UP and f.id > :afterId and f.followUpRequested = true and not exists (select newer.id from AgentReviewFeedback newer where newer.proposalId = f.proposalId and newer.actorType = com.box.l10n.mojito.entity.agentreview.ActorType.HUMAN and newer.id > f.id) and not exists (select response.id from AgentReviewFeedback response where response.respondsToFeedbackId = f.id) order by f.id")
  List<AgentReviewFeedback> findPendingByRunId(
      @Param("runId") Long runId, @Param("afterId") Long afterId, Pageable pageable);
}
