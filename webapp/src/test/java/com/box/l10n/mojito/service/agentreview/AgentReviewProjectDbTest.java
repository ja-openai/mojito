package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest;
import com.box.l10n.mojito.rest.review.ReviewProjectWS;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.*;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.review.GetProjectDetailView;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises actual service transactions, persisted lineage, and the existing human save endpoint.
 */
public class AgentReviewProjectDbTest extends ServiceTestBase {
  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();
  @Autowired private RepositoryService repositoryService;
  @Autowired private RepositoryLocaleRepository repositoryLocales;
  @Autowired private AssetService assetService;
  @Autowired private LocaleService localeService;
  @Autowired private TeamService teamService;
  @Autowired private TMService tmService;
  @Autowired private TMTextUnitCurrentVariantRepository currentVariants;
  @Autowired private AgentReviewService reviews;
  @Autowired private AgentReviewProjectService routing;
  @Autowired private AgentReviewProposalRepository proposals;
  @Autowired private AgentReviewFeedbackRepository feedback;
  @Autowired private TranslationIncidentRepository incidents;
  @Autowired private ReviewProjectService projects;
  @Autowired private ReviewProjectWS reviewWS;

  @Test
  public void routingWaitsForCommittedGroupAndRetriesWithoutDuplicatesOrTmWrites()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = submit(fixture, null, null);
    assertThat(routing.routeRun(fixture.run().id()).projectIds()).isEmpty();
    complete(fixture);
    var result = routing.routeRun(fixture.run().id());
    assertThat(result.errors()).isEmpty();
    assertThat(result.projectIds()).hasSize(1);
    assertThat(result.proposalCount()).isEqualTo(1);
    assertThat(routing.routeRun(fixture.run().id()).projectIds())
        .containsExactlyElementsOf(result.projectIds());
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    var saved = proposals.findById(proposal.getId()).orElseThrow();
    assertThat(saved.getDisposition()).isEqualTo(Disposition.ROUTED);
    var incident = incidents.findById(saved.getIncidentId()).orElseThrow();
    assertThat(incident.getReviewType()).isEqualTo("TRANSLATION_QUALITY");
    assertThat(incident.getReviewProjectId()).isNull();
    assertThat(incident.getResolutionReviewProjectId()).isEqualTo(result.projectIds().getFirst());
    assertThat(incident.getSelectedTarget()).isEqualTo("Désactiver les notifications");
    assertThat(row(saved).agentReview().reviewedTarget()).isEqualTo("Désactiver les notifications");
  }

  @Test
  public void otherReviewTypesDoNotCreateIncidentsOrProjects() throws Exception {
    Fixture fixture = fixture("OTHER_REVIEW");
    AgentReviewProposal proposal = submit(fixture, null, null);
    complete(fixture);
    assertThat(routing.routeRun(fixture.run().id()).projectIds()).isEmpty();
    assertThat(proposals.findById(proposal.getId()).orElseThrow().getIncidentId()).isNull();
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
  }

  @Test
  public void acceptCommitsTranslationAndFeedbackOnceAndSurvivesProjectDeletion() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    var request = decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT);
    var response = reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(current(fixture).getContent()).isEqualTo("Activer les notifications");
    assertThat(response.getBody().agentReview().disposition()).isEqualTo("RESOLVED");
    assertThat(response.getBody().agentReview().lastFeedbackRequestId())
        .isEqualTo(request.getAgentReview().requestId());
    assertThat(response.getBody().agentReview().stale()).isFalse();
    assertThat(
            reviewWS
                .saveDecision(proposal.getReviewProjectTextUnitId(), request)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).hasSize(1);
    var entry = feedback.findByProposalIdOrderByIdAsc(proposal.getId()).getFirst();
    assertThat(entry.getAppliedVariantId()).isEqualTo(current(fixture).getId());
    assertThat(incidents.findById(proposal.getIncidentId()).orElseThrow().getStatus())
        .isEqualTo(TranslationIncidentStatus.CLOSED);
    projects.adminBatchDeleteProjects(List.of(proposal.getReviewProjectId()));
    assertThat(proposals.findById(proposal.getId())).isPresent();
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).hasSize(1);
    assertThat(current(fixture).getContent()).isEqualTo("Activer les notifications");
  }

  @Test
  public void keepCurrentDoesNotApproveOrRewriteTranslation() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    var request = decision(proposal, AgentReviewDecisionRequest.Action.KEEP_CURRENT);
    var response = reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    assertThat(
            feedback
                .findByProposalIdOrderByIdAsc(proposal.getId())
                .getFirst()
                .getAppliedVariantId())
        .isNull();
    assertThat(
            reviewWS
                .saveDecision(proposal.getReviewProjectTextUnitId(), request)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).hasSize(1);
  }

  @Test
  public void deferKeepsWorkPendingAndRequestRevisionReturnsHumanFeedbackWithoutTmWrites()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    var deferred =
        reviewWS.saveDecision(
            proposal.getReviewProjectTextUnitId(),
            decision(proposal, AgentReviewDecisionRequest.Action.DEFER));
    assertThat(deferred.getBody().reviewProjectTextUnitDecision().decisionState())
        .isEqualTo("PENDING");
    proposal = proposals.findById(proposal.getId()).orElseThrow();
    var request = decision(proposal, AgentReviewDecisionRequest.Action.REQUEST_REVISION);
    var response = reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    assertThat(reviews.pendingFeedback(fixture.run().id(), 0, 50)).hasSize(1);
    assertThat(reviews.pendingFeedback(fixture.run().id(), 0, 50).getFirst().getAction())
        .isEqualTo(FeedbackAction.REQUEST_REVISION);
    assertThat(incidents.findById(proposal.getIncidentId()).orElseThrow().getStatus())
        .isEqualTo(TranslationIncidentStatus.OPEN);
    assertThat(routing.routeRun(fixture.run().id()).proposalCount()).isZero();
  }

  @Test
  public void changedCurrentAndMissingProposalMetadataCannotApplyStagedText() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    var request = decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT);
    tmService.addCurrentTMTextUnitVariant(
        fixture.textUnitId(),
        fixture.localeId(),
        "Nouvelle traduction",
        TMTextUnitVariant.Status.APPROVED,
        true);
    assertThatThrownBy(() -> reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request))
        .hasMessageContaining("changed");
    assertThat(current(fixture).getContent()).isEqualTo("Nouvelle traduction");
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).isEmpty();
    request.setAgentReview(null);
    assertThatThrownBy(() -> reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request))
        .hasMessageContaining("proposal");
    assertThat(current(fixture).getContent()).isEqualTo("Nouvelle traduction");
  }

  @Test
  public void challengeAllowsExplicitHumanReconsiderationWithoutOverwritingTheEarlierDecision()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    reviewWS.saveDecision(
        proposal.getReviewProjectTextUnitId(),
        decision(proposal, AgentReviewDecisionRequest.Action.REQUEST_REVISION));
    assertThat(row(proposal).agentReview().canReconsider()).isFalse();
    var beforeResponse = decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT);
    assertThatThrownBy(
            () -> reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), beforeResponse))
        .hasMessageContaining("already received a decision");
    var human = reviews.pendingFeedback(fixture.run().id(), 0, 50).getFirst();
    Fixture responseRun = followUp(fixture);
    var response =
        new ResponseRequest(
            responseRun.claim(),
            "challenge-1",
            human.getId(),
            FeedbackAction.CHALLENGE,
            "verifier-fr",
            "The source means enable; the original target means disable.",
            "[]");
    reviews.respondToFeedback(responseRun.run().id(), response);
    reviews.respondToFeedback(responseRun.run().id(), response);
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    assertThat(row(proposal).agentReview().canReconsider()).isTrue();
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).hasSize(2);
    assertThat(reviews.pendingFeedback(fixture.run().id(), 0, 50)).isEmpty();
    assertThatThrownBy(
            () -> reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), beforeResponse))
        .hasMessageContaining("changed");
    var accepted =
        reviewWS.saveDecision(
            proposal.getReviewProjectTextUnitId(),
            decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT));
    assertThat(accepted.getBody().agentReview().canReconsider()).isFalse();
    assertThat(current(fixture).getContent()).isEqualTo("Activer les notifications");
    assertThat(routing.history(proposal.getReviewProjectId(), proposal.getId()))
        .extracting(AgentReviewProjectService.FeedbackView::action)
        .containsExactly("REQUEST_REVISION", "CHALLENGE", "ACCEPT");
    var page = routing.history(proposal.getReviewProjectId(), proposal.getId(), 0, 2);
    assertThat(page).hasSize(2);
    assertThat(
            routing.history(
                proposal.getReviewProjectId(), proposal.getId(), page.getLast().id(), 2))
        .extracting(AgentReviewProjectService.FeedbackView::action)
        .containsExactly("ACCEPT");
  }

  @Test
  public void revisedProposalFromAnotherRunKeepsFindingIncidentAndFeedbackHistory()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal first = routed(fixture);
    reviewWS.saveDecision(
        first.getReviewProjectTextUnitId(),
        decision(first, AgentReviewDecisionRequest.Action.REQUEST_REVISION));
    var human = reviews.pendingFeedback(fixture.run().id(), 0, 50).getFirst();
    Fixture followUp = followUp(fixture);
    AgentReviewProposal next = submit(followUp, first.getId(), human.getId());
    assertThat(next.getFindingId()).isEqualTo(first.getFindingId());
    assertThat(next.getProposalRevision()).isEqualTo(2);
    assertThat(proposals.findById(first.getId()).orElseThrow().getDisposition())
        .isEqualTo(Disposition.SUPERSEDED);
    complete(followUp);
    var routed = routing.routeRun(followUp.run().id());
    assertThat(routed.errors()).isEmpty();
    next = proposals.findById(next.getId()).orElseThrow();
    assertThat(next.getIncidentId()).isEqualTo(first.getIncidentId());
    assertThat(next.getReviewProjectId()).isNotEqualTo(first.getReviewProjectId());
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    reviewWS.saveDecision(
        next.getReviewProjectTextUnitId(),
        decision(next, AgentReviewDecisionRequest.Action.ACCEPT));
    assertThat(routing.history(next.getReviewProjectId(), next.getId()))
        .extracting(AgentReviewProjectService.FeedbackView::action)
        .containsExactly("REQUEST_REVISION", "REVISED_PROPOSAL", "ACCEPT");
    assertThat(reviews.pendingFeedback(fixture.run().id(), 0, 50)).isEmpty();
  }

  @Test
  public void aClosedIncidentSkipsItsRevisionWithoutCreatingAnEmptyProject() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal first = routed(fixture);
    reviewWS.saveDecision(
        first.getReviewProjectTextUnitId(),
        decision(first, AgentReviewDecisionRequest.Action.REQUEST_REVISION));
    var human = reviews.pendingFeedback(fixture.run().id(), 0, 50).getFirst();
    Fixture followUp = followUp(fixture);
    AgentReviewProposal next = submit(followUp, first.getId(), human.getId());
    var incident = incidents.findById(first.getIncidentId()).orElseThrow();
    incident.setStatus(TranslationIncidentStatus.CLOSED);
    incidents.save(incident);
    complete(followUp);
    var routed = routing.routeRun(followUp.run().id());
    assertThat(routed.projectIds()).isEmpty();
    assertThat(routed.skippedCount()).isEqualTo(1);
    assertThat(routed.errors()).singleElement().asString().contains("incident is closed");
    assertThat(proposals.findById(next.getId()).orElseThrow().getDisposition())
        .isEqualTo(Disposition.OPEN);
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incidents.save(incident);
    assertThat(routing.routeRun(followUp.run().id()).proposalCount()).isEqualTo(1);
  }

  @Test
  public void editedAcceptanceRecordsTheExactAppliedHumanText() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    var request = decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT);
    request.setTarget("Autoriser les notifications");
    reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request);
    var entry = feedback.findByProposalIdOrderByIdAsc(proposal.getId()).getFirst();
    assertThat(entry.getAction()).isEqualTo(FeedbackAction.EDIT_ACCEPT);
    assertThat(entry.getFinalTarget()).isEqualTo("Autoriser les notifications");
    assertThat(entry.getAppliedVariantId()).isEqualTo(current(fixture).getId());
    assertThat(proposals.findById(proposal.getId()).orElseThrow().getProposedTarget())
        .isEqualTo("Activer les notifications");
  }

  @Test
  public void invalidProposalRemainsEvidenceButCannotBeAppliedEvenByAnAdministrator()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    Repository repository = new Repository();
    repository.setId(fixture.run().repositoryIds().getFirst());
    AssetIntegrityChecker checker = new AssetIntegrityChecker();
    checker.setAssetExtension("json");
    checker.setIntegrityCheckerType(IntegrityCheckerType.PRINTF_LIKE);
    repositoryService.updateAssetIntegrityCheckers(repository, Set.of(checker));
    AgentReviewProposal proposal = submit(fixture, null, null, "Activer %s les notifications");
    complete(fixture);
    assertThat(routing.routeRun(fixture.run().id()).errors()).isEmpty();
    proposal = proposals.findById(proposal.getId()).orElseThrow();
    var request = decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT);
    request.setTarget(proposal.getProposedTarget());
    Long rowId = proposal.getReviewProjectTextUnitId();
    assertThatThrownBy(() -> reviewWS.saveDecision(rowId, request))
        .hasMessageContaining("integrity checks");
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).isEmpty();
    assertThat(proposals.findById(proposal.getId()).orElseThrow().getProposedTarget())
        .isEqualTo("Activer %s les notifications");
    assertThat(row(proposal).reviewProjectTextUnitDecision()).isNull();
  }

  private Fixture followUp(Fixture previous) {
    var run =
        reviews.createRun(
            new CreateRunRequest(
                UUID.randomUUID().toString(),
                previous.run().reviewType(),
                previous.run().teamId(),
                "review-v1",
                "test-config",
                List.of(
                    new Group(
                        "group-1",
                        previous.run().repositoryIds().getFirst(),
                        previous.localeId(),
                        "notifications",
                        List.of(previous.textUnitId()),
                        "test-input-v1")),
                "{}",
                7,
                1500,
                false));
    run =
        reviews.claimRun(run.id(), new ClaimRequest("new-coordinator", run.claimGeneration(), 900));
    return new Fixture(
        run,
        new Claim(run.claimOwner(), run.claimGeneration()),
        previous.textUnitId(),
        previous.localeId(),
        previous.originalVariantId());
  }

  private AgentReviewProposal routed(Fixture fixture) {
    AgentReviewProposal proposal = submit(fixture, null, null);
    complete(fixture);
    assertThat(routing.routeRun(fixture.run().id()).errors()).isEmpty();
    return proposals.findById(proposal.getId()).orElseThrow();
  }

  private GetProjectDetailView.ReviewProjectTextUnit row(AgentReviewProposal proposal) {
    return projects
        .getProjectDetail(proposal.getReviewProjectId())
        .reviewProjectTextUnits()
        .stream()
        .filter(row -> row.id().equals(proposal.getReviewProjectTextUnitId()))
        .findFirst()
        .orElseThrow();
  }

  private ReviewProjectTextUnitDecisionRequest decision(
      AgentReviewProposal proposal, AgentReviewDecisionRequest.Action action) {
    var row = row(proposal);
    var request = new ReviewProjectTextUnitDecisionRequest();
    if (action == AgentReviewDecisionRequest.Action.ACCEPT) {
      request.setTarget("Activer les notifications");
      request.setStatus("APPROVED");
      request.setIncludedInLocalizedFile(true);
    }
    request.setDecisionState(
        action == AgentReviewDecisionRequest.Action.DEFER
            ? DecisionState.PENDING
            : DecisionState.DECIDED);
    request.setExpectedCurrentTmTextUnitVariantId(row.currentTmTextUnitVariant().id());
    request.setExpectedReviewStateRevision(row.reviewStateRevision());
    request.setAgentReview(
        new AgentReviewDecisionRequest(
            proposal.getId(),
            proposal.getProposalRevision(),
            row.agentReview().proposalVersion(),
            UUID.randomUUID().toString(),
            action,
            null,
            null,
            "Human explanation"));
    return request;
  }

  private AgentReviewProposal submit(Fixture fixture, Long previous, Long respondsTo) {
    return submit(fixture, previous, respondsTo, "Activer les notifications");
  }

  private AgentReviewProposal submit(
      Fixture fixture, Long previous, Long respondsTo, String proposedTarget) {
    return reviews.submitProposal(
        fixture.run().id(),
        new SubmitProposalRequest(
            fixture.claim(),
            "finding-1",
            "group-1",
            fixture.textUnitId(),
            "Enable notifications",
            null,
            fixture.originalVariantId(),
            "Désactiver les notifications",
            "APPROVED",
            true,
            proposedTarget,
            Category.OBVIOUS_ERROR,
            Readiness.READY,
            "The target reverses the action",
            "[]",
            "reviewer-fr",
            "verifier-fr",
            "Confirmed opposite meaning",
            null,
            previous,
            respondsTo));
  }

  private void complete(Fixture fixture) {
    var artifact =
        reviews.uploadArtifact(
            fixture.run().id(),
            new ArtifactRequest(
                fixture.claim(),
                "application/json",
                Base64.getEncoder()
                    .encodeToString("{\"reviewed\":1}".getBytes(StandardCharsets.UTF_8))));
    var run = reviews.getRun(fixture.run().id());
    reviews.checkpoint(
        run.id(),
        new CheckpointRequest(
            fixture.claim(),
            run.revision(),
            "group-1",
            GroupStatus.COMPLETED,
            1,
            artifact.sha256(),
            null));
  }

  private TMTextUnitVariant current(Fixture fixture) {
    return currentVariants
        .findByLocale_IdAndTmTextUnit_Id(fixture.localeId(), fixture.textUnitId())
        .getTmTextUnitVariant();
  }

  private Fixture fixture(String type) throws Exception {
    var repository = repositoryService.createRepository(testIdWatcher.getEntityName("repo"));
    var locale = localeService.findByBcp47Tag("fr-FR");
    var repositoryLocale = new RepositoryLocale();
    repositoryLocale.setRepository(repository);
    repositoryLocale.setLocale(locale);
    repositoryLocales.save(repositoryLocale);
    var team = teamService.createTeam(testIdWatcher.getEntityName("team"));
    var asset = assetService.createAssetWithContent(repository.getId(), "messages.json", "{}");
    var unit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), asset.getId(), "enable", "Enable notifications", null);
    var original =
        tmService.addCurrentTMTextUnitVariant(
            unit.getId(),
            locale.getId(),
            "Désactiver les notifications",
            TMTextUnitVariant.Status.APPROVED,
            true);
    var run =
        reviews.createRun(
            new CreateRunRequest(
                UUID.randomUUID().toString(),
                type,
                team.getId(),
                "review-v1",
                "test-config",
                List.of(
                    new Group(
                        "group-1",
                        repository.getId(),
                        locale.getId(),
                        "notifications",
                        List.of(unit.getId()),
                        "test-input-v1")),
                "{}",
                7,
                1500,
                false));
    run = reviews.claimRun(run.id(), new ClaimRequest("coordinator", run.claimGeneration(), 900));
    return new Fixture(
        run,
        new Claim(run.claimOwner(), run.claimGeneration()),
        unit.getId(),
        locale.getId(),
        original.getId());
  }

  private record Fixture(
      RunView run, Claim claim, Long textUnitId, Long localeId, Long originalVariantId) {}
}
