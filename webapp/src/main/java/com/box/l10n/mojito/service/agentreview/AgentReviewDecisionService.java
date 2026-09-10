package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitDetail;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Proposal lineage participates in the same transaction and locks as a human translation save. */
@Service
public class AgentReviewDecisionService {
  private final AgentReviewService reviews;
  private final AgentReviewProposalRepository proposals;
  private final AgentReviewFeedbackRepository feedback;
  private final AgentReviewRunRepository runs;
  private final TranslationIncidentRepository incidents;
  private final TMTextUnitIntegrityCheckService integrity;
  private final ObjectMapper mapper;
  @PersistenceContext private EntityManager entityManager;

  public AgentReviewDecisionService(
      AgentReviewService reviews,
      AgentReviewProposalRepository proposals,
      AgentReviewFeedbackRepository feedback,
      AgentReviewRunRepository runs,
      TranslationIncidentRepository incidents,
      TMTextUnitIntegrityCheckService integrity,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper mapper) {
    this.reviews = reviews;
    this.proposals = proposals;
    this.feedback = feedback;
    this.runs = runs;
    this.incidents = incidents;
    this.integrity = integrity;
    this.mapper = mapper;
  }

  public record Prepared(
      AgentReviewProposal proposal,
      AgentReviewDecisionRequest request,
      String contextFingerprint,
      boolean replay) {}

