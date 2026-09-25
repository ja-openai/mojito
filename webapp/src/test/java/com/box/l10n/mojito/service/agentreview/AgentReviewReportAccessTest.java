package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.agentreview.ActorType;
import com.box.l10n.mojito.entity.agentreview.AgentReviewFeedback;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.agentreview.AgentReviewRun;
import com.box.l10n.mojito.entity.agentreview.Category;
import com.box.l10n.mojito.entity.agentreview.Disposition;
import com.box.l10n.mojito.entity.agentreview.FeedbackAction;
import com.box.l10n.mojito.entity.agentreview.Readiness;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitDetail;
import com.box.l10n.mojito.service.security.user.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

public class AgentReviewReportAccessTest {
  private final UserService users = mock(UserService.class);
  private final ReviewProjectRepository projects = mock(ReviewProjectRepository.class);
  private final ReviewProjectService reviewProjects = mock(ReviewProjectService.class);
  private final AgentReviewProposalRepository proposals = mock(AgentReviewProposalRepository.class);
  private final AgentReviewRunRepository runs = mock(AgentReviewRunRepository.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final ReviewProject project = new ReviewProject();
  private final AgentReviewProposal proposal = new AgentReviewProposal();
  private final AgentReviewFeedback feedback = new AgentReviewFeedback();
  private final ReviewProjectTextUnitDetail row = mock(ReviewProjectTextUnitDetail.class);
  private AgentReviewDecisionService decisions;
  private AgentReviewProjectService reports;

  @Before
  @SuppressWarnings("unchecked")
  public void setup() {
    project.setId(10L);
    project.setAgentReviewRunId(30L);
    proposal.setId(20L);
    proposal.setRunId(30L);
    proposal.setReviewProjectId(10L);
    proposal.setReviewProjectTextUnitId(11L);
    proposal.setFindingId("finding");
    proposal.setProposalRevision(2);
    proposal.setVersion(3L);
    proposal.setSource("Enable notifications");
    proposal.setBaselineTarget("Désactiver les notifications");
    proposal.setProposedTarget("Activer les notifications");
    proposal.setRationale("The translation reversed the action.");
    proposal.setCategory(Category.OBVIOUS_ERROR);
    proposal.setReadiness(Readiness.READY);
    proposal.setDisposition(Disposition.RESOLVED);
    proposal.setVerificationRationale("Private verifier notes");
    proposal.setIntegrityDiagnostics("Private diagnostics");
    proposal.setEvidenceJson(
        "[{\"label\":\"Private screenshot\",\"artifactSha256\":\"" + "a".repeat(64) + "\"}]");
    AgentReviewRun run = new AgentReviewRun();
    run.setReviewType("TRANSLATION_QUALITY");
    when(runs.findById(30L)).thenReturn(Optional.of(run));
    when(row.reviewProjectTextUnitId()).thenReturn(11L);
    feedback.setId(40L);
    feedback.setProposalId(20L);
    feedback.setRequestKey("decision-retry-id");
    feedback.setActorType(ActorType.HUMAN);
    feedback.setAction(FeedbackAction.KEEP_CURRENT);
    feedback.setExplanation("Saved reviewer comment");

    TypedQuery<AgentReviewProposal> proposalQuery = mock(TypedQuery.class, RETURNS_SELF);
    when(entityManager.createQuery(anyString(), eq(AgentReviewProposal.class)))
        .thenReturn(proposalQuery);
    when(proposalQuery.getResultList()).thenReturn(List.of(proposal));
    when(proposalQuery.getResultStream()).thenAnswer(ignored -> Stream.empty());
    TypedQuery<AgentReviewFeedback> feedbackQuery = mock(TypedQuery.class, RETURNS_SELF);
    when(entityManager.createQuery(anyString(), eq(AgentReviewFeedback.class)))
        .thenReturn(feedbackQuery);
    when(feedbackQuery.getResultStream()).thenAnswer(ignored -> Stream.of(feedback));

    decisions =
        new AgentReviewDecisionService(
            null, proposals, null, runs, null, null, users, new ObjectMapper());
    ReflectionTestUtils.setField(decisions, "entityManager", entityManager);
    reports =
        new AgentReviewProjectService(
            null,
            runs,
            proposals,
            null,
            null,
            null,
            null,
            null,
            reviewProjects,
            projects,
            users,
            mock(PlatformTransactionManager.class),
            "TRANSLATION_QUALITY");
    ReflectionTestUtils.setField(reports, "entityManager", entityManager);
  }

  @Test
  public void nonAdministratorsRetainReviewControlsButReceiveNoDetailedReport() {
    for (Role role : List.of(Role.ROLE_TRANSLATOR, Role.ROLE_PM, Role.ROLE_USER)) {
      setRole(role);
      AgentReviewProposalView view = decisions.views(project, List.of(row)).get(11L);
      assertThat(view.verificationNotes()).as(role.name()).isNull();
      assertThat(view.integrityDiagnostics()).as(role.name()).isNull();
      assertThat(view.evidence()).as(role.name()).isEmpty();
      assertThat(view.reviewedTarget()).isEqualTo(proposal.getBaselineTarget());
      assertThat(view.proposedTarget()).isEqualTo(proposal.getProposedTarget());
      assertThat(view.rationale()).isEqualTo(proposal.getRationale());
      assertThat(view.verificationStatus()).isEqualTo("READY");
      assertThat(view.proposalId()).isEqualTo(20L);
      assertThat(view.proposalRevision()).isEqualTo(2);
      assertThat(view.proposalVersion()).isEqualTo(3L);
      assertThat(view.lastFeedbackRequestId()).isEqualTo("decision-retry-id");
      assertThat(view.canReviewAgain()).isTrue();
    }
  }

  @Test
  public void administratorsReceiveTheDetailedReport() {
    setRole(Role.ROLE_ADMIN);
    AgentReviewProposalView view = decisions.views(project, List.of(row)).get(11L);
    assertThat(view.verificationNotes()).isEqualTo("Private verifier notes");
    assertThat(view.integrityDiagnostics()).isEqualTo("Private diagnostics");
    assertThat(view.evidence())
        .containsExactly(
            new AgentReviewProposalView.Evidence(
                "Private screenshot",
                "/api/agent-reviews/projects/10/proposals/20/artifacts/" + "a".repeat(64)));
  }

  @Test
  public void authorizedTranslatorsAndProjectManagersCanReadCommentsWithoutEvidence() {
    givenFeedbackHistory();
    for (Role role : List.of(Role.ROLE_TRANSLATOR, Role.ROLE_PM)) {
      setRole(role);
      List<AgentReviewProjectService.FeedbackView> history = reports.history(10L, 20L, 0, 50);
      assertThat(history)
          .as(role.name())
          .extracting(AgentReviewProjectService.FeedbackView::explanation)
          .containsExactly("Saved reviewer comment", "Could you clarify the intended meaning?");
      assertThat(history)
          .as(role.name())
          .allSatisfy(entry -> assertThat(entry.evidenceJson()).isNull());
    }
    verify(reviewProjects, times(2)).assertCurrentUserCanReadProject(project);
  }

  @Test
  public void administratorsCanReadFeedbackForTheAuthorizedProjectProposal() {
    setRole(Role.ROLE_ADMIN);
    givenFeedbackHistory();

    List<AgentReviewProjectService.FeedbackView> history = reports.history(10L, 20L);
    assertThat(history)
        .extracting(AgentReviewProjectService.FeedbackView::explanation)
        .containsExactly("Saved reviewer comment", "Could you clarify the intended meaning?");
    assertThat(history)
        .last()
        .extracting(AgentReviewProjectService.FeedbackView::evidenceJson)
        .isEqualTo("{\"diagnostics\":\"Private evidence\"}");
    verify(reviewProjects).assertCurrentUserCanReadProject(project);
  }

  @Test
  public void unauthorizedProjectReadersCannotReadOrDiscoverFeedback() {
    setRole(Role.ROLE_TRANSLATOR);
    when(projects.findById(10L)).thenReturn(Optional.of(project));
    doThrow(new AccessDeniedException("Review project access denied"))
        .when(reviewProjects)
        .assertCurrentUserCanReadProject(project);

    assertThatThrownBy(() -> reports.history(10L, 20L)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(proposals, entityManager);
  }

  @Test
  public void aProposalFromAnotherProjectDoesNotGrantFeedbackAccess() {
    setRole(Role.ROLE_TRANSLATOR);
    when(projects.findById(10L)).thenReturn(Optional.of(project));
    proposal.setReviewProjectId(99L);
    when(proposals.findById(20L)).thenReturn(Optional.of(proposal));

    assertThatThrownBy(() -> reports.history(10L, 20L))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(reviewProjects).assertCurrentUserCanReadProject(project);
    verifyNoInteractions(entityManager);
  }

  @SuppressWarnings("unchecked")
  private void givenFeedbackHistory() {
    when(projects.findById(10L)).thenReturn(Optional.of(project));
    when(proposals.findById(20L)).thenReturn(Optional.of(proposal));
    AgentReviewFeedback response = new AgentReviewFeedback();
    response.setId(41L);
    response.setProposalId(20L);
    response.setActorType(ActorType.AGENT);
    response.setAction(FeedbackAction.CONTEXT_REQUEST);
    response.setExplanation("Could you clarify the intended meaning?");
    response.setEvidenceJson("{\"diagnostics\":\"Private evidence\"}");
    TypedQuery<Object[]> historyQuery = mock(TypedQuery.class, RETURNS_SELF);
    when(entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(historyQuery);
    when(historyQuery.getResultList())
        .thenReturn(List.of(new Object[] {feedback, 2}, new Object[] {response, 2}));
  }

  private void setRole(Role role) {
    when(users.isCurrentUserAdmin()).thenReturn(role == Role.ROLE_ADMIN);
    when(users.isCurrentUserPm()).thenReturn(role == Role.ROLE_PM);
    when(users.isCurrentUserTranslator()).thenReturn(role == Role.ROLE_TRANSLATOR);
  }
}
