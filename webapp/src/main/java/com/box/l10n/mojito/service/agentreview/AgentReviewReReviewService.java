package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.review.GetProjectDetailView;
import com.box.l10n.mojito.service.review.ReviewProjectCurrentVariantConflictException;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Explicit human re-review creates a separate round; it never undoes a translation save. */
@Service
public class AgentReviewReReviewService {
  private final AgentReviewProposalRepository proposals;
  private final AgentReviewFeedbackRepository feedback;
  private final ReviewProjectTextUnitRepository rows;
  private final ReviewProjectService projects;
  private final TMTextUnitCurrentVariantRepository currentVariants;
  private final TranslationIncidentRepository incidents;
  private final UserService users;
  private final TeamService teams;
  private final EntityManager entityManager;
  private final ObjectMapper mapper;

  public AgentReviewReReviewService(
      AgentReviewProposalRepository proposals,
      AgentReviewFeedbackRepository feedback,
      ReviewProjectTextUnitRepository rows,
      ReviewProjectService projects,
      TMTextUnitCurrentVariantRepository currentVariants,
      TranslationIncidentRepository incidents,
      UserService users,
      TeamService teams,
      EntityManager entityManager,
      ObjectMapper mapper) {
    this.proposals = proposals;
    this.feedback = feedback;
    this.rows = rows;
    this.projects = projects;
    this.currentVariants = currentVariants;
    this.incidents = incidents;
    this.users = users;
    this.teams = teams;
    this.entityManager = entityManager;
    this.mapper = mapper;
  }

  public record Request(
      String requestKey,
      Long expectedProposalVersion,
      Long expectedCurrentVariantId,
      String expectedSource,
      String expectedSourceComment,
      String expectedCurrentTarget,
      String expectedCurrentStatus,
      Boolean expectedCurrentIncludedInLocalizedFile) {}

  public record Result(Long projectId, Long proposalId, int proposalRevision) {}

  public record EditRequest(Request reopen, ReviewProjectTextUnitDecisionRequest decision) {}

