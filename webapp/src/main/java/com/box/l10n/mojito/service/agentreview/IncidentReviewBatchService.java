package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.*;
import com.box.l10n.mojito.service.DBUtils;
import com.box.l10n.mojito.service.WordCountService;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.review.ReviewFeatureRepository;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The same bounded incident selection and snapshot handoff for manual and scheduled batches. */
@Service
public class IncidentReviewBatchService {
  public record Request(
      List<Long> repositoryIds,
      List<Long> reviewFeatureIds,
      List<String> localeTags,
      List<String> excludedLocaleTags,
      String reviewType,
      Long teamId,
      String name,
      ZonedDateTime dueDate,
      Integer maxWordCountPerProject,
      Boolean assignTranslator,
      ReviewProjectType type,
      String notes,
      List<String> screenshotImageIds,
      Boolean allRepositories,
      Integer maxIncidentCount,
      Integer maxIncidentsPerProject,
      List<Long> incidentIds) {
    public Request(
        List<Long> repositoryIds,
        List<Long> reviewFeatureIds,
        List<String> localeTags,
        List<String> excludedLocaleTags,
        String reviewType,
        Long teamId,
        String name,
        ZonedDateTime dueDate,
        Integer maxWordCountPerProject,
        Boolean assignTranslator,
        ReviewProjectType type,
        String notes,
        List<String> screenshotImageIds,
        Boolean allRepositories) {
      this(
          repositoryIds,
          reviewFeatureIds,
          localeTags,
          excludedLocaleTags,
          reviewType,
          teamId,
          name,
          dueDate,
          maxWordCountPerProject,
          assignTranslator,
          type,
          notes,
          screenshotImageIds,
          allRepositories,
          null,
          null,
          null);
    }

    public Request(
        List<Long> repositoryIds,
        List<Long> reviewFeatureIds,
        List<String> localeTags,
        List<String> excludedLocaleTags,
        String reviewType,
        Long teamId,
        String name,
        ZonedDateTime dueDate,
        Integer maxWordCountPerProject,
        Boolean assignTranslator,
        ReviewProjectType type,
        String notes,
        List<String> screenshotImageIds) {
      this(
          repositoryIds,
          reviewFeatureIds,
          localeTags,
          excludedLocaleTags,
          reviewType,
          teamId,
          name,
          dueDate,
          maxWordCountPerProject,
          assignTranslator,
          type,
          notes,
          screenshotImageIds,
          false);
    }
  }

  public record Skipped(Long incidentId, String reason) {}

  public record Result(
      int eligibleIncidentCount,
      int skippedIncidentCount,
      int projectCount,
      List<String> localeTags,
      List<Long> projectIds,
      List<Long> requestIds,
      List<Skipped> skipped,
      int scannedIncidentCount,
      boolean hasMore) {
    public Result(
        int eligibleIncidentCount,
        int skippedIncidentCount,
        int projectCount,
        List<String> localeTags,
        List<Long> projectIds,
        List<Long> requestIds,
        List<Skipped> skipped) {
      this(
          eligibleIncidentCount,
          skippedIncidentCount,
          projectCount,
          localeTags,
          projectIds,
          requestIds,
          skipped,
          eligibleIncidentCount + skippedIncidentCount,
          false);
    }
  }

  private record Scope(Set<Long> repositoryIds, Set<Long> localeIds, boolean allRepositories) {}

  private record Candidate(
      TranslationIncident incident,
      TMTextUnit unit,
      Locale locale,
      TMTextUnitVariant current,
      AgentReviewProposal proposal) {}

  private record Group(Long runId, Long repositoryId, Long localeId, String key) {}

  record PlannedIncident(
      long incidentId,
      long unitId,
      Long runId,
      long repositoryId,
      long localeId,
      String groupKey,
      String localeTag,
      int wordCount) {}

  record PreviewPage(
      List<PlannedIncident> candidates,
      List<Skipped> skipped,
      int scannedCount,
      boolean hasMore,
      long lastScannedId,
      long upperBoundId) {}

  private record Selection(
      List<Candidate> candidates,
      List<Skipped> skipped,
      long lastScannedId,
      boolean hasMore,
      int scannedCount) {}

  private record CurrentKey(Long unitId, Long localeId) {}

  private record CheckpointState(Set<String> completedGroups, boolean invalid) {}

  // Internal scan page size; manual planning continues until its explicit scope is complete.
  @Value("${l10n.incident-review.batch-candidate-limit:500}")
  private int candidateLimit;

  @Value("${l10n.incident-review.batch-project-limit:25}")
  private int projectLimit;

  private final EntityManager em;
  private final UserService users;
  private final TeamService teams;
  private final ReviewFeatureRepository features;
  private final ReviewProjectService projects;
  private final AgentReviewProposalRepository proposals;
  private final AgentReviewRunRepository runs;
  private final StructuredBlobStorage blobs;
  private final ObjectMapper mapper;
  private final WordCountService words;
  private final AgentReviewStateService reviewedStates;
  private final IncidentReviewBatchCursorRepository cursors;
  private final DBUtils dbUtils;
  private final JdbcTemplate jdbc;
  private final IncidentReviewAssignmentService assignments;

  public IncidentReviewBatchService(
      EntityManager em,
      UserService users,
      TeamService teams,
      ReviewFeatureRepository features,
      ReviewProjectService projects,
      AgentReviewProposalRepository proposals,
      AgentReviewRunRepository runs,
      StructuredBlobStorage blobs,
      ObjectMapper mapper,
      WordCountService words,
      AgentReviewStateService reviewedStates,
      IncidentReviewBatchCursorRepository cursors,
      DBUtils dbUtils,
      JdbcTemplate jdbc,
      IncidentReviewAssignmentService assignments) {
    this.em = em;
    this.users = users;
    this.teams = teams;
    this.features = features;
    this.projects = projects;
    this.proposals = proposals;
    this.runs = runs;
    this.blobs = blobs;
    this.mapper = mapper;
    this.words = words;
    this.reviewedStates = reviewedStates;
    this.cursors = cursors;
    this.dbUtils = dbUtils;
    this.jdbc = jdbc;
    this.assignments = assignments;
  }

