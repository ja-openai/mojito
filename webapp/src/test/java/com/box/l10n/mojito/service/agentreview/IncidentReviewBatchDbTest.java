package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.review.ReviewFeatureRepository;
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

public class IncidentReviewBatchDbTest extends ServiceTestBase {
  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();
  @Autowired private RepositoryService repositoryService;
  @Autowired private RepositoryLocaleRepository repositoryLocales;
  @Autowired private AssetService assets;
  @Autowired private LocaleService locales;
  @Autowired private TeamService teams;
  @Autowired private TMService tm;
  @Autowired private TMTextUnitCurrentVariantRepository currents;
  @Autowired private TranslationIncidentRepository incidents;
  @Autowired private AgentReviewProposalRepository proposals;
  @Autowired private AgentReviewRunRepository runs;
  @Autowired private IncidentReviewBatchService batches;
  @Autowired private AgentReviewService reviews;
  @Autowired private AgentReviewProjectService routing;
  @Autowired private ReviewProjectService projects;
  @Autowired private ReviewFeatureRepository features;
  @Autowired private com.box.l10n.mojito.rest.review.ReviewProjectWS reviewWS;
  @Autowired private IncidentReviewBatchCursorRepository cursors;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  public void ordinaryApprovedIncidentUsesHumanIntakeAndPreservesCurrentAndProjectSettings()
      throws Exception {
    Fixture f = fixture();
    TranslationIncident incident = incident(f, "Reported concern needing human assessment");
    var request = request(f);
    var preview = batches.preview(request, teams.getCurrentUserIdOrThrow());
    assertThat(preview.eligibleIncidentCount()).isEqualTo(1);
    assertThat(preview.projectCount()).isEqualTo(1);
    assertThat(incidents.findById(incident.getId()).orElseThrow().getReviewFindingId()).isNull();
    var created = batches.create(request, teams.getCurrentUserIdOrThrow());
    assertThat(created.projectCount()).isEqualTo(preview.projectCount());
    assertThat(created.requestIds()).hasSize(1);
    var saved = incidents.findById(incident.getId()).orElseThrow();
    var proposal =
        proposals.findByFindingIdOrderByProposalRevisionAsc(saved.getReviewFindingId()).getFirst();
    assertThat(proposal.getIncidentId()).isEqualTo(incident.getId());
    assertThat(proposal.getReadiness()).isEqualTo(Readiness.HUMAN_REVIEW);
    assertThat(proposal.getCategory()).isEqualTo(Category.HUMAN_REVIEW);
    assertThat(proposal.getProposedTarget()).isNull();
    assertThat(proposal.getVerifierIdentity()).isNull();
    assertThat(proposal.getVerificationRationale()).isNull();
    assertThat(proposal.getRationale()).isEqualTo(incident.getReason());
    var detail = projects.getProjectDetail(created.projectIds().getFirst());
    assertThat(detail.type()).isEqualTo(ReviewProjectType.BUG_FIXES);
    assertThat(detail.dueDate()).isEqualToIgnoringNanos(request.dueDate());
    assertThat(detail.reviewProjectRequest().name()).isEqualTo(request.name());
    assertThat(detail.reviewProjectRequest().notes()).isEqualTo(request.notes());
    assertThat(detail.reviewProjectRequest().screenshotImageIds())
        .containsExactly("synthetic-image");
    assertThat(detail.reviewProjectTextUnits()).hasSize(1);
    assertThat(detail.reviewProjectTextUnits().getFirst().agentReview().reviewedTarget())
        .isEqualTo("Enregistrer les modifications");
    assertThat(current(f).getId()).isEqualTo(f.variantId());
    assertThat(current(f).getStatus()).isEqualTo(TMTextUnitVariant.Status.APPROVED);
    assertThat(batches.create(request, teams.getCurrentUserIdOrThrow()).projectIds()).isEmpty();
  }

