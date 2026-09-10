package com.box.l10n.mojito.service.agentreview;

import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.*;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Durable, resumable agent work. This service never writes translations or human approval.
 *
 * <p>Writes use READ_COMMITTED because database-backed artifacts commit in a separate transaction.
 * A repeatable-read snapshot can hide a newly published artifact or a concurrent idempotent create.
 * Explicit row locks, lease generations, and expected versions serialize review state changes.
 */
@Service
public class AgentReviewService {
  private static final Logger logger = LoggerFactory.getLogger(AgentReviewService.class);
  private final AgentReviewRunRepository runs;
  private final AgentReviewProposalRepository proposals;
  private final AgentReviewFeedbackRepository feedback;
  private final RepositoryRepository repositories;
  private final RepositoryLocaleRepository repositoryLocales;
  private final TMTextUnitRepository textUnits;
  private final TMTextUnitVariantRepository variants;
  private final TeamRepository teams;
  private final TeamService teamService;
  private final UserService userService;
  private final StructuredBlobStorage blobs;
  private final ObjectMapper mapper;
  private final EntityManager entityManager;
  private final TransactionTemplate itemTransaction;
  private Clock clock = Clock.systemUTC();

  @Autowired
  public AgentReviewService(
      AgentReviewRunRepository runs,
      AgentReviewProposalRepository proposals,
      AgentReviewFeedbackRepository feedback,
      RepositoryRepository repositories,
      RepositoryLocaleRepository repositoryLocales,
      TMTextUnitRepository textUnits,
      TMTextUnitVariantRepository variants,
      TeamRepository teams,
      TeamService teamService,
      UserService userService,
      StructuredBlobStorage blobs,
      ObjectMapper mapper,
      EntityManager entityManager,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.proposals = proposals;
    this.feedback = feedback;
    this.repositories = repositories;
    this.repositoryLocales = repositoryLocales;
    this.textUnits = textUnits;
    this.variants = variants;
    this.teams = teams;
    this.teamService = teamService;
    this.userService = userService;
    this.blobs = blobs;
    this.mapper = mapper;
    this.entityManager = entityManager;
    this.itemTransaction = new TransactionTemplate(transactionManager);
    this.itemTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.itemTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RunView createRun(CreateRunRequest request) {
    requireManager();
    require(request != null, "Run request is required");
    text(request.requestKey(), "requestKey", 128, true);
    text(request.reviewType(), "reviewType", 64, true);
    require(
        request.reviewType().matches("[A-Z][A-Z0-9_]*"),
        "reviewType must be an uppercase identifier");
    text(request.methodVersion(), "methodVersion", 128, true);
    text(request.configurationVersion(), "configurationVersion", 128, true);
    require(
        request.teamId() != null && teams.existsById(request.teamId()),
        "A valid teamId is required");
    teamService.assertCurrentUserCanAccessTeam(request.teamId());
    int dueDays = request.dueDateOffsetDays() == null ? 7 : request.dueDateOffsetDays();
    int maxWords =
        request.maxWordCountPerProject() == null ? 1500 : request.maxWordCountPerProject();
    require(dueDays >= 1 && dueDays <= 365, "dueDateOffsetDays must be between 1 and 365");
    require(
        maxWords >= 1 && maxWords <= 100000, "maxWordCountPerProject must be between 1 and 100000");
    jsonText(request.inputManifestJson(), "inputManifestJson", MAX_ARTIFACT_BYTES, true);
    String fingerprint = fingerprint(request);
    Long userId = teamService.getCurrentUserIdOrThrow();
    // Serialize creation for the actor so concurrent retries cannot both publish runs/artifacts.
    entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
    AgentReviewRun existing =
        runs.findByRequestedByUserIdAndRequestKey(userId, request.requestKey()).orElse(null);
    if (existing != null) {
      authorize(existing);
      sameFingerprint(existing.getRequestFingerprint(), fingerprint);
      return view(existing);
    }
    // A persisted run retains its frozen scope even if those live strings are later removed.
    // Only new runs need to establish that their initial scope still exists.
    validateGroups(request.groups());
    AgentReviewRun run = new AgentReviewRun();
    run.setRequestKey(request.requestKey());
    run.setRequestFingerprint(fingerprint);
    run.setInputFingerprint(
        fingerprint(new RunManifest(request.groups(), request.inputManifestJson())));
    run.setRequestedByUserId(userId);
    run.setReviewType(request.reviewType());
    run.setTeamId(request.teamId());
    run.setRepositoryIdsJson(
        json(request.groups().stream().map(Group::repositoryId).distinct().sorted().toList()));
    run.setLocaleIdsJson(
        json(request.groups().stream().map(Group::localeId).distinct().sorted().toList()));
    run.setMethodVersion(request.methodVersion());
    run.setConfigurationVersion(request.configurationVersion());
    run.setStatus(RunStatus.RUNNING);
    run.setPlannedGroupCount(request.groups().size());
    run.setDueDateOffsetDays(dueDays);
    run.setMaxWordCountPerProject(maxWords);
    run.setAssignTranslator(request.assignTranslator() == null || request.assignTranslator());
    // Save a non-null temporary hash to allocate the id; rollback leaves no readable half-run.
    run.setManifestSha256("pending");
    runs.saveAndFlush(run);
    run.setManifestSha256(
        putJson(run.getId(), new RunManifest(request.groups(), request.inputManifestJson())));
    return view(run);
  }

  @Transactional(readOnly = true)
  public RunView getRun(long runId) {
    return view(readRun(runId));
  }

  @Transactional(readOnly = true)
  public List<RunSummary> listRuns(Long teamId, int limit) {
    return listRunsPage(teamId, limit, null).runs();
  }

  @Transactional(readOnly = true)
  public RunPage listRunsPage(Long teamId, int limit, Long beforeId) {
    requireManager();
    require(teamId != null, "teamId is required");
    teamService.assertCurrentUserCanAccessTeam(teamId);
    page(0, limit);
    require(beforeId == null || beforeId > 0, "beforeId must be positive");
    List<AgentReviewRun> scanned =
        runs.findByTeamIdAndIdLessThanOrderByIdDesc(
            teamId, beforeId == null ? Long.MAX_VALUE : beforeId, PageRequest.of(0, limit));
    // Advance over the bounded raw page even when every row is outside the manager's locale scope.
    // The caller can then discover older accessible runs without an unbounded server-side scan.
    Long nextBeforeId = scanned.size() == limit ? scanned.getLast().getId() : null;
    return new RunPage(
        scanned.stream().filter(this::canAccess).map(this::summary).toList(), nextBeforeId);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RunView claimRun(long runId, ClaimRequest request) {
    AgentReviewRun run = lockedRun(runId);
    require(
        request != null && request.expectedGeneration() != null, "expectedGeneration is required");
    text(request.owner(), "owner", 128, true);
    require(run.getStatus() == RunStatus.RUNNING, "Only running runs can be claimed");
    if (request.expectedGeneration() != run.getClaimGeneration())
      conflict("Claim generation changed; inspect the run before takeover");
    int seconds = request.leaseSeconds() == null ? 300 : request.leaseSeconds();
    require(seconds >= 30 && seconds <= 1800, "leaseSeconds must be between 30 and 1800");
    Long userId = teamService.getCurrentUserIdOrThrow();
    boolean active = run.getLeaseExpiresAt() != null && run.getLeaseExpiresAt().isAfter(now());
    boolean sameOwner =
        Objects.equals(run.getClaimOwner(), request.owner())
            && Objects.equals(run.getClaimedByUserId(), userId);
    if (active && !sameOwner) conflict("Run has an active coordinator lease");
    if (!active) run.setClaimGeneration(run.getClaimGeneration() + 1);
    run.setClaimOwner(request.owner());
    run.setClaimedByUserId(userId);
    run.setLeaseExpiresAt(now().plusSeconds(seconds));
    return view(run);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Artifact uploadArtifact(long runId, ArtifactRequest request) {
    AgentReviewRun run = lockedRun(runId);
    require(request != null, "Artifact request is required");
    assertClaim(run, request.claim());
    text(request.contentType(), "contentType", 128, true);
    require(
        request.contentBase64() != null
            && request.contentBase64().length() <= ((MAX_ARTIFACT_BYTES + 2) / 3) * 4,
        "Artifact exceeds the size limit");
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(request.contentBase64());
    } catch (IllegalArgumentException e) {
      throw bad("contentBase64 must be valid base64");
    }
    require(bytes.length <= MAX_ARTIFACT_BYTES, "Artifact exceeds the size limit");
    String content =
        json(new ArtifactContent(request.contentType(), Base64.getEncoder().encodeToString(bytes)));
    String hash = putContent(runId, content);
    return new Artifact(
        hash, request.contentType(), Base64.getEncoder().encodeToString(bytes), bytes.length);
  }

  @Transactional(readOnly = true)
  public Artifact readArtifact(long runId, String sha256) {
    readRun(runId);
    return artifact(runId, sha256);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RunView checkpoint(long runId, CheckpointRequest request) {
    AgentReviewRun run = lockedRun(runId);
    require(request != null, "Checkpoint request is required");
    assertClaim(run, request.claim());
    String fingerprint = fingerprintWithoutClaim(request);
    if (Objects.equals(run.getCheckpointRequestFingerprint(), fingerprint)) return view(run);
    if (run.getRevision() != request.expectedRevision())
      conflict("Run checkpoint revision changed");
    Group group = group(run, request.groupKey());
    require(request.status() != null, "Checkpoint status is required");
    require(
        request.reviewedItemCount() >= 0
            && request.reviewedItemCount() <= group.tmTextUnitIds().size(),
        "reviewedItemCount must fit the group scope");
    if (request.status() == GroupStatus.COMPLETED) {
      require(
          request.reviewedItemCount() == group.tmTextUnitIds().size(),
          "Completed groups must account for every scoped string");
    }
    text(request.note(), "note", MAX_EVIDENCE_LENGTH, false);
    if (request.status() == GroupStatus.FAILED || request.status() == GroupStatus.MISSING_INPUT)
      text(request.note(), "failure or missing-input note", MAX_EVIDENCE_LENGTH, true);
    // Read verifies that the immutable artifact has been durably written in this run namespace.
    artifact(runId, request.artifactSha256());
    Map<String, GroupCheckpoint> groups = new LinkedHashMap<>(checkpointOf(run).groups());
    GroupCheckpoint previous = groups.get(request.groupKey());
    if (previous != null && previous.status() == GroupStatus.COMPLETED)
      conflict("Completed group is immutable; start a new run to review changed inputs");
    groups.put(
        request.groupKey(),
        new GroupCheckpoint(
            request.status(),
            request.reviewedItemCount(),
            request.artifactSha256(),
            request.note()));
    run.setCheckpointSha256(putJson(runId, new Checkpoint(groups)));
    run.setCheckpointRequestFingerprint(fingerprint);
    run.setRevision(run.getRevision() + 1);
    run.setCompletedGroupCount(
        (int) groups.values().stream().filter(g -> g.status() == GroupStatus.COMPLETED).count());
    run.setFailedGroupCount(
        (int)
            groups.values().stream()
                .filter(
                    g ->
                        g.status() == GroupStatus.FAILED || g.status() == GroupStatus.MISSING_INPUT)
                .count());
    run.setReviewedItemCount(
        groups.values().stream().mapToInt(GroupCheckpoint::reviewedItemCount).sum());
    return view(run);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RunView finishRun(long runId, FinishRequest request) {
    AgentReviewRun run = lockedRun(runId);
    require(request != null, "Finish request is required");
    // A retried successful completion remains readable after its lease is released.
    if (run.getStatus() != RunStatus.RUNNING) {
      boolean sameFinish =
          run.getRevision() == request.expectedRevision() + 1
              && (request.cancel()
                  ? run.getStatus() == RunStatus.CANCELLED
                  : run.getStatus() != RunStatus.CANCELLED);
      if (!sameFinish) conflict("Run already finished differently");
      return view(run);
    }
    assertClaim(run, request.claim());
    if (run.getRevision() != request.expectedRevision())
      conflict("Run checkpoint revision changed");
    if (!request.cancel()) {
      Checkpoint checkpoint = checkpointOf(run);
      require(
          checkpoint.groups().size() == run.getPlannedGroupCount()
              && checkpoint.groups().values().stream()
                  .noneMatch(g -> g.status() == GroupStatus.IN_PROGRESS),
          "Every group needs a completed, failed, or missing-input checkpoint before finishing");
    }
    run.setStatus(
        request.cancel()
            ? RunStatus.CANCELLED
            : run.getFailedGroupCount() > 0
                ? RunStatus.COMPLETED_WITH_ERRORS
                : RunStatus.COMPLETED);
    run.setCompletedAt(now());
    run.setLeaseExpiresAt(null);
    run.setRevision(run.getRevision() + 1);
    return view(run);
  }

  /**
   * Each item commits independently; a rejected item cannot roll back earlier successful findings.
   */
  public List<SubmissionResult> submitProposals(long runId, List<SubmitProposalRequest> requests) {
    readRun(runId);
    require(
        requests != null && !requests.isEmpty() && requests.size() <= MAX_BATCH_SIZE,
        "Submit between 1 and " + MAX_BATCH_SIZE + " proposals");
    List<SubmissionResult> result = new ArrayList<>();
    for (int i = 0; i < requests.size(); i++) {
      int index = i;
      SubmitProposalRequest request = requests.get(i);
      try {
        AgentReviewProposal proposal =
            itemTransaction.execute(status -> persistProposal(runId, request));
        result.add(
            new SubmissionResult(
                index,
                request.submissionKey(),
                proposal.getId(),
                proposal.getFindingId(),
                proposal.getProposalRevision(),
                null,
                null));
      } catch (AccessDeniedException e) {
        throw e;
      } catch (ResponseStatusException e) {
        result.add(
            new SubmissionResult(
                index,
                request == null ? null : request.submissionKey(),
                null,
                null,
                null,
                e.getStatusCode().toString(),
                e.getReason()));
      } catch (RuntimeException e) {
        logger.error("Agent review submission failed for run {} item {}", runId, index, e);
        result.add(
            new SubmissionResult(
                index,
                request == null ? null : request.submissionKey(),
                null,
                null,
                null,
                "INTERNAL_ERROR",
                "Submission failed; retry this item with the same submissionKey"));
      }
    }
    return result;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public AgentReviewProposal submitProposal(long runId, SubmitProposalRequest request) {
    return persistProposal(runId, request);
  }

  private AgentReviewProposal persistProposal(long runId, SubmitProposalRequest request) {
    AgentReviewRun run = lockedRun(runId);
    require(request != null, "Proposal request is required");
    assertClaim(run, request.claim());
    text(request.submissionKey(), "submissionKey", 128, true);
    String fingerprint = fingerprintWithoutClaim(request);
    AgentReviewProposal existing =
        proposals.findByRunIdAndSubmissionKey(runId, request.submissionKey()).orElse(null);
    if (existing != null) {
      sameFingerprint(existing.getRequestFingerprint(), fingerprint);
      return existing;
    }
    Group group = group(run, request.groupKey());
    require(
        group.tmTextUnitIds().contains(request.tmTextUnitId()),
        "String is not in the frozen group scope");
    assertGroupAcceptsNewWork(run, group);
    validateProposal(request, group);
    AgentReviewProposal previous = null;
    AgentReviewFeedback responded = null;
    if (request.previousProposalId() != null) {
      previous =
          proposals
              .findForUpdateById(request.previousProposalId())
              .orElseThrow(() -> missing("Previous proposal"));
      AgentReviewRun previousRun = readRun(previous.getRunId());
      assertSameWorkflow(run, previousRun);
      require(
          Objects.equals(previous.getRepositoryId(), group.repositoryId())
              && Objects.equals(previous.getLocaleId(), group.localeId())
              && Objects.equals(previous.getTmTextUnitId(), request.tmTextUnitId()),
          "Proposal revision must retain finding identity");
      boolean unroutedDraft =
          previous.getDisposition() == Disposition.OPEN
              && previous.getReviewProjectTextUnitId() == null
              && request.respondsToFeedbackId() == null;
      if (!unroutedDraft) {
        if (previous.getDisposition() != Disposition.FOLLOW_UP)
          conflict("A reviewed finding requires pending human feedback before revision");
        responded = pendingResponse(request.respondsToFeedbackId(), previous);
      }
    } else {
      require(
          request.respondsToFeedbackId() == null, "Feedback response requires previousProposalId");
    }
    AgentReviewProposal proposal = new AgentReviewProposal();
    proposal.setRunId(runId);
    proposal.setSubmissionKey(request.submissionKey());
    proposal.setRequestFingerprint(fingerprint);
    proposal.setFindingId(
        previous == null ? UUID.randomUUID().toString() : previous.getFindingId());
    proposal.setProposalRevision(previous == null ? 1 : previous.getProposalRevision() + 1);
    proposal.setPreviousProposalId(request.previousProposalId());
    proposal.setRespondsToFeedbackId(request.respondsToFeedbackId());
    proposal.setGroupKey(group.key());
    proposal.setRepositoryId(group.repositoryId());
    proposal.setLocaleId(group.localeId());
    proposal.setTmTextUnitId(request.tmTextUnitId());
    proposal.setSource(request.source());
    proposal.setSourceComment(request.sourceComment());
    proposal.setBaselineVariantId(request.baselineVariantId());
    proposal.setBaselineTarget(request.baselineTarget());
    proposal.setBaselineStatus(request.baselineStatus());
    proposal.setBaselineIncludedInLocalizedFile(request.baselineIncludedInLocalizedFile());
    proposal.setProposedTarget(request.proposedTarget());
    proposal.setCategory(request.category());
    proposal.setReadiness(request.readiness());
    proposal.setDisposition(Disposition.OPEN);
    proposal.setRationale(request.rationale());
    proposal.setEvidenceJson(request.evidenceJson());
    proposal.setProducerIdentity(request.producerIdentity());
    proposal.setVerifierIdentity(request.verifierIdentity());
    proposal.setVerificationRationale(request.verificationRationale());
    proposal.setIntegrityDiagnostics(request.integrityDiagnostics());
    if (previous != null) {
      advanceFeedbackVersion(previous);
      previous.setDisposition(Disposition.SUPERSEDED);
      proposal.setIncidentId(previous.getIncidentId());
    }
    proposals.saveAndFlush(proposal);
    if (previous != null) {
      AgentReviewFeedback response =
          agentFeedback(
              previous,
              "revision:" + proposal.getId(),
              fingerprint,
              FeedbackAction.REVISED_PROPOSAL,
              request.producerIdentity(),
              request.rationale(),
              request.evidenceJson(),
              responded == null ? null : responded.getId());
      response.setResponseProposalId(proposal.getId());
      response.setResponseRunId(runId);
      feedback.save(response);
    }
    return proposal;
  }

  @Transactional(readOnly = true)
  public List<AgentReviewProposal> listProposals(long runId, long afterId, int limit) {
    readRun(runId);
    page(afterId, limit);
    return proposals.findByRunIdAndIdGreaterThanOrderByIdAsc(
        runId, afterId, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  public List<AgentReviewProposal> proposalHistory(long proposalId, long afterId, int limit) {
    requireManager();
    page(afterId, limit);
    AgentReviewProposal proposal =
        proposals.findById(proposalId).orElseThrow(() -> missing("Proposal"));
    readRun(proposal.getRunId());
    List<AgentReviewProposal> history =
        proposals.findByFindingIdAndIdGreaterThanOrderByProposalRevisionAsc(
            proposal.getFindingId(), afterId, PageRequest.of(0, limit));
    history.forEach(p -> readRun(p.getRunId()));
    return history;
  }

  @Transactional(readOnly = true)
  public List<AgentReviewFeedback> pendingFeedback(long runId, long afterId, int limit) {
    readRun(runId);
    page(afterId, limit);
    return feedback.findPendingByRunId(runId, afterId, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  public List<AgentReviewFeedback> feedbackHistory(long proposalId, long afterId, int limit) {
    requireManager();
    page(afterId, limit);
    AgentReviewProposal proposal =
        proposals.findById(proposalId).orElseThrow(() -> missing("Proposal"));
    readRun(proposal.getRunId());
    return feedback.findByProposalIdAndIdGreaterThanOrderByIdAsc(
        proposalId, afterId, PageRequest.of(0, limit));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public AgentReviewFeedback respondToFeedback(long runId, ResponseRequest request) {
    AgentReviewRun run = lockedRun(runId);
    require(request != null, "Response request is required");
    assertClaim(run, request.claim());
    text(request.requestKey(), "requestKey", 128, true);
    require(request.feedbackId() != null, "feedbackId is required");
    require(
        request.action() == FeedbackAction.CHALLENGE
            || request.action() == FeedbackAction.CONTEXT_REQUEST,
        "Use proposal submission for a revision, or respond with CHALLENGE or CONTEXT_REQUEST");
    text(request.actorIdentity(), "actorIdentity", 255, true);
    text(request.explanation(), "explanation", MAX_EVIDENCE_LENGTH, true);
    jsonText(request.evidenceJson(), "evidenceJson", MAX_EVIDENCE_LENGTH, false);
    AgentReviewFeedback original =
        feedback.findById(request.feedbackId()).orElseThrow(() -> missing("Feedback"));
    AgentReviewProposal proposal =
        proposals
            .findForUpdateById(original.getProposalId())
            .orElseThrow(() -> missing("Proposal"));
    AgentReviewRun originalRun = readRun(proposal.getRunId());
    assertSameWorkflow(run, originalRun);
    Group responseGroup =
        manifest(run).groups().stream()
            .filter(
                g ->
                    Objects.equals(g.repositoryId(), proposal.getRepositoryId())
                        && Objects.equals(g.localeId(), proposal.getLocaleId())
                        && g.tmTextUnitIds().contains(proposal.getTmTextUnitId()))
            .findFirst()
            .orElseThrow(() -> bad("Feedback is outside this run scope"));
    String fingerprint = fingerprintWithoutClaim(request);
    AgentReviewFeedback existing =
        feedback
            .findByProposalIdAndActorTypeAndRequestKey(
                proposal.getId(), ActorType.AGENT, request.requestKey())
            .orElse(null);
    if (existing != null) {
      sameFingerprint(existing.getRequestFingerprint(), fingerprint);
      sameActor(existing);
      return existing;
    }
    assertGroupAcceptsNewWork(run, responseGroup);
    pendingResponse(original.getId(), proposal);
    advanceFeedbackVersion(proposal);
    AgentReviewFeedback response =
        agentFeedback(
            proposal,
            request.requestKey(),
            fingerprint,
            request.action(),
            request.actorIdentity(),
            request.explanation(),
            request.evidenceJson(),
            original.getId());
    response.setResponseRunId(runId);
    // A challenge is an auditable reply, never a changed human judgment or a reopened translation.
    return feedback.save(response);
  }

  /**
   * Called by the project bridge only after its project/assignment access and guarded-save checks.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public AgentReviewFeedback appendHumanFeedback(HumanFeedbackRequest request) {
    require(userService.isCurrentUserTranslationRole(), "A translation role is required");
    require(request != null && request.proposalId() != null, "proposalId is required");
    AgentReviewProposal proposal =
        proposals.findForUpdateById(request.proposalId()).orElseThrow(() -> missing("Proposal"));
    if (!userService.isCurrentUserAdmin())
      userService.checkUserCanEditLocale(proposal.getLocaleId());
    text(request.requestKey(), "requestKey", 128, true);
    text(request.explanation(), "explanation", MAX_EVIDENCE_LENGTH, false);
    text(request.finalTarget(), "finalTarget", MAX_TEXT_LENGTH, false);
    text(request.contextFingerprint(), "contextFingerprint", 64, true);
    require(
        request.contextFingerprint().matches("[a-f0-9]{64}"),
        "contextFingerprint must be a SHA-256 hash");
    String fingerprint = fingerprint(request);
    AgentReviewFeedback existing =
        feedback
            .findByProposalIdAndActorTypeAndRequestKey(
                proposal.getId(), ActorType.HUMAN, request.requestKey())
            .orElse(null);
    if (existing != null) {
      sameFingerprint(existing.getRequestFingerprint(), fingerprint);
      sameActor(existing);
      return existing;
    }
    if (proposal.getVersion() != request.expectedVersion())
      conflict("Proposal changed; reload before deciding");
    if (proposal.getDisposition() == Disposition.SUPERSEDED
        || proposal.getDisposition() == Disposition.RESOLVED)
      conflict("Proposal is already resolved or superseded");
    require(
        request.action() == FeedbackAction.ACCEPT
            || request.action() == FeedbackAction.EDIT_ACCEPT
            || request.action() == FeedbackAction.KEEP_CURRENT
            || request.action() == FeedbackAction.DEFER
            || request.action() == FeedbackAction.REQUEST_REVISION,
        "A human review action is required");
    boolean applies =
        request.action() == FeedbackAction.ACCEPT || request.action() == FeedbackAction.EDIT_ACCEPT;
    require(
        !applies || (request.finalTarget() != null && request.appliedVariantId() != null),
        "Accepted feedback requires the exact applied target and variant");
    require(
        applies || request.appliedVariantId() == null,
        "Non-applying decisions must not claim an applied variant");
    boolean followUp =
        request.followUpRequested() || request.action() == FeedbackAction.REQUEST_REVISION;
    require(
        !applies || !followUp,
        "Acceptance resolves this proposal; follow-up requires a separate finding");
    advanceFeedbackVersion(proposal);
    AgentReviewFeedback entry = new AgentReviewFeedback();
    entry.setProposalId(proposal.getId());
    entry.setRequestKey(request.requestKey());
    entry.setRequestFingerprint(fingerprint);
    entry.setActorType(ActorType.HUMAN);
    entry.setActorUserId(teamService.getCurrentUserIdOrThrow());
    entry.setActorIdentity(currentUsername());
    entry.setAction(request.action());
    entry.setOriginalAssessment(request.originalAssessment());
    entry.setSuggestionAssessment(request.suggestionAssessment());
    entry.setExplanation(request.explanation());
    entry.setFollowUpRequested(followUp);
    entry.setFinalTarget(request.finalTarget());
    entry.setAppliedVariantId(request.appliedVariantId());
    proposal.setDisposition(
        followUp
            ? Disposition.FOLLOW_UP
            : request.action() == FeedbackAction.DEFER ? Disposition.ROUTED : Disposition.RESOLVED);
    return feedback.save(entry);
  }

  /**
   * The routing bridge holds the run lock before proposal locks, serializing membership decisions.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public List<AgentReviewProposal> findReadyProposalsForUpdate(long runId) {
    lockedRun(runId);
    return proposals.findReadyForUpdateByRunId(runId);
  }

  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public void linkProposal(long proposalId, Long incidentId, Long projectId, Long rowId) {
    requireManager();
    AgentReviewProposal proposal =
        proposals.findForUpdateById(proposalId).orElseThrow(() -> missing("Proposal"));
    readRun(proposal.getRunId());
    require(
        incidentId != null && projectId != null && rowId != null,
        "Routing requires incident, project, and project-row identities");
    if (proposal.getDisposition() == Disposition.ROUTED) {
      if (!Objects.equals(proposal.getIncidentId(), incidentId)
          || !Objects.equals(proposal.getReviewProjectId(), projectId)
          || !Objects.equals(proposal.getReviewProjectTextUnitId(), rowId))
        conflict("Proposal is already routed elsewhere");
      return;
    }
    if (proposal.getDisposition() != Disposition.OPEN || proposal.getReadiness() != Readiness.READY)
      conflict("Only open verified proposals can be routed");
    if (proposal.getIncidentId() != null && !Objects.equals(proposal.getIncidentId(), incidentId))
      conflict("Finding must retain its existing incident identity");
    proposal.setIncidentId(incidentId);
    proposal.setReviewProjectId(projectId);
    proposal.setReviewProjectTextUnitId(rowId);
    proposal.setDisposition(Disposition.ROUTED);
  }

  private void validateGroups(List<Group> groups) {
    require(
        groups != null && !groups.isEmpty() && groups.size() <= MAX_GROUPS,
        "Run requires 1 to " + MAX_GROUPS + " groups");
    Set<String> keys = new LinkedHashSet<>();
    Set<String> identities = new LinkedHashSet<>();
    Map<Long, Repository> repositoryCache = new LinkedHashMap<>();
    Set<String> localePairs = new LinkedHashSet<>();
    for (Group group : groups) {
      require(group != null, "Group cannot be null");
      text(group.key(), "group key", 128, true);
      text(group.featureGroup(), "featureGroup", 255, true);
      text(group.inputFingerprint(), "inputFingerprint", 128, true);
      require(keys.add(group.key()), "Group keys must be unique");
      require(
          group.repositoryId() != null && group.localeId() != null,
          "Group repositoryId and localeId are required");
      Repository repository =
          repositoryCache.computeIfAbsent(
              group.repositoryId(),
              id -> repositories.findById(id).orElseThrow(() -> bad("Repository does not exist")));
      require(
          !Boolean.TRUE.equals(repository.getDeleted()),
          "Deleted repository is outside review scope");
      if (localePairs.add(group.repositoryId() + ":" + group.localeId())) {
        require(
            repositoryLocales.findByRepositoryIdAndLocaleId(group.repositoryId(), group.localeId())
                != null,
            "Locale is not configured for the repository");
        if (!userService.isCurrentUserAdmin()) userService.checkUserCanEditLocale(group.localeId());
      }
      require(
          group.tmTextUnitIds() != null
              && !group.tmTextUnitIds().isEmpty()
              && group.tmTextUnitIds().size() <= MAX_SCOPE_ITEMS,
          "Each group requires a bounded nonempty list of strings");
      for (Long id : group.tmTextUnitIds()) {
        require(
            id != null
                && id > 0
                && identities.add(group.repositoryId() + ":" + group.localeId() + ":" + id),
            "Strings must occur only once per repository/locale scope");
      }
      require(
          identities.size() <= MAX_SCOPE_ITEMS,
          "Run scope exceeds " + MAX_SCOPE_ITEMS + " string/locale pairs");
      List<TMTextUnit> units = textUnits.findAllById(group.tmTextUnitIds());
      require(
          units.size() == group.tmTextUnitIds().size()
              && units.stream()
                  .allMatch(
                      u ->
                          Objects.equals(
                              u.getAsset().getRepository().getId(), group.repositoryId())),
          "Every scoped string must belong to its declared repository");
    }
  }

  private void validateProposal(SubmitProposalRequest request, Group group) {
    text(request.source(), "source", MAX_TEXT_LENGTH, true);
    text(request.sourceComment(), "sourceComment", MAX_TEXT_LENGTH, false);
    text(request.baselineTarget(), "baselineTarget", MAX_TEXT_LENGTH, false);
    text(request.baselineStatus(), "baselineStatus", 32, false);
    text(request.proposedTarget(), "proposedTarget", MAX_TEXT_LENGTH, false);
    text(request.rationale(), "rationale", MAX_EVIDENCE_LENGTH, true);
    text(request.producerIdentity(), "producerIdentity", 255, true);
    text(request.verifierIdentity(), "verifierIdentity", 255, false);
    text(request.verificationRationale(), "verificationRationale", MAX_EVIDENCE_LENGTH, false);
    text(request.integrityDiagnostics(), "integrityDiagnostics", MAX_EVIDENCE_LENGTH, false);
    jsonText(request.evidenceJson(), "evidenceJson", MAX_EVIDENCE_LENGTH, false);
    require(
        request.category() != null && request.readiness() != null,
        "category and readiness are required");
    if (request.readiness() == Readiness.READY) {
      require(
          request.category() != Category.OPTIONAL_IMPROVEMENT,
          "Optional improvements remain outside automatic routing");
      text(request.verifierIdentity(), "verifierIdentity", 255, true);
      text(request.verificationRationale(), "verificationRationale", MAX_EVIDENCE_LENGTH, true);
      require(
          !request.producerIdentity().equals(request.verifierIdentity()),
          "A separate verifier identity is required");
    }
    TMTextUnit unit =
        textUnits.findById(request.tmTextUnitId()).orElseThrow(() -> bad("String does not exist"));
    require(
        Objects.equals(unit.getAsset().getRepository().getId(), group.repositoryId()),
        "String repository changed");
    require(
        Objects.equals(unit.getContent(), request.source())
            && Objects.equals(unit.getComment(), request.sourceComment()),
        "Reviewed source and comment must match the exact string identity");
    if (request.baselineVariantId() == null) {
      require(
          request.baselineTarget() == null
              && request.baselineStatus() == null
              && request.baselineIncludedInLocalizedFile() == null,
          "Missing baseline variant must have no baseline target/status/inclusion");
    } else {
      TMTextUnitVariant variant =
          variants
              .findById(request.baselineVariantId())
              .orElseThrow(() -> bad("Baseline variant does not exist"));
      require(
          Objects.equals(variant.getTmTextUnit().getId(), request.tmTextUnitId())
              && Objects.equals(variant.getLocale().getId(), group.localeId()),
          "Baseline variant belongs to a different string or locale");
      require(
          Objects.equals(variant.getContent(), request.baselineTarget())
              && Objects.equals(
                  variant.getStatus() == null ? null : variant.getStatus().name(),
                  request.baselineStatus())
              && Objects.equals(
                  variant.isIncludedInLocalizedFile(), request.baselineIncludedInLocalizedFile()),
          "Baseline target/status/inclusion must match the exact reviewed variant");
    }
    // Deliberately do not run integrity validation here: invalid suggestions are retained as
    // evidence.
    // Human application uses Mojito's guarded correction path and its mandatory integrity
    // validation.
  }

  private AgentReviewFeedback pendingResponse(Long feedbackId, AgentReviewProposal proposal) {
    require(feedbackId != null, "respondsToFeedbackId is required");
    AgentReviewFeedback original =
        feedback.findById(feedbackId).orElseThrow(() -> missing("Feedback"));
    require(
        Objects.equals(original.getProposalId(), proposal.getId())
            && original.getActorType() == ActorType.HUMAN
            && original.getFollowUpRequested(),
        "Response requires pending human feedback on this proposal revision");
    if (proposal.getDisposition() != Disposition.FOLLOW_UP)
      conflict("Finding is no longer awaiting agent follow-up");
    AgentReviewFeedback latest =
        feedback.findFirstByProposalIdOrderByIdDesc(proposal.getId()).orElse(original);
    if (!Objects.equals(latest.getId(), original.getId()))
      conflict("Human feedback changed; read the latest feedback before responding");
    if (feedback.findByRespondsToFeedbackId(feedbackId).isPresent())
      conflict("This feedback round already has an agent response");
    return original;
  }

  /** Every new judgment/reply changes the concurrency token, even if disposition stays the same. */
  private void advanceFeedbackVersion(AgentReviewProposal proposal) {
    entityManager.lock(proposal, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
  }

  private AgentReviewFeedback agentFeedback(
      AgentReviewProposal proposal,
      String key,
      String fingerprint,
      FeedbackAction action,
      String identity,
      String explanation,
      String evidence,
      Long respondsTo) {
    AgentReviewFeedback result = new AgentReviewFeedback();
    result.setProposalId(proposal.getId());
    result.setRequestKey(key);
    result.setRequestFingerprint(fingerprint);
    result.setActorType(ActorType.AGENT);
    result.setActorUserId(teamService.getCurrentUserIdOrThrow());
    result.setActorIdentity(identity);
    result.setAction(action);
    result.setExplanation(explanation);
    result.setEvidenceJson(evidence);
    result.setRespondsToFeedbackId(respondsTo);
    return result;
  }

  private AgentReviewRun readRun(long id) {
    requireManager();
    AgentReviewRun run = runs.findById(id).orElseThrow(() -> missing("Run"));
    authorize(run);
    return run;
  }

  private AgentReviewRun lockedRun(long id) {
    requireManager();
    AgentReviewRun run = runs.findForUpdateById(id).orElseThrow(() -> missing("Run"));
    authorize(run);
    return run;
  }

  private void authorize(AgentReviewRun run) {
    requireManager();
    teamService.assertCurrentUserCanAccessTeam(run.getTeamId());
    if (!userService.isCurrentUserAdmin())
      ids(run.getLocaleIdsJson()).forEach(userService::checkUserCanEditLocale);
  }

  private boolean canAccess(AgentReviewRun run) {
    try {
      authorize(run);
      return true;
    } catch (AccessDeniedException e) {
      return false;
    }
  }

  private void assertSameWorkflow(AgentReviewRun response, AgentReviewRun original) {
    require(
        Objects.equals(response.getTeamId(), original.getTeamId())
            && Objects.equals(response.getReviewType(), original.getReviewType()),
        "Follow-up runs must retain the original finding team and reviewType");
  }

  private void requireManager() {
    if (!userService.isCurrentUserAdminOrPm())
      throw new AccessDeniedException("Agent review execution requires a PM or administrator");
    teamService.getCurrentUserIdOrThrow();
  }

  private void assertClaim(AgentReviewRun run, Claim claim) {
    require(claim != null, "Coordinator claim is required");
    if (run.getStatus() != RunStatus.RUNNING
        || !Objects.equals(run.getClaimOwner(), claim.owner())
        || run.getClaimGeneration() != claim.generation()
        || !Objects.equals(run.getClaimedByUserId(), teamService.getCurrentUserIdOrThrow())
        || run.getLeaseExpiresAt() == null
        || !run.getLeaseExpiresAt().isAfter(now())) {
      conflict("Coordinator lease expired or changed; claim the run before writing");
    }
  }

  private Group group(AgentReviewRun run, String key) {
    return manifest(run).groups().stream()
        .filter(g -> Objects.equals(g.key(), key))
        .findFirst()
        .orElseThrow(() -> bad("Group is not in the immutable run manifest"));
  }

  private RunManifest manifest(AgentReviewRun run) {
    return readJson(run.getId(), run.getManifestSha256(), RunManifest.class);
  }

  private void assertGroupAcceptsNewWork(AgentReviewRun run, Group group) {
    GroupCheckpoint checkpoint = checkpointOf(run).groups().get(group.key());
    if (checkpoint != null && checkpoint.status() == GroupStatus.COMPLETED) {
      conflict("Completed group is immutable; use an unfinished group in a new run for new work");
    }
  }

  private Checkpoint checkpointOf(AgentReviewRun run) {
    return run.getCheckpointSha256() == null
        ? new Checkpoint(Map.of())
        : readJson(run.getId(), run.getCheckpointSha256(), Checkpoint.class);
  }

  private RunSummary summary(AgentReviewRun run) {
    return new RunSummary(
        run.getId(),
        run.getReviewType(),
        run.getTeamId(),
        ids(run.getRepositoryIdsJson()),
        ids(run.getLocaleIdsJson()),
        run.getMethodVersion(),
        run.getConfigurationVersion(),
        run.getInputFingerprint(),
        run.getManifestSha256(),
        run.getStatus(),
        run.getRevision(),
        run.getClaimOwner(),
        run.getClaimGeneration(),
        run.getLeaseExpiresAt(),
        run.getPlannedGroupCount(),
        run.getCompletedGroupCount(),
        run.getFailedGroupCount(),
        run.getReviewedItemCount(),
        run.getCheckpointSha256(),
        run.getDueDateOffsetDays(),
        run.getMaxWordCountPerProject(),
        run.getAssignTranslator(),
        run.getCreatedDate(),
        run.getCompletedAt());
  }

  private RunView view(AgentReviewRun run) {
    return new RunView(
        run.getId(),
        run.getReviewType(),
        run.getTeamId(),
        ids(run.getRepositoryIdsJson()),
        ids(run.getLocaleIdsJson()),
        run.getMethodVersion(),
        run.getConfigurationVersion(),
        run.getInputFingerprint(),
        run.getManifestSha256(),
        run.getStatus(),
        run.getRevision(),
        run.getClaimOwner(),
        run.getClaimGeneration(),
        run.getLeaseExpiresAt(),
        run.getPlannedGroupCount(),
        run.getCompletedGroupCount(),
        run.getFailedGroupCount(),
        run.getReviewedItemCount(),
        run.getCheckpointSha256(),
        checkpointOf(run),
        run.getDueDateOffsetDays(),
        run.getMaxWordCountPerProject(),
        run.getAssignTranslator(),
        run.getCreatedDate(),
        run.getCompletedAt());
  }

  private record ArtifactContent(String contentType, String contentBase64) {}

  private String putJson(long runId, Object value) {
    byte[] bytes = json(value).getBytes(StandardCharsets.UTF_8);
    require(bytes.length <= MAX_ARTIFACT_BYTES, "Structured artifact exceeds the size limit");
    return putContent(
        runId,
        json(new ArtifactContent("application/json", Base64.getEncoder().encodeToString(bytes))));
  }

  private String putContent(long runId, String content) {
    String hash = sha256(content);
    blobs.put(
        StructuredBlobStorage.Prefix.AGENT_REVIEW,
        blobName(runId, hash),
        content,
        Retention.PERMANENT);
    return hash;
  }

  /**
   * Trusted project bridge only: the caller must authorize the exact proposal and artifact link.
   */
  Artifact readStoredArtifact(long runId, String sha256) {
    return artifact(runId, sha256);
  }

  private Artifact artifact(long runId, String hash) {
    require(hash != null && hash.matches("[a-f0-9]{64}"), "Artifact SHA-256 is required");
    String content =
        blobs
            .getString(StructuredBlobStorage.Prefix.AGENT_REVIEW, blobName(runId, hash))
            .orElseThrow(() -> missing("Run artifact"));
    if (!sha256(content).equals(hash))
      throw new IllegalStateException("Stored review artifact checksum mismatch");
    ArtifactContent parsed = parse(content, ArtifactContent.class);
    byte[] bytes = Base64.getDecoder().decode(parsed.contentBase64());
    return new Artifact(hash, parsed.contentType(), parsed.contentBase64(), bytes.length);
  }

  private <T> T readJson(long runId, String hash, Class<T> type) {
    Artifact artifact = artifact(runId, hash);
    return parse(
        new String(Base64.getDecoder().decode(artifact.contentBase64()), StandardCharsets.UTF_8),
        type);
  }

  private String blobName(long runId, String hash) {
    return "runs/" + runId + "/artifacts/" + hash;
  }

  private String fingerprint(Object value) {
    return sha256(json(value));
  }

  private String fingerprintWithoutClaim(Object value) {
    var tree = mapper.valueToTree(value);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("claim");
    return sha256(json(tree));
  }

  private String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Cannot serialize review data", e);
    }
  }

  private <T> T parse(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored review data", e);
    }
  }

  private List<Long> ids(String value) {
    try {
      return mapper.readValue(value, new TypeReference<List<Long>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored run scope", e);
    }
  }

  private void jsonText(String value, String name, int maxLength, boolean required) {
    text(value, name, maxLength, required);
    if (value != null) {
      try {
        require(mapper.readTree(value) != null, name + " must contain JSON");
      } catch (JsonProcessingException e) {
        throw bad(name + " must be valid JSON");
      }
    }
  }

  private void text(String value, String name, int maxLength, boolean required) {
    require(!required || value != null && !value.isBlank(), name + " is required");
    require(
        value == null || value.length() <= maxLength,
        name + " exceeds " + maxLength + " characters");
  }

  private void sameFingerprint(String expected, String actual) {
    if (!Objects.equals(expected, actual))
      conflict("Idempotency key was already used with different content");
  }

  private void sameActor(AgentReviewFeedback value) {
    if (!Objects.equals(value.getActorUserId(), teamService.getCurrentUserIdOrThrow()))
      throw new AccessDeniedException("Feedback retry belongs to another actor");
  }

  private String currentUsername() {
    return userService
        .getCurrentUser()
        .map(User::getUsername)
        .orElseThrow(() -> new AccessDeniedException("Authenticated user is required"));
  }

  private ZonedDateTime now() {
    return ZonedDateTime.now(clock);
  }

  private void page(long afterId, int limit) {
    require(
        afterId >= 0 && limit >= 1 && limit <= 200,
        "afterId must be nonnegative and limit between 1 and 200");
  }

  private void require(boolean condition, String message) {
    if (!condition) throw bad(message);
  }

  private ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException missing(String name) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, name + " not found");
  }

  private void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
