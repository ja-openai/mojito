package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.agentreview.Category;
import com.box.l10n.mojito.entity.agentreview.Disposition;
import com.box.l10n.mojito.entity.agentreview.Readiness;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.service.agentreview.IncidentReviewAssignmentService.ProjectState;
import com.box.l10n.mojito.service.agentreview.IncidentReviewAssignmentService.RowState;
import com.box.l10n.mojito.service.agentreview.IncidentReviewAssignmentService.Snapshot;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class IncidentReviewAssignmentServiceTest {
  private final EntityManager em = mock(EntityManager.class);
  private final AgentReviewProposalRepository proposals = mock(AgentReviewProposalRepository.class);
  private final IncidentReviewAssignmentService service =
      new IncidentReviewAssignmentService(em, proposals);
  private final TranslationIncident incident = new TranslationIncident();
  private final AgentReviewProposal previous = new AgentReviewProposal();
  private ThreadBoundTransactionAdvice advice;
  private TypedQuery<Long> newer;

  @Before
  public void setup() {
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    advice = new ThreadBoundTransactionAdvice(transactions);
    incident.setId(1L);
    incident.setReviewFindingId("finding");
    incident.setReviewRunId(3L);
    incident.setResolutionReviewProjectId(4L);
    incident.setResolvedLocaleId(6L);
    incident.setSelectedTmTextUnitId(7L);
    previous.setId(2L);
    previous.setIncidentId(1L);
    previous.setFindingId("finding");
    previous.setRunId(3L);
    previous.setReviewProjectId(4L);
    previous.setReviewProjectTextUnitId(5L);
    previous.setLocaleId(6L);
    previous.setTmTextUnitId(7L);
    previous.setRepositoryId(8L);
    previous.setProposalRevision(1);
    previous.setSubmissionKey("original");
    previous.setRequestFingerprint("a".repeat(64));
    previous.setGroupKey("original-group");
    previous.setSource("Source");
    previous.setSourceComment("Context");
    previous.setBaselineTarget(null);
    previous.setBaselineVariantId(null);
    previous.setProposedTarget("Proposed");
    previous.setCategory(Category.HUMAN_REVIEW);
    previous.setReadiness(Readiness.HUMAN_REVIEW);
    previous.setRationale("Original human report");
    previous.setEvidenceJson("{\"proof\":true}");
    previous.setProducerIdentity("original-producer");
    previous.setVerifierIdentity("original-verifier");
    previous.setVerificationRationale("Original whole-state check");
    previous.setIntegrityDiagnostics("original-diagnostics");
    previous.setDisposition(Disposition.ROUTED);
    previous.setIntakeFingerprint("b".repeat(64));
    newer = query();
    when(em.createQuery(anyString(), eq(Long.class))).thenReturn(newer);
    when(newer.getSingleResult()).thenReturn(0L);
    when(proposals.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @After
  public void cleanup() {
    advice.close();
  }

  @Test
  public void historicalAttributionAloneDoesNotAssignAnOrdinaryIncident() {
    incident.setResolutionReviewProjectId(null);
    incident.setReviewFindingId(null);
    incident.setReviewProjectId(99L);
    assertThat(service.ineligible(incident, null, snapshot())).isNull();
  }

  @Test
  public void absentTranslationCannotHideWrongTextUnitOrLocaleLinkage() {
    incident.setSelectedTmTextUnitId(77L);
    assertThat(service.ineligible(incident, previous, snapshot())).isNotNull();
    incident.setSelectedTmTextUnitId(7L);
    incident.setResolvedLocaleId(66L);
    assertThat(service.ineligible(incident, previous, snapshot())).isNotNull();
    verifyNoInteractions(proposals);
  }

  @Test
  public void missingOrInconsistentHistoricalReferencesRemainHeld() {
    assertThat(
            service.ineligible(incident, previous, state(Map.of(), Map.of(), Set.of(), Set.of())))
        .isNotNull();
    assertThat(
            service.ineligible(
                incident,
                previous,
                state(
                    projects(ReviewProjectStatus.CLOSED),
                    Map.of(5L, new RowState(99L, 7L)),
                    Set.of(),
                    Set.of())))
        .isNotNull();
    assertThat(
            service.ineligible(
                incident,
                previous,
                state(
                    projects(ReviewProjectStatus.CLOSED),
                    Map.of(5L, new RowState(4L, 77L)),
                    Set.of(),
                    Set.of())))
        .isNotNull();
    previous.setIncidentId(99L);
    assertThat(service.ineligible(incident, previous, snapshot())).isNotNull();
  }

  @Test
  public void openProjectAndFinalOrFollowupStatesRemainBlocked() {
    assertThat(
            service.ineligible(
                incident,
                previous,
                state(projects(ReviewProjectStatus.OPEN), rows(), Set.of(), Set.of())))
        .contains("open review project");
    for (Disposition disposition :
        List.of(Disposition.RESOLVED, Disposition.FOLLOW_UP, Disposition.SUPERSEDED)) {
      previous.setDisposition(disposition);
      assertThat(service.ineligible(incident, previous, snapshot())).isNotNull();
    }
  }

  @Test
  public void recordedDecisionOrHumanFollowupBlocksEvenCorruptRoutedDisposition() {
    assertThat(
            service.ineligible(
                incident,
                previous,
                state(projects(ReviewProjectStatus.CLOSED), rows(), Set.of(2L), Set.of())))
        .contains("human decision");
    assertThat(
            service.ineligible(
                incident,
                previous,
                state(projects(ReviewProjectStatus.CLOSED), rows(), Set.of(), Set.of(5L))))
        .contains("recorded review decision");
    verifyNoInteractions(proposals);
  }

  @Test
  public void previewSnapshotCannotCreateARevision() {
    Snapshot s = snapshot();
    assertThatThrownBy(
            () ->
                service.prepareForRouting(
                    incident,
                    previous,
                    new Snapshot(
                        s.projects(), s.rows(), s.handledProposals(), s.decidedRows(), false)))
        .hasMessageContaining("locked state");
    verifyNoInteractions(proposals);
  }

  @Test
  public void newerRevisionBlocksBeforeOldRevisionOrLiveKeyChanges() {
    when(newer.getSingleResult()).thenReturn(1L);
    assertThatThrownBy(() -> service.prepareForRouting(incident, previous, snapshot()))
        .hasMessageContaining("newer finding revision");
    assertThat(previous.getDisposition()).isEqualTo(Disposition.ROUTED);
    assertThat(previous.getActiveIntakeFingerprint()).isEqualTo("b".repeat(64));
    verifyNoInteractions(proposals);
  }

  @Test
  public void reassignmentTransfersLiveKeyAfterFlushAndPreservesOriginalReportAndRoute() {
    doAnswer(
            invocation -> {
              assertThat(previous.getDisposition()).isEqualTo(Disposition.SUPERSEDED);
              assertThat(previous.getActiveIntakeFingerprint()).isNull();
              assertThat(previous.getIntakeFingerprint()).isEqualTo("b".repeat(64));
              assertThat(previous.getReviewProjectId()).isEqualTo(4L);
              assertThat(previous.getReviewProjectTextUnitId()).isEqualTo(5L);
              return null;
            })
        .when(proposals)
        .flush();
    var next = service.prepareForRouting(incident, previous, snapshot());
    assertThat(next)
        .usingRecursiveComparison()
        .ignoringFields(
            "id",
            "version",
            "submissionKey",
            "requestFingerprint",
            "proposalRevision",
            "previousProposalId",
            "disposition",
            "activeIntakeFingerprint",
            "reviewProjectId",
            "reviewProjectTextUnitId")
        .isEqualTo(previous);
    assertThat(next.getPreviousProposalId()).isEqualTo(2L);
    assertThat(next.getProposalRevision()).isEqualTo(2);
    assertThat(next.getActiveIntakeFingerprint()).isEqualTo("b".repeat(64));
    assertThat(next.getReviewProjectId()).isNull();
    assertThat(next.getReviewProjectTextUnitId()).isNull();
    assertThat(incident.getResolutionReviewProjectId()).isEqualTo(4L);
    var order = inOrder(proposals);
    order.verify(proposals).flush();
    order.verify(proposals).saveAndFlush(next);
    // Reopening the old project does not make its superseded finding assignable again.
    assertThat(
            service.ineligible(
                incident,
                previous,
                state(projects(ReviewProjectStatus.OPEN), rows(), Set.of(), Set.of())))
        .contains("already handled");
  }

  @Test
  public void acquiringProjectLockRefreshesPreviouslyLoadedClosedStatus() {
    var project = new ReviewProject();
    project.setId(4L);
    project.setStatus(ReviewProjectStatus.CLOSED);
    project.setAgentReviewRunId(3L);
    var locale = new Locale();
    locale.setId(6L);
    project.setLocale(locale);
    TypedQuery<ReviewProject> projectQuery = query();
    when(em.createQuery(anyString(), eq(ReviewProject.class))).thenReturn(projectQuery);
    when(projectQuery.getResultList()).thenReturn(List.of(project));
    TypedQuery<Object[]> rowQuery = query();
    when(em.createQuery(anyString(), eq(Object[].class))).thenReturn(rowQuery);
    when(rowQuery.getResultList()).thenReturn(Collections.singletonList(new Object[] {5L, 4L, 7L}));
    when(newer.getResultList()).thenReturn(List.of());
    doAnswer(
            invocation -> {
              project.setStatus(ReviewProjectStatus.OPEN);
              return null;
            })
        .when(em)
        .refresh(project, LockModeType.PESSIMISTIC_WRITE);
    var snapshot = service.load(List.of(incident), List.of(previous), true);
    assertThat(service.ineligible(incident, previous, snapshot)).contains("open review project");
    verify(projectQuery).setLockMode(LockModeType.PESSIMISTIC_WRITE);
    verifyNoInteractions(proposals);
  }

  private Snapshot snapshot() {
    return state(projects(ReviewProjectStatus.CLOSED), rows(), Set.of(), Set.of());
  }

  private Map<Long, ProjectState> projects(ReviewProjectStatus status) {
    return Map.of(4L, new ProjectState(status, 3L, 6L));
  }

  private Map<Long, RowState> rows() {
    return Map.of(5L, new RowState(4L, 7L));
  }

  private Snapshot state(
      Map<Long, ProjectState> projects,
      Map<Long, RowState> rows,
      Set<Long> handled,
      Set<Long> decided) {
    return new Snapshot(projects, rows, handled, decided, true);
  }

  @SuppressWarnings("unchecked")
  private <T> TypedQuery<T> query() {
    return mock(TypedQuery.class, RETURNS_SELF);
  }
}