  /** Caller has authorized the project/locale and locked TM/current before this proposal lock. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Prepared prepare(
      ReviewProjectTextUnit row,
      TMTextUnitVariant current,
      AgentReviewDecisionRequest request,
      String target,
      String comment,
      String status,
      Boolean included,
      DecisionState decisionState,
      Long expectedVariantId,
      String expectedReviewRevision,
      String decisionNotes) {
    if (row.getReviewProject().getAgentReviewRunId() == null) {
      if (request != null) throw new IllegalArgumentException("This row has no agent proposal");
      return null;
    }
    if (request == null
        || request.proposalId() == null
        || request.proposalRevision() == null
        || request.proposalVersion() == null
        || request.action() == null
        || request.requestId() == null
        || request.requestId().isBlank()
        || request.requestId().length() > 128
        || expectedReviewRevision == null) {
      throw new IllegalArgumentException(
          "A proposal, revision, request ID, and review revision are required");
    }
    if (request.explanation() != null && request.explanation().length() > 4000) {
      throw new IllegalArgumentException("Feedback explanation must be at most 4000 characters");
    }
    AgentReviewProposal proposal =
        proposals
            .findForUpdateById(request.proposalId())
            .orElseThrow(() -> new IllegalArgumentException("Agent proposal not found"));
    if (!Objects.equals(proposal.getReviewProjectTextUnitId(), row.getId())
        || !Objects.equals(proposal.getReviewProjectId(), row.getReviewProject().getId())
        || !Objects.equals(proposal.getTmTextUnitId(), row.getTmTextUnit().getId())
        || !Objects.equals(proposal.getLocaleId(), row.getReviewProject().getLocale().getId())
        || proposal.getProposalRevision() != request.proposalRevision()) {
      throw new IllegalArgumentException(
          "Proposal does not belong to this review row and revision");
    }
    boolean accept = request.action() == AgentReviewDecisionRequest.Action.ACCEPT;
    if (accept) {
      if (target == null
          || target.length() > AgentReviewContracts.MAX_TEXT_LENGTH
          || !"APPROVED".equals(status)
          || !Boolean.TRUE.equals(included)
          || decisionState != DecisionState.DECIDED) {
        throw new IllegalArgumentException(
            "Accept must save an approved translation and complete the row");
      }
    } else if (target != null
        || decisionState
            != (request.action() == AgentReviewDecisionRequest.Action.DEFER
                ? DecisionState.PENDING
                : DecisionState.DECIDED)) {
      throw new IllegalArgumentException("Feedback-only actions must not change the translation");
    }
    if (request.action() == AgentReviewDecisionRequest.Action.KEEP_CURRENT
        && request.originalAssessment() == OriginalAssessment.BAD) {
      throw new IllegalArgumentException(
          "Request another fix when the original translation is wrong");
    }
    String fingerprint =
        fingerprint(
            Arrays.asList(
                request,
                target,
                comment,
                status,
                included,
                decisionState,
                expectedVariantId,
                expectedReviewRevision,
                decisionNotes));
    Prepared prepared = new Prepared(proposal, request, fingerprint, false);
    Optional<AgentReviewFeedback> previous =
        feedback.findByProposalIdAndActorTypeAndRequestKey(
            proposal.getId(), ActorType.HUMAN, request.requestId());
    if (previous.isPresent()) {
      AgentReviewFeedback saved = previous.get();
      // The core validates the complete original request and authenticated author before replay.
      reviews.appendHumanFeedback(
          feedbackRequest(
              prepared,
              accept ? NormalizationUtils.normalize(target) : saved.getFinalTarget(),
              saved.getAppliedVariantId()));
      if (!Objects.equals(id(current), accept ? saved.getAppliedVariantId() : expectedVariantId)
          || !Objects.equals(row.getTmTextUnit().getContent(), proposal.getSource())
          || !Objects.equals(row.getTmTextUnit().getComment(), proposal.getSourceComment())) {
        throw conflict(
            "Translation changed after this decision; refresh to inspect the saved result");
      }
      return new Prepared(proposal, request, fingerprint, true);
    }
    if (row.getReviewProject().getStatus() != ReviewProjectStatus.OPEN) {
      throw conflict("Reopen the project before reviewing this proposal");
    }
    if (proposal.getVersion() != request.proposalVersion()
        || (proposal.getDisposition() != Disposition.ROUTED
            && !canReconsider(
                proposal,
                feedback.findFirstByProposalIdOrderByIdDesc(proposal.getId()).orElse(null)))) {
      throw conflict(
          "This proposal has changed or already received a decision; refresh the review");
    }
    if (accept) {
      if (stale(
          proposal,
          row.getTmTextUnit().getContent(),
          row.getTmTextUnit().getComment(),
          id(current),
          current == null ? null : current.getContent())) {
        throw conflict(
            "The reviewed translation has changed; request a fresh proposal before accepting");
      }
      try {
        integrity.checkTMTextUnitIntegrityForLocale(
            row.getTmTextUnit().getId(),
            NormalizationUtils.normalize(target),
            row.getReviewProject().getLocale().getBcp47Tag());
      } catch (IntegrityCheckException exception) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "The proposed translation failed integrity checks. Correct it before accepting.");
      }
    }
    return prepared;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Prepared prepared, TMTextUnitVariant finalVariant) {
    if (prepared == null || prepared.replay()) return;
    boolean applies = prepared.request().action() == AgentReviewDecisionRequest.Action.ACCEPT;
    AgentReviewFeedback saved =
        reviews.appendHumanFeedback(
            feedbackRequest(
                prepared,
                finalVariant == null ? null : finalVariant.getContent(),
                applies ? id(finalVariant) : null));
    Long incidentId = prepared.proposal().getIncidentId();
    if (incidentId != null
        && (saved.getAction() == FeedbackAction.ACCEPT
            || saved.getAction() == FeedbackAction.EDIT_ACCEPT
            || saved.getAction() == FeedbackAction.KEEP_CURRENT)) {
      incidents
          .findById(incidentId)
          .ifPresent(
              incident -> {
                incident.setStatus(TranslationIncidentStatus.CLOSED);
                incident.setResolution(
                    saved.getAction() == FeedbackAction.KEEP_CURRENT
                        ? TranslationIncidentResolution.REVIEW_DISMISSED
                        : TranslationIncidentResolution.REVIEW_APPLIED);
                incident.setClosedAt(ZonedDateTime.now());
                incident.setClosedByUsername(saved.getActorIdentity());
              });
    }
  }

  private AgentReviewContracts.HumanFeedbackRequest feedbackRequest(
      Prepared prepared, String finalTarget, Long variantId) {
    AgentReviewDecisionRequest request = prepared.request();
    FeedbackAction action =
        switch (request.action()) {
          case ACCEPT ->
              Objects.equals(
                      NormalizationUtils.normalize(prepared.proposal().getProposedTarget()),
                      finalTarget)
                  ? FeedbackAction.ACCEPT
                  : FeedbackAction.EDIT_ACCEPT;
          case KEEP_CURRENT -> FeedbackAction.KEEP_CURRENT;
          case DEFER -> FeedbackAction.DEFER;
          case REQUEST_REVISION -> FeedbackAction.REQUEST_REVISION;
        };
    return new AgentReviewContracts.HumanFeedbackRequest(
        request.requestId(),
        request.proposalId(),
        request.proposalVersion(),
        action,
        request.originalAssessment(),
        request.suggestionAssessment(),
        request.explanation(),
        request.action() == AgentReviewDecisionRequest.Action.REQUEST_REVISION,
        finalTarget,
        variantId,
        prepared.contextFingerprint());
  }

  /** Load proposal context only for agent projects, using a bounded number of queries. */
  @Transactional(readOnly = true)
  public Map<Long, AgentReviewProposalView> views(
      ReviewProject project, List<ReviewProjectTextUnitDetail> rows) {
    if (project.getAgentReviewRunId() == null || rows.isEmpty()) return Map.of();
    Map<Long, ReviewProjectTextUnitDetail> rowsById =
        rows.stream()
            .collect(
                Collectors.toMap(
                    ReviewProjectTextUnitDetail::reviewProjectTextUnitId, Function.identity()));
    List<AgentReviewProposal> projectProposals =
        entityManager
            .createQuery(
                "select p from AgentReviewProposal p where p.reviewProjectId = :projectId order by p.id",
                AgentReviewProposal.class)
            .setParameter("projectId", project.getId())
            .getResultList();
    if (projectProposals.isEmpty()) return Map.of();
    Map<Long, String> lastRequests =
        entityManager
            .createQuery(
                "select f from AgentReviewFeedback f where f.proposalId in :ids and f.actorType = :actor "
                    + "and f.id = (select max(f2.id) from AgentReviewFeedback f2 where f2.proposalId = f.proposalId and f2.actorType = :actor)",
                AgentReviewFeedback.class)
            .setParameter("ids", projectProposals.stream().map(AgentReviewProposal::getId).toList())
            .setParameter("actor", ActorType.HUMAN)
            .getResultStream()
            .collect(
                Collectors.toMap(
                    AgentReviewFeedback::getProposalId, AgentReviewFeedback::getRequestKey));
    Map<Long, AgentReviewFeedback> latestFeedback =
        entityManager
            .createQuery(
                "select f from AgentReviewFeedback f where f.proposalId in :ids "
                    + "and f.id = (select max(f2.id) from AgentReviewFeedback f2 where f2.proposalId = f.proposalId)",
                AgentReviewFeedback.class)
            .setParameter("ids", projectProposals.stream().map(AgentReviewProposal::getId).toList())
            .getResultStream()
            .collect(Collectors.toMap(AgentReviewFeedback::getProposalId, Function.identity()));
    String reviewType =
        runs.findById(project.getAgentReviewRunId())
            .map(AgentReviewRun::getReviewType)
            .orElse(null);
    Map<Long, AgentReviewProposalView> result = new HashMap<>();
    for (AgentReviewProposal proposal : projectProposals) {
      ReviewProjectTextUnitDetail row = rowsById.get(proposal.getReviewProjectTextUnitId());
      if (row != null)
        result.put(
            row.reviewProjectTextUnitId(),
            view(
                proposal,
                reviewType,
                row,
                lastRequests.get(proposal.getId()),
                latestFeedback.get(proposal.getId())));
    }
    return result;
  }

