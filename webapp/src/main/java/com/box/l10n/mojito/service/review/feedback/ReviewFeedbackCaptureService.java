package com.box.l10n.mojito.service.review.feedback;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.review.*;
import com.box.l10n.mojito.service.agentreview.AgentReviewProposalRepository;
import com.box.l10n.mojito.service.agentreview.AgentReviewRunRepository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptRepository;
import com.box.l10n.mojito.service.review.ReviewProjectClientContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewFeedbackCaptureService {
  private final ReviewFeedbackEventRepository events;
  private final AiTranslateTextUnitAttemptRepository attempts;
  private final AgentReviewProposalRepository proposals;
  private final AgentReviewRunRepository runs;
  private final ObjectMapper mapper;

  public ReviewFeedbackCaptureService(
      ReviewFeedbackEventRepository events,
      AiTranslateTextUnitAttemptRepository attempts,
      AgentReviewProposalRepository proposals,
      AgentReviewRunRepository runs,
      ObjectMapper mapper) {
    this.events = events;
    this.attempts = attempts;
    this.proposals = proposals;
    this.runs = runs;
    this.mapper = mapper;
  }

  public record Baseline(
      String target,
      boolean ai,
      String kind,
      String model,
      String promptVersion,
      Map<String, Object> provenance) {}

  @Transactional(readOnly = true)
  public Baseline baseline(ReviewProjectTextUnit row) {
    AgentReviewProposal proposal =
        row.getReviewProject().getAgentReviewRunId() == null
            ? null
            : proposals.findFirstByReviewProjectTextUnitIdOrderByIdDesc(row.getId()).orElse(null);
    return baseline(row, row.getTmTextUnitVariant(), proposal);
  }

  private Baseline baseline(
      ReviewProjectTextUnit row, TMTextUnitVariant reviewed, AgentReviewProposal proposal) {
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("glossaryVersion", null);
    provenance.put("styleGuideVersion", null);
    // A report's proposal is the AI output; the live/original translation is separate evidence.
    if (proposal != null && proposal.getProposedTarget() != null) {
      var run = runs.findById(proposal.getRunId()).orElse(null);
      provenance.put("proposalId", proposal.getId());
      provenance.put("proposalRevision", proposal.getProposalRevision());
      provenance.put("runId", proposal.getRunId());
      provenance.put("producerIdentity", proposal.getProducerIdentity());
      provenance.put("originalAtReview", proposal.getBaselineTarget());
      provenance.put("methodVersion", run == null ? null : run.getMethodVersion());
      provenance.put("configurationVersion", run == null ? null : run.getConfigurationVersion());
      provenance.put("manifestSha256", run == null ? null : run.getManifestSha256());
      return new Baseline(
          proposal.getProposedTarget(),
          true,
          "AGENT_PROPOSAL",
          "unknown",
          run == null || run.getConfigurationVersion() == null
              ? "unknown"
              : "configuration:" + run.getConfigurationVersion(),
          provenance);
    }
    return baseline(
        row.getTmTextUnit().getId(), row.getReviewProject().getLocale().getId(), reviewed);
  }

  @Transactional(readOnly = true)
  public Baseline baseline(TMTextUnitVariant reviewed) {
    return baseline(reviewed.getTmTextUnit().getId(), reviewed.getLocale().getId(), reviewed);
  }

  private Baseline baseline(Long unitId, Long localeId, TMTextUnitVariant reviewed) {
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("glossaryVersion", null);
    provenance.put("styleGuideVersion", null);
    var attempt =
        reviewed == null || reviewed.getId() == null
            ? null
            : attempts
                .findFirstByTmTextUnit_IdAndLocale_IdAndTmTextUnitVariant_IdAndStatusOrderByIdDesc(
                    unitId, localeId, reviewed.getId(), AiTranslateTextUnitAttempt.STATUS_IMPORTED)
                .orElse(null);
    if (attempt != null) {
      provenance.put("attemptId", attempt.getId());
      provenance.put("requestGroupId", attempt.getRequestGroupId());
      provenance.put("promptFingerprint", attempt.getPromptFingerprint());
      provenance.put("reasoningEffort", attempt.getReasoningEffort());
      provenance.put("textVerbosity", attempt.getTextVerbosity());
      provenance.put("requestPayloadBlobName", attempt.getRequestPayloadBlobName());
      return new Baseline(
          reviewed.getContent(),
          true,
          "AI_TRANSLATE",
          known(attempt.getModel()),
          known(attempt.getPromptFingerprint()),
          provenance);
    }
    return new Baseline(
        reviewed == null ? null : reviewed.getContent(),
        false,
        "UNATTRIBUTED",
        "unknown",
        "unknown",
        provenance);
  }

  /** Caller holds the review/current locks; evidence commits or rolls back with the acceptance. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void capture(
      ReviewProjectTextUnit row,
      ReviewProjectTextUnitDecision decision,
      TMTextUnitVariant reviewed,
      AgentReviewProposal proposal,
      String rawFinal,
      Long reviewerId,
      ReviewerFeedback feedback,
      ReviewProjectClientContext client) {
    if (decision.getDecisionState() != ReviewProjectTextUnitDecision.DecisionState.DECIDED) return;
    Baseline baseline = baseline(row, reviewed, proposal);
    var project = row.getReviewProject();
    var unit = row.getTmTextUnit();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", 1);
    payload.put("source", unit.getContent());
    payload.put("sourceComment", unit.getComment());
    payload.put("stringId", unit.getName());
    payload.put("repositoryId", unit.getAsset().getRepository().getId());
    payload.put(
        "requestId",
        project.getReviewProjectRequest() == null
            ? null
            : project.getReviewProjectRequest().getId());
    payload.put("reviewProjectTextUnitId", row.getId());
    payload.put("reviewedVariantId", reviewed == null ? null : reviewed.getId());
    payload.put(
        "acceptedVariantId", decision.getVariant() == null ? null : decision.getVariant().getId());
    payload.put("legacyDecisionNotes", decision.getNotes());
    payload.put("operationId", client == null ? null : client.operationId());
    String key =
        hash(
            json(
                Arrays.asList(
                    row.getId(),
                    decision.getId(),
                    decision.getVersion(),
                    reviewerId,
                    rawFinal,
                    feedback,
                    proposal == null ? null : proposal.getId())));
    saveEvent(
        key,
        project.getId(),
        unit,
        project.getLocale().getBcp47Tag(),
        baseline,
        decision.getVariant(),
        rawFinal,
        reviewerId,
        feedback,
        payload);
  }

  /** The Workbench save and evidence share the caller's transaction and current-variant lock. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void captureWorkbench(
      TMTextUnitVariant reviewed,
      TMTextUnitVariant accepted,
      Long currentVariantId,
      String rawFinal,
      Long reviewerId,
      ReviewerFeedback feedback,
      String operationId,
      String requestFingerprint,
      String eventKey) {
    var unit = reviewed.getTmTextUnit();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", 1);
    payload.put("surface", "WORKBENCH");
    payload.put("source", unit.getContent());
    payload.put("sourceComment", unit.getComment());
    payload.put("stringId", unit.getName());
    payload.put("repositoryId", unit.getAsset().getRepository().getId());
    payload.put("reviewedVariantId", reviewed.getId());
    payload.put("acceptedVariantId", accepted.getId());
    payload.put("currentVariantId", currentVariantId);
    payload.put(
        "reviewComplete",
        accepted.getStatus() == TMTextUnitVariant.Status.APPROVED
            || !accepted.isIncludedInLocalizedFile());
    payload.put("targetComment", accepted.getComment());
    payload.put(
        "savedBy",
        accepted.getCreatedByUser() == null ? null : accepted.getCreatedByUser().getUsername());
    payload.put("operationId", operationId);
    payload.put("requestFingerprint", requestFingerprint);
    saveEvent(
        eventKey,
        null,
        unit,
        reviewed.getLocale().getBcp47Tag(),
        baseline(reviewed),
        accepted,
        rawFinal,
        reviewerId,
        feedback,
        payload);
  }

  private void saveEvent(
      String key,
      Long projectId,
      TMTextUnit unit,
      String locale,
      Baseline baseline,
      TMTextUnitVariant accepted,
      String rawFinal,
      Long reviewerId,
      ReviewerFeedback feedback,
      Map<String, Object> payload) {
    var diff = ReviewEditDiff.compare(baseline.target(), rawFinal);
    boolean problematic = accepted != null && !accepted.isIncludedInLocalizedFile();
    String reviewerCategory =
        feedback == null || feedback.reason() == null
            ? null
            : switch (feedback.reason()) {
              case TERMINOLOGY -> "TERMINOLOGY";
              case WRONG_MEANING -> "MEANING";
              case TONE_STYLE -> "TONE_STYLE";
              case GRAMMAR -> "GRAMMAR";
              case TOO_LONG -> "LENGTH_UI_FIT";
              case CONTEXT -> "CONTEXT";
              case OTHER -> "UNKNOWN_MATERIAL_EDIT";
            };
    String category =
        problematic
            ? "MARKED_PROBLEMATIC"
            : diff.material() && reviewerCategory != null ? reviewerCategory : diff.category();
    payload.put("baseline", baseline);
    payload.put("finalAcceptedRaw", rawFinal);
    payload.put("finalStored", accepted == null ? null : accepted.getContent());
    payload.put("diff", diff);
    payload.put("transform", ReviewEditDiff.transform(diff, baseline.target(), rawFinal));
    payload.put("reviewerCategory", reviewerCategory);
    payload.put(
        "classificationSource", category.equals(reviewerCategory) ? "REVIEWER" : "DETERMINISTIC");
    payload.put(
        "terminologySignal",
        feedback != null && feedback.reason() == ReviewerFeedback.Reason.TERMINOLOGY
            ? "REVIEWER_REPORTED"
            : "UNASSESSED");
    payload.put("acceptedStatus", accepted == null ? null : accepted.getStatus());
    payload.put(
        "includedInLocalizedFile", accepted == null ? null : accepted.isIncludedInLocalizedFile());
    payload.put("feedback", feedback);
    // UI activity is an explicitly labeled client observation, never server-authenticated
    // provenance.
    payload.put(
        "action",
        feedback != null && feedback.aiSuggestionUsed()
            ? "AI_ASSISTED"
            : diff.rawChanged() ? "EDITED" : "ACCEPTED_UNCHANGED");
    payload.put("activitySource", feedback == null ? "UNAVAILABLE" : "CLIENT_REPORTED");
    payload.put("markedProblematic", problematic);
    if (events.existsByEventKey(key)) return;
    String pattern =
        hash(
            json(
                List.of(
                    diff.category(), ReviewEditDiff.transform(diff, baseline.target(), rawFinal))));
    events.save(
        new ReviewFeedbackEvent(
            key,
            projectId,
            unit.getId(),
            reviewerId,
            locale,
            baseline.model(),
            baseline.promptVersion(),
            category,
            pattern,
            hash(unit.getContent()),
            hash(baseline.target()),
            hash(rawFinal),
            baseline.ai(),
            json(payload)));
  }

  private static String known(String s) {
    return s == null || s.isBlank() ? "unknown" : s;
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Could not serialize review evidence", e);
    }
  }

  public static String hash(String value) {
    try {
      // Preserve null versus empty in hashes as well as the stored raw values.
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      (value == null ? "null:" : "text:" + value)
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
