package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
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
import java.time.ZonedDateTime;
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

  @Autowired
  private com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;

  @Autowired private RepositoryLocaleRepository repositoryLocales;
  @Autowired private AssetService assetService;
  @Autowired private LocaleService localeService;
  @Autowired private TeamService teamService;
  @Autowired private TMService tmService;
  @Autowired private TMTextUnitCurrentVariantRepository currentVariants;
  @Autowired private AgentReviewService reviews;
  @Autowired private AgentReviewProjectService routing;
  @Autowired private IncidentReviewBatchService batches;
  @Autowired private AgentReviewProposalRepository proposals;
  @Autowired private AgentReviewFeedbackRepository feedback;
  @Autowired private TranslationIncidentRepository incidents;
  @Autowired private ReviewProjectService projects;
  @Autowired private ReviewProjectWS reviewWS;
  @Autowired private AgentReviewReReviewService reReviews;
  @Autowired private AgentReviewStateService reviewedStates;

  @Autowired
  private com.box.l10n.mojito.service.badtranslation.TranslationIncidentService incidentService;

  @Autowired private com.box.l10n.mojito.service.tm.TMTextUnitRepository units;
  @Autowired private com.box.l10n.mojito.service.security.user.UserService userService;
  @Autowired private com.box.l10n.mojito.service.review.ReviewProjectRepository projectRepository;

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
  public void suspectedFindingWithoutCorrectionQueuesAndCreatesManualReviewWithoutVerification()
      throws Exception {
    assertSuspectedQueueToManualProject(null);
  }

  @Test
  public void suspectedFindingWithEmptyCorrectionPreservesItThroughManualProjectAndRetries()
      throws Exception {
    assertSuspectedQueueToManualProject("");
  }

  private void assertSuspectedQueueToManualProject(String proposedTarget) throws Exception {
    Fixture fixture = followUp(fixture("TRANSLATION_QUALITY"), RoutingPolicy.QUEUED);
    var request =
        new SubmitProposalRequest(
            fixture.claim(),
            "suspected-finding",
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
            Readiness.SUSPECTED,
            "The translation appears to reverse the requested action; human assessment is pending.",
            "{\"origin\":\"AI test fixture\",\"humanApproved\":false}",
            "agent:fr-reviewer:test-model",
            null,
            null,
            null,
            null,
            null,
            "meaning:notification-action");
    var submitted = reviews.submitProposal(fixture.run().id(), request);
    assertThat(reviews.submitProposal(fixture.run().id(), request).getId())
        .isEqualTo(submitted.getId());
    complete(fixture);

    var routed = routing.routeRun(fixture.run().id());
    assertThat(routed.errors()).isEmpty();
    assertThat(routed.skippedCount()).isZero();
    assertThat(routed.projectIds()).isEmpty();
    var queued = proposals.findById(submitted.getId()).orElseThrow();
    Long incidentId = queued.getIncidentId();
    assertThat(incidentId).isNotNull();
    assertThat(queued.getDisposition()).isEqualTo(Disposition.OPEN);
    assertThat(queued.getReviewProjectId()).isNull();
    assertThat(queued.getProposedTarget()).isEqualTo(proposedTarget);
    assertThat(queued.getProducerIdentity()).isEqualTo("agent:fr-reviewer:test-model");
    assertThat(queued.getVerifierIdentity()).isNull();
    assertThat(queued.getVerificationRationale()).isNull();
    assertThat(routing.routeRun(fixture.run().id()).errors()).isEmpty();
    assertThat(proposals.findById(submitted.getId()).orElseThrow().getIncidentId())
        .isEqualTo(incidentId);

    var batchRequest =
        new IncidentReviewBatchService.Request(
            fixture.run().repositoryIds(),
            null,
            List.of("fr-FR"),
            null,
            fixture.run().reviewType(),
            fixture.run().teamId(),
            "Suspected translation review",
            ZonedDateTime.now().plusDays(3),
            1500,
            false,
            ReviewProjectType.BUG_FIXES,
            null,
            null);
    Long actor = teamService.getCurrentUserIdOrThrow();
    assertThat(batches.preview(batchRequest, actor).eligibleIncidentCount()).isEqualTo(1);
    var created = batches.create(batchRequest, actor);
    assertThat(created.projectIds()).hasSize(1);
    assertThat(created.eligibleIncidentCount()).isEqualTo(1);
    assertThat(created.skipped()).isEmpty();
    var assigned = proposals.findById(submitted.getId()).orElseThrow();
    assertThat(assigned.getDisposition()).isEqualTo(Disposition.ROUTED);
    assertThat(assigned.getReviewProjectId()).isEqualTo(created.projectIds().getFirst());
    assertThat(assigned.getIncidentId()).isEqualTo(incidentId);
    assertThat(assigned.getProducerIdentity()).isEqualTo("agent:fr-reviewer:test-model");
    assertThat(assigned.getVerifierIdentity()).isNull();
    assertThat(assigned.getVerificationRationale()).isNull();
    assertThat(row(assigned).agentReview().proposedTarget()).isEqualTo(proposedTarget);
    assertThat(row(assigned).agentReview().verificationStatus()).isEqualTo("SUSPECTED");
    assertThat(incidents.findById(incidentId).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(assigned.getReviewProjectId());
    long projectCount = projectRepository.count();
    long incidentCount = incidents.count();
    assertThat(batches.create(batchRequest, actor).projectCount()).isZero();
    assertThat(routing.routeRun(fixture.run().id()).proposalCount()).isZero();
    assertThat(reviews.submitProposal(fixture.run().id(), request).getId())
        .isEqualTo(submitted.getId());
    assertThat(projectRepository.count()).isEqualTo(projectCount);
    assertThat(incidents.count()).isEqualTo(incidentCount);
    assertThat(feedback.findByProposalIdOrderByIdAsc(submitted.getId())).isEmpty();
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
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
  public void notesOnlyAcceptanceKeepsOriginalAndReplaysWithoutAnotherVariant() throws Exception {
    assertMetadataOnlyAcceptance(false);
  }

  @Test
  public void commentOnlyAcceptanceKeepsOriginalWithExactNewVariantReceipt() throws Exception {
    assertMetadataOnlyAcceptance(true);
  }

  private void assertMetadataOnlyAcceptance(boolean changeComment) throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    var request = decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT);
    request.setTarget(proposal.getBaselineTarget());
    if (changeComment) request.setComment("The original wording fits the context.");
    else request.setDecisionNotes("Reviewed the context; keep the original wording.");
    setOriginalAssessment(request, changeComment ? OriginalAssessment.GOOD : null);

    var saved = reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request).getBody();
    var current = current(fixture);
    assertThat(current.getContent()).isEqualTo(proposal.getBaselineTarget());
    if (changeComment) {
      assertThat(current.getId()).isNotEqualTo(fixture.originalVariantId());
      assertThat(current.getComment()).isEqualTo(request.getComment());
    } else {
      assertThat(current.getId()).isEqualTo(fixture.originalVariantId());
      assertThat(saved.reviewProjectTextUnitDecision().notes())
          .isEqualTo(request.getDecisionNotes());
    }
    var receipt = feedback.findByProposalIdOrderByIdAsc(proposal.getId()).getFirst();
    assertThat(receipt.getAction()).isEqualTo(FeedbackAction.KEEP_CURRENT);
    assertThat(receipt.getOriginalAssessment())
        .isEqualTo(request.getAgentReview().originalAssessment());
    assertThat(receipt.getAppliedVariantId()).isEqualTo(current.getId());
    assertThat(receipt.getReviewedStateFingerprint())
        .isEqualTo(
            AgentReviewStateFingerprint.of(
                fixture.textUnitId(),
                fixture.localeId(),
                proposal.getSource(),
                proposal.getSourceComment(),
                current.getId(),
                current.getContent(),
                current.getStatus().name(),
                current.isIncludedInLocalizedFile()));
    assertThat(incidents.findById(proposal.getIncidentId()).orElseThrow().getResolution())
        .isEqualTo(TranslationIncidentResolution.REVIEW_DISMISSED);

    var replay = reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request).getBody();
    assertThat(replay.currentTmTextUnitVariant().id()).isEqualTo(current.getId());
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).hasSize(1);
    assertThat(current(fixture).getId()).isEqualTo(current.getId());
    Fixture nextRun = followUp(fixture);
    assertThatThrownBy(() -> submitCurrent(nextRun))
        .hasMessageContaining("already been reviewed by a human");

    request.setDecisionNotes("Different retry payload");
    assertThatThrownBy(() -> reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request))
        .hasMessageContaining("different");
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).hasSize(1);
  }

  @Test
  public void badOriginalCannotBeAcceptedUnchangedByAddingMetadata() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal proposal = routed(fixture);
    var request = decision(proposal, AgentReviewDecisionRequest.Action.ACCEPT);
    request.setTarget(
        java.text.Normalizer.normalize(
            proposal.getBaselineTarget(), java.text.Normalizer.Form.NFD));
    request.setComment("Metadata does not fix the reported error.");
    request.setDecisionNotes("Needs a correction.");
    setOriginalAssessment(request, OriginalAssessment.BAD);
    String beforeRevision = row(proposal).reviewStateRevision();

    assertThatThrownBy(() -> reviewWS.saveDecision(proposal.getReviewProjectTextUnitId(), request))
        .hasMessageContaining("original translation is wrong");
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    assertThat(row(proposal).reviewStateRevision()).isEqualTo(beforeRevision);
    assertThat(feedback.findByProposalIdOrderByIdAsc(proposal.getId())).isEmpty();
    assertThat(incidents.findById(proposal.getIncidentId()).orElseThrow().getStatus())
        .isEqualTo(TranslationIncidentStatus.OPEN);

    reviewWS.saveDecision(
        proposal.getReviewProjectTextUnitId(),
        decision(proposal, AgentReviewDecisionRequest.Action.KEEP_CURRENT));
    AgentReviewProposal completed = proposals.findById(proposal.getId()).orElseThrow();
    var edit = decision(completed, AgentReviewDecisionRequest.Action.ACCEPT);
    edit.setTarget(completed.getBaselineTarget());
    edit.setDecisionNotes("A note cannot correct the retained translation.");
    setOriginalAssessment(edit, OriginalAssessment.BAD);
    var reopened =
        new AgentReviewReReviewService.EditRequest(reviewAgainRequest(completed, fixture), edit);
    assertThatThrownBy(
            () ->
                reviewWS.reopenAndSave(completed.getReviewProjectId(), completed.getId(), reopened))
        .hasMessageContaining("original translation is wrong");
    assertThat(current(fixture).getId()).isEqualTo(fixture.originalVariantId());
    assertThat(proposals.findByFindingIdOrderByProposalRevisionAsc(completed.getFindingId()))
        .hasSize(1);
    assertThat(feedback.findByProposalIdOrderByIdAsc(completed.getId())).hasSize(1);
  }

  private void setOriginalAssessment(
      ReviewProjectTextUnitDecisionRequest request, OriginalAssessment assessment) {
    var judgment = request.getAgentReview();
    request.setAgentReview(
        new AgentReviewDecisionRequest(
            judgment.proposalId(),
            judgment.proposalRevision(),
            judgment.proposalVersion(),
            judgment.requestId(),
            judgment.action(),
            assessment,
            judgment.suggestionAssessment(),
            judgment.explanation()));
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
    incident = incidents.findById(first.getIncidentId()).orElseThrow();
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

  @Test
  public void reviewAgainAfterAcceptancePreservesAppliedTextAndOldDecision() throws Exception {
    assertReviewAgain(AgentReviewDecisionRequest.Action.ACCEPT);
  }

  @Test
  public void reviewAgainAfterKeepCurrentPreservesOldDecision() throws Exception {
    assertReviewAgain(AgentReviewDecisionRequest.Action.KEEP_CURRENT);
  }

  @Test
  public void reopenReturnsSameRowToPendingAndKeepsAppliedTranslationAndFeedback()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.ACCEPT));
    original = proposals.findById(original.getId()).orElseThrow();
    var oldFeedback = feedback.findByProposalIdOrderByIdAsc(original.getId()).getFirst();
    Long currentId = current(fixture).getId();
    var request = reviewAgainRequest(original, fixture);
    var result = reReviews.reopen(original.getReviewProjectId(), original.getId(), request);
    AgentReviewProposal next = proposals.findById(result.proposalId()).orElseThrow();
    assertThat(result.projectId()).isEqualTo(original.getReviewProjectId());
    assertThat(next.getReviewProjectTextUnitId()).isEqualTo(original.getReviewProjectTextUnitId());
    assertThat(next.getPreviousProposalId()).isEqualTo(original.getId());
    assertThat(next.getBaselineVariantId()).isEqualTo(currentId);
    assertThat(next.getDisposition()).isEqualTo(Disposition.ROUTED);
    assertThat(current(fixture).getId()).isEqualTo(currentId);
    var reopened = row(next);
    assertThat(reopened.agentReview().proposalId()).isEqualTo(next.getId());
    assertThat(reopened.reviewProjectTextUnitDecision().decisionState())
        .isEqualTo(DecisionState.PENDING);
    assertThat(projectRepository.findById(result.projectId()).orElseThrow().getDecidedCount())
        .isZero();
    assertThat(feedback.findById(oldFeedback.getId()).orElseThrow().getAction())
        .isEqualTo(FeedbackAction.ACCEPT);
    assertThat(proposals.findById(original.getId()).orElseThrow().getDisposition())
        .isEqualTo(Disposition.RESOLVED);
    assertThat(reReviews.reopen(original.getReviewProjectId(), original.getId(), request))
        .isEqualTo(result);
    assertThat(projectRepository.findById(result.projectId()).orElseThrow().getDecidedCount())
        .isZero();
    reviewWS.saveDecision(
        next.getReviewProjectTextUnitId(),
        decision(next, AgentReviewDecisionRequest.Action.KEEP_CURRENT));
    assertThat(projectRepository.findById(result.projectId()).orElseThrow().getDecidedCount())
        .isEqualTo(1L);
    assertThat(current(fixture).getId()).isEqualTo(currentId);
    assertThat(reviews.feedbackHistory(next.getId(), 0, 50))
        .extracting(AgentReviewFeedback::getAction)
        .containsExactly(FeedbackAction.KEEP_CURRENT);
    assertThat(routing.history(next.getReviewProjectId(), next.getId()))
        .extracting(AgentReviewProjectService.FeedbackView::action)
        .containsExactly("ACCEPT", "REVIEW_AGAIN", "KEEP_CURRENT");
  }

  @Test
  public void reviewAgainAfterRequestedProposalConsumesPendingFeedback() throws Exception {
    assertReviewAgain(AgentReviewDecisionRequest.Action.REQUEST_REVISION);
  }

  @Test
  public void completedReviewCanBeEditedInPlaceWithOneAtomicAcceptance() throws Exception {
    assertCompletedEdit(AgentReviewDecisionRequest.Action.ACCEPT);
  }

  @Test
  public void completedMetadataSaveKeepsCorrectedTranslationAndScopesPriorBadAssessment()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    var accept = decision(original, AgentReviewDecisionRequest.Action.ACCEPT);
    setOriginalAssessment(accept, OriginalAssessment.BAD);
    reviewWS.saveDecision(original.getReviewProjectTextUnitId(), accept);
    original = proposals.findById(original.getId()).orElseThrow();
    var firstReceipt = feedback.findByProposalIdOrderByIdAsc(original.getId()).getFirst();
    Long acceptedVariantId = current(fixture).getId();
    var edit = decision(original, AgentReviewDecisionRequest.Action.ACCEPT);
    edit.setTarget(current(fixture).getContent());
    edit.setComment("Context for the accepted wording.");
    edit.setDecisionNotes("Added context after reviewing the saved translation.");
    setOriginalAssessment(edit, OriginalAssessment.BAD);
    var request =
        new AgentReviewReReviewService.EditRequest(reviewAgainRequest(original, fixture), edit);
    Long projectId = original.getReviewProjectId();
    Long proposalId = original.getId();

    var saved = reviewWS.reopenAndSave(projectId, proposalId, request).getBody();
    assertThat(saved.currentTmTextUnitVariant().content()).isEqualTo("Activer les notifications");
    assertThat(saved.currentTmTextUnitVariant().id()).isNotEqualTo(acceptedVariantId);
    assertThat(saved.currentTmTextUnitVariant().comment()).isEqualTo(edit.getComment());
    assertThat(saved.reviewProjectTextUnitDecision().notes()).isEqualTo(edit.getDecisionNotes());
    var receipt =
        feedback.findByProposalIdOrderByIdAsc(saved.agentReview().proposalId()).getFirst();
    assertThat(receipt.getAction()).isEqualTo(FeedbackAction.KEEP_CURRENT);
    assertThat(receipt.getOriginalAssessment()).isNull();
    assertThat(receipt.getAppliedVariantId()).isEqualTo(saved.currentTmTextUnitVariant().id());
    assertThat(feedback.findById(firstReceipt.getId()).orElseThrow().getOriginalAssessment())
        .isEqualTo(OriginalAssessment.BAD);
    assertThat(incidents.findById(original.getIncidentId()).orElseThrow().getResolution())
        .isEqualTo(TranslationIncidentResolution.REVIEW_DISMISSED);

    var replay = reviewWS.reopenAndSave(projectId, proposalId, request).getBody();
    assertThat(replay.agentReview().proposalId()).isEqualTo(saved.agentReview().proposalId());
    assertThat(replay.currentTmTextUnitVariant().id())
        .isEqualTo(saved.currentTmTextUnitVariant().id());
    assertThat(feedback.findByProposalIdOrderByIdAsc(receipt.getProposalId())).hasSize(1);
    assertThat(proposals.findByFindingIdOrderByProposalRevisionAsc(original.getFindingId()))
        .hasSize(2);
    assertThat(projectRepository.findById(projectId).orElseThrow().getDecidedCount()).isEqualTo(1L);
    edit.setComment("Changed retry");
    assertThatThrownBy(() -> reviewWS.reopenAndSave(projectId, proposalId, request))
        .hasMessageContaining("request key was already used");
  }

  @Test
  public void waitingForAgentReviewCanBeEditedWithoutLeavingPendingFeedback() throws Exception {
    assertCompletedEdit(AgentReviewDecisionRequest.Action.REQUEST_REVISION);
  }

  private void assertCompletedEdit(AgentReviewDecisionRequest.Action initialAction)
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(original.getReviewProjectTextUnitId(), decision(original, initialAction));
    original = proposals.findById(original.getId()).orElseThrow();
    var previousFeedback = feedback.findByProposalIdOrderByIdAsc(original.getId()).getFirst();
    Long previousCurrent = current(fixture).getId();
    var edit = decision(original, AgentReviewDecisionRequest.Action.ACCEPT);
    edit.setTarget("Autoriser les notifications");
    var request =
        new AgentReviewReReviewService.EditRequest(reviewAgainRequest(original, fixture), edit);
    var response = reviewWS.reopenAndSave(original.getReviewProjectId(), original.getId(), request);
    var saved = response.getBody();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(saved.id()).isEqualTo(original.getReviewProjectTextUnitId());
    assertThat(saved.agentReview().previousProposalId()).isEqualTo(original.getId());
    assertThat(saved.agentReview().proposalRevision())
        .isEqualTo(original.getProposalRevision() + 1);
    assertThat(saved.agentReview().lastFeedbackRequestId())
        .isEqualTo(edit.getAgentReview().requestId());
    assertThat(saved.currentTmTextUnitVariant().content()).isEqualTo(edit.getTarget());
    assertThat(current(fixture).getId()).isNotEqualTo(previousCurrent);
    assertThat(saved.reviewProjectTextUnitDecision().decisionState()).isEqualTo("DECIDED");
    assertThat(
            projectRepository
                .findById(original.getReviewProjectId())
                .orElseThrow()
                .getDecidedCount())
        .isEqualTo(1L);
    assertThat(feedback.findById(previousFeedback.getId()).orElseThrow().getAction())
        .isEqualTo(previousFeedback.getAction());
    assertThat(reviews.feedbackHistory(saved.agentReview().proposalId(), 0, 50))
        .extracting(AgentReviewFeedback::getAction)
        .containsExactly(FeedbackAction.EDIT_ACCEPT);
    assertThat(routing.history(original.getReviewProjectId(), saved.agentReview().proposalId()))
        .extracting(AgentReviewProjectService.FeedbackView::action)
        .containsExactly(initialAction.name(), "REVIEW_AGAIN", "EDIT_ACCEPT");
    assertThat(incidents.findById(original.getIncidentId()).orElseThrow().getStatus())
        .isEqualTo(TranslationIncidentStatus.CLOSED);
    assertThat(reviews.pendingFeedback(fixture.run().id(), 0, 50)).isEmpty();
    Long currentId = current(fixture).getId();
    var replay = reviewWS.reopenAndSave(original.getReviewProjectId(), original.getId(), request);
    assertThat(replay.getBody().agentReview().proposalId())
        .isEqualTo(saved.agentReview().proposalId());
    assertThat(current(fixture).getId()).isEqualTo(currentId);
    assertThat(proposals.findByFindingIdOrderByProposalRevisionAsc(original.getFindingId()))
        .hasSize(2);
    assertThat(
            projectRepository
                .findById(original.getReviewProjectId())
                .orElseThrow()
                .getDecidedCount())
        .isEqualTo(1L);
  }

  @Test
  public void invalidCompletedEditRollsBackTheNewRoundAndProgress() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.ACCEPT));
    original = proposals.findById(original.getId()).orElseThrow();
    Long projectId = original.getReviewProjectId();
    Long proposalId = original.getId();
    Long currentId = current(fixture).getId();
    String revision = row(original).reviewStateRevision();
    Repository repository = new Repository();
    repository.setId(fixture.run().repositoryIds().getFirst());
    AssetIntegrityChecker checker = new AssetIntegrityChecker();
    checker.setAssetExtension("json");
    checker.setIntegrityCheckerType(IntegrityCheckerType.PRINTF_LIKE);
    repositoryService.updateAssetIntegrityCheckers(repository, Set.of(checker));
    var edit = decision(original, AgentReviewDecisionRequest.Action.ACCEPT);
    edit.setTarget("Activer %s les notifications");
    var request =
        new AgentReviewReReviewService.EditRequest(reviewAgainRequest(original, fixture), edit);
    assertThatThrownBy(() -> reviewWS.reopenAndSave(projectId, proposalId, request))
        .hasMessageContaining("integrity checks");
    assertThat(current(fixture).getId()).isEqualTo(currentId);
    assertThat(row(original).reviewStateRevision()).isEqualTo(revision);
    assertThat(row(original).agentReview().proposalId()).isEqualTo(original.getId());
    assertThat(proposals.findByFindingIdOrderByProposalRevisionAsc(original.getFindingId()))
        .hasSize(1);
    assertThat(feedback.findByProposalIdOrderByIdAsc(original.getId())).hasSize(1);
    assertThat(projectRepository.findById(projectId).orElseThrow().getDecidedCount()).isEqualTo(1L);
    assertThat(incidents.findById(original.getIncidentId()).orElseThrow().getStatus())
        .isEqualTo(TranslationIncidentStatus.CLOSED);
  }

  private void assertReviewAgain(AgentReviewDecisionRequest.Action action) throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(original.getReviewProjectTextUnitId(), decision(original, action));
    AgentReviewProposal decided = proposals.findById(original.getId()).orElseThrow();
    var beforeDecision = row(decided).reviewProjectTextUnitDecision();
    var originalFeedback = feedback.findByProposalIdOrderByIdAsc(original.getId()).getFirst();
    TMTextUnitVariant before = current(fixture);
    var request = reviewAgainRequest(decided, fixture);
    var result = reReviews.reviewAgain(decided.getReviewProjectId(), decided.getId(), request);
    AgentReviewProposal next = proposals.findById(result.proposalId()).orElseThrow();
    assertThat(next.getReviewProjectId()).isNotEqualTo(decided.getReviewProjectId());
    assertThat(next.getPreviousProposalId()).isEqualTo(decided.getId());
    assertThat(next.getFindingId()).isEqualTo(decided.getFindingId());
    assertThat(next.getIncidentId()).isEqualTo(decided.getIncidentId());
    assertThat(next.getBaselineVariantId()).isEqualTo(before.getId());
    assertThat(next.getBaselineTarget()).isEqualTo(before.getContent());
    assertThat(next.getSource()).isEqualTo("Enable notifications");
    assertThat(next.getProposedTarget()).isNull();
    assertThat(next.getVerifierIdentity()).isNull();
    assertThat(next.getVerificationRationale()).isNull();
    assertThat(next.getReadiness()).isEqualTo(Readiness.HUMAN_REVIEW);
    assertThat(next.getCategory()).isEqualTo(Category.HUMAN_REVIEW);
    assertThat(next.getDisposition()).isEqualTo(Disposition.ROUTED);
    assertThat(current(fixture).getId()).isEqualTo(before.getId());
    assertThat(row(decided).reviewProjectTextUnitDecision()).isEqualTo(beforeDecision);
    assertThat(proposals.findById(decided.getId()).orElseThrow().getDisposition())
        .isEqualTo(decided.getDisposition());
    assertThat(feedback.findById(originalFeedback.getId()).orElseThrow().getAction())
        .isEqualTo(originalFeedback.getAction());
    assertThat(row(next).reviewProjectTextUnitDecision()).isNull();
    assertThat(row(decided).agentReview().nextReviewProjectId()).isEqualTo(result.projectId());
    assertThat(row(decided).agentReview().canReviewAgain()).isFalse();
    assertThat(incidents.findById(next.getIncidentId()).orElseThrow().getStatus())
        .isEqualTo(TranslationIncidentStatus.OPEN);
    assertThat(reReviews.reviewAgain(decided.getReviewProjectId(), decided.getId(), request))
        .isEqualTo(result);
    assertThatThrownBy(
            () ->
                reReviews.reviewAgain(
                    decided.getReviewProjectId(),
                    decided.getId(),
                    reviewAgainRequest(decided, fixture)))
        .hasMessageContaining("newer review round");
    if (action == AgentReviewDecisionRequest.Action.REQUEST_REVISION) {
      assertThat(reviews.pendingFeedback(fixture.run().id(), 0, 50)).isEmpty();
    }
  }

  @Test
  public void reviewAgainRejectsChangedSourceOrCurrentWithoutCreatingRound() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.KEEP_CURRENT));
    AgentReviewProposal decided = proposals.findById(original.getId()).orElseThrow();
    var request = reviewAgainRequest(decided, fixture);
    var unit = units.findById(fixture.textUnitId()).orElseThrow();
    unit.setComment("New product context");
    units.saveAndFlush(unit);
    assertThatThrownBy(
            () -> reReviews.reviewAgain(decided.getReviewProjectId(), decided.getId(), request))
        .hasMessageContaining("source or current translation changed");
    assertThat(proposals.findByFindingIdOrderByProposalRevisionAsc(original.getFindingId()))
        .hasSize(1);
    assertThat(feedback.findByProposalIdOrderByIdAsc(original.getId())).hasSize(1);
  }

  @Test
  public void assignedTranslatorCanReviewAgainButUnassignedTranslatorCannot() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.KEEP_CURRENT));
    AgentReviewProposal decided = proposals.findById(original.getId()).orElseThrow();
    var reviewer =
        userService.createUserWithRole(
            "rereview-" + UUID.randomUUID(),
            "test-only-password",
            com.box.l10n.mojito.security.Role.ROLE_TRANSLATOR);
    var authentication =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    var principal = new com.box.l10n.mojito.security.UserDetailsImpl(reviewer);
    try {
      org.springframework.security.core.context.SecurityContextHolder.getContext()
          .setAuthentication(
              new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                  principal, principal.getPassword(), principal.getAuthorities()));
      assertThatThrownBy(
              () ->
                  reReviews.reviewAgain(
                      decided.getReviewProjectId(),
                      decided.getId(),
                      reviewAgainRequest(decided, fixture)))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.getContext()
          .setAuthentication(authentication);
    }
    var project = projectRepository.findById(decided.getReviewProjectId()).orElseThrow();
    project.setAssignedTranslatorUser(reviewer);
    projectRepository.saveAndFlush(project);
    try {
      org.springframework.security.core.context.SecurityContextHolder.getContext()
          .setAuthentication(
              new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                  principal, principal.getPassword(), principal.getAuthorities()));
      var result =
          reReviews.reviewAgain(
              decided.getReviewProjectId(), decided.getId(), reviewAgainRequest(decided, fixture));
      assertThat(projects.getProjectDetail(result.projectId()).reviewProjectTextUnits()).hasSize(1);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.getContext()
          .setAuthentication(authentication);
    }
  }

  @Test
  public void acceptedStateSuppressesFreshAgentFindingAcrossRuns() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.ACCEPT));
    var receipt = feedback.findByProposalIdOrderByIdAsc(original.getId()).getFirst();
    assertThat(receipt.getReviewedStateFingerprint()).hasSize(64);
    Fixture nextRun = followUp(fixture);
    assertThatThrownBy(() -> submitCurrent(nextRun))
        .hasMessageContaining("already been reviewed by a human");
    assertThat(proposals.findByRunIdOrderByIdAsc(nextRun.run().id())).isEmpty();
  }

  @Test
  public void immediateRoutingPreservesStaleProposalsWithoutCreatingIncidents() throws Exception {
    assertHistoricalReportsDoNotRoute(RoutingPolicy.IMMEDIATE);
  }

  @Test
  public void queuedRoutingCreatesNoStaleIncidentAndBatchSelectsOnlyCurrentTranslation()
      throws Exception {
    assertHistoricalReportsDoNotRoute(RoutingPolicy.QUEUED);
  }

  private void assertHistoricalReportsDoNotRoute(RoutingPolicy policy) throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.ACCEPT));
    Long acceptedVariantId = current(fixture).getId();
    assertThat(current(fixture).getContent()).isEqualTo("Activer les notifications");
    assertThat(
            feedback
                .findByProposalIdOrderByIdAsc(original.getId())
                .getFirst()
                .getAppliedVariantId())
        .isEqualTo(acceptedVariantId);

    // The accepted B receipt must not hide a stale A finding once an unreviewed C is current.
    var current =
        tmService.addCurrentTMTextUnitVariant(
            fixture.textUnitId(),
            fixture.localeId(),
            "Autoriser les notifications",
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            true);
    assertThat(current.getId()).isNotIn(fixture.originalVariantId(), acceptedVariantId);
    Fixture historicalRun = followUp(fixture, policy);
    AgentReviewProposal historical = submit(historicalRun, null, null);
    assertThat(historical.getId()).isNotEqualTo(original.getId());
    assertThat(historical.getBaselineVariantId()).isEqualTo(fixture.originalVariantId());
    assertThat(historical.getBaselineTarget()).isEqualTo("Désactiver les notifications");
    complete(historicalRun);

    var historicalResult = routing.routeRun(historicalRun.run().id());
    assertThat(historicalResult.projectIds()).isEmpty();
    assertThat(historicalResult.proposalCount()).isZero();
    assertThat(historicalResult.skippedCount()).isEqualTo(1);
    assertThat(historicalResult.errors())
        .containsExactly(
            "Finding " + historical.getFindingId() + ": current string changed since the finding.");
    historical = proposals.findById(historical.getId()).orElseThrow();
    assertThat(historical.getDisposition()).isEqualTo(Disposition.OPEN);
    assertThat(historical.getReviewProjectId()).isNull();
    assertThat(historical.getReviewProjectTextUnitId()).isNull();
    assertThat(historical.getIncidentId()).isNull();
    assertThat(incidents.findByReviewFindingId(historical.getFindingId())).isEmpty();

    var retry = routing.routeRun(historicalRun.run().id());
    assertThat(retry.projectIds()).isEmpty();
    assertThat(retry.skippedCount()).isEqualTo(1);
    assertThat(retry.errors()).containsExactlyElementsOf(historicalResult.errors());
    assertThat(proposals.findById(historical.getId()).orElseThrow().getIncidentId()).isNull();
    assertThat(incidents.findByReviewFindingId(historical.getFindingId())).isEmpty();

    Fixture currentRun = followUp(fixture, policy);
    AgentReviewProposal currentProposal = submitCurrent(currentRun);
    assertThat(currentProposal.getBaselineVariantId()).isEqualTo(current.getId());
    complete(currentRun);
    var currentResult = routing.routeRun(currentRun.run().id());
    assertThat(currentResult.errors()).isEmpty();
    assertThat(currentResult.skippedCount()).isZero();
    if (policy == RoutingPolicy.QUEUED) {
      assertThat(currentResult.projectIds()).isEmpty();
      assertThat(currentResult.proposalCount()).isZero();
      var queued = proposals.findById(currentProposal.getId()).orElseThrow();
      assertThat(queued.getIncidentId()).isNotNull();
      assertThat(queued.getReviewProjectId()).isNull();
      var batch =
          batches.create(
              new IncidentReviewBatchService.Request(
                  fixture.run().repositoryIds(),
                  null,
                  List.of("fr-FR"),
                  null,
                  fixture.run().reviewType(),
                  fixture.run().teamId(),
                  "Current translation review",
                  ZonedDateTime.now().plusDays(3),
                  1500,
                  false,
                  ReviewProjectType.BUG_FIXES,
                  null,
                  null),
              teamService.getCurrentUserIdOrThrow());
      assertThat(batch.eligibleIncidentCount()).isEqualTo(1);
      assertThat(batch.projectIds()).hasSize(1);
      assertThat(batch.skipped()).isEmpty();
    } else {
      assertThat(currentResult.projectIds()).hasSize(1);
      assertThat(currentResult.proposalCount()).isEqualTo(1);
    }
    var routedCurrent = proposals.findById(currentProposal.getId()).orElseThrow();
    assertThat(routedCurrent.getDisposition()).isEqualTo(Disposition.ROUTED);
    assertThat(row(routedCurrent).agentReview().reviewedTarget())
        .isEqualTo("Autoriser les notifications");
    assertThat(current(fixture).getId()).isEqualTo(current.getId());
    assertThat(proposals.findById(historical.getId()).orElseThrow().getReviewProjectId()).isNull();
    assertThat(proposals.findById(historical.getId()).orElseThrow().getIncidentId()).isNull();
    assertThat(incidents.findByReviewFindingId(historical.getFindingId())).isEmpty();
  }

  @Test
  public void staleKeepCurrentReceiptsActualNewSourceAndTranslationNotOldBaseline()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    var unit = units.findById(fixture.textUnitId()).orElseThrow();
    unit.setContent("Manage notifications");
    unit.setComment("The settings section");
    units.saveAndFlush(unit);
    tmService.addCurrentTMTextUnitVariant(
        fixture.textUnitId(),
        fixture.localeId(),
        "Gérer les notifications",
        TMTextUnitVariant.Status.REVIEW_NEEDED,
        false);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.KEEP_CURRENT));
    var receipt = feedback.findByProposalIdOrderByIdAsc(original.getId()).getFirst();
    var current = current(fixture);
    assertThat(receipt.getReviewedStateFingerprint())
        .isEqualTo(
            AgentReviewStateFingerprint.of(
                fixture.textUnitId(),
                fixture.localeId(),
                "Manage notifications",
                "The settings section",
                current.getId(),
                current.getContent(),
                "REVIEW_NEEDED",
                false));
    Fixture nextRun = followUp(fixture);
    assertThatThrownBy(() -> submitCurrent(nextRun))
        .hasMessageContaining("already been reviewed by a human");
    unit = units.findById(fixture.textUnitId()).orElseThrow();
    unit.setComment("Changed context after the human review");
    units.saveAndFlush(unit);
    assertThat(submitCurrent(nextRun).getId()).isNotNull();
  }

  @Test
  public void pendingConcernIsReusedAcrossRunsAndAliasReplaysAfterDecision() throws Exception {
    Fixture first = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = submit(first, null, null);
    Fixture second = followUp(first);
    assertThat(submit(second, null, null).getId()).isEqualTo(original.getId());
    assertThat(proposals.findByRunIdOrderByIdAsc(second.run().id())).isEmpty();
    complete(first);
    routing.routeRun(first.run().id());
    original = proposals.findById(original.getId()).orElseThrow();
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.KEEP_CURRENT));
    assertThat(submit(second, null, null).getId()).isEqualTo(original.getId());
    assertThat(proposals.findById(original.getId()).orElseThrow().getActiveIntakeFingerprint())
        .isNull();
    assertThat(proposals.findById(original.getId()).orElseThrow().getIntakeFingerprint())
        .hasSize(64);
  }

  @Test
  public void cancelledUnroutedDraftReleasesIdentityWithoutErasingHistory() throws Exception {
    Fixture first = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = submit(first, null, null);
    reviews.finishRun(
        first.run().id(),
        new FinishRequest(first.claim(), reviews.getRun(first.run().id()).revision(), true));
    assertThat(proposals.findById(original.getId()).orElseThrow().getActiveIntakeFingerprint())
        .isNull();
    assertThat(proposals.findById(original.getId()).orElseThrow().getIntakeFingerprint())
        .hasSize(64);
    assertThat(submit(followUp(first), null, null).getId()).isNotEqualTo(original.getId());
  }

  @Test
  public void concurrentFreshRunsReuseOnePendingFinding() throws Exception {
    Fixture first = fixture("TRANSLATION_QUALITY");
    Fixture second = followUp(first);
    var authentication =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Long> one =
          () -> concurrentSubmit(first, authentication, start);
      java.util.concurrent.Callable<Long> two =
          () -> concurrentSubmit(second, authentication, start);
      var a = executor.submit(one);
      var b = executor.submit(two);
      start.countDown();
      assertThat(a.get(20, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(b.get(20, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(
              proposals.findByRunIdOrderByIdAsc(first.run().id()).size()
                  + proposals.findByRunIdOrderByIdAsc(second.run().id()).size())
          .isEqualTo(1);
    }
  }

  private Long concurrentSubmit(
      Fixture fixture,
      org.springframework.security.core.Authentication authentication,
      java.util.concurrent.CountDownLatch start)
      throws Exception {
    var context =
        org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    org.springframework.security.core.context.SecurityContextHolder.setContext(context);
    try {
      start.await(10, java.util.concurrent.TimeUnit.SECONDS);
      return submit(fixture, null, null).getId();
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test
  public void distinctConcernsRemainIndependentAndStableRuleKeySurvivesRewording()
      throws Exception {
    Fixture first = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal spelling =
        submitConcern(first, "spelling", "spelling:notifications", "Spelling error");
    AgentReviewProposal meaning =
        submitConcern(first, "meaning", "meaning:negation", "Wrong meaning");
    assertThat(spelling.getId()).isNotEqualTo(meaning.getId());
    assertThat(
            submitConcern(
                    followUp(first),
                    "rescan",
                    "spelling:notifications",
                    "Same spelling defect explained differently")
                .getId())
        .isEqualTo(spelling.getId());
    assertThat(submitConcern(first, "reason-a", null, "A separate concern").getId())
        .isNotEqualTo(
            submitConcern(first, "reason-b", null, "Another substantive concern").getId());
  }

  @Test
  public void reviewedReceiptsBelongOnlyToOwningTeamAndTypeAndGenericScopeIsExplicit()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = routed(fixture);
    reviewWS.saveDecision(
        original.getReviewProjectTextUnitId(),
        decision(original, AgentReviewDecisionRequest.Action.KEEP_CURRENT));
    String state =
        feedback
            .findByProposalIdOrderByIdAsc(original.getId())
            .getFirst()
            .getReviewedStateFingerprint();
    Long otherTeam = teamService.createTeam(testIdWatcher.getEntityName("other-team")).getId();
    assertThat(reviewedStates.isReviewed(fixture.run().teamId(), "TRANSLATION_QUALITY", state))
        .isTrue();
    assertThat(reviewedStates.isReviewed(otherTeam, "TRANSLATION_QUALITY", state)).isFalse();
    assertThat(reviewedStates.isReviewed(fixture.run().teamId(), "OTHER_REVIEW", state)).isFalse();
    assertThat(reviewedStates.isReviewed(null, "TRANSLATION_QUALITY", state)).isFalse();
    String repository =
        repositoryRepository
            .findById(fixture.run().repositoryIds().getFirst())
            .orElseThrow()
            .getName();
    assertThatThrownBy(
            () ->
                incidentService.createIncident(
                    new com.box.l10n.mojito.service.badtranslation.TranslationIncidentService
                        .CreateIncidentRequest(
                        "enable",
                        "fr-FR",
                        repository,
                        "Reported issue",
                        null,
                        fixture.run().teamId(),
                        "TRANSLATION_QUALITY",
                        null)))
        .hasMessageContaining("already been reviewed");
    assertThat(
            incidentService
                .createIncident(
                    new com.box.l10n.mojito.service.badtranslation.TranslationIncidentService
                        .CreateIncidentRequest(
                        "enable", "fr-FR", repository, "Reported issue", null))
                .id())
        .isNotNull();
  }

  @Test
  public void genericIntakeReusesPendingIncidentAndPreservesDistinctReasons() throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    String repository =
        repositoryRepository
            .findById(fixture.run().repositoryIds().getFirst())
            .orElseThrow()
            .getName();
    var request =
        new com.box.l10n.mojito.service.badtranslation.TranslationIncidentService
            .CreateIncidentRequest(
            "enable",
            "fr-FR",
            repository,
            "Wrong meaning",
            null,
            fixture.run().teamId(),
            null,
            null);
    var first = incidentService.createIncident(request);
    assertThat(first.lookupResolutionStatus()).isEqualTo("UNIQUE_MATCH");
    assertThat(first.selectedTmTextUnitId()).isEqualTo(fixture.textUnitId());
    var duplicate =
        incidentService.createIncident(
            new com.box.l10n.mojito.service.badtranslation.TranslationIncidentService
                .CreateIncidentRequest(
                "enable",
                "fr-FR",
                repository,
                "  Wrong   meaning ",
                "different-source-reference",
                fixture.run().teamId(),
                null,
                null));
    assertThat(duplicate.id()).isEqualTo(first.id());
    var different =
        incidentService.createIncident(
            new com.box.l10n.mojito.service.badtranslation.TranslationIncidentService
                .CreateIncidentRequest(
                "enable",
                "fr-FR",
                repository,
                "Spelling defect",
                null,
                fixture.run().teamId(),
                null,
                null));
    assertThat(different.id()).isNotEqualTo(first.id());
    incidentService.updateStatus(
        first.id(),
        new com.box.l10n.mojito.service.badtranslation.TranslationIncidentService
            .UpdateStatusRequest(TranslationIncidentStatus.CLOSED));
    // Legacy/manual closure is not a guarded human decision and must not invent a receipt.
    assertThat(incidentService.createIncident(request).id()).isNotEqualTo(first.id());
  }

  @Test
  public void recentLegacyPendingFindingIsLazilyKeyedWithoutCollapsingOtherConcern()
      throws Exception {
    Fixture fixture = fixture("TRANSLATION_QUALITY");
    AgentReviewProposal original = submit(fixture, null, null);
    original.setIntakeFingerprint(null);
    proposals.saveAndFlush(original);
    assertThat(submit(followUp(fixture), null, null).getId()).isEqualTo(original.getId());
    assertThat(proposals.findById(original.getId()).orElseThrow().getIntakeFingerprint())
        .hasSize(64);
  }

  private AgentReviewProposal submitConcern(
      Fixture fixture, String submissionKey, String concernKey, String reason) {
    var unit = units.findById(fixture.textUnitId()).orElseThrow();
    var current = current(fixture);
    return reviews.submitProposal(
        fixture.run().id(),
        new SubmitProposalRequest(
            fixture.claim(),
            submissionKey,
            "group-1",
            fixture.textUnitId(),
            unit.getContent(),
            unit.getComment(),
            current.getId(),
            current.getContent(),
            current.getStatus().name(),
            current.isIncludedInLocalizedFile(),
            "Suggested target",
            Category.OBVIOUS_ERROR,
            Readiness.READY,
            reason,
            "[]",
            "producer",
            "verifier",
            "Checked independently",
            null,
            null,
            null,
            concernKey));
  }

  private AgentReviewProposal submitCurrent(Fixture fixture) {
    var unit = units.findById(fixture.textUnitId()).orElseThrow();
    var current = current(fixture);
    return reviews.submitProposal(
        fixture.run().id(),
        new SubmitProposalRequest(
            fixture.claim(),
            "new-finding",
            "group-1",
            fixture.textUnitId(),
            unit.getContent(),
            unit.getComment(),
            current.getId(),
            current.getContent(),
            current.getStatus().name(),
            current.isIncludedInLocalizedFile(),
            "A proposed correction",
            Category.OBVIOUS_ERROR,
            Readiness.READY,
            "A possible issue",
            "[]",
            "producer",
            "verifier",
            "Checked the finding",
            null,
            null,
            null));
  }

  private AgentReviewReReviewService.Request reviewAgainRequest(
      AgentReviewProposal proposal, Fixture fixture) {
    var unit = units.findById(fixture.textUnitId()).orElseThrow();
    var current = current(fixture);
    return new AgentReviewReReviewService.Request(
        UUID.randomUUID().toString(),
        proposal.getVersion(),
        current.getId(),
        unit.getContent(),
        unit.getComment(),
        current.getContent(),
        current.getStatus().name(),
        current.isIncludedInLocalizedFile());
  }

  private Fixture followUp(Fixture previous) {
    return followUp(previous, RoutingPolicy.IMMEDIATE);
  }

  private Fixture followUp(Fixture previous, RoutingPolicy routingPolicy) {
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
                false,
                routingPolicy));
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
    repositoryLocale.setParentLocale(
        repositoryLocales.findByRepositoryAndParentLocaleIsNull(repository));
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
