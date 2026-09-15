package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.*;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.review.GetProjectDetailView;
import com.box.l10n.mojito.service.review.ReviewProjectClientContext;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class AgentReviewReopenServiceTest {
  private final AgentReviewProposalRepository proposals = mock(AgentReviewProposalRepository.class);
  private final AgentReviewFeedbackRepository feedback = mock(AgentReviewFeedbackRepository.class);
  private final ReviewProjectTextUnitRepository rows = mock(ReviewProjectTextUnitRepository.class);
  private final ReviewProjectService projects = mock(ReviewProjectService.class);
  private final TMTextUnitCurrentVariantRepository currentVariants =
      mock(TMTextUnitCurrentVariantRepository.class);
  private final TranslationIncidentRepository incidents = mock(TranslationIncidentRepository.class);
  private final UserService users = mock(UserService.class);
  private final TeamService teams = mock(TeamService.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final Map<Long, AgentReviewProposal> stored = new LinkedHashMap<>();
  private final List<AgentReviewFeedback> events = new ArrayList<>();
  private final AgentReviewProposal original = new AgentReviewProposal();
  private final ReviewProjectTextUnit row = new ReviewProjectTextUnit();
  private final TMTextUnitVariant current = new TMTextUnitVariant();
  private final TranslationIncident incident = new TranslationIncident();
  private AgentReviewReReviewService service;
  private ThreadBoundTransactionAdvice transactionAdvice;
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private String rowRevision = "reviewed-revision";

  @Before
  public void setup() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    transactionAdvice = new ThreadBoundTransactionAdvice(transactions);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("reviewer", "unused"));
    when(teams.getCurrentUserIdOrThrow()).thenReturn(4L);
    original.setId(10L);
    original.setRunId(1L);
    original.setFindingId("finding");
    original.setProposalRevision(1);
    original.setVersion(2L);
    original.setDisposition(Disposition.RESOLVED);
    original.setReviewProjectId(7L);
    original.setReviewProjectTextUnitId(8L);
    original.setTmTextUnitId(9L);
    original.setLocaleId(3L);
    original.setIncidentId(30L);
    original.setSource("Enable notifications");
    original.setRationale("The original translation reversed the action.");
    original.setProposedTarget("Activer les notifications");
    original.setBaselineTarget("Désactiver les notifications");
    original.setCategory(Category.OBVIOUS_ERROR);
    original.setReadiness(Readiness.READY);
    original.setProducerIdentity("reporting-agent");
    original.setVerifierIdentity("verifying-agent");
    original.setVerificationRationale("The source action is Enable.");
    original.setIntegrityDiagnostics("No placeholder changes.");
    original.setEvidenceJson(
        "[{\"label\":\"Screenshot\",\"url\":\"https://example.com/evidence\"}]");
    stored.put(10L, original);
    ReviewProject project = new ReviewProject();
    project.setId(7L);
    project.setAgentReviewRunId(1L);
    row.setId(8L);
    row.setReviewProject(project);
    TMTextUnit unit = new TMTextUnit();
    unit.setId(9L);
    unit.setContent(original.getSource());
    row.setTmTextUnit(unit);
    current.setId(20L);
    current.setContent("Activer les notifications");
    current.setStatus(TMTextUnitVariant.Status.APPROVED);
    current.setIncludedInLocalizedFile(true);
    TMTextUnitCurrentVariant currentRow = new TMTextUnitCurrentVariant();
    currentRow.setTmTextUnitVariant(current);
    incident.setId(30L);
    incident.setStatus(TranslationIncidentStatus.CLOSED);
    when(incidents.findById(30L)).thenReturn(Optional.of(incident));
    when(rows.findById(8L)).thenReturn(Optional.of(row));
    when(currentVariants.findForUpdateByLocaleIdAndTmTextUnitId(3L, 9L)).thenReturn(currentRow);
    when(proposals.findById(anyLong()))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
    when(proposals.findForUpdateById(anyLong()))
        .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
    when(proposals.findByFindingIdOrderByProposalRevisionAsc("finding"))
        .thenAnswer(call -> new ArrayList<>(stored.values()));
    when(proposals.findByRunIdAndSubmissionKey(eq(1L), anyString()))
        .thenAnswer(
            call ->
                stored.values().stream()
                    .filter(p -> Objects.equals(p.getSubmissionKey(), call.getArgument(1)))
                    .findFirst());
    when(proposals.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              AgentReviewProposal next = call.getArgument(0);
              next.setId(100L + stored.size());
              stored.put(next.getId(), next);
              return next;
            });
    when(feedback.save(any()))
        .thenAnswer(
            call -> {
              AgentReviewFeedback event = call.getArgument(0);
              events.add(event);
              return event;
            });
    when(feedback.findByProposalIdAndActorTypeAndRequestKey(
            anyLong(), eq(ActorType.HUMAN), anyString()))
        .thenAnswer(
            call ->
                events.stream()
                    .filter(
                        event ->
                            Objects.equals(event.getProposalId(), call.getArgument(0))
                                && Objects.equals(event.getRequestKey(), call.getArgument(2)))
                    .findFirst());
    when(projects.reopenHumanReviewRow(row, current))
        .thenAnswer(
            call -> {
              rowRevision = "pending-revision";
              return row;
            });
    when(projects.getReviewProjectTextUnit(8L)).thenAnswer(call -> detail());
    service =
        new AgentReviewReReviewService(
            proposals,
            feedback,
            rows,
            projects,
            currentVariants,
            incidents,
            users,
            teams,
            entityManager,
            new ObjectMapper());
  }

  @After
  public void cleanup() {
    SecurityContextHolder.clearContext();
    transactionAdvice.close();
  }

  private AgentReviewReReviewService.Request request(String key) {
    return new AgentReviewReReviewService.Request(
        key, 2L, 20L, "Enable notifications", null, "Activer les notifications", "APPROVED", true);
  }

  @Test
  public void reopenKeepsSameRowAndAppliedTextAndAppendsFreshHumanReview() {
    var result = service.reopen(7L, 10L, request("reopen"));
    AgentReviewProposal next = stored.get(result.proposalId());
    assertThat(result.projectId()).isEqualTo(7L);
    assertThat(next.getReviewProjectTextUnitId()).isEqualTo(8L);
    assertThat(next.getPreviousProposalId()).isEqualTo(10L);
    assertThat(next.getProposalRevision()).isEqualTo(2);
    assertThat(next.getDisposition()).isEqualTo(Disposition.ROUTED);
    assertThat(next.getBaselineVariantId()).isEqualTo(20L);
    assertThat(next.getBaselineTarget()).isEqualTo(current.getContent());
    assertThat(next.getProposedTarget()).isNull();
    assertFreshHumanReview(next);
    assertThat(next.getRationale()).isEqualTo(original.getRationale());
    assertThat(original.getDisposition()).isEqualTo(Disposition.RESOLVED);
    assertThat(original.getProposedTarget()).isEqualTo("Activer les notifications");
    assertThat(current.getId()).isEqualTo(20L);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getAction()).isEqualTo(FeedbackAction.REVIEW_AGAIN);
    assertThat(events.getFirst().getProposalId()).isEqualTo(10L);
    assertThat(events.getFirst().getResponseProposalId()).isEqualTo(next.getId());
    assertThat(incident.getStatus()).isEqualTo(TranslationIncidentStatus.OPEN);
    verify(projects, never()).createHumanReReviewProject(any(), any());
  }

  private void useUnchangedReport() {
    original.setBaselineVariantId(current.getId());
    original.setBaselineTarget(current.getContent());
    original.setBaselineStatus(current.getStatus().name());
    original.setBaselineIncludedInLocalizedFile(current.isIncludedInLocalizedFile());
    original.setProposedTarget("Une autre proposition");
  }

  private void assertFreshHumanReview(AgentReviewProposal next) {
    assertThat(next.getProposedTarget()).isNull();
    assertThat(next.getCategory()).isEqualTo(Category.HUMAN_REVIEW);
    assertThat(next.getReadiness()).isEqualTo(Readiness.HUMAN_REVIEW);
    assertThat(next.getProducerIdentity()).isEqualTo("reviewer");
    assertThat(next.getEvidenceJson()).isNull();
    assertThat(next.getVerifierIdentity()).isNull();
    assertThat(next.getVerificationRationale()).isNull();
    assertThat(next.getIntegrityDiagnostics()).isNull();
  }

  @Test
  public void reopenUnchangedDismissedReportRetainsSuggestionEvidenceAndOrigin() {
    useUnchangedReport();

    AgentReviewProposal next = stored.get(service.reopen(7L, 10L, request("revisit")).proposalId());

    assertThat(next.getProposedTarget()).isEqualTo(original.getProposedTarget());
    assertThat(next.getCategory()).isEqualTo(original.getCategory());
    assertThat(next.getReadiness()).isEqualTo(original.getReadiness());
    assertThat(next.getRationale()).isEqualTo(original.getRationale());
    assertThat(next.getEvidenceJson()).isEqualTo(original.getEvidenceJson());
    assertThat(next.getProducerIdentity()).isEqualTo("reporting-agent");
    assertThat(next.getVerifierIdentity()).isEqualTo("verifying-agent");
    assertThat(next.getVerificationRationale()).isEqualTo(original.getVerificationRationale());
    assertThat(next.getIntegrityDiagnostics()).isEqualTo(original.getIntegrityDiagnostics());
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getActorIdentity()).isEqualTo("reviewer");
    assertThat(events.getFirst().getActorUserId()).isEqualTo(4L);
    assertThat(events.getFirst().getProposalId()).isEqualTo(original.getId());
    assertThat(events.getFirst().getResponseProposalId()).isEqualTo(next.getId());
    assertThat(original.getDisposition()).isEqualTo(Disposition.RESOLVED);
  }

  @Test
  public void reopenDoesNotReuseSuggestionAfterSourceChanged() {
    useUnchangedReport();
    original.setSource("Disable notifications");
    assertFreshHumanReview(stored.get(service.reopen(7L, 10L, request("source")).proposalId()));
  }

  @Test
  public void reopenDoesNotReuseSuggestionAfterSourceContextChanged() {
    useUnchangedReport();
    original.setSourceComment("Previous context");
    assertFreshHumanReview(stored.get(service.reopen(7L, 10L, request("context")).proposalId()));
  }

  @Test
  public void reopenDoesNotReuseSuggestionForAnotherVariantWithTheSameText() {
    useUnchangedReport();
    original.setBaselineVariantId(19L);
    assertFreshHumanReview(stored.get(service.reopen(7L, 10L, request("variant")).proposalId()));
  }

  @Test
  public void reopenDoesNotReuseSuggestionAfterTargetChanged() {
    useUnchangedReport();
    original.setBaselineTarget("Désactiver les notifications");
    assertFreshHumanReview(stored.get(service.reopen(7L, 10L, request("target")).proposalId()));
  }

  @Test
  public void reopenDoesNotReuseSuggestionAfterTranslationStatusChanged() {
    useUnchangedReport();
    original.setBaselineStatus("NEEDS_REVIEW");
    assertFreshHumanReview(stored.get(service.reopen(7L, 10L, request("status")).proposalId()));
  }

  @Test
  public void reopenDoesNotReuseSuggestionAfterExportInclusionChanged() {
    useUnchangedReport();
    original.setBaselineIncludedInLocalizedFile(false);
    assertFreshHumanReview(stored.get(service.reopen(7L, 10L, request("inclusion")).proposalId()));
  }

  @Test
  public void replayDoesNotReopenAgainAndAnotherRequestCannotCreateCompetingRevision() {
    var first = service.reopen(7L, 10L, request("same-key"));
    assertThat(service.reopen(7L, 10L, request("same-key"))).isEqualTo(first);
    assertThat(stored).hasSize(2);
    assertThat(events).hasSize(1);
    verify(projects).reopenHumanReviewRow(row, current);
    assertThatThrownBy(() -> service.reopen(7L, 10L, request("different-key")))
        .hasMessageContaining("newer review round");
  }

  @Test
  public void staleSnapshotCannotReopenOrChangeProgress() {
    current.setContent("Changed elsewhere");
    assertThatThrownBy(() -> service.reopen(7L, 10L, request("stale")))
        .hasMessageContaining("source or current translation changed");
    assertThat(stored).hasSize(1);
    assertThat(events).isEmpty();
    verify(projects, never()).reopenHumanReviewRow(any(), any());
  }

  @Test
  public void existingReviewAgainStillCreatesASeparateProject() {
    ReviewProject nextProject = new ReviewProject();
    nextProject.setId(70L);
    ReviewProjectTextUnit nextRow = new ReviewProjectTextUnit();
    nextRow.setId(80L);
    nextRow.setReviewProject(nextProject);
    when(projects.createHumanReReviewProject(row, current)).thenReturn(nextRow);
    assertThat(service.reviewAgain(7L, 10L, request("old-client")).projectId()).isEqualTo(70L);
    verify(projects, never()).reopenHumanReviewRow(any(), any());
  }

  private AgentReviewReReviewService.EditRequest editRequest() {
    var decision = new ReviewProjectTextUnitDecisionRequest();
    decision.setTarget("Autoriser les notifications");
    decision.setStatus("APPROVED");
    decision.setIncludedInLocalizedFile(true);
    decision.setDecisionState(DecisionState.DECIDED);
    decision.setExpectedCurrentTmTextUnitVariantId(20L);
    decision.setExpectedReviewStateRevision("reviewed-revision");
    decision.setAgentReview(
        new AgentReviewDecisionRequest(
            10L,
            1,
            2L,
            "accept-edit",
            AgentReviewDecisionRequest.Action.ACCEPT,
            null,
            null,
            "Human correction"));
    return new AgentReviewReReviewService.EditRequest(request("edit"), decision);
  }

  private GetProjectDetailView.ReviewProjectTextUnit detail() {
    AgentReviewProposal latest = stored.values().stream().reduce((a, b) -> b).orElseThrow();
    var proposal =
        new AgentReviewProposalView(
            latest.getId(),
            latest.getPreviousProposalId(),
            latest.getProposalRevision(),
            latest.getVersion(),
            "finding",
            1L,
            "TRANSLATION_QUALITY",
            latest.getSource(),
            latest.getBaselineTarget(),
            latest.getProposedTarget(),
            latest.getRationale(),
            "HUMAN_REVIEW",
            "HUMAN_REVIEW",
            null,
            null,
            List.of(),
            latest.getDisposition().name(),
            false,
            null,
            false,
            true,
            null);
    var unit =
        new GetProjectDetailView.TmTextUnit(
            9L,
            "name",
            row.getTmTextUnit().getContent(),
            row.getTmTextUnit().getComment(),
            null,
            null,
            2L);
    var variant =
        new GetProjectDetailView.TmTextUnitVariant(
            current.getId(), current.getContent(), "APPROVED", true, null);
    return new GetProjectDetailView.ReviewProjectTextUnit(
        8L, unit, variant, variant, null, null, null, List.of(), List.of(), rowRevision, proposal);
  }

  private void stubSuccessfulSave() {
    when(projects.saveDecision(
            eq(8L),
            anyString(),
            nullable(String.class),
            eq("APPROVED"),
            eq(true),
            eq(DecisionState.DECIDED),
            eq(20L),
            eq(false),
            nullable(String.class),
            eq("pending-revision"),
            any(),
            nullable(ReviewProjectClientContext.class)))
        .thenAnswer(
            call -> {
              AgentReviewDecisionRequest judgment = call.getArgument(10);
              AgentReviewProposal next = stored.get(judgment.proposalId());
              assertThat(judgment.proposalRevision()).isEqualTo(2);
              assertThat(judgment.proposalVersion()).isEqualTo(next.getVersion());
              assertThat(judgment.requestId()).isEqualTo("accept-edit");
              assertThat(judgment.explanation()).isEqualTo("Human correction");
              current.setId(21L);
              current.setContent(call.getArgument(1));
              rowRevision = "saved-revision";
              next.setDisposition(Disposition.RESOLVED);
              next.setVersion(next.getVersion() + 1);
              var receipt = new AgentReviewFeedback();
              receipt.setProposalId(next.getId());
              receipt.setRequestKey(judgment.requestId());
              receipt.setActorUserId(4L);
              receipt.setAction(FeedbackAction.EDIT_ACCEPT);
              receipt.setAppliedVariantId(21L);
              events.add(receipt);
              return detail();
            });
  }

  @Test
  public void completedEditUsesFreshGuardsAndReplaysExactlyWithoutAnotherRoundOrSave() {
    stubSuccessfulSave();
    var request = editRequest();
    var saved = service.reopenAndSave(7L, 10L, request);
    assertThat(saved.currentTmTextUnitVariant().content())
        .isEqualTo(request.decision().getTarget());
    assertThat(saved.agentReview().previousProposalId()).isEqualTo(10L);
    assertThat(original.getDisposition()).isEqualTo(Disposition.RESOLVED);
    request
        .decision()
        .setClientContext(
            new ReviewProjectClientContext(
                1, "page", "operation", 2L, null, "accept", null, null, null));
    assertThat(service.reopenAndSave(7L, 10L, request)).isEqualTo(saved);
    assertThat(stored).hasSize(2);
    assertThat(events).hasSize(2);
    verify(projects, times(1)).reopenHumanReviewRow(row, current);
    verify(projects, times(1))
        .saveDecision(
            eq(8L),
            anyString(),
            nullable(String.class),
            eq("APPROVED"),
            eq(true),
            eq(DecisionState.DECIDED),
            eq(20L),
            eq(false),
            nullable(String.class),
            eq("pending-revision"),
            any(),
            nullable(ReviewProjectClientContext.class));
    request.decision().setTarget("Different correction");
    assertThatThrownBy(() -> service.reopenAndSave(7L, 10L, request))
        .hasMessageContaining("request key was already used");
  }

  @Test
  public void replayCannotAcknowledgeAChangedCurrentTranslation() {
    stubSuccessfulSave();
    var request = editRequest();
    service.reopenAndSave(7L, 10L, request);
    current.setId(22L);
    assertThatThrownBy(() -> service.reopenAndSave(7L, 10L, request))
        .hasMessageContaining("Translation changed after this decision");
    assertThat(stored).hasSize(2);
  }

  @Test
  public void staleDecisionRevisionCannotReopenBeforeSaving() {
    var request = editRequest();
    rowRevision = "concurrently-changed-revision";
    assertThatThrownBy(() -> service.reopenAndSave(7L, 10L, request))
        .isInstanceOf(
            com.box.l10n.mojito.service.review.ReviewProjectCurrentVariantConflictException.class);
    verify(projects, never()).reopenHumanReviewRow(any(), any());
    assertThat(stored).hasSize(1);
  }

  @Test
  public void rejectedSaveRollsBackTheOuterReopenTransaction() {
    when(projects.saveDecision(
            anyLong(),
            anyString(),
            nullable(String.class),
            anyString(),
            anyBoolean(),
            any(),
            anyLong(),
            anyBoolean(),
            nullable(String.class),
            anyString(),
            any(),
            nullable(ReviewProjectClientContext.class)))
        .thenThrow(new IllegalArgumentException("Invalid translation"));
    assertThatThrownBy(() -> service.reopenAndSave(7L, 10L, editRequest()))
        .hasMessageContaining("Invalid translation");
    verify(transactions).rollback(any());
    verify(transactions, never()).commit(any());
  }

  @Test
  public void completedEditDoesNotPermitBypassingCurrentSnapshotOrApproval() {
    var request = editRequest();
    request.decision().setOverrideChangedCurrent(true);
    assertThatThrownBy(() -> service.reopenAndSave(7L, 10L, request))
        .hasMessageContaining("guarded accepted translation");
    request.decision().setOverrideChangedCurrent(false);
    request.decision().setStatus("REVIEW_NEEDED");
    assertThatThrownBy(() -> service.reopenAndSave(7L, 10L, request))
        .hasMessageContaining("guarded accepted translation");
    verify(projects, never()).reopenHumanReviewRow(any(), any());
  }
}