  // Transport diagnostics deliberately do not participate in semantic retry identity.
  private record EditFingerprint(
      Request reopen,
      String target,
      String comment,
      String status,
      Boolean included,
      DecisionState decisionState,
      Long expectedCurrentVariantId,
      String expectedReviewRevision,
      String decisionNotes,
      AgentReviewDecisionRequest agentReview,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          com.box.l10n.mojito.service.review.feedback.ReviewerFeedback reviewFeedback) {}

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result reviewAgain(long projectId, long proposalId, Request request) {
    return result(createRound(projectId, proposalId, request, false, null));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result reopen(long projectId, long proposalId, Request request) {
    return result(createRound(projectId, proposalId, request, true, null));
  }

  /** Reopens and saves in one transaction so failed validation never leaves an extra round. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GetProjectDetailView.ReviewProjectTextUnit reopenAndSave(
      long projectId, long proposalId, EditRequest request) {
    validateEdit(proposalId, request);
    var decision = request.decision();
    var judgment = decision.getAgentReview();
    AgentReviewProposal next = createRound(projectId, proposalId, request.reopen(), true, decision);
    var current = projects.getReviewProjectTextUnit(next.getReviewProjectTextUnitId());
    var receipt =
        feedback
            .findByProposalIdAndActorTypeAndRequestKey(
                next.getId(), ActorType.HUMAN, judgment.requestId())
            .orElse(null);
    if (receipt != null) {
      // createRound already validated the full original payload and authenticated actor key.
      // The ordinary save's generated revision/version changed after success, so return its
      // durable receipt rather than reconstructing a different ordinary-save fingerprint.
      if (!Objects.equals(receipt.getActorUserId(), teams.getCurrentUserIdOrThrow())
          || !Objects.equals(currentVariantId(current), receipt.getAppliedVariantId())
          || !Objects.equals(current.tmTextUnit().content(), next.getSource())
          || !Objects.equals(current.tmTextUnit().comment(), next.getSourceComment())
          || current.agentReview() == null
          || !Objects.equals(current.agentReview().proposalId(), next.getId())) {
        throw conflict(
            "Translation changed after this decision; refresh to inspect the saved result");
      }
      return current;
    }
    AgentReviewProposal original = proposals.findById(proposalId).orElseThrow();
    boolean sameReviewedText =
        Objects.equals(original.getSource(), next.getSource())
            && Objects.equals(original.getSourceComment(), next.getSourceComment())
            && Objects.equals(
                NormalizationUtils.normalize(original.getBaselineTarget()),
                NormalizationUtils.normalize(next.getBaselineTarget()));
    AgentReviewDecisionRequest nextJudgment =
        new AgentReviewDecisionRequest(
            next.getId(),
            next.getProposalRevision(),
            next.getVersion(),
            judgment.requestId(),
            judgment.action(),
            // The edit request refers to the prior report. Its assessment of A must not mark
            // the newly reviewed B as bad after an earlier correction changed the baseline.
            sameReviewedText ? judgment.originalAssessment() : null,
            judgment.suggestionAssessment(),
            judgment.explanation());
    return projects.saveDecision(
        next.getReviewProjectTextUnitId(),
        decision.getTarget(),
        decision.getComment(),
        decision.getStatus(),
        decision.getIncludedInLocalizedFile(),
        decision.getDecisionState(),
        request.reopen().expectedCurrentVariantId(),
        false,
        decision.getDecisionNotes(),
        current.reviewStateRevision(),
        nextJudgment,
        decision.getClientContext(),
        decision.getReviewFeedback());
  }

  private void validateEdit(long proposalId, EditRequest request) {
    var decision = request == null ? null : request.decision();
    var judgment = decision == null ? null : decision.getAgentReview();
    if (request == null
        || request.reopen() == null
        || decision == null
        || judgment == null
        || !Objects.equals(judgment.proposalId(), proposalId)
        || judgment.proposalRevision() == null
        || !Objects.equals(judgment.proposalVersion(), request.reopen().expectedProposalVersion())
        || judgment.requestId() == null
        || judgment.requestId().isBlank()
        || judgment.action() != AgentReviewDecisionRequest.Action.ACCEPT
        || decision.getTarget() == null
        || !"APPROVED".equals(decision.getStatus())
        || !Boolean.TRUE.equals(decision.getIncludedInLocalizedFile())
        || decision.getDecisionState() != DecisionState.DECIDED
        || Boolean.TRUE.equals(decision.getOverrideChangedCurrent())
        || decision.getExpectedReviewStateRevision() == null
        || decision.getExpectedReviewStateRevision().isBlank()
        || !Objects.equals(
            decision.getExpectedCurrentTmTextUnitVariantId(),
            request.reopen().expectedCurrentVariantId())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "A guarded accepted translation is required to edit a completed review");
    }
  }

  private AgentReviewProposal createRound(
      long projectId,
      long proposalId,
      Request request,
      boolean sameRow,
      ReviewProjectTextUnitDecisionRequest decision) {
    if (request == null
        || request.requestKey() == null
        || request.requestKey().isBlank()
        || request.requestKey().length() > 128
        || request.expectedProposalVersion() == null
        || request.expectedSource() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Request key, proposal version and current source are required");
    }
    AgentReviewProposal initial =
        proposals
            .findById(proposalId)
            .filter(p -> Objects.equals(p.getReviewProjectId(), projectId))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Proposal not found in project"));
    ReviewProjectTextUnit previousRow =
        rows.findById(initial.getReviewProjectTextUnitId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review row not found"));
    projects.assertCurrentUserCanReadProject(previousRow.getReviewProject());
    users.checkUserCanEditLocale(initial.getLocaleId());
    // Preserve the translation-save lock order: source, current translation, proposal.
    entityManager.refresh(previousRow.getTmTextUnit(), LockModeType.PESSIMISTIC_WRITE);
    var currentRow =
        currentVariants.findForUpdateByLocaleIdAndTmTextUnitId(
            initial.getLocaleId(), initial.getTmTextUnitId());
    TMTextUnitVariant current = currentRow == null ? null : currentRow.getTmTextUnitVariant();
    AgentReviewProposal previous = proposals.findForUpdateById(proposalId).orElseThrow();
    entityManager.refresh(previous, LockModeType.PESSIMISTIC_WRITE);
    String key =
        (decision != null
                ? "human-review-edit:"
                : sameRow ? "human-review-reopen:" : "human-review-again:")
            + hash(teams.getCurrentUserIdOrThrow() + ":" + request.requestKey());
    String fingerprint =
        decision == null
            ? hash(request)
            : hash(
                new EditFingerprint(
                    request,
                    decision.getTarget(),
                    decision.getComment(),
                    decision.getStatus(),
                    decision.getIncludedInLocalizedFile(),
                    decision.getDecisionState(),
                    decision.getExpectedCurrentTmTextUnitVariantId(),
                    decision.getExpectedReviewStateRevision(),
                    decision.getDecisionNotes(),
                    decision.getAgentReview(),
                    decision.getReviewFeedback()));
    AgentReviewProposal replay =
        proposals.findByRunIdAndSubmissionKey(previous.getRunId(), key).orElse(null);
    if (replay != null) {
      if (!Objects.equals(replay.getPreviousProposalId(), proposalId)
          || !Objects.equals(replay.getRequestFingerprint(), fingerprint)) {
        throw conflict("This request key was already used for another review");
      }
      return replay;
    }
    if (previous.getVersion() != request.expectedProposalVersion()
        || (previous.getDisposition() != Disposition.RESOLVED
            && previous.getDisposition() != Disposition.FOLLOW_UP)) {
      throw conflict(
          "This finding changed or has not been reviewed; refresh before starting another round");
    }
    if (proposals.findByFindingIdOrderByProposalRevisionAsc(previous.getFindingId()).stream()
        .anyMatch(p -> p.getProposalRevision() > previous.getProposalRevision())) {
      throw conflict("A newer review round already exists for this finding");
    }
    if (decision != null) {
      var currentDetail = projects.getReviewProjectTextUnit(previousRow.getId());
      if (!Objects.equals(
              decision.getAgentReview().proposalRevision(), previous.getProposalRevision())
          || !Objects.equals(
              decision.getExpectedReviewStateRevision(), currentDetail.reviewStateRevision())) {
        throw new ReviewProjectCurrentVariantConflictException(
            request.expectedCurrentVariantId(), currentVariantId(currentDetail), currentDetail);
      }
    }
    var unit = previousRow.getTmTextUnit();
    if (!Objects.equals(request.expectedSource(), unit.getContent())
        || !Objects.equals(request.expectedSourceComment(), unit.getComment())
        || !Objects.equals(
            request.expectedCurrentVariantId(), current == null ? null : current.getId())
        || !Objects.equals(
            request.expectedCurrentTarget(), current == null ? null : current.getContent())
        || !Objects.equals(
            request.expectedCurrentStatus(), current == null ? null : current.getStatus().name())
        || !Objects.equals(
            request.expectedCurrentIncludedInLocalizedFile(),
            current == null ? null : current.isIncludedInLocalizedFile())) {
      throw conflict(
          "The source or current translation changed; refresh before starting another round");
    }
    ReviewProjectTextUnit nextRow =
        sameRow
            ? projects.reopenHumanReviewRow(previousRow, current)
            : projects.createHumanReReviewProject(previousRow, current);
    AgentReviewProposal next = new AgentReviewProposal();
    next.setRunId(previous.getRunId());
    next.setSubmissionKey(key);
    next.setRequestFingerprint(fingerprint);
    next.setFindingId(previous.getFindingId());
    next.setProposalRevision(previous.getProposalRevision() + 1);
    next.setPreviousProposalId(previous.getId());
    next.setGroupKey(previous.getGroupKey());
    next.setRepositoryId(previous.getRepositoryId());
    next.setLocaleId(previous.getLocaleId());
    next.setTmTextUnitId(previous.getTmTextUnitId());
    next.setSource(unit.getContent());
    next.setSourceComment(unit.getComment());
    next.setBaselineVariantId(current == null ? null : current.getId());
    next.setBaselineTarget(current == null ? null : current.getContent());
    next.setBaselineStatus(current == null ? null : current.getStatus().name());
    next.setBaselineIncludedInLocalizedFile(
        current == null ? null : current.isIncludedInLocalizedFile());
    boolean preserveReport =
        sameRow
            && Objects.equals(previous.getSource(), next.getSource())
            && Objects.equals(previous.getSourceComment(), next.getSourceComment())
            && Objects.equals(previous.getBaselineVariantId(), next.getBaselineVariantId())
            && Objects.equals(previous.getBaselineTarget(), next.getBaselineTarget())
            && Objects.equals(previous.getBaselineStatus(), next.getBaselineStatus())
            && Objects.equals(
                previous.getBaselineIncludedInLocalizedFile(),
                next.getBaselineIncludedInLocalizedFile());
    String reviewerIdentity = SecurityContextHolder.getContext().getAuthentication().getName();
    // Reconsidering an unchanged translation keeps the report that prompted the decision.
    // A changed source or current translation requires a fresh human review without old advice.
    next.setProposedTarget(preserveReport ? previous.getProposedTarget() : null);
    next.setCategory(preserveReport ? previous.getCategory() : Category.HUMAN_REVIEW);
    next.setReadiness(preserveReport ? previous.getReadiness() : Readiness.HUMAN_REVIEW);
    next.setEvidenceJson(preserveReport ? previous.getEvidenceJson() : null);
    next.setVerifierIdentity(preserveReport ? previous.getVerifierIdentity() : null);
    next.setVerificationRationale(preserveReport ? previous.getVerificationRationale() : null);
    next.setIntegrityDiagnostics(preserveReport ? previous.getIntegrityDiagnostics() : null);
    next.setDisposition(Disposition.ROUTED);
    next.setRationale(
        sameRow
            ? previous.getRationale()
            : "Review requested again using the current translation. Previous finding: "
                + previous.getRationale());
    next.setProducerIdentity(preserveReport ? previous.getProducerIdentity() : reviewerIdentity);
    next.setIncidentId(previous.getIncidentId());
    next.setReviewProjectId(nextRow.getReviewProject().getId());
    next.setReviewProjectTextUnitId(nextRow.getId());
    proposals.saveAndFlush(next);
    AgentReviewFeedback event = new AgentReviewFeedback();
    event.setProposalId(previous.getId());
    event.setRequestKey(key);
    event.setRequestFingerprint(fingerprint);
    event.setActorType(ActorType.HUMAN);
    event.setActorUserId(teams.getCurrentUserIdOrThrow());
    event.setActorIdentity(reviewerIdentity);
    event.setAction(FeedbackAction.REVIEW_AGAIN);
    event.setExplanation(
        sameRow
            ? "Marked pending for another review using the current translation."
            : "Started a new human review round from the current translation.");
    event.setResponseProposalId(next.getId());
    AgentReviewFeedback latest =
        feedback.findFirstByProposalIdOrderByIdDesc(previous.getId()).orElse(null);
    if (latest != null && latest.getFollowUpRequested())
      event.setRespondsToFeedbackId(latest.getId());
    feedback.save(event);
    if (previous.getIncidentId() != null) {
      var incident = incidents.findById(previous.getIncidentId()).orElseThrow();
      incident.setStatus(TranslationIncidentStatus.OPEN);
      incident.setResolution(TranslationIncidentResolution.PENDING_REVIEW);
      incident.setClosedAt(null);
      incident.setClosedByUsername(null);
      incident.setResolutionReviewProjectId(next.getReviewProjectId());
    }
    return next;
  }

  private Long currentVariantId(GetProjectDetailView.ReviewProjectTextUnit row) {
    return row.currentTmTextUnitVariant() == null ? null : row.currentTmTextUnitVariant().id();
  }

  private Result result(AgentReviewProposal proposal) {
    return new Result(
        proposal.getReviewProjectId(), proposal.getId(), proposal.getProposalRevision());
  }

  private String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not identify review request", exception);
    }
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
