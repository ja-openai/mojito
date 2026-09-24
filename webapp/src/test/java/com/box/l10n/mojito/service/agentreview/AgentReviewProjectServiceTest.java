package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentIntakeService;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class AgentReviewProjectServiceTest {
  private final AgentReviewService reviews = mock(AgentReviewService.class);
  private final AgentReviewRunRepository runs = mock(AgentReviewRunRepository.class);
  private final AgentReviewProposalRepository proposals = mock(AgentReviewProposalRepository.class);
  private final AgentReviewStateService reviewedStates = mock(AgentReviewStateService.class);
  private final TranslationIncidentIntakeService incidentIntake =
      mock(TranslationIncidentIntakeService.class);
  private final TMTextUnitCurrentVariantRepository currentVariants =
      mock(TMTextUnitCurrentVariantRepository.class);
  private final TranslationIncidentRepository incidents = mock(TranslationIncidentRepository.class);
  private final TMTextUnitRepository textUnits = mock(TMTextUnitRepository.class);
  private final ReviewProjectService reviewProjects = mock(ReviewProjectService.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final AgentReviewRun run = new AgentReviewRun();
  private final AgentReviewProposal proposal = new AgentReviewProposal();
  private final TMTextUnit unit = new TMTextUnit();
  private final TMTextUnitVariant current = new TMTextUnitVariant();
  private final TMTextUnitCurrentVariant currentRow = new TMTextUnitCurrentVariant();
  private AgentReviewProjectService service;

  @Before
  @SuppressWarnings("unchecked")
  public void setup() {
    run.setId(1L);
    run.setReviewType("TRANSLATION_QUALITY");
    run.setTeamId(5L);
    run.setStatus(RunStatus.RUNNING);
    run.setRoutingPolicy(RoutingPolicy.QUEUED);
    proposal.setId(2L);
    proposal.setRunId(1L);
    proposal.setFindingId("finding");
    proposal.setGroupKey("fr/settings");
    proposal.setRepositoryId(3L);
    proposal.setLocaleId(4L);
    proposal.setTmTextUnitId(6L);
    proposal.setSource("Enable notifications");
    proposal.setSourceComment("Notification settings action");
    proposal.setBaselineVariantId(7L);
    proposal.setBaselineTarget("Désactiver les notifications");
    proposal.setBaselineStatus("REVIEW_NEEDED");
    proposal.setBaselineIncludedInLocalizedFile(true);
    proposal.setProposedTarget("Activer les notifications");
    proposal.setRationale("The translation reverses the action.");
    proposal.setCategory(Category.OBVIOUS_ERROR);
    proposal.setReadiness(Readiness.READY);
    proposal.setDisposition(Disposition.OPEN);

    Repository repository = new Repository();
    repository.setId(3L);
    repository.setName("test-repository");
    Asset asset = new Asset();
    asset.setRepository(repository);
    asset.setPath("strings.json");
    unit.setId(6L);
    unit.setName("notifications.enable");
    unit.setAsset(asset);
    unit.setContent(proposal.getSource());
    unit.setComment(proposal.getSourceComment());
    current.setId(7L);
    current.setContent(proposal.getBaselineTarget());
    current.setStatus(TMTextUnitVariant.Status.REVIEW_NEEDED);
    current.setIncludedInLocalizedFile(true);
    currentRow.setTmTextUnitVariant(current);
    Locale locale = new Locale();
    locale.setId(4L);
    locale.setBcp47Tag("fr");

    AgentReviewContracts.RunView view = mock(AgentReviewContracts.RunView.class);
    when(view.checkpoint())
        .thenReturn(
            new AgentReviewContracts.Checkpoint(
                Map.of(
                    "fr/settings",
                    new AgentReviewContracts.GroupCheckpoint(
                        AgentReviewContracts.GroupStatus.COMPLETED, 1, "artifact", null))));
    when(reviews.getRun(1L)).thenReturn(view);
    when(runs.findForUpdateById(1L)).thenReturn(Optional.of(run));
    when(proposals.findByRunIdOrderByIdAsc(1L)).thenReturn(List.of(proposal));
    when(reviews.findReadyProposalsForUpdate(1L)).thenReturn(List.of(proposal));
    when(entityManager.find(TMTextUnit.class, 6L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(unit);
    when(entityManager.find(TMTextUnit.class, 6L)).thenReturn(unit);
    when(entityManager.find(Locale.class, 4L)).thenReturn(locale);
    when(currentVariants.findForUpdateByLocaleIdAndTmTextUnitId(4L, 6L)).thenReturn(currentRow);
    when(textUnits.findById(6L)).thenReturn(Optional.of(unit));
    when(incidents.findForUpdateByReviewFindingId("finding")).thenReturn(Optional.empty());
    when(incidentIntake.saveOrReuse(any()))
        .thenAnswer(
            invocation -> {
              TranslationIncident incident = invocation.getArgument(0);
              incident.setId(8L);
              return incident;
            });
    TypedQuery<Long> projectIds = mock(TypedQuery.class, RETURNS_SELF);
    when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(projectIds);
    when(projectIds.getResultList()).thenReturn(List.of());
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service =
        new AgentReviewProjectService(
            reviews,
            runs,
            proposals,
            reviewedStates,
            incidentIntake,
            currentVariants,
            incidents,
            textUnits,
            reviewProjects,
            mock(ReviewProjectRepository.class),
            mock(UserService.class),
            transactions,
            "TRANSLATION_QUALITY");
    ReflectionTestUtils.setField(service, "entityManager", entityManager);
  }

  @Test
  public void sourceDriftCreatesNoIncidentOrProject() {
    unit.setContent("Disable notifications");
    assertStaleWithoutIncident();
  }

  @Test
  public void contextDriftCreatesNoIncidentOrProject() {
    unit.setComment("Different UI action");
    assertStaleWithoutIncident();
  }

  @Test
  public void targetDriftCreatesNoIncidentOrProject() {
    current.setContent("Autoriser les notifications");
    assertStaleWithoutIncident();
  }

  @Test
  public void sameTextWithNewVariantCreatesNoIncidentOrProject() {
    current.setId(9L);
    assertStaleWithoutIncident();
  }

  @Test
  public void sameTextWithNewApprovalCreatesNoIncidentOrProject() {
    current.setStatus(TMTextUnitVariant.Status.APPROVED);
    assertStaleWithoutIncident();
  }

  @Test
  public void refreshedApprovalIsCheckedBeforeAnyIncidentWrite() {
    doAnswer(
            invocation -> {
              current.setStatus(TMTextUnitVariant.Status.APPROVED);
              return null;
            })
        .when(entityManager)
        .refresh(current, LockModeType.PESSIMISTIC_WRITE);
    assertStaleWithoutIncident();
  }

  @Test
  public void inclusionDriftCreatesNoIncidentOrProject() {
    current.setIncludedInLocalizedFile(false);
    assertStaleWithoutIncident();
  }

  @Test
  public void removedCurrentTranslationCreatesNoIncidentOrProject() {
    when(currentVariants.findForUpdateByLocaleIdAndTmTextUnitId(4L, 6L)).thenReturn(null);
    assertStaleWithoutIncident();
  }

  @Test
  public void emptyTargetDoesNotMatchNullBaseline() {
    proposal.setBaselineTarget(null);
    current.setContent("");
    assertStaleWithoutIncident();
  }

  @Test
  public void staleRetryPreservesExistingIncidentAssociationWithoutReusingIt() {
    proposal.setIncidentId(30L);
    current.setContent("Autoriser les notifications");
    assertStaleWithoutIncident();
    assertThat(proposal.getIncidentId()).isEqualTo(30L);
  }

  @Test
  public void freshQueuedFindingCreatesIncidentOnlyAfterRefreshingLockedBaseline() {
    var result = service.routeRun(1L);

    assertThat(result.errors()).isEmpty();
    assertThat(result.projectIds()).isEmpty();
    assertThat(result.skippedCount()).isZero();
    assertThat(proposal.getIncidentId()).isEqualTo(8L);
    var order = inOrder(entityManager, reviews, currentVariants, incidents, incidentIntake);
    order.verify(entityManager).find(TMTextUnit.class, 6L, LockModeType.PESSIMISTIC_WRITE);
    order.verify(reviews).findReadyProposalsForUpdate(1L);
    order.verify(entityManager).refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    order.verify(currentVariants).findForUpdateByLocaleIdAndTmTextUnitId(4L, 6L);
    order.verify(entityManager).refresh(currentRow, LockModeType.PESSIMISTIC_WRITE);
    order.verify(entityManager).refresh(current, LockModeType.PESSIMISTIC_WRITE);
    order.verify(incidents).findForUpdateByReviewFindingId("finding");
    order.verify(incidentIntake).saveOrReuse(any());
    verifyNoInteractions(reviewProjects);
  }

  @Test
  public void freshQueuedRetryReusesExistingIncidentWithoutNewIntake() {
    TranslationIncident incident = new TranslationIncident();
    incident.setId(30L);
    incident.setStatus(TranslationIncidentStatus.OPEN);
    when(incidents.findForUpdateByReviewFindingId("finding")).thenReturn(Optional.of(incident));

    assertThat(service.routeRun(1L).errors()).isEmpty();
    assertThat(service.routeRun(1L).errors()).isEmpty();
    assertThat(proposal.getIncidentId()).isEqualTo(30L);
    verifyNoInteractions(incidentIntake, reviewProjects);
  }

  @Test
  public void closedIncidentRemainsClosedAndUnrouted() {
    TranslationIncident incident = new TranslationIncident();
    incident.setId(30L);
    incident.setStatus(TranslationIncidentStatus.CLOSED);
    when(incidents.findForUpdateByReviewFindingId("finding")).thenReturn(Optional.of(incident));

    var result = service.routeRun(1L);

    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.errors()).singleElement().asString().contains("incident is closed");
    assertThat(incident.getStatus()).isEqualTo(TranslationIncidentStatus.CLOSED);
    assertThat(proposal.getIncidentId()).isNull();
    verifyNoInteractions(incidentIntake, reviewProjects);
  }

  @Test
  public void humanReviewedCurrentStateCreatesNoIncidentOrProject() {
    when(reviewedStates.isReviewed(eq(5L), eq("TRANSLATION_QUALITY"), anyString()))
        .thenReturn(true);

    var result = service.routeRun(1L);

    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.errors()).singleElement().asString().contains("already reviewed");
    assertThat(proposal.getIncidentId()).isNull();
    verifyNoInteractions(incidents, incidentIntake, reviewProjects);
  }

  private void assertStaleWithoutIncident() {
    Long previousIncidentId = proposal.getIncidentId();
    for (RoutingPolicy policy : List.of(RoutingPolicy.QUEUED, RoutingPolicy.IMMEDIATE)) {
      run.setRoutingPolicy(policy);
      var result = service.routeRun(1L);
      assertThat(result.projectIds()).as(policy.name()).isEmpty();
      assertThat(result.proposalCount()).as(policy.name()).isZero();
      assertThat(result.skippedCount()).as(policy.name()).isEqualTo(1);
      assertThat(result.errors())
          .as(policy.name())
          .containsExactly("Finding finding: current string changed since the finding.");
      assertThat(proposal.getIncidentId()).as(policy.name()).isEqualTo(previousIncidentId);
      assertThat(proposal.getDisposition()).as(policy.name()).isEqualTo(Disposition.OPEN);
      assertThat(proposal.getReviewProjectId()).as(policy.name()).isNull();
      assertThat(proposal.getReviewProjectTextUnitId()).as(policy.name()).isNull();
    }
    verifyNoInteractions(reviewedStates, incidents, incidentIntake, reviewProjects);
  }
}