  private AgentReviewProposalView view(
      AgentReviewProposal proposal,
      String reviewType,
      ReviewProjectTextUnitDetail row,
      String lastRequestId,
      AgentReviewFeedback latestFeedback) {
    return new AgentReviewProposalView(
        proposal.getId(),
        proposal.getProposalRevision(),
        proposal.getVersion(),
        proposal.getFindingId(),
        proposal.getRunId(),
        reviewType,
        proposal.getSource(),
        proposal.getBaselineTarget(),
        proposal.getProposedTarget(),
        proposal.getRationale(),
        proposal.getCategory().name(),
        proposal.getReadiness().name(),
        proposal.getVerificationRationale(),
        proposal.getIntegrityDiagnostics(),
        evidence(proposal),
        proposal.getDisposition().name(),
        proposal.getDisposition() != Disposition.RESOLVED
            && stale(
                proposal,
                row.tmTextUnitContent(),
                row.tmTextUnitComment(),
                row.currentTmTextUnitVariantId(),
                row.currentTmTextUnitVariantContent()),
        lastRequestId,
        canReconsider(proposal, latestFeedback));
  }

  private List<AgentReviewProposalView.Evidence> evidence(AgentReviewProposal proposal) {
    String json = proposal.getEvidenceJson();
    if (json == null || json.isBlank()) return List.of();
    try {
      JsonNode root = mapper.readTree(json);
      if (root.isObject() && root.has("items")) root = root.get("items");
      List<AgentReviewProposalView.Evidence> result = new ArrayList<>();
      if (root.isArray()) {
        for (JsonNode item : root) {
          if (result.size() == 30) break;
          String label =
              item.isTextual() ? item.asText() : item.path("label").asText(item.toString());
          String sha256 = item.path("artifactSha256").asText("");
          String url =
              sha256.matches("[a-f0-9]{64}")
                  ? "/api/agent-reviews/projects/"
                      + proposal.getReviewProjectId()
                      + "/proposals/"
                      + proposal.getId()
                      + "/artifacts/"
                      + sha256
                  : safeUrl(item.path("url").asText(null));
          result.add(new AgentReviewProposalView.Evidence(label, url));
        }
      } else
        result.add(
            new AgentReviewProposalView.Evidence(
                root.isTextual() ? root.asText() : root.toPrettyString(), null));
      return result;
    } catch (Exception exception) {
      return List.of(new AgentReviewProposalView.Evidence(json, null));
    }
  }

  private String safeUrl(String value) {
    if (value == null) return null;
    try {
      URI uri = URI.create(value);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
              && uri.getHost() != null
              && uri.getUserInfo() == null
          ? value
          : null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private boolean canReconsider(AgentReviewProposal proposal, AgentReviewFeedback latest) {
    return proposal.getDisposition() == Disposition.FOLLOW_UP
        && latest != null
        && latest.getActorType() == ActorType.AGENT
        && (latest.getAction() == FeedbackAction.CHALLENGE
            || latest.getAction() == FeedbackAction.CONTEXT_REQUEST);
  }

  private boolean stale(
      AgentReviewProposal proposal,
      String source,
      String sourceComment,
      Long variantId,
      String target) {
    return !Objects.equals(proposal.getSource(), source)
        || !Objects.equals(proposal.getSourceComment(), sourceComment)
        || !Objects.equals(proposal.getBaselineVariantId(), variantId)
        || !Objects.equals(proposal.getBaselineTarget(), target);
  }

  private Long id(TMTextUnitVariant variant) {
    return variant == null ? null : variant.getId();
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private String fingerprint(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      mapper.writeValueAsStringUnchecked(value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
