package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.locale.LocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/** Manual planning spans internal read pages and creates only the explicitly planned incidents. */
public class IncidentReviewManualBatchDbTest extends ServiceTestBase {
  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired private RepositoryService repositories;
  @Autowired private RepositoryLocaleRepository repositoryLocales;
  @Autowired private LocaleRepository locales;
  @Autowired private AssetService assets;
  @Autowired private TMService tm;
  @Autowired private TeamService teams;
  @Autowired private TranslationIncidentRepository incidents;
  @Autowired private IncidentReviewBatchCursorRepository cursors;
  @Autowired private IncidentReviewBatchService batches;
  @Autowired private ManualIncidentReviewService manual;

  @Test
  public void completeManualSelectionSpansMoreThanFiveHundredIncidentsAndSixtyFourLocales()
      throws Exception {
    Fixture f = fixture("", 64, 9);
    List<TranslationIncident> pending = new ArrayList<>();
    for (Locale locale : f.locales()) {
      for (TMTextUnit unit : f.units()) pending.add(incident(f, locale, unit));
    }
    List<Long> ids =
        incidents.saveAllAndFlush(pending).stream().map(TranslationIncident::getId).toList();
    var request = request(f, null, null, null, null);
    long cursorCount = cursors.count();

    var preview = manual.preview(request, actor());

    assertThat(preview.eligibleIncidentCount()).isEqualTo(576);
    assertThat(preview.projectCount()).isEqualTo(64);
    assertThat(preview.localeTags()).hasSize(64);
    assertThat(preview.scannedIncidentCount()).isEqualTo(576);
    assertThat(preview.skippedIncidentCount()).isZero();
    assertThat(preview.hasMore()).isFalse();
    assertThat(preview.limitReached()).isFalse();
    assertThat(preview.incidentBatches())
        .hasSize(64)
        .allSatisfy(batch -> assertThat(batch).hasSize(9));
    assertThat(plannedIds(preview)).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(preview.projectIds()).isEmpty();
    assertThat(preview.requestIds()).isEmpty();
    assertThat(cursors.count()).isEqualTo(cursorCount);

    int createdIncidents = 0;
    int createdProjects = 0;
    for (List<Long> batch : preview.incidentBatches()) {
      var created = manual.create(withIds(request, batch), actor());
      createdIncidents += created.eligibleIncidentCount();
      createdProjects += created.projectCount();
      assertThat(created.skippedIncidentCount()).isZero();
      assertThat(created.hasMore()).isFalse();
    }
    assertThat(createdIncidents).isEqualTo(576);
    assertThat(createdProjects).isEqualTo(64);
    assertThat(incidents.findAllById(ids))
        .allSatisfy(incident -> assertThat(incident.getResolutionReviewProjectId()).isNotNull());
    assertThat(cursors.count()).isEqualTo(cursorCount);
  }

  @Test
  public void projectSplitsAndDuplicateWavesDoNotDependOnInternalPageSize() throws Exception {
    Fixture f = fixture("", 1, 5);
    List<TranslationIncident> saved =
        incidents.saveAllAndFlush(
            List.of(
                incident(f, 0),
                incident(f, 1),
                incident(f, 2),
                incident(f, 0),
                incident(f, 3),
                incident(f, 4)));
    var request = request(f, null, null, 2, null);
    Object originalLimit = ReflectionTestUtils.getField(batches, "candidateLimit");
    try {
      ReflectionTestUtils.setField(batches, "candidateLimit", 2);
      var smallPages = manual.preview(request, actor());
      ReflectionTestUtils.setField(batches, "candidateLimit", 500);
      var largePages = manual.preview(request, actor());

      assertThat(smallPages.eligibleIncidentCount()).isEqualTo(6);
      assertThat(smallPages.projectCount()).isEqualTo(4);
      assertThat(smallPages.incidentBatches()).isEqualTo(largePages.incidentBatches());
      assertThat(smallPages.incidentBatches().stream().map(List::size).toList())
          .containsExactlyInAnyOrder(2, 2, 1, 1);
      assertThat(plannedIds(smallPages))
          .containsExactlyInAnyOrderElementsOf(
              saved.stream().map(TranslationIncident::getId).toList());
      assertThat(smallPages.incidentBatches())
          .noneSatisfy(
              batch -> assertThat(batch).contains(saved.get(0).getId(), saved.get(3).getId()));
    } finally {
      ReflectionTestUtils.setField(batches, "candidateLimit", originalLimit);
    }
  }