  @Transactional(readOnly = true)
  public Result preview(Request request, Long requestedByUserId) {
    Scope scope = validate(request, requestedByUserId);
    Selection selection = select(request, scope, cursor(request, scope, false), false);
    int projectCount =
        waves(selection.candidates()).stream()
            .mapToInt(wave -> countProjects(wave, maxWords(request)))
            .sum();
    return result(selection, projectCount, List.of(), List.of());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result create(Request request, Long requestedByUserId) {
    Scope scope = validate(request, requestedByUserId);
    IncidentReviewBatchCursor cursor = cursor(request, scope, true);
    Selection selection = select(request, scope, cursor, true);
    // Advancing and project assignment commit together, including slices containing only skips.
    cursor.setLastScannedIncidentId(selection.hasMore() ? selection.lastScannedId() : 0L);
    if (!selection.hasMore()) cursor.setSweepUpperBoundId(0L);
    return createSelected(request, requestedByUserId, selection);
  }

  /**
   * Each manual preview page has its own read transaction and contains only scalar planning data.
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public PreviewPage previewManualPage(
      Request request, Long requestedByUserId, long afterId, Long upperBoundId) {
    Scope scope = validate(request, requestedByUserId);
    IncidentReviewBatchCursor cursor = new IncidentReviewBatchCursor();
    cursor.setLastScannedIncidentId(afterId);
    if (upperBoundId == null) {
      upperBoundId =
          em.createQuery("select max(i.id) from TranslationIncident i", Long.class)
              .getSingleResult();
    }
    cursor.setSweepUpperBoundId(upperBoundId == null ? 0 : upperBoundId);
    Selection selection = select(request, scope, cursor, false, false);
    PreviewPage page =
        new PreviewPage(
            selection.candidates().stream()
                .map(
                    c -> {
                      Group group = group(c);
                      return new PlannedIncident(
                          c.incident().getId(),
                          c.unit().getId(),
                          group.runId(),
                          group.repositoryId(),
                          group.localeId(),
                          group.key(),
                          c.locale().getBcp47Tag(),
                          words.getEnglishWordCount(c.unit().getContent()));
                    })
                .toList(),
            selection.skipped(),
            selection.scannedCount(),
            selection.hasMore(),
            selection.lastScannedId(),
            cursor.getSweepUpperBoundId());
    // Open-in-view otherwise keeps every scanned entity alive across these read transactions.
    em.clear();
    return page;
  }

  /** Recheck one explicitly planned project, keeping the normal global entity lock order. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result createManualProject(Request request, Long requestedByUserId) {
    Scope scope = validate(request, requestedByUserId);
    List<Long> ids = request.incidentIds();
    int maxIncidents =
        request.maxIncidentsPerProject() == null ? 500 : request.maxIncidentsPerProject();
    if (ids == null
        || ids.isEmpty()
        || ids.size() > maxIncidents
        || ids.stream().anyMatch(id -> id == null || id < 1)
        || new HashSet<>(ids).size() != ids.size()
        || (request.maxIncidentCount() != null && ids.size() > request.maxIncidentCount())) {
      throw new IllegalArgumentException(
          "Provide one previewed project of distinct incidentIds within the configured limits");
    }
    Selection selection =
        selectIds(
            request,
            scope,
            new IncidentReviewBatchCursor(),
            true,
            ids.stream().sorted().toList(),
            false,
            false);
    // Out-of-scope IDs never expose incident details, and must not silently count as processed.
    if (selection.candidates().size() + selection.skipped().size() != ids.size()) {
      throw new IllegalArgumentException(
          "Some selected incidents are unavailable in this scope; preview again");
    }
    if (waves(selection.candidates()).stream()
            .mapToInt(wave -> countProjects(wave, maxWords(request)))
            .sum()
        > 1) {
      throw new IllegalArgumentException(
          "incidentIds must describe one project within the configured word limit; preview again");
    }
    return createSelected(request, requestedByUserId, selection);
  }

  private Result createSelected(Request request, Long requestedByUserId, Selection selection) {
    if (selection.candidates().isEmpty()) return result(selection, 0, List.of(), List.of());
    var assignmentState =
        assignments.load(
            selection.candidates().stream().map(Candidate::incident).toList(),
            selection.candidates().stream().map(Candidate::proposal).toList(),
            true);
    List<Candidate> ordinary =
        selection.candidates().stream().filter(c -> c.proposal() == null).toList();
    Map<String, List<Candidate>> intakeByType = new LinkedHashMap<>();
    for (Candidate c : ordinary)
      intakeByType
          .computeIfAbsent(
              c.incident().getReviewType() == null
                  ? "TRANSLATION_QUALITY"
                  : c.incident().getReviewType(),
              ignored -> new ArrayList<>())
          .add(c);
    Map<Long, AgentReviewProposal> stagedProposals = new HashMap<>();
    for (List<Candidate> intake : intakeByType.values())
      stagedProposals.putAll(stageHumanIntake(request, requestedByUserId, intake));
    List<Candidate> staged =
        selection.candidates().stream()
            .map(
                c ->
                    c.proposal() != null
                        ? new Candidate(
                            c.incident(),
                            c.unit(),
                            c.locale(),
                            c.current(),
                            assignments.prepareForRouting(
                                c.incident(), c.proposal(), assignmentState))
                        : new Candidate(
                            c.incident(),
                            c.unit(),
                            c.locale(),
                            c.current(),
                            stagedProposals.get(c.incident().getId())))
            .toList();
    List<Long> projectIds = new ArrayList<>();
    Set<Long> requestIds = new LinkedHashSet<>();
    for (List<Candidate> wave : waves(staged)) {
      Candidate first = wave.getFirst();
      List<Long> created =
          projects.createAgentReviewProjects(
              first.proposal().getRunId(),
              first.locale().getId(),
              request.name().trim(),
              request.teamId(),
              request.dueDate(),
              maxWords(request),
              !Boolean.FALSE.equals(request.assignTranslator()),
              wave.stream()
                  .map(
                      c ->
                          new ReviewProjectService.AgentReviewCandidate(
                              c.unit().getId(),
                              c.proposal().getBaselineVariantId(),
                              c.proposal().getSource()))
                  .toList(),
              request.type(),
              request.notes(),
              request.screenshotImageIds(),
              requestedByUserId);
      List<ReviewProjectTextUnit> rows =
          em.createQuery(
                  "select r from ReviewProjectTextUnit r join fetch r.reviewProject p join fetch"
                      + " p.reviewProjectRequest where p.id in :ids",
                  ReviewProjectTextUnit.class)
              .setParameter("ids", created)
              .getResultList();
      Map<Long, ReviewProjectTextUnit> byString = new HashMap<>();
      for (ReviewProjectTextUnit row : rows) {
        byString.put(row.getTmTextUnit().getId(), row);
        requestIds.add(row.getReviewProject().getReviewProjectRequest().getId());
      }
      for (Candidate candidate : wave) {
        ReviewProjectTextUnit row = byString.get(candidate.unit().getId());
        if (row == null) throw new IllegalStateException("Created project omitted incident string");
        AgentReviewProposal proposal = candidate.proposal();
        proposal.setIncidentId(candidate.incident().getId());
        proposal.setReviewProjectId(row.getReviewProject().getId());
        proposal.setReviewProjectTextUnitId(row.getId());
        proposal.setDisposition(Disposition.ROUTED);
        candidate.incident().setResolutionReviewProjectId(row.getReviewProject().getId());
      }
      projectIds.addAll(created);
    }
    return result(selection, projectIds.size(), projectIds, new ArrayList<>(requestIds));
  }

  /** Validate the actor and request shape without scanning incidents or resolving locale scope. */
  public void validateRequest(Request request, Long requestedByUserId) {
    if (!users.isCurrentUserAdminOrPm())
      throw new AccessDeniedException("Incident review creation requires a PM or administrator");
    if (request == null || request.teamId() == null || requestedByUserId == null)
      throw new IllegalArgumentException("Request, team and requesting user are required");
    if (!Objects.equals(requestedByUserId, teams.getCurrentUserIdOrThrow()))
      throw new AccessDeniedException("The requesting user must be the authenticated actor");
    teams.assertCurrentUserCanAccessTeam(request.teamId());
    if (request.name() == null
        || request.name().isBlank()
        || request.name().length() > 255
        || request.dueDate() == null)
      throw new IllegalArgumentException("A name (up to 255 characters) and due date are required");
    if (request.maxWordCountPerProject() != null
        && (maxWords(request) < 1 || maxWords(request) > 100000))
      throw new IllegalArgumentException("maxWordCountPerProject must be between 1 and 100000");
    if (request.maxIncidentCount() != null && request.maxIncidentCount() < 1)
      throw new IllegalArgumentException("maxIncidentCount must be a positive integer");
    if (request.maxIncidentsPerProject() != null
        && (request.maxIncidentsPerProject() < 1 || request.maxIncidentsPerProject() > 5000))
      throw new IllegalArgumentException("maxIncidentsPerProject must be between 1 and 5000");
    if (request.type() == ReviewProjectType.TERMINOLOGY
        || request.type() == ReviewProjectType.TERM_CANDIDATE)
      throw new IllegalArgumentException("Incident review does not use terminology project types");
    if (request.reviewType() != null
        && !request.reviewType().isBlank()
        && !request.reviewType().matches("[A-Z][A-Z0-9_]{0,63}"))
      throw new IllegalArgumentException("reviewType must be an uppercase identifier");
    List<Long> repositories = request.repositoryIds() == null ? List.of() : request.repositoryIds();
    List<Long> featureIds =
        request.reviewFeatureIds() == null ? List.of() : request.reviewFeatureIds();
    boolean allRepositories = Boolean.TRUE.equals(request.allRepositories());
    if (allRepositories && (!repositories.isEmpty() || !featureIds.isEmpty()))
      throw new IllegalArgumentException(
          "All repositories cannot be combined with a repository or feature filter");
    if (!allRepositories && repositories.isEmpty() == featureIds.isEmpty())
      throw new IllegalArgumentException(
          "Choose repositoryIds or reviewFeatureIds, or explicitly set allRepositories");
    if (repositories.size() + featureIds.size() > 200
        || repositories.stream().anyMatch(Objects::isNull)
        || featureIds.stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Select at most 200 identified repositories or features");
  }

  private Scope validate(Request request, Long requestedByUserId) {
    validateRequest(request, requestedByUserId);
    List<Long> repositories = request.repositoryIds() == null ? List.of() : request.repositoryIds();
    List<Long> featureIds =
        request.reviewFeatureIds() == null ? List.of() : request.reviewFeatureIds();
    boolean allRepositories = Boolean.TRUE.equals(request.allRepositories());
    Set<Long> repositoryIds = new LinkedHashSet<>(repositories);
    for (Long id : featureIds) {
      ReviewFeature feature =
          features
              .findByIdWithRepositories(id)
              .orElseThrow(() -> new IllegalArgumentException("Unknown review feature: " + id));
      feature.getRepositories().stream()
          .filter(r -> !r.getDeleted())
          .forEach(r -> repositoryIds.add(r.getId()));
    }
    for (Long id : repositoryIds) {
      Repository repository = em.find(Repository.class, id);
      if (repository == null || repository.getDeleted())
        throw new IllegalArgumentException("Unknown repository: " + id);
    }
    Set<String> requestedLocales = normalized(request.localeTags());
    Set<String> excluded = normalized(request.excludedLocaleTags());
    Set<Long> locales = new LinkedHashSet<>();
    if (allRepositories || !repositoryIds.isEmpty()) {
      var localeQuery =
          em.createQuery(
              "select distinct rl.locale from RepositoryLocale rl where rl.parentLocale is not null"
                  + " and rl.repository.deleted = false"
                  + (allRepositories ? "" : " and rl.repository.id in :ids"),
              Locale.class);
      if (!allRepositories) localeQuery.setParameter("ids", repositoryIds);
      for (Locale locale : localeQuery.getResultList()) {
        String tag = locale.getBcp47Tag().toLowerCase(java.util.Locale.ROOT);
        if ((requestedLocales.isEmpty() || requestedLocales.contains(tag))
            && !excluded.contains(tag)) {
          try {
            users.checkUserCanEditLocale(locale.getId());
            locales.add(locale.getId());
          } catch (AccessDeniedException exception) {
            if (!allRepositories || !requestedLocales.isEmpty()) throw exception;
          }
        }
      }
    }
    return new Scope(repositoryIds, locales, allRepositories);
  }

  private IncidentReviewBatchCursor cursor(Request request, Scope scope, boolean lock) {
    String key =
        hash(
            json(
                List.of(
                    scope.allRepositories(),
                    scope.repositoryIds().stream().sorted().toList(),
                    scope.localeIds().stream().sorted().toList(),
                    requestedReviewType(request) == null ? "*" : requestedReviewType(request))));
    IncidentReviewBatchCursor cursor =
        lock
            ? cursors.findForUpdate(request.teamId(), key).orElse(null)
            : cursors.findByTeamIdAndScopeFingerprint(request.teamId(), key).orElse(null);
    if (cursor == null) {
      if (lock) {
        // Serialize only initial cursor creation; the unique scope row owns subsequent calls.
        em.find(Team.class, request.teamId(), LockModeType.PESSIMISTIC_WRITE);
        cursor = cursors.findForUpdate(request.teamId(), key).orElse(null);
      }
      if (cursor == null) {
        cursor = new IncidentReviewBatchCursor();
        cursor.setTeamId(request.teamId());
        cursor.setScopeFingerprint(key);
        if (lock) cursors.saveAndFlush(cursor);
      }
    }
    if (cursor.getSweepUpperBoundId() == 0) {
      Long upper =
          em.createQuery("select max(i.id) from TranslationIncident i", Long.class)
              .getSingleResult();
      // Preview must never mutate a managed cursor, even within a read-only transaction.
      if (!lock) {
        IncidentReviewBatchCursor preview = new IncidentReviewBatchCursor();
        preview.setLastScannedIncidentId(cursor.getLastScannedIncidentId());
        cursor = preview;
      }
      cursor.setSweepUpperBoundId(upper == null ? 0L : upper);
    }
    return cursor;
  }

  private Selection select(
      Request request, Scope scope, IncidentReviewBatchCursor cursor, boolean lock) {
    return select(request, scope, cursor, lock, true);
  }

  private Selection select(
      Request request,
      Scope scope,
      IncidentReviewBatchCursor cursor,
      boolean lock,
      boolean boundedProjects) {
    if ((!scope.allRepositories() && scope.repositoryIds().isEmpty())
        || scope.localeIds().isEmpty()) return new Selection(List.of(), List.of(), 0L, false, 0);
    String type = requestedReviewType(request);
    int limit = Math.clamp(candidateLimit, 1, 500);
    // Seek before repository/team/locale filtering: even a scope with no matches performs
    // bounded work and advances, instead of scanning millions of active unrelated incidents.
    List<Long> found = seekIds(cursor, type, false, limit + 1);
    if ("TRANSLATION_QUALITY".equals(type)) {
      // Two exact ranges avoid sorting/scanning the combined quality-or-null index range.
      SortedSet<Long> merged = new TreeSet<>(found);
      merged.addAll(seekIds(cursor, null, true, limit + 1));
      found = merged.stream().limit(limit + 1).toList();
    }
    boolean hasMore = found.size() > limit;
    List<Long> ids = found.subList(0, Math.min(found.size(), limit));
    return selectIds(request, scope, cursor, lock, ids, hasMore, boundedProjects);
  }

  private Selection selectIds(
      Request request,
      Scope scope,
      IncidentReviewBatchCursor cursor,
      boolean lock,
      List<Long> ids,
      boolean hasMore,
      boolean boundedProjects) {
    if (ids.isEmpty()
        || scope.localeIds().isEmpty()
        || (!scope.allRepositories() && scope.repositoryIds().isEmpty()))
      return new Selection(List.of(), List.of(), cursor.getLastScannedIncidentId(), false, 0);
    String repositoryPredicate =
        scope.allRepositories()
            ? "(i.selectedTmTextUnitId in (select t.id from TMTextUnit t where"
                + " t.asset.repository.deleted = false) or i.repositoryName in (select r.name"
                + " from Repository r where r.deleted = false))"
            : "(i.selectedTmTextUnitId in (select t.id from TMTextUnit t where"
                + " t.asset.repository.id in :repositories) or i.repositoryName in (select r.name"
                + " from Repository r where r.id in :repositories))";

    // Read scalar routing references first. Entity state is loaded only after its locks are held.
    var scopeQuery =
        em.createQuery(
                "select i.selectedTmTextUnitId, i.resolvedLocaleId, i.reviewFindingId,"
                    + " i.reviewRunId, i.id from TranslationIncident i where i.id in :ids and "
                    + repositoryPredicate
                    + " and (i.resolvedLocaleId in :locales or i.resolvedLocaleId is null)"
                    + " and (i.reviewTeamId is null or i.reviewTeamId = :teamId)"
                    + " and (i.reviewRunId is null or i.reviewRunId in (select r.id"
                    + " from AgentReviewRun r where r.teamId = :teamId))",
                Object[].class)
            .setParameter("ids", ids)
            .setParameter("locales", scope.localeIds())
            .setParameter("teamId", request.teamId());
    if (!scope.allRepositories()) scopeQuery.setParameter("repositories", scope.repositoryIds());
    List<Object[]> references = scopeQuery.getResultList();
    if (references.isEmpty())
      return new Selection(List.of(), List.of(), ids.getLast(), hasMore, ids.size());
    Set<Long> matchingIds = new LinkedHashSet<>();
    Set<Long> unitIds = new TreeSet<>(), localeIds = new TreeSet<>(), runIds = new TreeSet<>();
    Set<String> findings = new HashSet<>();
    Map<Long, Set<Long>> unitsByLocale = new TreeMap<>();
    for (Object[] row : references) {
      matchingIds.add((Long) row[4]);
      Long unitId = (Long) row[0], localeId = (Long) row[1];
      if (unitId != null) unitIds.add(unitId);
      if (localeId != null) localeIds.add(localeId);
      if (unitId != null && localeId != null)
        unitsByLocale.computeIfAbsent(localeId, ignored -> new TreeSet<>()).add(unitId);
      if (row[2] != null) findings.add((String) row[2]);
      if (row[3] != null) runIds.add((Long) row[3]);
    }
    String latestPredicate =
        " p.findingId in :findings and not exists (select n.id"
            + " from AgentReviewProposal n where n.findingId = p.findingId"
            + " and n.proposalRevision > p.proposalRevision)";
    if (!findings.isEmpty())
      runIds.addAll(
          em.createQuery(
                  "select distinct p.runId from AgentReviewProposal p where" + latestPredicate,
                  Long.class)
              .setParameter("findings", findings)
              .getResultList());
    Map<Long, AgentReviewRun> runById = new HashMap<>();
    if (!runIds.isEmpty()) {
      var runQuery =
          em.createQuery(
                  "select r from AgentReviewRun r where r.id in :ids order by r.id",
                  AgentReviewRun.class)
              .setParameter("ids", runIds);
      if (lock) runQuery.setLockMode(LockModeType.PESSIMISTIC_WRITE);
      for (AgentReviewRun run : runQuery.getResultList()) runById.put(run.getId(), run);
    }
    // Match routing/decision lock order: run, parent string, current row, proposal, incident.
    // Parent locks cover the absence of a current target as well.
    Map<Long, TMTextUnit> units = new HashMap<>();
    if (!unitIds.isEmpty()) {
      var unitQuery =
          em.createQuery(
                  "select t from TMTextUnit t join fetch t.asset a"
                      + " join fetch a.repository where t.id in :ids order by t.id",
                  TMTextUnit.class)
              .setParameter("ids", unitIds);
      if (lock) unitQuery.setLockMode(LockModeType.PESSIMISTIC_WRITE);
      for (TMTextUnit unit : unitQuery.getResultList()) units.put(unit.getId(), unit);
    }
    Map<Long, Locale> locales = new HashMap<>();
    if (!localeIds.isEmpty())
      for (Locale locale :
          em.createQuery("select l from Locale l where l.id in :ids", Locale.class)
              .setParameter("ids", localeIds)
              .getResultList()) locales.put(locale.getId(), locale);
    Map<CurrentKey, TMTextUnitVariant> current = loadCurrent(unitsByLocale, lock);
    Map<String, AgentReviewProposal> latest = new HashMap<>();
    if (!findings.isEmpty()) {
      var proposalQuery =
          em.createQuery(
                  "select p from AgentReviewProposal p where" + latestPredicate + " order by p.id",
                  AgentReviewProposal.class)
              .setParameter("findings", findings);
      if (lock) proposalQuery.setLockMode(LockModeType.PESSIMISTIC_WRITE);
      for (AgentReviewProposal proposal : proposalQuery.getResultList())
        latest.put(proposal.getFindingId(), proposal);
    }
    var incidentQuery =
        em.createQuery(
                "select i from TranslationIncident i where i.id in :ids order by i.id",
                TranslationIncident.class)
            .setParameter("ids", matchingIds);
    if (lock) incidentQuery.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    List<Candidate> scanned =
        incidentQuery.getResultList().stream()
            .map(
                i ->
                    new Candidate(
                        i,
                        units.get(i.getSelectedTmTextUnitId()),
                        locales.get(i.getResolvedLocaleId()),
                        current.get(
                            new CurrentKey(i.getSelectedTmTextUnitId(), i.getResolvedLocaleId())),
                        latest.get(i.getReviewFindingId())))
            .toList();
    var assignmentState =
        assignments.load(
            scanned.stream().map(Candidate::incident).toList(),
            scanned.stream().map(Candidate::proposal).toList(),
            lock);
    Map<String, Set<String>> fingerprintsByType = new HashMap<>();
    for (Candidate c : scanned)
      if (c.unit() != null && c.locale() != null)
        fingerprintsByType
            .computeIfAbsent(effectiveType(c.incident()), ignored -> new HashSet<>())
            .add(fingerprint(c));
    Map<String, Set<String>> reviewed = new HashMap<>();
    fingerprintsByType.forEach(
        (reviewType, hashes) ->
            reviewed.put(
                reviewType, reviewedStates.reviewed(request.teamId(), reviewType, hashes)));
    Map<Long, CheckpointState> checkpoints = new HashMap<>();
    List<Candidate> eligible = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    ProjectBudget budget =
        new ProjectBudget(
            maxWords(request),
            boundedProjects ? Math.clamp(projectLimit, 1, 25) : Integer.MAX_VALUE);
    Map<Long, Candidate> byId = new HashMap<>();
    for (Candidate candidate : scanned) byId.put(candidate.incident().getId(), candidate);
    long lastId = cursor.getLastScannedIncidentId();
    int examined = 0;
    for (Long id : ids) {
      Candidate candidate = byId.get(id);
      if (candidate == null) {
        // Advance over other scopes without returning their incident IDs or contents.
        lastId = id;
        examined++;
        continue;
      }
      String reason =
          ineligible(request, scope, candidate, runById, checkpoints, reviewed, assignmentState);
      if (reason == null) {
        if (!budget.add(candidate)) {
          hasMore = true;
          break;
        }
        eligible.add(candidate);
      } else skipped.add(new Skipped(candidate.incident().getId(), reason));
      lastId = id;
      examined++;
    }
    return new Selection(eligible, skipped, lastId, hasMore, examined);
  }

  private List<Long> seekIds(
      IncidentReviewBatchCursor cursor, String reviewType, boolean nullTypeOnly, int limit) {
    boolean typed = reviewType != null || nullTypeOnly;
    // Seek a bounded range of open incidents, including assignments to closed projects.
    // Project status is checked under the assignment locks after this indexed range read.
    String index =
        dbUtils.isMysql()
            ? " force index ("
                + (typed
                    ? "I__TRANSLATION_INCIDENT__TYPED_OPEN_SEEK"
                    : "I__TRANSLATION_INCIDENT__OPEN_SEEK")
                + ")"
            : "";
    String typePredicate =
        nullTypeOnly
            ? " and review_type is null"
            : reviewType == null ? "" : " and review_type = ?";
    List<Object> parameters = new ArrayList<>();
    parameters.add(cursor.getLastScannedIncidentId());
    parameters.add(cursor.getSweepUpperBoundId());
    if (reviewType != null) parameters.add(reviewType);
    parameters.add(limit);
    return jdbc.queryForList(
        "select id from translation_incident"
            + index
            + " where status = 'OPEN'"
            + " and id > ? and id <= ?"
            + typePredicate
            + " order by id limit ?",
        Long.class,
        parameters.toArray());
  }

  private Map<CurrentKey, TMTextUnitVariant> loadCurrent(
      Map<Long, Set<Long>> unitsByLocale, boolean lock) {
    Map<CurrentKey, TMTextUnitVariant> result = new HashMap<>();
    if (unitsByLocale.isEmpty()) return result;
    List<String> conditions = new ArrayList<>();
    int n = 0;
    for (Long ignored : unitsByLocale.keySet()) {
      conditions.add("(c.locale.id = :locale" + n + " and c.tmTextUnit.id in :units" + n + ")");
      n++;
    }
    var query =
        em.createQuery(
            "select c from TMTextUnitCurrentVariant c left join fetch"
                + " c.tmTextUnitVariant where "
                + String.join(" or ", conditions)
                + " order by c.tmTextUnit.id, c.locale.id",
            TMTextUnitCurrentVariant.class);
    n = 0;
    for (var entry : unitsByLocale.entrySet()) {
      query.setParameter("locale" + n, entry.getKey()).setParameter("units" + n, entry.getValue());
      n++;
    }
    if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
    for (TMTextUnitCurrentVariant row : query.getResultList())
      result.put(
          new CurrentKey(row.getTmTextUnit().getId(), row.getLocale().getId()),
          row.getTmTextUnitVariant());
    return result;
  }

  private static String requestedReviewType(Request request) {
    return request.reviewType() == null || request.reviewType().isBlank()
        ? null
        : request.reviewType();
  }

  private static String effectiveType(TranslationIncident incident) {
    return incident.getReviewType() == null ? "TRANSLATION_QUALITY" : incident.getReviewType();
  }

  private static String fingerprint(Candidate c) {
    return AgentReviewStateFingerprint.of(
        c.unit().getId(),
        c.locale().getId(),
        c.unit().getContent(),
        c.unit().getComment(),
        c.current() == null ? null : c.current().getId(),
        c.current() == null ? null : c.current().getContent(),
        c.current() == null ? null : c.current().getStatus().name(),
        c.current() == null ? null : c.current().isIncludedInLocalizedFile());
  }

  private String ineligible(
      Request request,
      Scope scope,
      Candidate c,
      Map<Long, AgentReviewRun> runById,
      Map<Long, CheckpointState> checkpoints,
      Map<String, Set<String>> reviewed,
      IncidentReviewAssignmentService.Snapshot assignmentState) {
    TranslationIncident i = c.incident();
    if (i.getStatus() != TranslationIncidentStatus.OPEN) return "Incident is closed";
    String assignmentReason = assignments.ineligible(i, c.proposal(), assignmentState);
    if (assignmentReason != null) return assignmentReason;
    if (i.getResolution() == TranslationIncidentResolution.REJECTED)
      return "Incident was already handled";
    if (i.getReviewTeamId() != null && !Objects.equals(i.getReviewTeamId(), request.teamId()))
      return "Incident belongs to a different team";
    String type = effectiveType(i);
    if (request.reviewType() != null
        && !request.reviewType().isBlank()
        && !request.reviewType().equals(type)) return "Different review type";
    if (c.unit() == null) return "Incident has no resolved string";
    if (c.locale() == null) return "Incident has no resolved locale";
    if (!scope.allRepositories()
        && !scope.repositoryIds().contains(c.unit().getAsset().getRepository().getId()))
      return "String belongs to a repository outside this selection";
    if (!scope.localeIds().contains(c.locale().getId())) return "Locale is outside this selection";
    if (c.unit().getAsset().getDeleted()) return "String's asset was deleted";
    if (c.unit().getAsset().getRepository().getDeleted()) return "String's repository was deleted";
    if (!"UNIQUE_MATCH".equals(i.getLookupResolutionStatus())
        || !"EXACT".equals(i.getLocaleResolutionStrategy())
        || i.isLocaleUsedFallback()) return "Incident needs an exact string and locale match";
    AgentReviewProposal p = c.proposal();
    if (i.getReviewFindingId() != null && p == null) return "Linked proposal is unavailable";
    if (p != null) {
      AgentReviewRun run = runById.get(p.getRunId());
      if (run == null || !Objects.equals(run.getTeamId(), request.teamId()))
        return "Finding belongs to a different team";
      if (run.getStatus() == RunStatus.CANCELLED) return "Review run was cancelled";
      // Assignment status and pending/final human feedback were checked above.
      if ((p.getReadiness() != Readiness.READY && p.getReadiness() != Readiness.HUMAN_REVIEW)
          || p.getCategory() == Category.OPTIONAL_IMPROVEMENT)
        return "Finding is not ready for human review";
      CheckpointState checkpoint =
          checkpoints.computeIfAbsent(run.getId(), ignored -> completed(run));
      if (checkpoint.invalid()) return "Review checkpoint is unavailable or invalid";
      if (!checkpoint.completedGroups().contains(p.getGroupKey()))
        return "Review group is not complete";
      if (!Objects.equals(p.getSource(), c.unit().getContent())
          || !Objects.equals(p.getSourceComment(), c.unit().getComment())
          || !sameCurrent(
              c.current(),
              p.getBaselineVariantId(),
              p.getBaselineTarget(),
              p.getBaselineStatus(),
              p.getBaselineIncludedInLocalizedFile()))
        return "Current string changed since the finding";
    } else if (!Objects.equals(i.getSelectedSource(), c.unit().getContent())
        || (i.getIntakeFingerprint() != null
            && !Objects.equals(i.getSelectedSourceComment(), c.unit().getComment()))
        || !sameCurrent(
            c.current(),
            i.getSelectedTmTextUnitVariantId(),
            i.getSelectedTarget(),
            i.getSelectedTranslationStatus(),
            i.getSelectedIncludedInLocalizedFile())) {
      return "Current string changed since the incident";
    }
    if ((p == null || p.getRespondsToFeedbackId() == null)
        && reviewed.getOrDefault(type, Set.of()).contains(fingerprint(c)))
      return "Current translation was already reviewed; use Review again for a new round";
    return null;
  }

  private boolean sameCurrent(
      TMTextUnitVariant current, Long id, String target, String status, Boolean included) {
    return current == null
        ? id == null && target == null
        : Objects.equals(current.getId(), id)
            && Objects.equals(current.getContent(), target)
            && Objects.equals(current.getStatus().name(), status)
            && Objects.equals(current.isIncludedInLocalizedFile(), included);
  }

  private CheckpointState completed(AgentReviewRun run) {
    if (run.getCheckpointSha256() == null) return new CheckpointState(Set.of(), false);
    try {
      String content =
          blobs
              .getString(
                  StructuredBlobStorage.Prefix.AGENT_REVIEW,
                  "runs/" + run.getId() + "/artifacts/" + run.getCheckpointSha256())
              .orElseThrow();
      byte[] json =
          Base64.getDecoder().decode(mapper.readTree(content).get("contentBase64").asText());
      var checkpoint = mapper.readValue(json, AgentReviewContracts.Checkpoint.class);
      Set<String> completed = new HashSet<>();
      checkpoint
          .groups()
          .forEach(
              (key, value) -> {
                if (value.status() == AgentReviewContracts.GroupStatus.COMPLETED)
                  completed.add(key);
              });
      return new CheckpointState(completed, false);
    } catch (RuntimeException | java.io.IOException e) {
      return new CheckpointState(Set.of(), true);
    }
  }

  private Group group(Candidate c) {
    AgentReviewProposal p = c.proposal();
    return new Group(
        p == null ? null : p.getRunId(),
        c.unit().getAsset().getRepository().getId(),
        c.locale().getId(),
        p == null ? effectiveType(c.incident()) : p.getGroupKey());
  }

  private List<List<Candidate>> waves(List<Candidate> candidates) {
    Map<Group, List<List<Candidate>>> groups = new LinkedHashMap<>();
    Map<Group, Map<Long, Integer>> occurrences = new HashMap<>();
    for (Candidate c : candidates) {
      Group key = group(c);
      int waveIndex =
          occurrences
                  .computeIfAbsent(key, ignored -> new HashMap<>())
                  .merge(c.unit().getId(), 1, Integer::sum)
              - 1;
      List<List<Candidate>> waves = groups.computeIfAbsent(key, ignored -> new ArrayList<>());
      if (waves.size() <= waveIndex) waves.add(new ArrayList<>());
      waves.get(waveIndex).add(c);
    }
    return groups.values().stream().flatMap(Collection::stream).toList();
  }

  /** Count the exact grouping/word split while accepting only a bounded input prefix. */
  private class ProjectBudget {
    private final int maxWords;
    private final int maxProjects;
    private int projectCount;
    private final Map<Group, Map<Long, Integer>> occurrences = new HashMap<>();
    private final Map<Group, List<Long>> chunkWords = new HashMap<>();

    ProjectBudget(int maxWords, int maxProjects) {
      this.maxWords = maxWords;
      this.maxProjects = maxProjects;
    }

    boolean add(Candidate c) {
      Group key = group(c);
      Map<Long, Integer> counts = occurrences.computeIfAbsent(key, ignored -> new HashMap<>());
      int waveIndex = counts.getOrDefault(c.unit().getId(), 0);
      List<Long> totals = chunkWords.computeIfAbsent(key, ignored -> new ArrayList<>());
      int count = words.getEnglishWordCount(c.unit().getContent());
      boolean newProject = totals.size() <= waveIndex || totals.get(waveIndex) + count > maxWords;
      if (newProject && projectCount == maxProjects) return false;
      counts.put(c.unit().getId(), waveIndex + 1);
      if (totals.size() <= waveIndex) totals.add((long) count);
      else totals.set(waveIndex, newProject ? count : totals.get(waveIndex) + count);
      if (newProject) projectCount++;
      return true;
    }
  }

  private int countProjects(List<Candidate> wave, int max) {
    int count = 0, total = 0;
    boolean present = false;
    for (Candidate candidate : wave) {
      int n = words.getEnglishWordCount(candidate.unit().getContent());
      if (present && total + n > max) {
        count++;
        total = 0;
        present = false;
      }
      total += n;
      present = true;
    }
    return count + (present ? 1 : 0);
  }

  private Map<Long, AgentReviewProposal> stageHumanIntake(
      Request request, Long actor, List<Candidate> candidates) {
    Map<Long, AgentReviewProposal> staged = new HashMap<>();
    // A run describes this human intake operation, not an agent assessment or verification.
    AgentReviewRun run = new AgentReviewRun();
    run.setRequestKey("incident-batch:" + UUID.randomUUID());
    run.setRequestFingerprint(hash(run.getRequestKey()));
    run.setRequestedByUserId(actor);
    run.setTeamId(request.teamId());
    run.setReviewType(
        candidates.getFirst().incident().getReviewType() == null
            ? "TRANSLATION_QUALITY"
            : candidates.getFirst().incident().getReviewType());
    run.setMethodVersion("human-incident-intake-v1");
    run.setConfigurationVersion("incident-batch-v1");
    run.setRepositoryIdsJson(
        json(
            candidates.stream()
                .map(c -> c.unit().getAsset().getRepository().getId())
                .distinct()
                .sorted()
                .toList()));
    run.setLocaleIdsJson(
        json(candidates.stream().map(c -> c.locale().getId()).distinct().sorted().toList()));
    run.setStatus(RunStatus.COMPLETED);
    run.setCompletedAt(ZonedDateTime.now());
    run.setRoutingPolicy(RoutingPolicy.QUEUED);
    run.setDueDateOffsetDays(7);
    run.setMaxWordCountPerProject(maxWords(request));
    run.setAssignTranslator(!Boolean.FALSE.equals(request.assignTranslator()));
    run.setManifestSha256("pending");
    run.setInputFingerprint(
        hash(json(candidates.stream().map(c -> c.incident().getId()).toList())));
    runs.saveAndFlush(run);
    Map<String, List<Candidate>> groups = new LinkedHashMap<>();
    for (Candidate c : candidates)
      groups
          .computeIfAbsent(
              "incidents-" + c.unit().getAsset().getRepository().getId() + "-" + c.locale().getId(),
              ignored -> new ArrayList<>())
          .add(c);
    List<AgentReviewContracts.Group> manifestGroups = new ArrayList<>();
    Map<String, AgentReviewContracts.GroupCheckpoint> checkpoints = new LinkedHashMap<>();
    for (Map.Entry<String, List<Candidate>> group : groups.entrySet()) {
      Candidate first = group.getValue().getFirst();
      String evidence = json(group.getValue().stream().map(this::snapshot).toList());
      manifestGroups.add(
          new AgentReviewContracts.Group(
              group.getKey(),
              first.unit().getAsset().getRepository().getId(),
              first.locale().getId(),
              "Incident intake",
              group.getValue().stream().map(c -> c.unit().getId()).distinct().toList(),
              hash(evidence)));
      checkpoints.put(
          group.getKey(),
          new AgentReviewContracts.GroupCheckpoint(
              AgentReviewContracts.GroupStatus.COMPLETED,
              0,
              artifact(run.getId(), evidence),
              "Human incident intake complete; linguistic review is pending."));
      for (Candidate c : group.getValue()) {
        AgentReviewProposal p = new AgentReviewProposal();
        p.setRunId(run.getId());
        p.setSubmissionKey("incident:" + c.incident().getId());
        p.setRequestFingerprint(hash(p.getSubmissionKey()));
        p.setFindingId(UUID.randomUUID().toString());
        p.setProposalRevision(1);
        p.setGroupKey(group.getKey());
        p.setRepositoryId(c.unit().getAsset().getRepository().getId());
        p.setLocaleId(c.locale().getId());
        p.setTmTextUnitId(c.unit().getId());
        p.setSource(c.unit().getContent());
        p.setSourceComment(c.unit().getComment());
        if (c.current() != null) {
          p.setBaselineVariantId(c.current().getId());
          p.setBaselineTarget(c.current().getContent());
          p.setBaselineStatus(c.current().getStatus().name());
          p.setBaselineIncludedInLocalizedFile(c.current().isIncludedInLocalizedFile());
        }
        p.setCategory(Category.HUMAN_REVIEW);
        p.setReadiness(Readiness.HUMAN_REVIEW);
        p.setDisposition(Disposition.OPEN);
        p.setRationale(
            c.incident().getReason() == null
                ? "Incident submitted for human assessment."
                : c.incident().getReason());
        p.setEvidenceJson(
            json(
                Map.of(
                    "incidentId",
                    c.incident().getId(),
                    "intake",
                    "Human-requested incident review; no agent verification or replacement"
                        + " asserted.")));
        p.setProducerIdentity("human-incident-intake:user:" + actor);
        p.setIncidentId(c.incident().getId());
        if (Objects.equals(c.incident().getReviewTeamId(), request.teamId()))
          p.setIntakeFingerprint(c.incident().getIntakeFingerprint());
        proposals.save(p);
        staged.put(c.incident().getId(), p);
        c.incident().setReviewFindingId(p.getFindingId());
        c.incident().setReviewRunId(run.getId());
        c.incident().setReviewType(run.getReviewType());
      }
    }
    run.setPlannedGroupCount(groups.size());
    run.setCompletedGroupCount(groups.size());
    var manifest =
        new AgentReviewContracts.RunManifest(
            manifestGroups,
            json(Map.of("kind", "HUMAN_INCIDENT_INTAKE", "requestedByUserId", actor)));
    run.setInputFingerprint(hash(json(manifest)));
    run.setManifestSha256(artifact(run.getId(), manifest));
    run.setCheckpointSha256(
        artifact(run.getId(), new AgentReviewContracts.Checkpoint(checkpoints)));
    return staged;
  }

  private Map<String, Object> snapshot(Candidate candidate) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("incidentId", candidate.incident().getId());
    snapshot.put("tmTextUnitId", candidate.unit().getId());
    snapshot.put("source", candidate.unit().getContent());
    snapshot.put("sourceComment", candidate.unit().getComment());
    snapshot.put(
        "baselineVariantId", candidate.current() == null ? null : candidate.current().getId());
    snapshot.put(
        "baselineTarget", candidate.current() == null ? null : candidate.current().getContent());
    snapshot.put(
        "baselineStatus",
        candidate.current() == null ? null : candidate.current().getStatus().name());
    snapshot.put(
        "baselineIncludedInLocalizedFile",
        candidate.current() == null ? null : candidate.current().isIncludedInLocalizedFile());
    return snapshot;
  }

  private String artifact(long runId, Object value) {
    String content =
        json(
            Map.of(
                "contentType",
                "application/json",
                "contentBase64",
                Base64.getEncoder().encodeToString(json(value).getBytes(StandardCharsets.UTF_8))));
    String hash = hash(content);
    blobs.put(
        StructuredBlobStorage.Prefix.AGENT_REVIEW,
        "runs/" + runId + "/artifacts/" + hash,
        content,
        Retention.PERMANENT);
    return hash;
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<String> normalized(List<String> tags) {
    Set<String> result = new LinkedHashSet<>();
    if (tags != null)
      for (String tag : tags) {
        if (tag == null || tag.isBlank())
          throw new IllegalArgumentException("Locale tags must not be blank");
        result.add(tag.trim().toLowerCase(java.util.Locale.ROOT));
      }
    return result;
  }

  private static int maxWords(Request request) {
    return request.maxWordCountPerProject() == null
        ? Integer.MAX_VALUE
        : request.maxWordCountPerProject();
  }

  private Result result(
      Selection selection, int count, List<Long> projectIds, List<Long> requestIds) {
    return new Result(
        selection.candidates().size(),
        selection.skipped().size(),
        count,
        selection.candidates().stream()
            .map(c -> c.locale().getBcp47Tag())
            .distinct()
            .sorted()
            .toList(),
        projectIds,
        requestIds,
        selection.skipped(),
        selection.scannedCount(),
        selection.hasMore());
  }
}
