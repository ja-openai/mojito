package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
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
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class IncidentReviewAssignmentDbTest extends ServiceTestBase {
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
  public void closedUndecidedProjectCanBeRoutedAgainWithoutErasingHistory() throws Exception {
    Fixture f = fixture();
    var incident = incident(f, "Needs a human review");
    var first = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    Long oldProject = first.projectIds().getFirst();
    var original = history(incident.getId()).getFirst();
    String intakeKey = UUID.randomUUID().toString().replace("-", "").repeat(2);
    original.setIntakeFingerprint(intakeKey);
    original = proposals.saveAndFlush(original);
    Long originalRow = original.getReviewProjectTextUnitId();
    assertThat(batches.preview(request(f), teams.getCurrentUserIdOrThrow()).eligibleIncidentCount())
        .isZero();
    projects.updateProjectStatus(oldProject, ReviewProjectStatus.CLOSED, "Cancelled before review");
    var preview = batches.preview(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(preview.eligibleIncidentCount()).isEqualTo(1);
    assertThat(history(incident.getId())).hasSize(1);
    var next = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(next.projectIds()).hasSize(1).doesNotContain(oldProject);
    var history = history(incident.getId());
    assertThat(history).hasSize(2);
    var latest = history.getFirst();
    var previous = history.getLast();
    assertThat(latest.getFindingId()).isEqualTo(original.getFindingId());
    assertThat(latest.getPreviousProposalId()).isEqualTo(original.getId());
    assertThat(latest.getProposalRevision()).isEqualTo(original.getProposalRevision() + 1);
    assertThat(latest.getRationale()).isEqualTo(original.getRationale());
    assertThat(latest.getBaselineVariantId()).isEqualTo(original.getBaselineVariantId());
    assertThat(latest.getReviewProjectId()).isEqualTo(next.projectIds().getFirst());
    assertThat(previous.getReviewProjectId()).isEqualTo(oldProject);
    assertThat(previous.getReviewProjectTextUnitId()).isEqualTo(originalRow);
    assertThat(previous.getDisposition()).isEqualTo(Disposition.SUPERSEDED);
    assertThat(previous.getActiveIntakeFingerprint()).isNull();
    assertThat(latest.getActiveIntakeFingerprint()).isEqualTo(intakeKey);
    assertThat(incidents.findById(incident.getId()).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(latest.getReviewProjectId());
    assertThat(projects.getProjectDetail(oldProject).status())
        .isEqualTo(ReviewProjectStatus.CLOSED);
    assertThat(batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds()).isEmpty();
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  @Test
  public void historicalTranslationProjectDoesNotAssignANewIncident() throws Exception {
    Fixture f = fixture();
    incident(f, "Existing concern");
    Long project =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    var fresh = incident(f, "Different newly reported concern");
    fresh.setReviewProjectId(project);
    incidents.saveAndFlush(fresh);
    assertThat(batches.preview(request(f), teams.getCurrentUserIdOrThrow()).eligibleIncidentCount())
        .isEqualTo(1);
    var created = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(created.projectIds()).hasSize(1).doesNotContain(project);
    assertThat(incidents.findById(fresh.getId()).orElseThrow().getReviewProjectId())
        .isEqualTo(project);
  }

  @Test
  public void closingAReviewedProjectDoesNotRepeatTheHumanDecision() throws Exception {
    Fixture f = fixture();
    var first = incident(f, "Initial concern");
    Long project =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    decide(project, AgentReviewDecisionRequest.Action.KEEP_CURRENT);
    projects.updateProjectStatus(project, ReviewProjectStatus.CLOSED, "Complete");
    assertThat(incidents.findById(first.getId()).orElseThrow().getStatus())
        .isEqualTo(TranslationIncidentStatus.CLOSED);
    incident(f, "Repeated report of the same translation state");
    var result = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(result.projectIds()).isEmpty();
    assertThat(result.skipped())
        .extracting(IncidentReviewBatchService.Skipped::reason)
        .contains("Current translation was already reviewed; use Review again for a new round");
  }

  @Test
  public void closedProjectWithPendingHumanFollowupIsNotAutomaticallyRerouted() throws Exception {
    Fixture f = fixture();
    var incident = incident(f, "Needs another proposal");
    Long project =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    decide(project, AgentReviewDecisionRequest.Action.REQUEST_REVISION);
    projects.updateProjectStatus(project, ReviewProjectStatus.CLOSED, "Wait for the follow-up");
    assertThat(batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds()).isEmpty();
    assertThat(history(incident.getId())).hasSize(1);
  }

  @Test
  public void closedProjectDoesNotBypassFreshnessChecks() throws Exception {
    Fixture f = fixture();
    var incident = incident(f, "Original target concern");
    Long project =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    projects.updateProjectStatus(project, ReviewProjectStatus.CLOSED, "Cancelled");
    tm.addCurrentTMTextUnitVariant(
        f.unitId(), f.localeId(), "Nouvelle traduction", TMTextUnitVariant.Status.APPROVED, true);
    var result = batches.create(request(f), teams.getCurrentUserIdOrThrow());
    assertThat(result.projectIds()).isEmpty();
    assertThat(result.skipped()).isNotEmpty();
    assertThat(history(incident.getId())).hasSize(1);
  }

  @Test
  public void reopeningBeforeCreationKeepsTheOriginalAssignment() throws Exception {
    Fixture f = fixture();
    var incident = incident(f, "Concern");
    Long project =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    projects.updateProjectStatus(project, ReviewProjectStatus.CLOSED, "Cancelled");
    assertThat(batches.preview(request(f), teams.getCurrentUserIdOrThrow()).eligibleIncidentCount())
        .isEqualTo(1);
    projects.updateProjectStatus(project, ReviewProjectStatus.OPEN, null);
    assertThat(batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds()).isEmpty();
    assertThat(history(incident.getId())).hasSize(1);
  }

  @Test
  public void concurrentReroutesCreateOnlyOneNewAssignment() throws Exception {
    Fixture f = fixture();
    var incident = incident(f, "Concern");
    Long project =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    projects.updateProjectStatus(project, ReviewProjectStatus.CLOSED, "Cancelled");
    var security = org.springframework.security.core.context.SecurityContextHolder.getContext();
    Long actor = teams.getCurrentUserIdOrThrow();
    var base = request(f);
    var allRepositories =
        new IncidentReviewBatchService.Request(
            null,
            null,
            null,
            null,
            base.reviewType(),
            base.teamId(),
            base.name(),
            base.dueDate(),
            base.maxWordCountPerProject(),
            false,
            base.type(),
            null,
            null,
            true);
    var start = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    java.util.function.Function<
            IncidentReviewBatchService.Request,
            java.util.concurrent.Callable<IncidentReviewBatchService.Result>>
        task =
            selection ->
                () -> {
                  org.springframework.security.core.context.SecurityContextHolder.setContext(
                      security);
                  try {
                    start.await();
                    return batches.create(selection, actor);
                  } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                  }
                };
    try {
      var first = executor.submit(task.apply(base));
      var second = executor.submit(task.apply(allRepositories));
      start.countDown();
      assertThat(
              first.get(30, java.util.concurrent.TimeUnit.SECONDS).projectCount()
                  + second.get(30, java.util.concurrent.TimeUnit.SECONDS).projectCount())
          .isEqualTo(1);
      assertThat(history(incident.getId())).hasSize(2);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void deferredUndecidedReviewCanMoveToANewProject() throws Exception {
    Fixture f = fixture();
    var incident = incident(f, "Deferred human review");
    Long project =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    decide(project, AgentReviewDecisionRequest.Action.DEFER);
    projects.updateProjectStatus(project, ReviewProjectStatus.CLOSED, "Cancelled");
    assertThat(batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds())
        .hasSize(1)
        .doesNotContain(project);
    assertThat(history(incident.getId())).hasSize(2);
  }

  @Test
  public void reopeningOldProjectCannotReviewItsSupersededAssignment() throws Exception {
    Fixture f = fixture();
    var incident = incident(f, "Concern");
    Long oldProject =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    projects.updateProjectStatus(oldProject, ReviewProjectStatus.CLOSED, "Cancelled");
    Long newProject =
        batches.create(request(f), teams.getCurrentUserIdOrThrow()).projectIds().getFirst();
    projects.updateProjectStatus(oldProject, ReviewProjectStatus.OPEN, null);
    assertThatThrownBy(() -> decide(oldProject, AgentReviewDecisionRequest.Action.KEEP_CURRENT))
        .hasMessageContaining("proposal");
    assertThat(incidents.findById(incident.getId()).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(newProject);
    assertThat(current(f).getId()).isEqualTo(f.variantId());
  }

  private void decide(Long projectId, AgentReviewDecisionRequest.Action action) throws Exception {
    var row = projects.getProjectDetail(projectId).reviewProjectTextUnits().getFirst();
    var request = new com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest();
    request.setDecisionState(
        action == AgentReviewDecisionRequest.Action.DEFER
            ? com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.PENDING
            : com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState
                .DECIDED);
    request.setExpectedCurrentTmTextUnitVariantId(row.currentTmTextUnitVariant().id());
    request.setExpectedReviewStateRevision(row.reviewStateRevision());
    request.setAgentReview(
        new AgentReviewDecisionRequest(
            row.agentReview().proposalId(),
            row.agentReview().proposalRevision(),
            row.agentReview().proposalVersion(),
            UUID.randomUUID().toString(),
            action,
            null,
            null,
            "Human assessment for this test"));
    reviewWS.saveDecision(row.id(), request);
  }

  private List<AgentReviewProposal> history(Long incidentId) {
    return proposals
        .findByFindingIdOrderByProposalRevisionAsc(
            incidents.findById(incidentId).orElseThrow().getReviewFindingId())
        .reversed();
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