  @Test
  public void allTypesStartsFreshDespiteAnOlderScheduledCursor() throws Exception {
    Fixture f = fixture("", 1, 3);
    List<TranslationIncident> stale = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      var incident = incident(f, i);
      incident.setReviewType("TRANSLATION_QUALITY");
      incident.setSelectedSource("An older source");
      stale.add(incident);
    }
    stale = incidents.saveAllAndFlush(stale);
    var allTypes = withType(request(f, null, null, null, null), null);
    batches.create(allTypes, actor());
    var cursor =
        cursors.findAll().stream()
            .filter(row -> row.getTeamId().equals(f.teamId()))
            .findFirst()
            .orElseThrow();
    cursor.setLastScannedIncidentId(stale.get(1).getId());
    cursor.setSweepUpperBoundId(stale.get(2).getId());
    cursor = cursors.saveAndFlush(cursor);
    long previousPosition = cursor.getLastScannedIncidentId();
    long previousUpperBound = cursor.getSweepUpperBoundId();
    var quality = incident(f, 0);
    quality.setReviewType("TRANSLATION_QUALITY");
    var legacy = incident(f, 1);
    legacy.setReviewType(null);
    var fresh = incidents.saveAllAndFlush(List.of(quality, legacy));

    var allPreview = manual.preview(allTypes, actor());
    var qualityPreview = manual.preview(withType(allTypes, "TRANSLATION_QUALITY"), actor());

