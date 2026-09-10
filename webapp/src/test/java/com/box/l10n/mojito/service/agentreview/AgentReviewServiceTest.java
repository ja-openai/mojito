package com.box.l10n.mojito.service.agentreview;

import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class AgentReviewServiceTest {
  private final AgentReviewRunRepository runs = mock(AgentReviewRunRepository.class);
  private final AgentReviewProposalRepository proposals = mock(AgentReviewProposalRepository.class);
  private final AgentReviewFeedbackRepository feedback = mock(AgentReviewFeedbackRepository.class);
  private final RepositoryRepository repositories = mock(RepositoryRepository.class);
  private final RepositoryLocaleRepository repositoryLocales =
      mock(RepositoryLocaleRepository.class);
  private final TMTextUnitRepository textUnits = mock(TMTextUnitRepository.class);
  private final TMTextUnitVariantRepository variants = mock(TMTextUnitVariantRepository.class);
  private final TeamRepository teams = mock(TeamRepository.class);
  private final TeamService teamService = mock(TeamService.class);
  private final UserService userService = mock(UserService.class);
  private final StructuredBlobStorage blobs = mock(StructuredBlobStorage.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final Map<String, String> storedBlobs = new HashMap<>();
  private final Map<Long, AgentReviewRun> storedRuns = new HashMap<>();
  private final Map<Long, AgentReviewProposal> storedProposals = new HashMap<>();
  private final Map<Long, AgentReviewFeedback> storedFeedback = new HashMap<>();
  private final AtomicLong proposalIds = new AtomicLong(100);
  private final AtomicLong feedbackIds = new AtomicLong(200);
  private AgentReviewService service;
  private RunView run;
  private Claim claim;
  private Clock clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);

  @Before
  public void setup() {
    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    when(userService.isCurrentUserTranslationRole()).thenReturn(true);
    when(teamService.getCurrentUserIdOrThrow()).thenReturn(4L);
    User user = new User();
    user.setId(4L);
    user.setUsername("review-manager");
    when(userService.getCurrentUser()).thenReturn(Optional.of(user));
    when(teams.existsById(5L)).thenReturn(true);
    Repository repository = new Repository();
    repository.setId(2L);
    repository.setDeleted(false);
    when(repositories.findById(2L)).thenReturn(Optional.of(repository));
    when(repositoryLocales.findByRepositoryIdAndLocaleId(2L, 3L))
        .thenReturn(new RepositoryLocale());
    Asset asset = new Asset();
    asset.setRepository(repository);
    TMTextUnit unit = new TMTextUnit();
    unit.setId(6L);
    unit.setAsset(asset);
    unit.setContent("Save");
    unit.setComment("Button");
    when(textUnits.findById(6L)).thenReturn(Optional.of(unit));
    when(textUnits.findAllById(List.of(6L))).thenReturn(List.of(unit));
    Locale locale = new Locale();
    locale.setId(3L);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setId(7L);
    variant.setTmTextUnit(unit);
    variant.setLocale(locale);
    variant.setContent("Ancien");
    variant.setStatus(TMTextUnitVariant.Status.APPROVED);
    variant.setIncludedInLocalizedFile(true);
    when(variants.findById(7L)).thenReturn(Optional.of(variant));
    when(runs.findByRequestedByUserIdAndRequestKey(anyLong(), anyString()))
        .thenAnswer(
            i ->
                storedRuns.values().stream()
                    .filter(
                        r ->
                            r.getRequestedByUserId().equals(i.getArgument(0))
                                && r.getRequestKey().equals(i.getArgument(1)))
                    .findFirst());
    when(runs.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              AgentReviewRun r = i.getArgument(0);
              r.setId((long) storedRuns.size() + 1);
              storedRuns.put(r.getId(), r);
              return r;
            });
    when(runs.findById(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(storedRuns.get(i.getArgument(0))));
    when(runs.findForUpdateById(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(storedRuns.get(i.getArgument(0))));
    doAnswer(
            i -> {
              storedBlobs.put(i.getArgument(1), i.getArgument(2));
              return null;
            })
        .when(blobs)
        .put(
            eq(StructuredBlobStorage.Prefix.AGENT_REVIEW),
            anyString(),
            anyString(),
            eq(Retention.PERMANENT));
    when(blobs.getString(eq(StructuredBlobStorage.Prefix.AGENT_REVIEW), anyString()))
        .thenAnswer(i -> Optional.ofNullable(storedBlobs.get(i.getArgument(1))));
    when(proposals.findByRunIdAndSubmissionKey(anyLong(), anyString()))
        .thenAnswer(
            i ->
                storedProposals.values().stream()
                    .filter(
                        p ->
                            p.getRunId().equals(i.getArgument(0))
                                && p.getSubmissionKey().equals(i.getArgument(1)))
                    .findFirst());
    when(proposals.saveAndFlush(any()))
        .thenAnswer(
            i -> {
              AgentReviewProposal p = i.getArgument(0);
              p.setId(proposalIds.incrementAndGet());
              storedProposals.put(p.getId(), p);
              return p;
            });
    when(proposals.findById(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(storedProposals.get(i.getArgument(0))));
    when(proposals.findForUpdateById(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(storedProposals.get(i.getArgument(0))));
    when(feedback.save(any()))
        .thenAnswer(
            i -> {
              AgentReviewFeedback f = i.getArgument(0);
              f.setId(feedbackIds.incrementAndGet());
              storedFeedback.put(f.getId(), f);
              return f;
            });
    when(feedback.findFirstByProposalIdOrderByIdDesc(anyLong()))
        .thenAnswer(
            i ->
                storedFeedback.values().stream()
                    .filter(f -> f.getProposalId().equals(i.getArgument(0)))
                    .max(Comparator.comparing(AgentReviewFeedback::getId)));
    when(feedback.findById(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(storedFeedback.get(i.getArgument(0))));
    when(feedback.findByProposalIdAndActorTypeAndRequestKey(anyLong(), any(), anyString()))
        .thenAnswer(
            i ->
                storedFeedback.values().stream()
                    .filter(
                        f ->
                            f.getProposalId().equals(i.getArgument(0))
                                && f.getActorType().equals(i.getArgument(1))
                                && f.getRequestKey().equals(i.getArgument(2)))
                    .findFirst());
    when(feedback.findByRespondsToFeedbackId(anyLong()))
        .thenAnswer(
            i ->
                storedFeedback.values().stream()
                    .filter(f -> Objects.equals(f.getRespondsToFeedbackId(), i.getArgument(0)))
                    .findFirst());
    doAnswer(
            i -> {
              AgentReviewProposal p = i.getArgument(0);
              p.setVersion(p.getVersion() + 1);
              return null;
            })
        .when(entityManager)
        .lock(
            any(AgentReviewProposal.class),
            eq(jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service =
        new AgentReviewService(
            runs,
            proposals,
            feedback,
            repositories,
            repositoryLocales,
            textUnits,
            variants,
            teams,
            teamService,
            userService,
            blobs,
            new ObjectMapper().findAndRegisterModules(),
            entityManager,
            transactions);
    ReflectionTestUtils.setField(service, "clock", clock);
    run = service.createRun(create("run-1", "v1"));
    RunView claimed = service.claimRun(run.id(), new ClaimRequest("laptop-session", 0L, 300));
    claim = new Claim(claimed.claimOwner(), claimed.claimGeneration());
  }

  @Test
  public void createRetryReturnsSameFrozenRunAndRejectsDifferentScope() {
    assertEquals(run.id(), service.createRun(create("run-1", "v1")).id());
    assertEquals(1, storedRuns.size());
    assertConflict(() -> service.createRun(create("run-1", "v2")));
    verify(entityManager, times(3))
        .find(eq(User.class), eq(4L), eq(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
  }

  @Test
  public void createRetryRetainsFrozenRunAfterLiveScopeRemoval() {
    when(textUnits.findAllById(List.of(6L))).thenReturn(List.of());
    clearInvocations(textUnits, repositories, repositoryLocales);

    assertEquals(run.id(), service.createRun(create("run-1", "v1")).id());
    verifyNoInteractions(textUnits, repositories, repositoryLocales);
    assertConflict(() -> service.createRun(create("run-1", "v2")));
    assertBad(() -> service.createRun(create("new", "v1")));
    assertEquals(1, storedRuns.size());

    doThrow(new AccessDeniedException("locale")).when(userService).checkUserCanEditLocale(3L);
    assertThrows(AccessDeniedException.class, () -> service.createRun(create("run-1", "v1")));
  }

  @Test
  public void createRejectsUnknownRepositoryLocaleAndRepeatedScope() {
    Group group = group();
    CreateRunRequest repeated =
        new CreateRunRequest(
            "duplicate",
            "TRANSLATION_QUALITY",
            5L,
            "v1",
            "v1",
            List.of(group, new Group("other", 2L, 3L, "other", List.of(6L), "input")),
            "{}",
            null,
            null,
            null);
    assertBad(() -> service.createRun(repeated));
    when(repositoryLocales.findByRepositoryIdAndLocaleId(2L, 3L)).thenReturn(null);
    assertBad(() -> service.createRun(create("new", "v1")));
  }

  @Test
  public void allExecutionReadsAndWritesEnforceRoleAndTeamAndLocale() {
    when(userService.isCurrentUserAdminOrPm()).thenReturn(false);
    assertThrows(AccessDeniedException.class, () -> service.getRun(run.id()));
    assertThrows(
        AccessDeniedException.class, () -> service.submitProposal(run.id(), proposal("a")));
    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    doThrow(new AccessDeniedException("team")).when(teamService).assertCurrentUserCanAccessTeam(5L);
    assertThrows(AccessDeniedException.class, () -> service.getRun(run.id()));
    doNothing().when(teamService).assertCurrentUserCanAccessTeam(5L);
    doThrow(new AccessDeniedException("locale")).when(userService).checkUserCanEditLocale(3L);
    assertThrows(
        AccessDeniedException.class, () -> service.readArtifact(run.id(), run.manifestSha256()));
  }

  @Test
  public void runDiscoveryCursorAdvancesPastInaccessibleLocales() {
    AgentReviewRun hidden100 = new AgentReviewRun();
    hidden100.setId(100L);
    hidden100.setTeamId(5L);
    hidden100.setLocaleIdsJson("[99]");
    AgentReviewRun hidden99 = new AgentReviewRun();
    hidden99.setId(99L);
    hidden99.setTeamId(5L);
    hidden99.setLocaleIdsJson("[99]");
    doThrow(new AccessDeniedException("locale")).when(userService).checkUserCanEditLocale(99L);
    when(runs.findByTeamIdAndIdLessThanOrderByIdDesc(eq(5L), eq(Long.MAX_VALUE), any()))
        .thenReturn(List.of(hidden100, hidden99));
    when(runs.findByTeamIdAndIdLessThanOrderByIdDesc(eq(5L), eq(99L), any()))
        .thenReturn(List.of(storedRuns.get(run.id())));
    RunPage first = service.listRunsPage(5L, 2, null);
    assertTrue(first.runs().isEmpty());
    assertEquals(Long.valueOf(99), first.nextBeforeId());
    RunPage second = service.listRunsPage(5L, 2, first.nextBeforeId());
    assertEquals(List.of(run.id()), second.runs().stream().map(RunSummary::id).toList());
    assertNull(second.nextBeforeId());
  }

  @Test
  public void runDiscoveryDoesNotReadHistoricalCheckpointBlobs() {
    AgentReviewRun stored = storedRuns.get(run.id());
    String checkpointHash = "a".repeat(64);
    stored.setCheckpointSha256(checkpointHash);
    stored.setCompletedGroupCount(1);
    stored.setReviewedItemCount(1);
    when(runs.findByTeamIdAndIdLessThanOrderByIdDesc(eq(5L), eq(Long.MAX_VALUE), any()))
        .thenReturn(List.of(stored));
    clearInvocations(blobs);

    RunSummary summary = service.listRunsPage(5L, 50, null).runs().getFirst();

    assertEquals(run.id(), summary.id());
    assertEquals(checkpointHash, summary.checkpointSha256());
    assertEquals(1, summary.completedGroupCount());
    assertEquals(1, summary.reviewedItemCount());
    verifyNoInteractions(blobs);
  }

  @Test
  public void coordinatorTakeoverFencesOldWorkerAndAllowsExactRetryWithNewClaim() {
    AgentReviewProposal first = service.submitProposal(run.id(), proposal("a"));
    assertConflict(() -> service.claimRun(run.id(), new ClaimRequest("devbox", 1L, 300)));
    ReflectionTestUtils.setField(
        service, "clock", Clock.offset(clock, java.time.Duration.ofMinutes(6)));
    RunView takeover = service.claimRun(run.id(), new ClaimRequest("devbox", 1L, 300));
    assertEquals(2, takeover.claimGeneration());
    assertConflict(() -> service.submitProposal(run.id(), proposal("b")));
    claim = new Claim("devbox", 2);
    assertEquals(first.getId(), service.submitProposal(run.id(), proposal("a")).getId());
    assertEquals(1, storedProposals.size());
  }

  @Test
  public void claimRenewalRequiresSameActorAndGeneration() {
    assertEquals(
        1,
        service.claimRun(run.id(), new ClaimRequest("laptop-session", 1L, 600)).claimGeneration());
    assertConflict(() -> service.claimRun(run.id(), new ClaimRequest("laptop-session", 0L, 600)));
    when(teamService.getCurrentUserIdOrThrow()).thenReturn(99L);
    assertConflict(() -> service.claimRun(run.id(), new ClaimRequest("laptop-session", 1L, 600)));
    assertConflict(() -> service.submitProposal(run.id(), proposal("a")));
  }

  @Test
  public void noFindingsCoverageSurvivesCheckpointAndCompletion() {
    String artifact = upload("{\"reviewed\":[6],\"findings\":[]}");
    CheckpointRequest checkpoint =
        new CheckpointRequest(
            claim, 0, "fr/settings", GroupStatus.COMPLETED, 1, artifact, "No issues found");
    RunView saved = service.checkpoint(run.id(), checkpoint);
    assertEquals(1, saved.completedGroupCount());
    assertEquals(1, saved.reviewedItemCount());
    assertEquals(
        saved.checkpointSha256(), service.checkpoint(run.id(), checkpoint).checkpointSha256());
    assertEquals(1, service.checkpoint(run.id(), checkpoint).revision());
    assertEquals(
        RunStatus.COMPLETED,
        service.finishRun(run.id(), new FinishRequest(claim, 1, false)).status());
    assertEquals(
        RunStatus.COMPLETED,
        service.finishRun(run.id(), new FinishRequest(claim, 1, false)).status());
    assertTrue(storedProposals.isEmpty());
    verify(blobs, atLeastOnce())
        .put(
            eq(StructuredBlobStorage.Prefix.AGENT_REVIEW),
            anyString(),
            anyString(),
            eq(Retention.PERMANENT));
    verifyNoInteractions(variants);
  }

  @Test
  public void checkpointCannotClaimMissingOrFailedInputsPassed() {
    String artifact = upload("{}");
    assertBad(
        () ->
            service.checkpoint(
                run.id(),
                new CheckpointRequest(
                    claim, 0, "fr/settings", GroupStatus.COMPLETED, 0, artifact, null)));
    assertBad(() -> service.finishRun(run.id(), new FinishRequest(claim, 0, false)));
    RunView saved =
        service.checkpoint(
            run.id(),
            new CheckpointRequest(
                claim,
                0,
                "fr/settings",
                GroupStatus.MISSING_INPUT,
                0,
                artifact,
                "Screenshot unavailable"));
    assertEquals(0, saved.completedGroupCount());
    assertEquals(1, saved.failedGroupCount());
    assertEquals(
        RunStatus.COMPLETED_WITH_ERRORS,
        service.finishRun(run.id(), new FinishRequest(claim, 1, false)).status());
  }

  @Test
  public void checkpointRejectsUnpublishedForeignAndTamperedArtifactsWithoutAdvancing() {
    assertThrows(
        ResponseStatusException.class,
        () ->
            service.checkpoint(
                run.id(),
                new CheckpointRequest(
                    claim, 0, "fr/settings", GroupStatus.COMPLETED, 1, "a".repeat(64), null)));
    assertEquals(0, service.getRun(run.id()).revision());
    String hash = upload("{}");
    storedBlobs.put("runs/" + run.id() + "/artifacts/" + hash, "tampered");
    assertThrows(
        IllegalStateException.class,
        () ->
            service.checkpoint(
                run.id(),
                new CheckpointRequest(
                    claim, 0, "fr/settings", GroupStatus.COMPLETED, 1, hash, null)));
    assertEquals(0, service.getRun(run.id()).revision());
  }

  @Test
  public void checkpointCasAndCompletedGroupAreImmutable() {
    String artifact = upload("{}");
    service.checkpoint(
        run.id(),
        new CheckpointRequest(claim, 0, "fr/settings", GroupStatus.COMPLETED, 1, artifact, null));
    assertConflict(
        () ->
            service.checkpoint(
                run.id(),
                new CheckpointRequest(
                    claim, 0, "fr/settings", GroupStatus.FAILED, 0, artifact, "failure")));
    assertConflict(
        () ->
            service.checkpoint(
                run.id(),
                new CheckpointRequest(
                    claim, 1, "fr/settings", GroupStatus.FAILED, 0, artifact, "failure")));
  }

  @Test
  public void completedGroupRejectsNewProposalsAndRevisionsButAcknowledgesSavedSubmissions() {
    SubmitProposalRequest initial = proposal("initial");
    AgentReviewProposal first = service.submitProposal(run.id(), initial);
    SubmitProposalRequest revision =
        changed(
            proposal("revision"),
            "Corrected",
            Category.OBVIOUS_ERROR,
            Readiness.READY,
            first.getId(),
            null);
    AgentReviewProposal revised = service.submitProposal(run.id(), revision);
    String artifact = upload("{}");
    RunView completedGroup =
        service.checkpoint(
            run.id(),
            new CheckpointRequest(
                claim, 0, "fr/settings", GroupStatus.COMPLETED, 1, artifact, null));

    assertEquals(RunStatus.RUNNING, completedGroup.status());
    assertEquals(first.getId(), service.submitProposal(run.id(), initial).getId());
    assertEquals(revised.getId(), service.submitProposal(run.id(), revision).getId());
    assertConflict(() -> service.submitProposal(run.id(), proposal("new-finding")));
    assertConflict(
        () ->
            service.submitProposal(
                run.id(),
                changed(
                    proposal("new-revision"),
                    "Another correction",
                    Category.OBVIOUS_ERROR,
                    Readiness.READY,
                    revised.getId(),
                    null)));
    assertEquals(2, storedProposals.size());
    assertEquals(Disposition.OPEN, revised.getDisposition());
    assertEquals(1, storedFeedback.size());
  }

  @Test
  public void submissionIsIdempotentAndKeepsInvalidReplacementAsEvidence() {
    SubmitProposalRequest invalid =
        changed(proposal("a"), "bad {", Category.OBVIOUS_ERROR, Readiness.READY, null, null);
    AgentReviewProposal first = service.submitProposal(run.id(), invalid);
    assertEquals("bad {", first.getProposedTarget());
    assertEquals(Disposition.OPEN, first.getDisposition());
    assertEquals(first.getId(), service.submitProposal(run.id(), invalid).getId());
    assertConflict(() -> service.submitProposal(run.id(), proposal("a")));
    verify(variants, never()).save(any());
    verify(textUnits, never()).save(any());
  }

  @Test
  public void optionalImprovementCannotMasqueradeAsVerifiedError() {
    assertBad(
        () ->
            service.submitProposal(
                run.id(),
                changed(
                    proposal("a"),
                    "Better",
                    Category.OPTIONAL_IMPROVEMENT,
                    Readiness.READY,
                    null,
                    null)));
    AgentReviewProposal optional =
        service.submitProposal(
            run.id(),
            changed(
                proposal("a"),
                "Better",
                Category.OPTIONAL_IMPROVEMENT,
                Readiness.OPTIONAL,
                null,
                null));
    assertEquals(Readiness.OPTIONAL, optional.getReadiness());
  }

  @Test
  public void submissionRetainsHistoricalBaselineButRejectsFabricatedSnapshot() {
    // The known variant remains valid even when it is no longer current; application checks
    // current.
    AgentReviewProposal saved = service.submitProposal(run.id(), proposal("a"));
    assertEquals(Long.valueOf(7), saved.getBaselineVariantId());
    TMTextUnitVariant variant = variants.findById(7L).orElseThrow();
    variant.setContent("Different");
    assertBad(() -> service.submitProposal(run.id(), proposal("b")));
  }

  @Test
  public void bulkSubmissionCommitsSuccessfulItemsAndReportsBadItemIndependently() {
    SubmitProposalRequest invalid =
        changed(
            proposal("bad"), "Better", Category.OPTIONAL_IMPROVEMENT, Readiness.READY, null, null);
    List<SubmissionResult> results =
        service.submitProposals(run.id(), List.of(proposal("a"), invalid, proposal("b")));
    assertNotNull(results.get(0).proposalId());
    assertNull(results.get(0).errorCode());
    assertNull(results.get(1).proposalId());
    assertNotNull(results.get(1).errorCode());
    assertNotNull(results.get(2).proposalId());
    assertEquals(2, storedProposals.size());
    verify(transactions, times(2)).commit(any());
    verify(transactions).rollback(any());
  }

  @Test
  public void humanKeepCurrentAndDeferDoNotModifyBaselineOrTm() {
    AgentReviewProposal proposal = service.submitProposal(run.id(), proposal("a"));
    HumanFeedbackRequest keep =
        human(proposal, "keep", FeedbackAction.KEEP_CURRENT, false, null, null);
    AgentReviewFeedback kept = service.appendHumanFeedback(keep);
    assertEquals(Disposition.RESOLVED, proposal.getDisposition());
    assertNull(kept.getAppliedVariantId());
    assertEquals("Ancien", proposal.getBaselineTarget());
    assertEquals(kept.getId(), service.appendHumanFeedback(keep).getId());
    AgentReviewProposal second = service.submitProposal(run.id(), proposal("b"));
    service.appendHumanFeedback(human(second, "defer", FeedbackAction.DEFER, false, null, null));
    assertEquals(Disposition.ROUTED, second.getDisposition());
    verify(variants, never()).save(any());
    verify(textUnits, never()).save(any());
  }

  @Test
  public void humanFeedbackRetryRequiresSameContentAndActorBeforeStateChecks() {
    AgentReviewProposal proposal = service.submitProposal(run.id(), proposal("a"));
    HumanFeedbackRequest accepted =
        human(proposal, "accept", FeedbackAction.ACCEPT, false, "Nouveau", 8L);
    service.appendHumanFeedback(accepted);
    assertEquals(Disposition.RESOLVED, proposal.getDisposition());
    proposal.setVersion(1);
    service.appendHumanFeedback(accepted);
    assertConflict(
        () ->
            service.appendHumanFeedback(
                new HumanFeedbackRequest(
                    "accept",
                    proposal.getId(),
                    0,
                    FeedbackAction.ACCEPT,
                    null,
                    null,
                    null,
                    false,
                    "Different",
                    8L,
                    "a".repeat(64))));
    when(teamService.getCurrentUserIdOrThrow()).thenReturn(99L);
    assertThrows(AccessDeniedException.class, () -> service.appendHumanFeedback(accepted));
  }

  @Test
  public void heldDraftCanBeRevisedAndVerifiedBeforeHumanRouting() {
    AgentReviewProposal held =
        service.submitProposal(
            run.id(),
            changed(proposal("held"), null, Category.OBVIOUS_ERROR, Readiness.HOLD, null, null));
    AgentReviewProposal ready =
        service.submitProposal(
            run.id(),
            changed(
                proposal("verified"),
                "Enregistrer",
                Category.OBVIOUS_ERROR,
                Readiness.READY,
                held.getId(),
                null));
    assertEquals(held.getFindingId(), ready.getFindingId());
    assertEquals(2, ready.getProposalRevision());
    assertNull(held.getProposedTarget());
    assertEquals(Disposition.SUPERSEDED, held.getDisposition());
    assertEquals(Disposition.OPEN, ready.getDisposition());
    assertEquals(Readiness.READY, ready.getReadiness());
    AgentReviewFeedback revision = storedFeedback.values().iterator().next();
    assertEquals(ActorType.AGENT, revision.getActorType());
    assertEquals(FeedbackAction.REVISED_PROPOSAL, revision.getAction());
    assertNull(revision.getRespondsToFeedbackId());
    assertEquals(ready.getId(), revision.getResponseProposalId());
    assertNull(revision.getAppliedVariantId());
    assertEquals(
        ready.getId(),
        service
            .submitProposal(
                run.id(),
                changed(
                    proposal("verified"),
                    "Enregistrer",
                    Category.OBVIOUS_ERROR,
                    Readiness.READY,
                    held.getId(),
                    null))
            .getId());
    assertEquals(1, storedFeedback.size());
  }

  @Test
  public void optionalDraftCanRetainFindingIdentityWhileNewEvidenceEstablishesError() {
    AgentReviewProposal optional =
        service.submitProposal(
            run.id(),
            changed(
                proposal("optional"),
                "Better",
                Category.OPTIONAL_IMPROVEMENT,
                Readiness.OPTIONAL,
                null,
                null));
    AgentReviewProposal verified =
        service.submitProposal(
            run.id(),
            changed(
                proposal("verified"),
                "Correct",
                Category.CONSISTENCY_ERROR,
                Readiness.READY,
                optional.getId(),
                null));
    assertEquals(optional.getFindingId(), verified.getFindingId());
    assertEquals(Category.OPTIONAL_IMPROVEMENT, optional.getCategory());
    assertEquals(Category.CONSISTENCY_ERROR, verified.getCategory());
  }

  @Test
  public void agentOnlyRevisionCannotReplaceRoutedProposalOrBypassHumanFeedback() {
    AgentReviewProposal routed = service.submitProposal(run.id(), proposal("one"));
    service.linkProposal(routed.getId(), 40L, 50L, 60L);
    assertConflict(
        () ->
            service.submitProposal(
                run.id(),
                changed(
                    proposal("replacement"),
                    "Again",
                    Category.OBVIOUS_ERROR,
                    Readiness.READY,
                    routed.getId(),
                    null)));
    service.appendHumanFeedback(
        human(routed, "revise", FeedbackAction.REQUEST_REVISION, true, null, null));
    assertBad(
        () ->
            service.submitProposal(
                run.id(),
                changed(
                    proposal("replacement"),
                    "Again",
                    Category.OBVIOUS_ERROR,
                    Readiness.READY,
                    routed.getId(),
                    null)));
    assertEquals(Disposition.FOLLOW_UP, routed.getDisposition());
    assertEquals(1, storedProposals.size());
  }

  @Test
  public void agentRevisionPreservesFindingAndHumanFeedbackWithoutApplyingTranslation() {
    AgentReviewProposal first = service.submitProposal(run.id(), proposal("a"));
    AgentReviewFeedback human =
        service.appendHumanFeedback(
            human(first, "follow-up", FeedbackAction.REQUEST_REVISION, true, null, null));
    SubmitProposalRequest revised =
        changed(
            proposal("revision"),
            "Enregistrer",
            Category.OBVIOUS_ERROR,
            Readiness.READY,
            first.getId(),
            human.getId());
    AgentReviewProposal next = service.submitProposal(run.id(), revised);
    assertEquals(first.getFindingId(), next.getFindingId());
    assertEquals(2, next.getProposalRevision());
    assertEquals(Disposition.SUPERSEDED, first.getDisposition());
    assertEquals(Disposition.OPEN, next.getDisposition());
    assertEquals(FeedbackAction.REQUEST_REVISION, human.getAction());
    AgentReviewFeedback response = feedback.findByRespondsToFeedbackId(human.getId()).orElseThrow();
    assertEquals(next.getId(), response.getResponseProposalId());
    assertEquals(next.getId(), service.submitProposal(run.id(), revised).getId());
    assertEquals(2, storedFeedback.size());
    verify(variants, never()).save(any());
  }

  @Test
  public void followupCannotMoveFindingAcrossTeamsOrReviewTypes() {
    AgentReviewProposal first = service.submitProposal(run.id(), proposal("one"));
    AgentReviewFeedback human =
        service.appendHumanFeedback(
            human(first, "follow", FeedbackAction.REQUEST_REVISION, true, null, null));
    CreateRunRequest request = create("another-run", "v1");
    RunView other =
        service.createRun(
            new CreateRunRequest(
                request.requestKey(),
                "OTHER_REVIEW",
                request.teamId(),
                request.methodVersion(),
                request.configurationVersion(),
                request.groups(),
                request.inputManifestJson(),
                null,
                null,
                null));
    RunView claimed = service.claimRun(other.id(), new ClaimRequest("other-worker", 0L, 300));
    claim = new Claim(claimed.claimOwner(), claimed.claimGeneration());
    SubmitProposalRequest revision =
        changed(
            proposal("revision"),
            "Again",
            Category.OBVIOUS_ERROR,
            Readiness.READY,
            first.getId(),
            human.getId());
    assertBad(() -> service.submitProposal(other.id(), revision));
    ResponseRequest response =
        new ResponseRequest(
            claim,
            "response",
            human.getId(),
            FeedbackAction.CHALLENGE,
            "verifier",
            "Evidence",
            "{}");
    assertBad(() -> service.respondToFeedback(other.id(), response));
    storedRuns.get(other.id()).setReviewType("TRANSLATION_QUALITY");
    storedRuns.get(other.id()).setTeamId(999L);
    assertBad(() -> service.submitProposal(other.id(), revision));
    assertBad(() -> service.respondToFeedback(other.id(), response));
    assertEquals(Disposition.FOLLOW_UP, first.getDisposition());
    assertEquals(1, storedFeedback.size());
  }

  @Test
  public void revisedProposalCannotSilentlyReopenResolvedFinding() {
    AgentReviewProposal first = service.submitProposal(run.id(), proposal("a"));
    AgentReviewFeedback human =
        service.appendHumanFeedback(
            human(first, "keep", FeedbackAction.KEEP_CURRENT, false, null, null));
    assertConflict(
        () ->
            service.submitProposal(
                run.id(),
                changed(
                    proposal("revision"),
                    "Again",
                    Category.OBVIOUS_ERROR,
                    Readiness.READY,
                    first.getId(),
                    human.getId())));
  }

  @Test
  public void challengeIsSingleAppendOnlyResponseAndCannotOverrideHuman() {
    AgentReviewProposal proposal = service.submitProposal(run.id(), proposal("a"));
    AgentReviewFeedback human =
        service.appendHumanFeedback(
            human(proposal, "follow", FeedbackAction.REQUEST_REVISION, true, null, null));
    ResponseRequest challenge =
        new ResponseRequest(
            claim,
            "challenge",
            human.getId(),
            FeedbackAction.CHALLENGE,
            "verifier",
            "The screenshot confirms the noun refers to a file.",
            "{}");
    AgentReviewFeedback response = service.respondToFeedback(run.id(), challenge);
    assertEquals(response.getId(), service.respondToFeedback(run.id(), challenge).getId());
    assertEquals(FeedbackAction.REQUEST_REVISION, human.getAction());
    assertEquals(Disposition.FOLLOW_UP, proposal.getDisposition());
    assertConflict(
        () ->
            service.respondToFeedback(
                run.id(),
                new ResponseRequest(
                    claim,
                    "again",
                    human.getId(),
                    FeedbackAction.CHALLENGE,
                    "verifier",
                    "More",
                    "{}")));
    assertEquals(2, storedFeedback.size());
    verify(variants, never()).save(any());
  }

  @Test
  public void completedRespondingGroupRejectsNewRepliesButAcknowledgesSavedResponse() {
    AgentReviewProposal proposal = service.submitProposal(run.id(), proposal("initial"));
    AgentReviewFeedback original =
        service.appendHumanFeedback(
            human(proposal, "first-round", FeedbackAction.REQUEST_REVISION, true, null, null));
    RunView respondingRun = service.createRun(create("responding-run", "v1"));
    RunView claimed =
        service.claimRun(respondingRun.id(), new ClaimRequest("responding-worker", 0L, 300));
    claim = new Claim(claimed.claimOwner(), claimed.claimGeneration());
    ResponseRequest request =
        new ResponseRequest(
            claim,
            "first-reply",
            original.getId(),
            FeedbackAction.CHALLENGE,
            "verifier",
            "Please reconsider the screenshot evidence",
            "{}");
    AgentReviewFeedback response = service.respondToFeedback(respondingRun.id(), request);
    String artifact =
        service
            .uploadArtifact(
                respondingRun.id(), new ArtifactRequest(claim, "application/json", "e30="))
            .sha256();
    RunView completedGroup =
        service.checkpoint(
            respondingRun.id(),
            new CheckpointRequest(
                claim, 0, "fr/settings", GroupStatus.COMPLETED, 1, artifact, null));
    AgentReviewFeedback nextRound =
        service.appendHumanFeedback(
            human(proposal, "next-round", FeedbackAction.REQUEST_REVISION, true, null, null));

    assertEquals(RunStatus.RUNNING, completedGroup.status());
    assertEquals(response.getId(), service.respondToFeedback(respondingRun.id(), request).getId());
    ResponseStatusException conflict =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.respondToFeedback(
                    respondingRun.id(),
                    new ResponseRequest(
                        claim,
                        "next-reply",
                        nextRound.getId(),
                        FeedbackAction.CONTEXT_REQUEST,
                        "verifier",
                        "Need more context",
                        "{}")));
    assertEquals(409, conflict.getStatusCode().value());
    assertTrue(conflict.getReason().contains("Completed group"));
    assertEquals(3, storedFeedback.size());
    assertEquals(Disposition.FOLLOW_UP, proposal.getDisposition());
  }

  private CreateRunRequest create(String key, String method) {
    return new CreateRunRequest(
        key,
        "TRANSLATION_QUALITY",
        5L,
        method,
        "config-v1",
        List.of(group()),
        "{\"sourceVersion\":\"abc\"}",
        null,
        null,
        null);
  }

  private Group group() {
    return new Group("fr/settings", 2L, 3L, "settings", List.of(6L), "snapshot-v1");
  }

  private SubmitProposalRequest proposal(String key) {
    return new SubmitProposalRequest(
        claim,
        key,
        "fr/settings",
        6L,
        "Save",
        "Button",
        7L,
        "Ancien",
        "APPROVED",
        true,
        "Nouveau",
        Category.OBVIOUS_ERROR,
        Readiness.READY,
        "Meaning mismatch",
        "{}",
        "locale-fr",
        "verifier-fr",
        "Checked screen context",
        null,
        null,
        null);
  }

  private SubmitProposalRequest changed(
      SubmitProposalRequest p,
      String target,
      Category category,
      Readiness readiness,
      Long previous,
      Long respondsTo) {
    return new SubmitProposalRequest(
        p.claim(),
        p.submissionKey(),
        p.groupKey(),
        p.tmTextUnitId(),
        p.source(),
        p.sourceComment(),
        p.baselineVariantId(),
        p.baselineTarget(),
        p.baselineStatus(),
        p.baselineIncludedInLocalizedFile(),
        target,
        category,
        readiness,
        p.rationale(),
        p.evidenceJson(),
        p.producerIdentity(),
        p.verifierIdentity(),
        p.verificationRationale(),
        p.integrityDiagnostics(),
        previous,
        respondsTo);
  }

  private HumanFeedbackRequest human(
      AgentReviewProposal p,
      String key,
      FeedbackAction action,
      boolean followup,
      String target,
      Long variant) {
    return new HumanFeedbackRequest(
        key,
        p.getId(),
        p.getVersion(),
        action,
        null,
        null,
        null,
        followup,
        target,
        variant,
        "a".repeat(64));
  }

  private String upload(String value) {
    return service
        .uploadArtifact(
            run.id(),
            new ArtifactRequest(
                claim,
                "application/json",
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))))
        .sha256();
  }

  private void assertConflict(Runnable action) {
    assertEquals(
        409, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
  }

  private void assertBad(Runnable action) {
    assertEquals(
        400, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
  }
}