  @Test
  public void distinctIncidentFindingsForSameStringUseSeparateWavesAndFeatureRetryDoesNotRepeat()
      throws Exception {
    Fixture f = fixture();
    incident(f, "First concern");
    incident(f, "Separate concern");
    var preview = batches.preview(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(preview.eligibleIncidentCount()).isEqualTo(2);
    assertThat(preview.projectCount()).isEqualTo(2);
    assertThat(batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds()).hasSize(2);
    ReviewFeature feature = new ReviewFeature();
    feature.setName(testIdWatcher.getEntityName("feature"));
    feature.setRepositories(Set.of(f.repository()));
    feature = features.save(feature);
    var base = request(f);
    var byFeature =
        new IncidentReviewBatchService.Request(
            null,
            List.of(feature.getId()),
            null,
            null,
            base.reviewType(),
            base.teamId(),
            base.name(),
            base.dueDate(),
            1500,
            false,
            base.type(),
            base.notes(),
            base.screenshotImageIds());
    assertThat(batches.create(byFeature, teams.getCurrentUserIdOrThrow()).projectIds()).isEmpty();
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  @Test
  public void staleAndFallbackIncidentsAreExplainedWithoutMutation() throws Exception {
    Fixture f = fixture();
    TranslationIncident stale = incident(f, "Old captured target");
    stale.setSelectedTarget("Different text");
    incidents.save(stale);
    TranslationIncident fallback = incident(f, "Fallback lookup");
    fallback.setLocaleUsedFallback(true);
    incidents.save(fallback);
    var result = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(result.projectIds()).isEmpty();
    assertThat(result.skippedIncidentCount()).isEqualTo(2);
    assertThat(result.skipped())
        .extracting(IncidentReviewBatchService.Skipped::reason)
        .containsExactly(
            "Current string changed since the incident",
            "Incident needs an exact string and locale match");
    assertThat(incidents.findById(stale.getId()).orElseThrow().getSelectedTarget())
        .isEqualTo("Different text");
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  @Test
  public void queuedAgentRunCreatesIncidentWithoutProjectUntilBatchAndPreservesRun()
      throws Exception {
    Fixture f = fixture();
    String reviewType = testReviewType();
    var run =
        reviews.createRun(
            new AgentReviewContracts.CreateRunRequest(
                UUID.randomUUID().toString(),
                reviewType,
                f.teamId(),
                "fixture",
                "fixture",
                List.of(
                    new AgentReviewContracts.Group(
                        "group",
                        f.repository().getId(),
                        f.localeId(),
                        "feature",
                        List.of(f.unitId()),
                        "snapshot")),
                "{}",
                7,
                1500,
                false,
                RoutingPolicy.QUEUED));
    run = reviews.claimRun(run.id(), new AgentReviewContracts.ClaimRequest("fixture", 0L, 900));
    var claim = new AgentReviewContracts.Claim(run.claimOwner(), run.claimGeneration());
    var proposal =
        reviews.submitProposal(
            run.id(),
            new AgentReviewContracts.SubmitProposalRequest(
                claim,
                "finding",
                "group",
                f.unitId(),
                "Save changes",
                null,
                f.variantId(),
                "Enregistrer les modifications",
                "APPROVED",
                true,
                "Sauvegarder les modifications",
                Category.CONSISTENCY_ERROR,
                Readiness.READY,
                "Synthetic fixture concern",
                "[]",
                "fixture-producer",
                "fixture-verifier",
                "Synthetic test fixture",
                null,
                null,
                null));
    String encoded = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
    String hash =
        reviews
            .uploadArtifact(
                run.id(),
                new AgentReviewContracts.ArtifactRequest(claim, "application/json", encoded))
            .sha256();
    reviews.checkpoint(
        run.id(),
        new AgentReviewContracts.CheckpointRequest(
            claim,
            run.revision(),
            "group",
            AgentReviewContracts.GroupStatus.COMPLETED,
            1,
            hash,
            "Synthetic fixture completed"));
    assertThat(reviews.getRun(run.id()).routingPolicy()).isEqualTo(RoutingPolicy.QUEUED);
    assertThat(routing.routeRun(run.id()).projectIds()).isEmpty();
    proposal = proposals.findById(proposal.getId()).orElseThrow();
    assertThat(proposal.getIncidentId()).isNotNull();
    assertThat(proposal.getDisposition()).isEqualTo(Disposition.OPEN);
    assertThat(proposal.getReviewProjectId()).isNull();
    var otherTeam = teams.createTeam(testIdWatcher.getEntityName("other-team"));
    var defaults = request(f);
    var otherTeamGlobal =
        new IncidentReviewBatchService.Request(
            null,
            null,
            null,
            null,
            reviewType,
            otherTeam.getId(),
            defaults.name(),
            defaults.dueDate(),
            null,
            false,
            defaults.type(),
            null,
            null,
            true);
    assertThat(
            batches
                .preview(otherTeamGlobal, teams.getCurrentUserIdOrThrow())
                .eligibleIncidentCount())
        .isZero();
    var result = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(result.projectIds()).hasSize(1);
    var routed = proposals.findById(proposal.getId()).orElseThrow();
    assertThat(routed.getRunId()).isEqualTo(run.id());
    assertThat(routed.getIncidentId()).isEqualTo(proposal.getIncidentId());
    assertThat(routing.routeRun(run.id()).proposalCount()).isZero();
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  @Test
  public void staleIncidentStatusSaveCannotEraseReviewAssignment() throws Exception {
    Fixture f = fixture();
    TranslationIncident stale = incident(f, "Concern");
    var created = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    stale.setStatus(TranslationIncidentStatus.CLOSED);
    assertThatThrownBy(() -> incidents.saveAndFlush(stale))
        .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    assertThat(incidents.findById(stale.getId()).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(created.projectIds().getFirst());
  }

  @Test
  public void twoConcurrentBatchesAssignEachIncidentOnlyOnce() throws Exception {
    Fixture f = fixture();
    incident(f, "One incident shared by two requests");
    var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
    Long actor = teams.getCurrentUserIdOrThrow();
    var start = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    java.util.concurrent.Callable<IncidentReviewBatchService.Result> task =
        () -> {
          org.springframework.security.core.context.SecurityContextHolder.setContext(security);
          try {
            start.await();
            return batches.create(request(f), actor);
          } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
          }
        };
    try {
      var first = executor.submit(task);
      var second = executor.submit(task);
      start.countDown();
      assertThat(
              first.get(30, java.util.concurrent.TimeUnit.SECONDS).projectCount()
                  + second.get(30, java.util.concurrent.TimeUnit.SECONDS).projectCount())
          .isEqualTo(1);
      assertThat(current(f).getId()).isEqualTo(f.variantId());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void newIncidentCannotBypassARecordedHumanDecisionForUnchangedCurrentText()
      throws Exception {
    Fixture f = fixture();
    incident(f, "First reported concern");
    var created = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    var row =
        projects
            .getProjectDetail(created.projectIds().getFirst())
            .reviewProjectTextUnits()
            .getFirst();
    var decision = new com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest();
    decision.setDecisionState(
        com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.DECIDED);
    decision.setExpectedCurrentTmTextUnitVariantId(row.currentTmTextUnitVariant().id());
    decision.setExpectedReviewStateRevision(row.reviewStateRevision());
    decision.setAgentReview(
        new AgentReviewDecisionRequest(
            row.agentReview().proposalId(),
            row.agentReview().proposalRevision(),
            row.agentReview().proposalVersion(),
            UUID.randomUUID().toString(),
            AgentReviewDecisionRequest.Action.KEEP_CURRENT,
            null,
            null,
            "Current translation is correct"));
    reviewWS.saveDecision(row.id(), decision);
    incident(f, "Another report for exactly the same state");
    var retried = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(retried.projectIds()).isEmpty();
    assertThat(retried.skipped())
        .extracting(IncidentReviewBatchService.Skipped::reason)
        .containsExactly(
            "Current translation was already reviewed; use Review again for a new round");
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  @Test
  public void explicitAllRepositoriesCollectsOneTypeAcrossRepositoriesForTheSelectedTeam()
      throws Exception {
    Fixture first = fixture();
    Fixture second = fixture("second");
    String type = "GLOBAL_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    TranslationIncident one = incident(first, "First repository concern");
    one.setReviewType(type);
    incidents.save(one);
    TranslationIncident two = incident(second, "Second repository concern");
    two.setReviewType(type);
    incidents.save(two);
    TranslationIncident otherType = incident(first, "Different review type");
    otherType.setReviewType(type + "_OTHER");
    incidents.save(otherType);
    var base = request(first);
    var all =
        new IncidentReviewBatchService.Request(
            null,
            null,
            List.of("fr-FR"),
            null,
            type,
            first.teamId(),
            base.name(),
            base.dueDate(),
            null,
            false,
            base.type(),
            base.notes(),
            null,
            true);
    var preview = batches.preview(all, teams.getCurrentUserIdOrThrow());
    assertThat(preview.eligibleIncidentCount()).isEqualTo(2);
    assertThat(preview.projectCount()).isEqualTo(2);
    var created = batches.create(all, teams.getCurrentUserIdOrThrow());
    assertThat(created.projectIds()).hasSize(2);
    assertThat(incidents.findById(otherType.getId()).orElseThrow().getResolutionReviewProjectId())
        .isNull();
    for (Long id : created.projectIds()) {
      var detail = projects.getProjectDetail(id);
      assertThat(detail.assignment().teamId()).isEqualTo(first.teamId());
      assertThat(detail.reviewProjectTextUnits()).hasSize(1);
    }
    assertThat(current(first).getId()).isEqualTo(first.variantId());
    assertThat(current(second).getId()).isEqualTo(second.variantId());
    assertThat(batches.create(all, teams.getCurrentUserIdOrThrow()).projectIds()).isEmpty();
    var ambiguous =
        new IncidentReviewBatchService.Request(
            List.of(first.repository().getId()),
            null,
            null,
            null,
            type,
            first.teamId(),
            base.name(),
            base.dueDate(),
            null,
            false,
            base.type(),
            null,
            null,
            true);
    assertThatThrownBy(() -> batches.preview(ambiguous, teams.getCurrentUserIdOrThrow()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be combined");
  }

  @Test
  public void requiresExplicitSingleScopeAndHonorsLocaleExclusion() throws Exception {
    Fixture f = fixture();
    incident(f, "Concern");
    var base = request(f);
    var excluded =
        new IncidentReviewBatchService.Request(
            base.repositoryIds(),
            null,
            null,
            List.of("fr-FR"),
            null,
            base.teamId(),
            base.name(),
            base.dueDate(),
            1500,
            false,
            base.type(),
            null,
            null);
    assertThat(batches.preview(excluded, teams.getCurrentUserIdOrThrow()).eligibleIncidentCount())
        .isZero();
    var invalid =
        new IncidentReviewBatchService.Request(
            null,
            null,
            null,
            null,
            null,
            base.teamId(),
            base.name(),
            base.dueDate(),
            1500,
            false,
            base.type(),
            null,
            null);
    assertThatThrownBy(() -> batches.create(invalid, teams.getCurrentUserIdOrThrow()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Choose repositoryIds or reviewFeatureIds");
  }

  @Test
  public void largeQueueAdvancesPastSkippedPagesAndKeepsAFiniteSweep() throws Exception {
    Fixture f = fixture();
    var stale = incident(f, "Synthetic stale queue fixture");
    stale.setSelectedTarget("Older captured translation");
    stale = incidents.saveAndFlush(stale);
    Long staleId = stale.getId();
    // More than the previous 10,000-row rejection threshold, without 10,000 service calls.
    cloneIncidentRows(staleId, 10000);
    TranslationIncident eligible = incident(f, "Eligible after ten thousand stale incidents");
    var request = request(f);
    Long actor = teams.getCurrentUserIdOrThrow();
    long cursorCount = cursors.count();
    var preview = batches.preview(request, actor);
    assertThat(preview.scannedIncidentCount()).isEqualTo(500);
    assertThat(preview.eligibleIncidentCount()).isZero();
    assertThat(preview.hasMore()).isTrue();
    assertThat(cursors.count()).isEqualTo(cursorCount);

    var first = batches.create(request, actor);
    assertThat(first.skipped()).isEqualTo(preview.skipped());
    assertThat(first.scannedIncidentCount()).isEqualTo(500);
    assertThat(first.projectIds()).isEmpty();
    var savedCursor =
        cursors.findAll().stream()
            .filter(c -> c.getTeamId().equals(f.teamId()))
            .findFirst()
            .orElseThrow();
    long afterFirst = savedCursor.getLastScannedIncidentId();
    var nextPreview = batches.preview(request, actor);
    assertThat(nextPreview.skipped().getFirst().incidentId()).isGreaterThan(afterFirst);
    assertThat(cursors.findById(savedCursor.getId()).orElseThrow().getLastScannedIncidentId())
        .isEqualTo(afterFirst);
    // Continuous arrivals cannot prevent the original sweep from reaching its end and wrapping.
    TranslationIncident later = incident(f, "Arrived after this sweep began");
    int scanned = first.scannedIncidentCount();
    int projectCount = 0;
    var slice = first;
    int calls = 1;
    while (slice.hasMore()) {
      assertThat(calls++).isLessThan(25);
      slice = batches.create(request, actor);
      assertThat(slice.scannedIncidentCount()).isLessThanOrEqualTo(500);
      scanned += slice.scannedIncidentCount();
      projectCount += slice.projectCount();
    }
    assertThat(scanned).isEqualTo(10002);
    assertThat(projectCount).isEqualTo(1);
    assertThat(incidents.findById(eligible.getId()).orElseThrow().getResolutionReviewProjectId())
        .isNotNull();
    assertThat(incidents.findById(later.getId()).orElseThrow().getResolutionReviewProjectId())
        .isNull();
    var unchanged = incidents.findById(staleId).orElseThrow();
    assertThat(unchanged.getStatus()).isEqualTo(TranslationIncidentStatus.OPEN);
    assertThat(unchanged.getResolutionReviewProjectId()).isNull();
    assertThat(unchanged.getSelectedTarget()).isEqualTo("Older captured translation");
    assertThat(current(f).getId()).isEqualTo(f.variantId());
    assertThat(current(f).getContent()).isEqualTo("Enregistrer les modifications");
    assertThat(current(f).getStatus()).isEqualTo(TMTextUnitVariant.Status.APPROVED);
    var ended = cursors.findById(savedCursor.getId()).orElseThrow();
    assertThat(ended.getLastScannedIncidentId()).isZero();
    assertThat(ended.getSweepUpperBoundId()).isZero();
    assertThat(batches.preview(request, actor).skipped().getFirst().incidentId())
        .isEqualTo(staleId);
  }

  @Test
  public void projectLimitAdvancesOnlyTheProcessedPrefixAndRepositoryFeatureScopesShareProgress()
      throws Exception {
    Fixture f = fixture();
    for (int i = 0; i < 28; i++) incident(f, "Distinct synthetic concern " + i);
    Long actor = teams.getCurrentUserIdOrThrow();
    var first = batches.preview(request(f), actor);
    assertThat(first.projectCount()).isEqualTo(25);
    assertThat(first.scannedIncidentCount()).isEqualTo(25);
    assertThat(first.hasMore()).isTrue();
    assertThat(batches.create(request(f), actor).projectIds()).hasSize(25);
    ReviewFeature feature = new ReviewFeature();
    feature.setName(testIdWatcher.getEntityName("bounded-feature"));
    feature.setRepositories(Set.of(f.repository()));
    feature = features.save(feature);
    var original = request(f);
    var equivalent =
        new IncidentReviewBatchService.Request(
            null,
            List.of(feature.getId()),
            original.localeTags(),
            original.excludedLocaleTags(),
            original.reviewType(),
            original.teamId(),
            "Another display name",
            original.dueDate(),
            original.maxWordCountPerProject(),
            original.assignTranslator(),
            original.type(),
            null,
            null);
    var remainder = batches.create(equivalent, actor);
    assertThat(remainder.projectIds()).hasSize(3);
    assertThat(remainder.scannedIncidentCount()).isEqualTo(3);
    assertThat(remainder.hasMore()).isFalse();
    assertThat(cursors.findAll().stream().filter(c -> c.getTeamId().equals(f.teamId()))).hasSize(1);
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  @Test
  public void overlappingGlobalAndRepositoryBatchesCannotAssignAnIncidentTwice() throws Exception {
    Fixture f = fixture();
    String type = testReviewType();
    var base = request(f);
    var global =
        new IncidentReviewBatchService.Request(
            null,
            null,
            null,
            null,
            type,
            f.teamId(),
            base.name(),
            base.dueDate(),
            1500,
            false,
            base.type(),
            null,
            null,
            true);
    var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
    Long actor = teams.getCurrentUserIdOrThrow();
    // Existing independent cursors avoid serializing the test on first-time team setup.
    batches.create(base, actor);
    batches.create(global, actor);
    var incident = incident(f, "One incident shared by overlapping selection scopes");
    incident.setReviewType(type);
    incidents.saveAndFlush(incident);
    var start = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    java.util.function.Function<
            IncidentReviewBatchService.Request,
            java.util.concurrent.Callable<IncidentReviewBatchService.Result>>
        task =
            request ->
                () -> {
                  org.springframework.security.core.context.SecurityContextHolder.setContext(
                      security);
                  try {
                    start.await();
                    return batches.create(request, actor);
                  } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                  }
                };
    try {
      var first = executor.submit(task.apply(base));
      var second = executor.submit(task.apply(global));
      start.countDown();
      assertThat(
              first.get(30, java.util.concurrent.TimeUnit.SECONDS).projectCount()
                  + second.get(30, java.util.concurrent.TimeUnit.SECONDS).projectCount())
          .isEqualTo(1);
      assertThat(current(f).getId()).isEqualTo(f.variantId());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void unrelatedScopesAreBoundedAndDoNotExposeIncidentDetails() throws Exception {
    Fixture selected = fixture();
    Fixture other = fixture("other");
    TranslationIncident outside = incident(other, "Private to another repository/team scope");
    outside.setReviewTeamId(other.teamId());
    outside = incidents.saveAndFlush(outside);
    cloneIncidentRows(outside.getId(), 500);
    TranslationIncident matching = incident(selected, "Selected scope after unrelated work");
    Long actor = teams.getCurrentUserIdOrThrow();
    var preview = batches.preview(request(selected), actor);
    assertThat(preview.scannedIncidentCount()).isEqualTo(500);
    assertThat(preview.eligibleIncidentCount()).isZero();
    assertThat(preview.skippedIncidentCount()).isZero();
    assertThat(preview.skipped()).isEmpty();
    assertThat(preview.hasMore()).isTrue();
    var first = batches.create(request(selected), actor);
    assertThat(first.projectIds()).isEmpty();
    assertThat(first.scannedIncidentCount()).isEqualTo(500);
    var second = batches.create(request(selected), actor);
    assertThat(second.scannedIncidentCount()).isEqualTo(2);
    assertThat(second.projectIds()).hasSize(1);
    assertThat(second.hasMore()).isFalse();
    assertThat(second.skipped()).isEmpty();
    assertThat(incidents.findById(matching.getId()).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(second.projectIds().getFirst());
    assertThat(incidents.findById(outside.getId()).orElseThrow().getResolutionReviewProjectId())
        .isNull();
    assertThat(current(selected).getId()).isEqualTo(selected.variantId());
    assertThat(current(other).getId()).isEqualTo(other.variantId());
  }

  @Test
  public void qualitySelectionIncludesLegacyNullTypesAndPreservesIncidentOrder() throws Exception {
    Fixture f = fixture();
    var legacy = incident(f, "Legacy untyped concern");
    legacy.setReviewType(null);
    legacy = incidents.saveAndFlush(legacy);
    var quality = incident(f, "Typed translation quality concern");
    quality.setReviewType("TRANSLATION_QUALITY");
    quality = incidents.saveAndFlush(quality);
    var other = incident(f, "Unrelated incident type");
    var base = request(f);
    var request =
        new IncidentReviewBatchService.Request(
            base.repositoryIds(),
            null,
            null,
            null,
            "TRANSLATION_QUALITY",
            base.teamId(),
            base.name(),
            base.dueDate(),
            null,
            false,
            base.type(),
            null,
            null);
    var preview = batches.preview(request, teams.getCurrentUserIdOrThrow());
    assertThat(preview.eligibleIncidentCount()).isEqualTo(2);
    var created = batches.create(request, teams.getCurrentUserIdOrThrow());
    assertThat(created.projectIds()).hasSize(2);
    assertThat(incidents.findById(legacy.getId()).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(created.projectIds().getFirst());
    assertThat(incidents.findById(quality.getId()).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(created.projectIds().getLast());
    assertThat(incidents.findById(other.getId()).orElseThrow().getResolutionReviewProjectId())
        .isNull();
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  private void cloneIncidentRows(Long incidentId, int copies) {
    jdbc.batchUpdate(
        "insert into translation_incident"
            + " (version,review_type,status,resolution,lookup_resolution_status,"
            + "locale_resolution_strategy,locale_used_fallback,repository_name,string_id,"
            + "observed_locale,resolved_locale,resolved_locale_id,reason,lookup_candidate_count,"
            + "selected_tm_text_unit_id,selected_tm_text_unit_variant_id,selected_source,"
            + "selected_target,selected_translation_status,selected_included_in_localized_file)"
            + " select"
            + " 0,review_type,status,resolution,lookup_resolution_status,locale_resolution_strategy,"
            + "locale_used_fallback,repository_name,string_id,observed_locale,resolved_locale,"
            + "resolved_locale_id,reason,lookup_candidate_count,selected_tm_text_unit_id,"
            + "selected_tm_text_unit_variant_id,selected_source,selected_target,selected_translation_status,selected_included_in_localized_file"
            + " from translation_incident where id = ?",
        java.util.stream.IntStream.range(0, copies).boxed().toList(),
        1000,
        (statement, ignored) -> statement.setLong(1, incidentId));
  }

  private String testReviewType() {
    return "TEST_"
        + UUID.nameUUIDFromBytes(
                testIdWatcher.getEntityName("scope").getBytes(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "")
            .toUpperCase();
  }

  private IncidentReviewBatchService.Request request(Fixture f) {
    return new IncidentReviewBatchService.Request(
        List.of(f.repository().getId()),
        null,
        null,
        null,
        testReviewType(),
        f.teamId(),
        "Incident review batch",
        ZonedDateTime.now().plusDays(3),
        1500,
        false,
        ReviewProjectType.BUG_FIXES,
        "Human review of incidents",
        List.of("synthetic-image"));
  }

  private TranslationIncident incident(Fixture f, String reason) {
    TranslationIncident i = new TranslationIncident();
    i.setStatus(TranslationIncidentStatus.OPEN);
    i.setReviewType(testReviewType());
    i.setResolution(TranslationIncidentResolution.PENDING_REVIEW);
    i.setLookupResolutionStatus("UNIQUE_MATCH");
    i.setLocaleResolutionStrategy("EXACT");
    i.setRepositoryName(f.repository().getName());
    i.setStringId("save");
    i.setObservedLocale("fr-FR");
    i.setResolvedLocale("fr-FR");
    i.setResolvedLocaleId(f.localeId());
    i.setReason(reason);
    i.setLookupCandidateCount(1);
    i.setSelectedTmTextUnitId(f.unitId());
    i.setSelectedTmTextUnitVariantId(f.variantId());
    i.setSelectedSource("Save changes");
    i.setSelectedTarget("Enregistrer les modifications");
    i.setSelectedTranslationStatus("APPROVED");
    i.setSelectedIncludedInLocalizedFile(true);
    return incidents.save(i);
  }

  private TMTextUnitVariant current(Fixture f) {
    return currents
        .findByLocale_IdAndTmTextUnit_Id(f.localeId(), f.unitId())
        .getTmTextUnitVariant();
  }

  private Fixture fixture() throws Exception {
    return fixture("");
  }

  private Fixture fixture(String suffix) throws Exception {
    var repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("repo" + suffix));
    var locale = locales.findByBcp47Tag("fr-FR");
    var repositoryLocale = new RepositoryLocale();
    repositoryLocale.setRepository(repository);
    repositoryLocale.setLocale(locale);
    repositoryLocale.setParentLocale(
        repositoryLocales.findByRepositoryAndParentLocaleIsNull(repository));
    repositoryLocales.save(repositoryLocale);
    var team = teams.createTeam(testIdWatcher.getEntityName("team" + suffix));
    var asset = assets.createAssetWithContent(repository.getId(), "messages.json", "{}");
    var unit =
        tm.addTMTextUnit(repository.getTm().getId(), asset.getId(), "save", "Save changes", null);
    var variant =
        tm.addCurrentTMTextUnitVariant(
            unit.getId(),
            locale.getId(),
            "Enregistrer les modifications",
            TMTextUnitVariant.Status.APPROVED,
            true);
    return new Fixture(repository, team.getId(), locale.getId(), unit.getId(), variant.getId());
  }

  private record Fixture(
      com.box.l10n.mojito.entity.Repository repository,
      Long teamId,
      Long localeId,
      Long unitId,
      Long variantId) {}
}