    assertThat(allPreview.eligibleIncidentCount()).isEqualTo(2);
    assertThat(plannedIds(allPreview))
        .containsExactlyInAnyOrderElementsOf(
            fresh.stream().map(TranslationIncident::getId).toList());
    assertThat(plannedIds(allPreview)).containsAll(plannedIds(qualityPreview));
    assertThat(qualityPreview.eligibleIncidentCount()).isEqualTo(2);
    var unchanged = cursors.findById(cursor.getId()).orElseThrow();
    assertThat(unchanged.getLastScannedIncidentId()).isEqualTo(previousPosition);
    assertThat(unchanged.getSweepUpperBoundId()).isEqualTo(previousUpperBound);
  }

  @Test
  public void totalLimitCountsEligibleIncidentsAfterSkippedAndUnrelatedPages() throws Exception {
    Fixture f = fixture("", 1, 5);
    Fixture outside = fixture("outside", 1, 1);
    incidents.saveAllAndFlush(List.of(incident(outside, 0), incident(outside, 0)));
    List<TranslationIncident> stale = new ArrayList<>();
    for (int i = 0; i < 103; i++) {
      var incident = incident(f, i % f.units().size());
      incident.setSelectedSource("An older source");
      stale.add(incident);
    }
    stale = incidents.saveAllAndFlush(stale);
    List<TranslationIncident> eligible = new ArrayList<>();
    for (int i = 0; i < 5; i++) eligible.add(incident(f, i));
    eligible = incidents.saveAllAndFlush(eligible);
    Object originalLimit = ReflectionTestUtils.getField(batches, "candidateLimit");
    try {
      ReflectionTestUtils.setField(batches, "candidateLimit", 2);
      var preview = manual.preview(request(f, null, 3, 2, null), actor());

      assertThat(preview.eligibleIncidentCount()).isEqualTo(3);
      assertThat(preview.projectCount()).isEqualTo(2);
      assertThat(preview.skippedIncidentCount()).isEqualTo(103);
      assertThat(preview.skipped())
          .extracting(IncidentReviewBatchService.Skipped::incidentId)
          .containsExactlyElementsOf(
              stale.subList(3, 103).stream().map(TranslationIncident::getId).toList());
      assertThat(plannedIds(preview))
          .containsExactlyElementsOf(
              eligible.subList(0, 3).stream().map(TranslationIncident::getId).toList());
      assertThat(preview.limitReached()).isTrue();
      assertThat(preview.hasMore()).isFalse();
    } finally {
      ReflectionTestUtils.setField(batches, "candidateLimit", originalLimit);
    }
  }

  @Test
  public void explicitIncidentAndWordLimitsControlSplitsAndCreationRechecksWordBudget()
      throws Exception {
    Fixture f = fixture("", 1, 7);
    List<TranslationIncident> pending = new ArrayList<>();
    for (int i = 0; i < 7; i++) pending.add(incident(f, i));
    pending = incidents.saveAllAndFlush(pending);

    assertThat(manual.preview(request(f, null, null, 3, null), actor()).incidentBatches())
        .extracting(List::size)
        .containsExactly(3, 3, 1);
    assertThat(manual.preview(request(f, 5, null, 3, null), actor()).incidentBatches())
        .extracting(List::size)
        .containsExactly(2, 2, 2, 1);
    assertThat(manual.preview(request(f, 1, null, 3, null), actor()).incidentBatches())
        .hasSize(7)
        .allSatisfy(batch -> assertThat(batch).hasSize(1));
    assertThat(manual.preview(request(f, 100000, null, null, null), actor()).projectCount())
        .isEqualTo(1);

    // A create call must remain one project even if a client changes the previewed grouping.
    List<Long> tooManyWords =
        pending.subList(0, 3).stream().map(TranslationIncident::getId).toList();
    assertThatThrownBy(() -> manual.create(request(f, 5, null, 3, tooManyWords), actor()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one project");
    var created = manual.create(request(f, 5, null, 3, tooManyWords.subList(0, 2)), actor());
    assertThat(created.eligibleIncidentCount()).isEqualTo(2);
    assertThat(created.projectCount()).isEqualTo(1);
    assertThat(created.skippedIncidentCount()).isZero();
  }

  @Test
  public void createRechecksSnapshotsAndDoesNotIncludeIncidentsAddedAfterPreview()
      throws Exception {
    Fixture f = fixture("", 1, 4);
    var pending =
        incidents.saveAllAndFlush(List.of(incident(f, 0), incident(f, 1), incident(f, 2)));
    var request = request(f, null, null, null, null);
    var preview = manual.preview(request, actor());
    var changed = incidents.findById(pending.get(0).getId()).orElseThrow();
    changed.setSelectedSource("A stale incident snapshot");
    incidents.saveAndFlush(changed);
    var closed = incidents.findById(pending.get(2).getId()).orElseThrow();
    closed.setStatus(TranslationIncidentStatus.CLOSED);
    incidents.saveAndFlush(closed);
    var later = incidents.saveAndFlush(incident(f, 3));

    var created = manual.create(withIds(request, plannedIds(preview)), actor());

    assertThat(created.eligibleIncidentCount()).isEqualTo(1);
    assertThat(created.projectCount()).isEqualTo(1);
    assertThat(created.skippedIncidentCount()).isEqualTo(2);
    assertThat(
            incidents.findById(pending.get(1).getId()).orElseThrow().getResolutionReviewProjectId())
        .isEqualTo(created.projectIds().getFirst());
    assertThat(incidents.findById(later.getId()).orElseThrow().getResolutionReviewProjectId())
        .isNull();
    assertThat(manual.create(withIds(request, plannedIds(preview)), actor()).projectIds())
        .isEmpty();
  }

  @Test
  public void selectedIdCreationRejectsInvalidPayloadsAndCannotRouteOutsideScope()
      throws Exception {
    Fixture f = fixture("", 1, 2);
    Fixture outside = fixture("outside", 1, 1);
    var selected = incidents.saveAllAndFlush(List.of(incident(f, 0), incident(f, 1)));
    var other = incidents.saveAndFlush(incident(outside, 0));
    var request = request(f, null, null, null, null);

    assertThatThrownBy(() -> manual.create(request, actor()))
        .isInstanceOf(IllegalArgumentException.class);
    for (List<Long> invalid :
        List.of(
            List.<Long>of(),
            List.of(0L),
            List.of(-1L),
            List.of(selected.getFirst().getId(), selected.getFirst().getId()),
            LongStream.rangeClosed(1, 5001).boxed().toList())) {
      assertThatThrownBy(() -> manual.create(withIds(request, invalid), actor()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                manual.create(
                    request(
                        f,
                        null,
                        null,
                        1,
                        selected.stream().map(TranslationIncident::getId).toList()),
                    actor()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manual.preview(request(f, null, 0, null, null), actor()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manual.preview(request(f, null, null, 0, null), actor()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> manual.preview(request(f, null, null, 5001, null), actor()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> manual.create(withIds(request, List.of(other.getId())), actor()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unavailable in this scope")
        .hasMessageNotContaining(other.getReason());
    assertThat(incidents.findById(other.getId()).orElseThrow().getResolutionReviewProjectId())
        .isNull();
    assertThat(incidents.findAllById(selected.stream().map(TranslationIncident::getId).toList()))
        .allSatisfy(incident -> assertThat(incident.getResolutionReviewProjectId()).isNull());
  }

  private List<Long> plannedIds(ManualIncidentReviewService.Preview preview) {
    return preview.incidentBatches().stream().flatMap(List::stream).toList();
  }

  private Long actor() {
    return teams.getCurrentUserIdOrThrow();
  }

  private IncidentReviewBatchService.Request request(
      Fixture f, Integer maxWords, Integer maxTotal, Integer maxPerProject, List<Long> ids) {
    return new IncidentReviewBatchService.Request(
        List.of(f.repository().getId()),
        null,
        f.locales().stream().map(Locale::getBcp47Tag).toList(),
        null,
        testReviewType(),
        f.teamId(),
        "Manual incident review",
        ZonedDateTime.now().plusDays(3),
        maxWords,
        false,
        ReviewProjectType.NORMAL,
        null,
        null,
        false,
        maxTotal,
        maxPerProject,
        ids);
  }

  private IncidentReviewBatchService.Request withIds(
      IncidentReviewBatchService.Request request, List<Long> ids) {
    return new IncidentReviewBatchService.Request(
        request.repositoryIds(),
        request.reviewFeatureIds(),
        request.localeTags(),
        request.excludedLocaleTags(),
        request.reviewType(),
        request.teamId(),
        request.name(),
        request.dueDate(),
        request.maxWordCountPerProject(),
        request.assignTranslator(),
        request.type(),
        request.notes(),
        request.screenshotImageIds(),
        request.allRepositories(),
        request.maxIncidentCount(),
        request.maxIncidentsPerProject(),
        ids);
  }

  private IncidentReviewBatchService.Request withType(
      IncidentReviewBatchService.Request request, String type) {
    return new IncidentReviewBatchService.Request(
        request.repositoryIds(),
        request.reviewFeatureIds(),
        request.localeTags(),
        request.excludedLocaleTags(),
        type,
        request.teamId(),
        request.name(),
        request.dueDate(),
        request.maxWordCountPerProject(),
        request.assignTranslator(),
        request.type(),
        request.notes(),
        request.screenshotImageIds(),
        request.allRepositories(),
        request.maxIncidentCount(),
        request.maxIncidentsPerProject(),
        request.incidentIds());
  }

  private TranslationIncident incident(Fixture f, int unitIndex) {
    return incident(f, f.locales().getFirst(), f.units().get(unitIndex));
  }

  private TranslationIncident incident(Fixture f, Locale locale, TMTextUnit unit) {
    TranslationIncident incident = new TranslationIncident();
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incident.setResolution(TranslationIncidentResolution.PENDING_REVIEW);
    incident.setReviewType(testReviewType());
    incident.setReviewTeamId(f.teamId());
    incident.setLookupResolutionStatus("UNIQUE_MATCH");
    incident.setLocaleResolutionStrategy("EXACT");
    incident.setRepositoryName(f.repository().getName());
    incident.setStringId(unit.getName());
    incident.setObservedLocale(locale.getBcp47Tag());
    incident.setResolvedLocale(locale.getBcp47Tag());
    incident.setResolvedLocaleId(locale.getId());
    incident.setReason("Synthetic incident for manual batching");
    incident.setLookupCandidateCount(1);
    incident.setSelectedTmTextUnitId(unit.getId());
    incident.setSelectedSource(unit.getContent());
    return incident;
  }

  private Fixture fixture(String suffix, int localeCount, int unitCount) throws Exception {
    Repository repository =
        repositories.createRepository(testIdWatcher.getEntityName("repo" + suffix));
    RepositoryLocale root = repositoryLocales.findByRepositoryAndParentLocaleIsNull(repository);
    List<Locale> selectedLocales =
        locales.findAll().stream()
            .filter(locale -> !locale.getId().equals(root.getLocale().getId()))
            .sorted(Comparator.comparing(Locale::getBcp47Tag))
            .limit(localeCount)
            .toList();
    assertThat(selectedLocales).hasSize(localeCount);
    List<RepositoryLocale> targets = new ArrayList<>();
    for (Locale locale : selectedLocales) {
      RepositoryLocale target = new RepositoryLocale();
      target.setRepository(repository);
      target.setLocale(locale);
      target.setParentLocale(root);
      targets.add(target);
    }
    repositoryLocales.saveAllAndFlush(targets);
    var team = teams.createTeam(testIdWatcher.getEntityName("team" + suffix));
    var asset = assets.createAssetWithContent(repository.getId(), "messages.json", "{}");
    List<TMTextUnit> units = new ArrayList<>();
    for (int i = 0; i < unitCount; i++) {
      units.add(
          tm.addTMTextUnit(
              repository.getTm().getId(), asset.getId(), "string-" + i, "Save changes", null));
    }
    return new Fixture(repository, team.getId(), selectedLocales, units);
  }

  private String testReviewType() {
    return "TEST_"
        + UUID.nameUUIDFromBytes(
                testIdWatcher.getEntityName("scope").getBytes(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "")
            .toUpperCase(java.util.Locale.ROOT);
  }

  private record Fixture(
      Repository repository, Long teamId, List<Locale> locales, List<TMTextUnit> units) {}
}
