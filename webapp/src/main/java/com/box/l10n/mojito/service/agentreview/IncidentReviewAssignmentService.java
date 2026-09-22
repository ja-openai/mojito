package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.agentreview.ActorType;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.agentreview.Disposition;
import com.box.l10n.mojito.entity.agentreview.FeedbackAction;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Closed assignment recovery; neither project closure nor reassignment is a human decision. */
@Service
public class IncidentReviewAssignmentService {
  private final EntityManager em;
  private final AgentReviewProposalRepository proposals;

  public IncidentReviewAssignmentService(
      EntityManager em, AgentReviewProposalRepository proposals) {
    this.em = em;
    this.proposals = proposals;
  }

  public record ProjectState(ReviewProjectStatus status, Long runId, Long localeId) {}

  public record RowState(Long projectId, Long textUnitId) {}

  public record Snapshot(
      Map<Long, ProjectState> projects,
      Map<Long, RowState> rows,
      Set<Long> handledProposals,
      Set<Long> decidedRows,
      boolean locked) {}

  /** Call after the existing run, string, current, proposal and incident locks on create. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Snapshot load(
      Collection<TranslationIncident> incidents,
      Collection<AgentReviewProposal> candidates,
      boolean lock) {
    Set<Long> projectIds = new TreeSet<>();
    Set<Long> rowIds = new TreeSet<>();
    Set<Long> proposalIds = new TreeSet<>();
    for (TranslationIncident incident : incidents) {
      if (incident.getResolutionReviewProjectId() != null)
        projectIds.add(incident.getResolutionReviewProjectId());
    }
    for (AgentReviewProposal proposal : candidates) {
      if (proposal == null) continue;
      proposalIds.add(proposal.getId());
      if (proposal.getReviewProjectId() != null) projectIds.add(proposal.getReviewProjectId());
      if (proposal.getReviewProjectTextUnitId() != null)
        rowIds.add(proposal.getReviewProjectTextUnitId());
    }
    Map<Long, ProjectState> projectStates = new HashMap<>();
    if (!projectIds.isEmpty()) {
      var query =
          em.createQuery(
                  "select p from ReviewProject p where p.id in :ids order by p.id",
                  ReviewProject.class)
              .setParameter("ids", projectIds);
      if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
      for (ReviewProject project : query.getResultList()) {
        // A caller may already have loaded this entity before acquiring its status lock.
        if (lock) em.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        projectStates.put(
            project.getId(),
            new ProjectState(
                project.getStatus(), project.getAgentReviewRunId(), project.getLocale().getId()));
      }
    }
    Map<Long, RowState> rowStates = new HashMap<>();
    Set<Long> decidedRows = new HashSet<>();
    if (!rowIds.isEmpty()) {
      for (Object[] row :
          em.createQuery(
                  "select r.id, r.reviewProject.id, r.tmTextUnit.id from ReviewProjectTextUnit r"
                      + " where r.id in :ids",
                  Object[].class)
              .setParameter("ids", rowIds)
              .getResultList())
        rowStates.put((Long) row[0], new RowState((Long) row[1], (Long) row[2]));
      decidedRows.addAll(
          em.createQuery(
                  "select d.reviewProjectTextUnit.id from ReviewProjectTextUnitDecision d"
                      + " where d.reviewProjectTextUnit.id in :ids and d.decisionState = :state",
                  Long.class)
              .setParameter("ids", rowIds)
              .setParameter("state", DecisionState.DECIDED)
              .getResultList());
    }
    Set<Long> handled = new HashSet<>();
    if (!proposalIds.isEmpty())
      handled.addAll(
          em.createQuery(
                  "select distinct f.proposalId from AgentReviewFeedback f where"
                      + " f.proposalId in :ids and f.actorType = :human and"
                      + " (f.action <> :defer or f.followUpRequested = true"
                      + " or f.reviewedStateFingerprint is not null)",
                  Long.class)
              .setParameter("ids", proposalIds)
              .setParameter("human", ActorType.HUMAN)
              .setParameter("defer", FeedbackAction.DEFER)
              .getResultList());
    return new Snapshot(
        Map.copyOf(projectStates),
        Map.copyOf(rowStates),
        Set.copyOf(handled),
        Set.copyOf(decidedRows),
        lock);
  }

  /**
   * Assignment checks only; the caller still enforces state, scope, readiness and human receipts.
   */
  public String ineligible(
      TranslationIncident incident, AgentReviewProposal proposal, Snapshot snapshot) {
    Long assignment = incident.getResolutionReviewProjectId();
    if (proposal == null)
      return assignment == null && incident.getReviewFindingId() == null
          ? null
          : "Linked review assignment is unavailable";
    if (!Objects.equals(proposal.getIncidentId(), incident.getId())
        || !Objects.equals(proposal.getFindingId(), incident.getReviewFindingId())
        || !Objects.equals(proposal.getTmTextUnitId(), incident.getSelectedTmTextUnitId())
        || !Objects.equals(proposal.getLocaleId(), incident.getResolvedLocaleId()))
      return "Incident and finding assignment references disagree";
    if (proposal.getDisposition() != Disposition.OPEN
        && proposal.getDisposition() != Disposition.ROUTED)
      return "Finding was already handled or requires follow-up";
    if (snapshot.handledProposals().contains(proposal.getId()))
      return "Finding has a human decision or follow-up";
    if (proposal.getDisposition() == Disposition.OPEN) {
      return assignment == null
              && proposal.getReviewProjectId() == null
              && proposal.getReviewProjectTextUnitId() == null
          ? null
          : "Incident and finding assignment references disagree";
    }
    if (assignment == null
        || !Objects.equals(assignment, proposal.getReviewProjectId())
        || proposal.getReviewProjectTextUnitId() == null)
      return "Incident and finding assignment references disagree";
    ProjectState project = snapshot.projects().get(assignment);
    RowState row = snapshot.rows().get(proposal.getReviewProjectTextUnitId());
    if (project == null || row == null) return "Linked review assignment is unavailable";
    if (!Objects.equals(project.runId(), proposal.getRunId())
        || !Objects.equals(project.localeId(), proposal.getLocaleId())
        || !Objects.equals(row.projectId(), assignment)
        || !Objects.equals(row.textUnitId(), proposal.getTmTextUnitId()))
      return "Incident and finding assignment references disagree";
    if (project.status() == ReviewProjectStatus.OPEN)
      return "Already assigned to an open review project";
    if (project.status() != ReviewProjectStatus.CLOSED)
      return "Linked review assignment status is unavailable";
    if (snapshot.decidedRows().contains(proposal.getReviewProjectTextUnitId()))
      return "Finding has a recorded review decision";
    return null;
  }

  /**
   * Run only for a fully eligible create candidate in the transaction that installs its new route.
   * The old project and feedback remain attached to the superseded revision. Preview never calls
   * it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public AgentReviewProposal prepareForRouting(
      TranslationIncident incident, AgentReviewProposal previous, Snapshot snapshot) {
    if (!snapshot.locked()) throw new IllegalStateException("Reassignment requires locked state");
    String reason = ineligible(incident, previous, snapshot);
    if (reason != null) throw new IllegalStateException(reason);
    if (previous == null || previous.getDisposition() == Disposition.OPEN) return previous;
    Long newer =
        em.createQuery(
                "select count(p.id) from AgentReviewProposal p where p.findingId = :finding"
                    + " and p.proposalRevision > :revision",
                Long.class)
            .setParameter("finding", previous.getFindingId())
            .setParameter("revision", previous.getProposalRevision())
            .getSingleResult();
    if (newer != 0) throw new IllegalStateException("A newer finding revision already exists");
    AgentReviewProposal next = new AgentReviewProposal();
    String key = "closed-project-reroute:" + previous.getId();
    next.setRunId(previous.getRunId());
    next.setSubmissionKey(key);
    next.setRequestFingerprint(hash(key));
    next.setFindingId(previous.getFindingId());
    next.setProposalRevision(Math.addExact(previous.getProposalRevision(), 1));
    next.setPreviousProposalId(previous.getId());
    next.setRespondsToFeedbackId(previous.getRespondsToFeedbackId());
    next.setGroupKey(previous.getGroupKey());
    next.setRepositoryId(previous.getRepositoryId());
    next.setLocaleId(previous.getLocaleId());
    next.setTmTextUnitId(previous.getTmTextUnitId());
    next.setSource(previous.getSource());
    next.setSourceComment(previous.getSourceComment());
    next.setBaselineVariantId(previous.getBaselineVariantId());
    next.setBaselineTarget(previous.getBaselineTarget());
    next.setBaselineStatus(previous.getBaselineStatus());
    next.setBaselineIncludedInLocalizedFile(previous.getBaselineIncludedInLocalizedFile());
    next.setProposedTarget(previous.getProposedTarget());
    next.setCategory(previous.getCategory());
    next.setReadiness(previous.getReadiness());
    next.setRationale(previous.getRationale());
    next.setEvidenceJson(previous.getEvidenceJson());
    next.setProducerIdentity(previous.getProducerIdentity());
    next.setVerifierIdentity(previous.getVerifierIdentity());
    next.setVerificationRationale(previous.getVerificationRationale());
    next.setIntegrityDiagnostics(previous.getIntegrityDiagnostics());
    next.setIncidentId(previous.getIncidentId());
    // Existing proposal revision semantics release the unique live claim before inserting its heir.
    previous.setDisposition(Disposition.SUPERSEDED);
    proposals.flush();
    next.setDisposition(Disposition.OPEN);
    next.setIntakeFingerprint(previous.getIntakeFingerprint());
    return proposals.saveAndFlush(next);
  }

  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
