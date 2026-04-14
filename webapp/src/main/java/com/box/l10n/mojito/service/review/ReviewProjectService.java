package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Locale_;
import com.box.l10n.mojito.entity.review.*;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest_;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.entity.review.ReviewProject_;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.entity.security.user.User_;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.WordCountService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.team.TeamSlackNotificationService;
import com.box.l10n.mojito.service.team.TeamUserRepository;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.google.common.base.Stopwatch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

@Service
public class ReviewProjectService {

  private static final Logger logger = LoggerFactory.getLogger(ReviewProjectService.class);
  private static final String METRIC_PREFIX = "ReviewProjectService";
  private static final String SEARCH_MODE_PROJECTS = "projects";
  private static final String SEARCH_MODE_REQUESTS = "requests";
  private static final long SEARCH_PHASE_SLOW_LOG_THRESHOLD_MS = 250;
  private static final long SEARCH_TOTAL_SLOW_LOG_THRESHOLD_MS = 1_000;

  private final ReviewProjectRepository reviewProjectRepository;
  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository;
  private final ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository;
  private final ReviewProjectRequestRepository reviewProjectRequestRepository;
  private final ReviewProjectRequestScreenshotRepository reviewProjectScreenshotRepository;
  private final ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository;
  private final LocaleService localeService;
  private final TextUnitSearcher textUnitSearcher;
  private final TMTextUnitRepository tmTextUnitRepository;
  private final TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;
  private final TMService tmService;
  private final WordCountService wordCountService;
  private final UserService userService;
  private final UserRepository userRepository;
  private final TeamService teamService;
  private final TeamRepository teamRepository;
  private final TeamUserRepository teamUserRepository;
  private final ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository;
  private final TeamSlackNotificationService teamSlackNotificationService;
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  private final ReviewFeatureRepository reviewFeatureRepository;
  private final MeterRegistry meterRegistry;

  @PersistenceContext private EntityManager entityManager;

  public ReviewProjectService(
      ReviewProjectRepository reviewProjectRepository,
      ReviewProjectTextUnitRepository reviewProjectTextUnitRepository,
      ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository,
      ReviewProjectRequestRepository reviewProjectRequestRepository,
      ReviewProjectRequestScreenshotRepository reviewProjectScreenshotRepository,
      ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository,
      LocaleService localeService,
      TextUnitSearcher textUnitSearcher,
      TMTextUnitRepository tmTextUnitRepository,
      TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository,
      TMService tmService,
      WordCountService wordCountService,
      UserService userService,
      UserRepository userRepository,
      TeamService teamService,
      TeamRepository teamRepository,
      TeamUserRepository teamUserRepository,
      ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository,
      TeamSlackNotificationService teamSlackNotificationService,
      QuartzPollableTaskScheduler quartzPollableTaskScheduler,
      ReviewFeatureRepository reviewFeatureRepository,
      MeterRegistry meterRegistry) {
    this.reviewProjectRepository = reviewProjectRepository;
    this.reviewProjectTextUnitRepository = reviewProjectTextUnitRepository;
    this.reviewProjectTextUnitDecisionRepository = reviewProjectTextUnitDecisionRepository;
    this.reviewProjectRequestRepository = reviewProjectRequestRepository;
    this.reviewProjectScreenshotRepository = reviewProjectScreenshotRepository;
    this.reviewProjectRequestSlackThreadRepository = reviewProjectRequestSlackThreadRepository;
    this.localeService = localeService;
    this.textUnitSearcher = textUnitSearcher;
    this.tmTextUnitRepository = tmTextUnitRepository;
    this.tmTextUnitCurrentVariantRepository = tmTextUnitCurrentVariantRepository;
    this.tmService = tmService;
    this.wordCountService = wordCountService;
    this.userService = userService;
    this.userRepository = userRepository;
    this.teamService = teamService;
    this.teamRepository = teamRepository;
    this.teamUserRepository = teamUserRepository;
    this.reviewProjectAssignmentHistoryRepository = reviewProjectAssignmentHistoryRepository;
    this.teamSlackNotificationService = teamSlackNotificationService;
    this.quartzPollableTaskScheduler = quartzPollableTaskScheduler;
    this.reviewFeatureRepository = reviewFeatureRepository;
    this.meterRegistry = meterRegistry;
  }

  public PollableFuture<CreateReviewProjectRequestResult> createReviewProjectRequestAsync(
      CreateReviewProjectRequestCommand request) {
    Long requestedByUserId = teamService.getCurrentUserIdOrThrow();
    if (request.teamId() != null) {
      teamService.assertUserCanAccessTeam(request.teamId(), requestedByUserId);
    }

    CreateReviewProjectRequestCommand asyncRequest =
        new CreateReviewProjectRequestCommand(
            request.localeTags(),
            request.notes(),
            request.tmTextUnitIds(),
            request.reviewFeatureId(),
            request.statusFilter(),
            request.skipTextUnitsInOpenProjects(),
            request.type(),
            request.dueDate(),
            request.screenshotImageIds(),
            request.name(),
            request.teamId(),
            request.assignTranslator(),
            requestedByUserId);

    QuartzJobInfo<CreateReviewProjectRequestCommand, CreateReviewProjectRequestResult>
        quartzJobInfo =
            QuartzJobInfo.newBuilder(ReviewProjectCreateRequestJob.class)
                .withInlineInput(false)
                .withInput(asyncRequest)
                .withMessage("Create review project request")
                .build();
    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  @Transactional
  public CreateReviewProjectRequestResult createReviewProjectRequest(
      CreateReviewProjectRequestCommand request) {
    if (CollectionUtils.isEmpty(request.localeTags())) {
      throw new IllegalArgumentException("At least one locale must be provided");
    }

    if (request.dueDate() == null) {
      throw new IllegalArgumentException("Due date must be provided");
    }

    if (request.name() == null || request.name().trim().isEmpty()) {
      throw new IllegalArgumentException("Name must be provided");
    }

    boolean hasTmTextUnitIds =
        request.tmTextUnitIds() != null && !request.tmTextUnitIds().isEmpty();
    boolean hasReviewFeatureId = request.reviewFeatureId() != null;
    if (hasTmTextUnitIds == hasReviewFeatureId) {
      throw new IllegalArgumentException(
          "Exactly one of tmTextUnitIds or reviewFeatureId must be provided");
    }

    if (request.type() == null) {
      throw new IllegalArgumentException("type must be provided");
    }

    if (request.requestedByUserId() == null) {
      throw new IllegalArgumentException("requestedByUserId must be provided");
    }

    logger.info(
        "Create review project request: name='{}', teamId={}, requestedLocales={}, tmTextUnitCount={}, reviewFeatureId={}, statusFilter={}, skipTextUnitsInOpenProjects={}",
        request.name(),
        request.teamId(),
        request.localeTags(),
        hasTmTextUnitIds ? request.tmTextUnitIds().size() : null,
        request.reviewFeatureId(),
        request.statusFilter(),
        Boolean.TRUE.equals(request.skipTextUnitsInOpenProjects()));

    List<LocalePlan> localePlans = new ArrayList<>();
    ReviewFeature reviewFeature =
        hasReviewFeatureId ? getReviewFeatureOrThrow(request.reviewFeatureId()) : null;
    for (String localeTag : new LinkedHashSet<>(request.localeTags())) {
      Locale locale = localeService.findByBcp47Tag(localeTag);
      if (locale == null) {
        localePlans.add(errorLocalePlan(localeTag, "Unknown locale: " + localeTag));
        continue;
      }

      try {
        List<TextUnitDTO> candidates =
            hasTmTextUnitIds
                ? searchReviewCandidates(request.tmTextUnitIds(), locale, request.statusFilter())
                : searchReviewFeatureCandidates(
                    reviewFeature,
                    locale,
                    getManualReviewFeatureStatusFilter(request.statusFilter()));
        if (Boolean.TRUE.equals(request.skipTextUnitsInOpenProjects())) {
          int originalCandidateCount = candidates.size();
          candidates = excludeOpenReviewProjectTextUnits(candidates, locale);
          if (originalCandidateCount != candidates.size()) {
            logger.info(
                "Excluded {} text units already covered by open review projects for locale '{}'",
                originalCandidateCount - candidates.size(),
                locale.getBcp47Tag());
          }
        }
        if (candidates.isEmpty()) {
          logger.info(
              "Skipping review project locale '{}' for request '{}' because no text units matched",
              localeTag,
              request.name());
          localePlans.add(skippedLocalePlan(localeTag));
          continue;
        }
        logger.info(
            "Prepared review project locale '{}' for request '{}' with {} matched text units",
            locale.getBcp47Tag(),
            request.name(),
            candidates.size());

        localePlans.add(preparedLocalePlan(locale.getBcp47Tag(), locale, candidates, 1, null));
      } catch (RuntimeException e) {
        logger.warn(
            "Failed to prepare review project locale '{}' for request '{}': {}",
            localeTag,
            request.name(),
            e.getMessage());
        localePlans.add(errorLocalePlan(localeTag, e.getMessage()));
      }
    }

    List<LocaleCandidates> localesToCreate = getPreparedLocales(localePlans);
    if (localesToCreate.isEmpty()) {
      logger.info(
          "No review project locales created: name='{}', teamId={}, requestedLocales={}",
          request.name(),
          request.teamId(),
          request.localeTags());
      return buildCreateReviewProjectRequestResult(
          null, request.name(), request.dueDate(), List.of(), request.localeTags(), localePlans);
    }

    PersistedReviewProjectRequest persistedReviewProjectRequest =
        persistPreparedReviewProjectRequest(
            request.name(),
            request.notes(),
            request.screenshotImageIds(),
            request.type(),
            request.dueDate(),
            request.teamId(),
            request.requestedByUserId(),
            request.assignTranslator(),
            localesToCreate);

    return buildCreateReviewProjectRequestResult(
        persistedReviewProjectRequest, request.localeTags(), localePlans);
  }

  @Transactional
  public CreateReviewProjectRequestResult createAutomatedReviewProjectRequest(
      CreateAutomatedReviewProjectRequestCommand request) {
    if (request == null) {
      throw new IllegalArgumentException("request must be provided");
    }
    if (request.reviewFeatureId() == null) {
      throw new IllegalArgumentException("reviewFeatureId must be provided");
    }
    if (request.teamId() == null) {
      throw new IllegalArgumentException("teamId must be provided");
    }
    if (request.requestedByUserId() == null) {
      throw new IllegalArgumentException("requestedByUserId must be provided");
    }
    if (request.dueDate() == null) {
      throw new IllegalArgumentException("dueDate must be provided");
    }
    if (request.name() == null || request.name().trim().isEmpty()) {
      throw new IllegalArgumentException("name must be provided");
    }

    ReviewFeature reviewFeature = getReviewFeatureOrThrow(request.reviewFeatureId());
    int maxWordCountPerProject =
        request.maxWordCountPerProject() == null
            ? Integer.MAX_VALUE
            : request.maxWordCountPerProject();
    if (maxWordCountPerProject < 1) {
      throw new IllegalArgumentException("maxWordCountPerProject must be positive");
    }

    List<LocalePlan> localePlans = new ArrayList<>();
    for (Locale locale : getReviewFeatureLocales(reviewFeature)) {
      try {
        List<TextUnitDTO> candidates =
            searchReviewFeatureCandidates(reviewFeature, locale, StatusFilter.REVIEW_NEEDED);
        candidates = excludeOpenReviewProjectTextUnits(candidates, locale);
        if (candidates.isEmpty()) {
          localePlans.add(skippedLocalePlan(locale.getBcp47Tag()));
          continue;
        }

        int chunkCount = 0;
        for (List<TextUnitDTO> chunk :
            splitCandidatesByMaxWordCount(candidates, maxWordCountPerProject)) {
          if (chunk.isEmpty()) {
            continue;
          }
          chunkCount++;
        }
        localePlans.add(
            preparedLocalePlan(locale.getBcp47Tag(), locale, candidates, chunkCount, null));
        logger.info(
            "Prepared automated review project locale '{}' for feature '{}' with {} candidates across {} chunk(s)",
            locale.getBcp47Tag(),
            reviewFeature.getName(),
            candidates.size(),
            chunkCount);
      } catch (RuntimeException e) {
        logger.warn(
            "Failed to prepare automated review project locale '{}' for feature '{}': {}",
            locale.getBcp47Tag(),
            reviewFeature.getName(),
            e.getMessage());
        localePlans.add(errorLocalePlan(locale.getBcp47Tag(), e.getMessage()));
      }
    }

    List<LocaleCandidates> localesToCreate =
        getPreparedLocalesWithChunks(localePlans, maxWordCountPerProject);
    if (localesToCreate.isEmpty()) {
      logger.info(
          "No automated review project request created for feature '{}' because no eligible text units were found",
          reviewFeature.getName());
      return buildCreateReviewProjectRequestResult(
          null,
          request.name().trim(),
          request.dueDate(),
          List.of(),
          getReviewFeatureLocales(reviewFeature).stream().map(Locale::getBcp47Tag).toList(),
          localePlans);
    }

    PersistedReviewProjectRequest persistedReviewProjectRequest =
        persistPreparedReviewProjectRequest(
            request.name().trim(),
            request.notes(),
            null,
            ReviewProjectType.NORMAL,
            request.dueDate(),
            request.teamId(),
            request.requestedByUserId(),
            request.assignTranslator(),
            localesToCreate);

    return buildCreateReviewProjectRequestResult(
        persistedReviewProjectRequest,
        getReviewFeatureLocales(reviewFeature).stream().map(Locale::getBcp47Tag).toList(),
        localePlans);
  }

  private record LocaleCandidates(Locale locale, List<TextUnitDTO> candidates) {}

  private enum LocalePlanStatus {
    PREPARED,
    SKIPPED_NO_TEXT_UNITS,
    ERROR
  }

  private record LocalePlan(
      String localeTag,
      Locale locale,
      List<TextUnitDTO> candidates,
      int projectCount,
      LocalePlanStatus status,
      String message) {}

  private record PersistedReviewProjectRequest(
      Long requestId,
      String requestName,
      List<String> localeTags,
      ZonedDateTime dueDate,
      List<Long> projectIds) {}

  private record CreateAssignmentDefaults(
      Team team, User defaultPmUser, Map<String, User> defaultTranslatorByLocaleTagLowercase) {}

  private record ProjectAccessContext(
      Long userId,
      boolean admin,
      boolean pm,
      boolean translator,
      Set<Long> pmTeamIds,
      Set<Long> translatorTeamIds,
      Set<Long> editableLocaleIds,
      boolean canTranslateAllLocales) {}

  @Transactional(readOnly = true)
  public SearchReviewProjectsView searchReviewProjects(SearchReviewProjectsCriteria request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    Stopwatch totalStopwatch = Stopwatch.createStarted();
    Stopwatch phaseStopwatch = Stopwatch.createStarted();
    ProjectAccessContext accessContext = createProjectAccessContext();
    recordSearchPhase(SEARCH_MODE_PROJECTS, "accessContext", phaseStopwatch, 1);

    phaseStopwatch = Stopwatch.createStarted();
    List<SearchReviewProjectDetail> projectDetails =
        getProjectDetailsByCriteria(request, accessContext);
    recordSearchPhase(
        SEARCH_MODE_PROJECTS, "getProjectDetails", phaseStopwatch, projectDetails.size());

    phaseStopwatch = Stopwatch.createStarted();
    List<SearchReviewProjectsView.ReviewProject> reviewProjects =
        toReviewProjectViews(projectDetails);
    recordSearchPhase(SEARCH_MODE_PROJECTS, "buildResponse", phaseStopwatch, reviewProjects.size());
    recordSearchPhase(SEARCH_MODE_PROJECTS, "total", totalStopwatch, reviewProjects.size());

    return new SearchReviewProjectsView(reviewProjects);
  }

  @Transactional(readOnly = true)
  public SearchReviewProjectRequestsView searchReviewProjectRequests(
      SearchReviewProjectsCriteria request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    Stopwatch totalStopwatch = Stopwatch.createStarted();
    Stopwatch phaseStopwatch = Stopwatch.createStarted();
    ProjectAccessContext accessContext = createProjectAccessContext();
    recordSearchPhase(SEARCH_MODE_REQUESTS, "accessContext", phaseStopwatch, 1);

    List<ReviewProjectStatus> requestedStatuses = getRequestModeStatuses(request.statuses());
    List<ReviewProjectStatus> projectStatuses = getRequestModeStatuses(request.projectStatuses());
    boolean includeOpen = requestedStatuses.contains(ReviewProjectStatus.OPEN);
    boolean includeClosed = requestedStatuses.contains(ReviewProjectStatus.CLOSED);

    phaseStopwatch = Stopwatch.createStarted();
    List<Long> requestIds = findRequestIdsByStatuses(request, requestedStatuses, accessContext);
    recordSearchPhase(SEARCH_MODE_REQUESTS, "findRequestIds", phaseStopwatch, requestIds.size());
    if (requestIds.isEmpty()) {
      recordSearchPhase(SEARCH_MODE_REQUESTS, "total", totalStopwatch, 0);
      return new SearchReviewProjectRequestsView(List.of());
    }

    phaseStopwatch = Stopwatch.createStarted();
    List<SearchReviewProjectDetail> projectDetails =
        getProjectDetailsByRequestIds(requestIds, projectStatuses, request, accessContext);
    recordSearchPhase(
        SEARCH_MODE_REQUESTS, "getProjectDetails", phaseStopwatch, projectDetails.size());

    phaseStopwatch = Stopwatch.createStarted();
    List<SearchReviewProjectsView.ReviewProject> reviewProjects =
        toReviewProjectViews(projectDetails);
    recordSearchPhase(
        SEARCH_MODE_REQUESTS, "buildProjectViews", phaseStopwatch, reviewProjects.size());

    phaseStopwatch = Stopwatch.createStarted();
    Map<Long, List<SearchReviewProjectsView.ReviewProject>> projectsByRequestId =
        reviewProjects.stream()
            .filter(
                reviewProject ->
                    reviewProject.reviewProjectRequest() != null
                        && reviewProject.reviewProjectRequest().id() != null)
            .collect(
                Collectors.groupingBy(reviewProject -> reviewProject.reviewProjectRequest().id()));

    List<SearchReviewProjectRequestsView.ReviewProjectRequestGroup> groups = new ArrayList<>();
    for (Long requestId : requestIds) {
      List<SearchReviewProjectsView.ReviewProject> groupedProjects =
          projectsByRequestId.getOrDefault(requestId, List.of());
      if (groupedProjects.isEmpty()) {
        continue;
      }

      List<SearchReviewProjectsView.ReviewProject> sortedProjects =
          groupedProjects.stream()
              .sorted(
                  Comparator.comparing(
                          (SearchReviewProjectsView.ReviewProject reviewProject) ->
                              reviewProject.locale() != null
                                      && reviewProject.locale().bcp47Tag() != null
                                  ? reviewProject.locale().bcp47Tag()
                                  : "")
                      .thenComparing(SearchReviewProjectsView.ReviewProject::id))
              .toList();

      int openProjectCount =
          (int)
              sortedProjects.stream()
                  .filter(reviewProject -> reviewProject.status() == ReviewProjectStatus.OPEN)
                  .count();
      int closedProjectCount =
          (int)
              sortedProjects.stream()
                  .filter(reviewProject -> reviewProject.status() == ReviewProjectStatus.CLOSED)
                  .count();
      boolean matchesStatusFilter =
          (includeOpen && openProjectCount > 0) || (includeClosed && closedProjectCount > 0);
      if (!matchesStatusFilter) {
        continue;
      }
      int textUnitCount =
          sortedProjects.stream()
              .mapToInt(project -> Optional.ofNullable(project.textUnitCount()).orElse(0))
              .sum();
      int wordCount =
          sortedProjects.stream()
              .mapToInt(project -> Optional.ofNullable(project.wordCount()).orElse(0))
              .sum();
      long decidedCount =
          sortedProjects.stream()
              .mapToLong(project -> Optional.ofNullable(project.decidedCount()).orElse(0L))
              .sum();
      ZonedDateTime dueDate =
          sortedProjects.stream()
              .map(SearchReviewProjectsView.ReviewProject::dueDate)
              .filter(Objects::nonNull)
              .min(ZonedDateTime::compareTo)
              .orElse(null);

      SearchReviewProjectsView.ReviewProject firstProject = sortedProjects.get(0);
      SearchReviewProjectsView.ReviewProjectRequest requestInfo =
          firstProject.reviewProjectRequest();

      groups.add(
          new SearchReviewProjectRequestsView.ReviewProjectRequestGroup(
              requestId,
              requestInfo != null ? requestInfo.name() : null,
              requestInfo != null ? requestInfo.createdByUsername() : null,
              openProjectCount,
              closedProjectCount,
              textUnitCount,
              wordCount,
              decidedCount,
              dueDate,
              sortedProjects));
    }

    recordSearchPhase(SEARCH_MODE_REQUESTS, "groupResponse", phaseStopwatch, groups.size());
    recordSearchPhase(SEARCH_MODE_REQUESTS, "total", totalStopwatch, groups.size());
    return new SearchReviewProjectRequestsView(groups);
  }

  private void recordSearchPhase(String mode, String phase, Stopwatch stopwatch, int resultCount) {
    long elapsedMillis = stopwatch.elapsed().toMillis();
    Tags tags = Tags.of("mode", mode, "phase", phase);
    meterRegistry.timer(metricName("searchPhaseDuration"), tags).record(stopwatch.elapsed());
    meterRegistry.summary(metricName("searchPhaseResultCount"), tags).record(resultCount);

    long slowLogThresholdMillis =
        "total".equals(phase)
            ? SEARCH_TOTAL_SLOW_LOG_THRESHOLD_MS
            : SEARCH_PHASE_SLOW_LOG_THRESHOLD_MS;
    if (elapsedMillis >= slowLogThresholdMillis) {
      logger.info(
          "Review project search phase completed: mode={}, phase={}, elapsedMs={}, resultCount={}",
          mode,
          phase,
          elapsedMillis,
          resultCount);
    } else {
      logger.debug(
          "Review project search phase completed: mode={}, phase={}, elapsedMs={}, resultCount={}",
          mode,
          phase,
          elapsedMillis,
          resultCount);
    }
  }

  private String metricName(String suffix) {
    return METRIC_PREFIX + "." + suffix;
  }

  private List<ReviewProjectStatus> getRequestModeStatuses(List<ReviewProjectStatus> statuses) {
    if (statuses == null || statuses.isEmpty()) {
      return List.of(ReviewProjectStatus.OPEN, ReviewProjectStatus.CLOSED);
    }
    return statuses;
  }

  private List<SearchReviewProjectDetail> getProjectDetailsByCriteria(
      SearchReviewProjectsCriteria request, ProjectAccessContext accessContext) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<SearchReviewProjectDetail> cq = cb.createQuery(SearchReviewProjectDetail.class);
    Root<ReviewProject> root = cq.from(ReviewProject.class);
    Join<ReviewProject, Locale> localeJoin = root.join(ReviewProject_.locale, JoinType.LEFT);
    Join<ReviewProject, ReviewProjectRequest> requestJoin =
        root.join(ReviewProject_.reviewProjectRequest, JoinType.LEFT);
    Join<ReviewProject, User> createdByUserJoin =
        root.join(ReviewProject_.createdByUser, JoinType.LEFT);
    Join<ReviewProjectRequest, User> requestCreatedByUserJoin =
        requestJoin.join(ReviewProjectRequest_.createdByUser, JoinType.LEFT);
    Join<ReviewProject, Team> teamJoin = root.join(ReviewProject_.team, JoinType.LEFT);
    Join<ReviewProject, User> assignedPmJoin =
        root.join(ReviewProject_.assignedPmUser, JoinType.LEFT);
    Join<ReviewProject, User> assignedTranslatorJoin =
        root.join(ReviewProject_.assignedTranslatorUser, JoinType.LEFT);

    List<Predicate> predicates =
        buildProjectSearchPredicates(
            cb,
            root,
            localeJoin,
            requestJoin,
            createdByUserJoin,
            teamJoin,
            assignedPmJoin,
            assignedTranslatorJoin,
            request,
            request.statuses(),
            accessContext);

    Predicate[] predicateArray = predicates.toArray(Predicate[]::new);

    cq.where(predicateArray)
        .select(
            cb.construct(
                SearchReviewProjectDetail.class,
                root.get(ReviewProject_.id),
                root.get(ReviewProject_.createdDate),
                root.get(ReviewProject_.lastModifiedDate),
                root.get(ReviewProject_.dueDate),
                root.get(ReviewProject_.closeReason),
                root.get(ReviewProject_.textUnitCount),
                root.get(ReviewProject_.wordCount),
                root.get(ReviewProject_.decidedCount),
                root.get(ReviewProject_.type),
                root.get(ReviewProject_.status),
                createdByUserJoin.get(User_.username),
                localeJoin.get(Locale_.id),
                localeJoin.get(Locale_.bcp47Tag),
                requestJoin.get(ReviewProjectRequest_.id),
                requestJoin.get(ReviewProjectRequest_.name),
                requestCreatedByUserJoin.get(User_.username),
                teamJoin.get(Team_.id),
                teamJoin.get(Team_.name),
                assignedPmJoin.get(User_.id),
                assignedPmJoin.get(User_.username),
                assignedTranslatorJoin.get(User_.id),
                assignedTranslatorJoin.get(User_.username)))
        .distinct(true)
        .orderBy(cb.desc(root.get(ReviewProject_.id)));

    TypedQuery<SearchReviewProjectDetail> query = entityManager.createQuery(cq);
    query.setMaxResults(request.limit());

    return query.getResultList();
  }

  private List<Long> findRequestIdsByStatuses(
      SearchReviewProjectsCriteria request,
      List<ReviewProjectStatus> statuses,
      ProjectAccessContext accessContext) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<Long> cq = cb.createQuery(Long.class);
    Root<ReviewProject> root = cq.from(ReviewProject.class);
    Join<ReviewProject, Locale> localeJoin = root.join(ReviewProject_.locale, JoinType.LEFT);
    Join<ReviewProject, ReviewProjectRequest> requestJoin =
        root.join(ReviewProject_.reviewProjectRequest, JoinType.LEFT);
    Join<ReviewProject, User> createdByUserJoin =
        root.join(ReviewProject_.createdByUser, JoinType.LEFT);
    Join<ReviewProject, Team> teamJoin = root.join(ReviewProject_.team, JoinType.LEFT);
    Join<ReviewProject, User> assignedPmJoin =
        root.join(ReviewProject_.assignedPmUser, JoinType.LEFT);
    Join<ReviewProject, User> assignedTranslatorJoin =
        root.join(ReviewProject_.assignedTranslatorUser, JoinType.LEFT);

    List<Predicate> predicates =
        buildProjectSearchPredicates(
            cb,
            root,
            localeJoin,
            requestJoin,
            createdByUserJoin,
            teamJoin,
            assignedPmJoin,
            assignedTranslatorJoin,
            request,
            statuses,
            accessContext);
    predicates.add(cb.isNotNull(requestJoin.get(ReviewProjectRequest_.id)));

    cq.where(predicates.toArray(Predicate[]::new))
        .select(requestJoin.get(ReviewProjectRequest_.id))
        .groupBy(requestJoin.get(ReviewProjectRequest_.id))
        .orderBy(cb.desc(requestJoin.get(ReviewProjectRequest_.id)));

    TypedQuery<Long> query = entityManager.createQuery(cq);
    query.setMaxResults(request.limit());
    return query.getResultList();
  }

  private List<SearchReviewProjectDetail> getProjectDetailsByRequestIds(
      List<Long> requestIds,
      List<ReviewProjectStatus> projectStatuses,
      SearchReviewProjectsCriteria request,
      ProjectAccessContext accessContext) {
    if (requestIds == null || requestIds.isEmpty()) {
      return List.of();
    }

    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<SearchReviewProjectDetail> cq = cb.createQuery(SearchReviewProjectDetail.class);
    Root<ReviewProject> root = cq.from(ReviewProject.class);
    Join<ReviewProject, Locale> localeJoin = root.join(ReviewProject_.locale, JoinType.LEFT);
    Join<ReviewProject, ReviewProjectRequest> requestJoin =
        root.join(ReviewProject_.reviewProjectRequest, JoinType.LEFT);
    Join<ReviewProject, User> createdByUserJoin =
        root.join(ReviewProject_.createdByUser, JoinType.LEFT);
    Join<ReviewProjectRequest, User> requestCreatedByUserJoin =
        requestJoin.join(ReviewProjectRequest_.createdByUser, JoinType.LEFT);
    Join<ReviewProject, Team> teamJoin = root.join(ReviewProject_.team, JoinType.LEFT);
    Join<ReviewProject, User> assignedPmJoin =
        root.join(ReviewProject_.assignedPmUser, JoinType.LEFT);
    Join<ReviewProject, User> assignedTranslatorJoin =
        root.join(ReviewProject_.assignedTranslatorUser, JoinType.LEFT);

    List<Predicate> predicates = new ArrayList<>();
    predicates.add(requestJoin.get(ReviewProjectRequest_.id).in(requestIds));
    if (projectStatuses != null && !projectStatuses.isEmpty()) {
      predicates.add(root.get(ReviewProject_.status).in(projectStatuses));
    }
    predicates.add(
        buildScopePredicate(
            cb,
            localeJoin,
            teamJoin,
            assignedPmJoin,
            assignedTranslatorJoin,
            request,
            accessContext));

    cq.where(predicates.toArray(Predicate[]::new))
        .select(
            cb.construct(
                SearchReviewProjectDetail.class,
                root.get(ReviewProject_.id),
                root.get(ReviewProject_.createdDate),
                root.get(ReviewProject_.lastModifiedDate),
                root.get(ReviewProject_.dueDate),
                root.get(ReviewProject_.closeReason),
                root.get(ReviewProject_.textUnitCount),
                root.get(ReviewProject_.wordCount),
                root.get(ReviewProject_.decidedCount),
                root.get(ReviewProject_.type),
                root.get(ReviewProject_.status),
                createdByUserJoin.get(User_.username),
                localeJoin.get(Locale_.id),
                localeJoin.get(Locale_.bcp47Tag),
                requestJoin.get(ReviewProjectRequest_.id),
                requestJoin.get(ReviewProjectRequest_.name),
                requestCreatedByUserJoin.get(User_.username),
                teamJoin.get(Team_.id),
                teamJoin.get(Team_.name),
                assignedPmJoin.get(User_.id),
                assignedPmJoin.get(User_.username),
                assignedTranslatorJoin.get(User_.id),
                assignedTranslatorJoin.get(User_.username)))
        .distinct(true)
        .orderBy(cb.desc(root.get(ReviewProject_.id)));

    return entityManager.createQuery(cq).getResultList();
  }

  private List<SearchReviewProjectsView.ReviewProject> toReviewProjectViews(
      List<SearchReviewProjectDetail> projectDetails) {
    if (projectDetails == null || projectDetails.isEmpty()) {
      return List.of();
    }

    return projectDetails.stream()
        .map(
            detail -> {
              return new SearchReviewProjectsView.ReviewProject(
                  detail.id(),
                  detail.createdDate(),
                  detail.lastModifiedDate(),
                  detail.dueDate(),
                  detail.closeReason(),
                  detail.textUnitCount(),
                  detail.wordCount(),
                  Optional.ofNullable(detail.decidedCount()).orElse(0L),
                  detail.type(),
                  detail.status(),
                  detail.createdByUsername(),
                  new SearchReviewProjectsView.Locale(detail.localeId(), detail.localeTag()),
                  new SearchReviewProjectsView.ReviewProjectRequest(
                      detail.requestId(), detail.requestName(), detail.requestCreatedByUsername()),
                  new SearchReviewProjectsView.Assignment(
                      detail.teamId(),
                      detail.teamName(),
                      detail.assignedPmUserId(),
                      detail.assignedPmUsername(),
                      detail.assignedTranslatorUserId(),
                      detail.assignedTranslatorUsername()));
            })
        .toList();
  }

  private List<Predicate> buildProjectSearchPredicates(
      CriteriaBuilder cb,
      Root<ReviewProject> root,
      Join<ReviewProject, Locale> localeJoin,
      Join<ReviewProject, ReviewProjectRequest> requestJoin,
      Join<ReviewProject, User> createdByUserJoin,
      Join<ReviewProject, Team> teamJoin,
      Join<ReviewProject, User> assignedPmJoin,
      Join<ReviewProject, User> assignedTranslatorJoin,
      SearchReviewProjectsCriteria request,
      List<ReviewProjectStatus> statuses,
      ProjectAccessContext accessContext) {
    List<Predicate> predicates = new ArrayList<>();

    if (statuses != null && !statuses.isEmpty()) {
      predicates.add(root.get(ReviewProject_.status).in(statuses));
    }

    if (request.types() != null) {
      predicates.add(root.get(ReviewProject_.type).in(request.types()));
    }

    if (request.localeTags() != null) {
      predicates.add(localeJoin.get(Locale_.bcp47Tag).in(request.localeTags()));
    }

    if (request.createdAfter() != null) {
      predicates.add(
          cb.greaterThanOrEqualTo(root.get(ReviewProject_.createdDate), request.createdAfter()));
    }
    if (request.createdBefore() != null) {
      predicates.add(
          cb.lessThanOrEqualTo(root.get(ReviewProject_.createdDate), request.createdBefore()));
    }
    if (request.dueAfter() != null) {
      predicates.add(cb.greaterThanOrEqualTo(root.get(ReviewProject_.dueDate), request.dueAfter()));
    }
    if (request.dueBefore() != null) {
      predicates.add(cb.lessThanOrEqualTo(root.get(ReviewProject_.dueDate), request.dueBefore()));
    }

    if (request.searchQuery() != null) {
      predicates.add(buildSearchPredicate(cb, root, requestJoin, createdByUserJoin, request));
    }

    predicates.add(
        buildScopePredicate(
            cb,
            localeJoin,
            teamJoin,
            assignedPmJoin,
            assignedTranslatorJoin,
            request,
            accessContext));

    return predicates;
  }

  private Predicate buildScopePredicate(
      CriteriaBuilder cb,
      Join<ReviewProject, Locale> localeJoin,
      Join<ReviewProject, Team> teamJoin,
      Join<ReviewProject, User> assignedPmJoin,
      Join<ReviewProject, User> assignedTranslatorJoin,
      SearchReviewProjectsCriteria request,
      ProjectAccessContext accessContext) {
    if (accessContext.admin()) {
      return cb.conjunction();
    }

    SearchReviewProjectsCriteria.AssignedScope assignedScope =
        request.assignedScope() != null
            ? request.assignedScope()
            : SearchReviewProjectsCriteria.AssignedScope.TO_ME;

    List<Predicate> visibilityPredicates = new ArrayList<>();

    if (assignedScope == SearchReviewProjectsCriteria.AssignedScope.TO_ME) {
      if (accessContext.pm()) {
        visibilityPredicates.add(cb.equal(assignedPmJoin.get(User_.id), accessContext.userId()));
      }
      if (accessContext.translator()) {
        visibilityPredicates.add(
            cb.equal(assignedTranslatorJoin.get(User_.id), accessContext.userId()));
      }
    } else if (assignedScope == SearchReviewProjectsCriteria.AssignedScope.TO_TEAM) {
      if (accessContext.pm() && !accessContext.pmTeamIds().isEmpty()) {
        visibilityPredicates.add(teamJoin.get(Team_.id).in(accessContext.pmTeamIds()));
      }
      if (accessContext.translator() && !accessContext.translatorTeamIds().isEmpty()) {
        Predicate teamPredicate = teamJoin.get(Team_.id).in(accessContext.translatorTeamIds());
        Predicate localePredicate =
            accessContext.canTranslateAllLocales()
                ? cb.conjunction()
                : accessContext.editableLocaleIds().isEmpty()
                    ? cb.disjunction()
                    : localeJoin.get(Locale_.id).in(accessContext.editableLocaleIds());
        visibilityPredicates.add(cb.and(teamPredicate, localePredicate));
      }
    }

    if (visibilityPredicates.isEmpty()) {
      return cb.disjunction();
    }
    if (visibilityPredicates.size() == 1) {
      return visibilityPredicates.get(0);
    }
    return cb.or(visibilityPredicates.toArray(Predicate[]::new));
  }

  private ProjectAccessContext createProjectAccessContext() {
    Long userId = teamService.getCurrentUserIdOrThrow();
    boolean admin = userService.isCurrentUserAdmin();
    boolean pm = userService.isCurrentUserPm();
    boolean translator = userService.isCurrentUserTranslator();

    if (!admin && !pm && !translator) {
      throw new AccessDeniedException("Review project access is not allowed for current role");
    }

    Set<Long> pmTeamIds =
        pm
            ? teamUserRepository.findByUserIdAndRole(userId, TeamUserRole.PM).stream()
                .map(teamUser -> teamUser.getTeam().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
            : Set.of();

    Set<Long> translatorTeamIds =
        translator
            ? teamUserRepository.findByUserIdAndRole(userId, TeamUserRole.TRANSLATOR).stream()
                .map(teamUser -> teamUser.getTeam().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))
            : Set.of();

    User currentUser =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

    boolean canTranslateAllLocales = currentUser.getCanTranslateAllLocales();
    Set<Long> editableLocaleIds =
        canTranslateAllLocales
            ? Set.of()
            : currentUser.getUserLocales().stream()
                .map(userLocale -> userLocale.getLocale().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

    return new ProjectAccessContext(
        userId,
        admin,
        pm,
        translator,
        pmTeamIds,
        translatorTeamIds,
        editableLocaleIds,
        canTranslateAllLocales);
  }

  private void assertCurrentUserCanReadProject(ReviewProject reviewProject) {
    if (reviewProject == null) {
      throw new IllegalArgumentException("reviewProject must not be null");
    }
    ProjectAccessContext accessContext = createProjectAccessContext();
    if (!canReadProject(reviewProject, accessContext)) {
      throw new AccessDeniedException("Review project access denied");
    }
  }

  private boolean canReadProject(ReviewProject reviewProject, ProjectAccessContext accessContext) {
    if (accessContext.admin()) {
      return true;
    }

    Long teamId = reviewProject.getTeam() != null ? reviewProject.getTeam().getId() : null;
    Long localeId = reviewProject.getLocale() != null ? reviewProject.getLocale().getId() : null;
    Long assignedPmUserId =
        reviewProject.getAssignedPmUser() != null
            ? reviewProject.getAssignedPmUser().getId()
            : null;
    Long assignedTranslatorUserId =
        reviewProject.getAssignedTranslatorUser() != null
            ? reviewProject.getAssignedTranslatorUser().getId()
            : null;

    if (accessContext.pm()) {
      if (Objects.equals(assignedPmUserId, accessContext.userId())) {
        return true;
      }
      if (teamId != null && accessContext.pmTeamIds().contains(teamId)) {
        return true;
      }
    }

    if (accessContext.translator()) {
      if (Objects.equals(assignedTranslatorUserId, accessContext.userId())) {
        return true;
      }
      boolean localeAllowed =
          accessContext.canTranslateAllLocales()
              || (localeId != null && accessContext.editableLocaleIds().contains(localeId));
      if (localeAllowed && teamId != null && accessContext.translatorTeamIds().contains(teamId)) {
        return true;
      }
    }

    return false;
  }

  private Predicate buildStringPredicate(
      CriteriaBuilder cb,
      Expression<String> expression,
      String searchQuery,
      SearchReviewProjectsCriteria.SearchMatchType matchType) {

    boolean ignoreCase = matchType == SearchReviewProjectsCriteria.SearchMatchType.ILIKE;
    String queryValue = ignoreCase ? searchQuery.toLowerCase() : searchQuery;
    Expression<String> valueExpression = ignoreCase ? cb.lower(expression) : expression;

    return switch (matchType) {
      case EXACT -> cb.equal(valueExpression, searchQuery);
      case ILIKE, CONTAINS -> {
        String pattern = "%" + escapeForLike(queryValue) + "%";
        yield cb.like(valueExpression, pattern, '\\');
      }
    };
  }

  private Predicate buildSearchPredicate(
      CriteriaBuilder cb,
      Root<ReviewProject> root,
      Join<ReviewProject, ReviewProjectRequest> requestJoin,
      Join<ReviewProject, User> createdByUserJoin,
      SearchReviewProjectsCriteria request) {
    SearchReviewProjectsCriteria.SearchField searchField = request.searchField();
    SearchReviewProjectsCriteria.SearchMatchType matchType = request.searchMatchType();

    Expression<String> expression;
    expression =
        switch (searchField) {
          case ID -> root.get(ReviewProject_.id).as(String.class);
          case NAME -> requestJoin.get(ReviewProjectRequest_.name);
          case REQUEST_ID -> requestJoin.get(ReviewProjectRequest_.id).as(String.class);
          case CREATED_BY -> createdByUserJoin.get(User_.username);
        };

    return buildStringPredicate(cb, expression, request.searchQuery(), matchType);
  }

  private String escapeForLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  @Transactional(readOnly = true)
  public GetProjectDetailView getProjectDetail(Long projectId) {
    ReviewProject reviewProject =
        reviewProjectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));
    assertCurrentUserCanReadProject(reviewProject);

    ReviewProjectDetail project =
        reviewProjectRepository
            .findDetailById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));

    List<ReviewProjectTextUnitDetail> textUnitDetails =
        reviewProjectTextUnitRepository.findDetailByReviewProjectId(projectId);

    List<GetProjectDetailView.ReviewProjectTextUnit> reviewProjectTextUnits =
        textUnitDetails.stream().map(this::toReviewProjectTextUnit).toList();

    List<String> screenshotImageIds =
        project.reviewProjectRequestId() == null
            ? List.of()
            : reviewProjectScreenshotRepository.findImageNamesByReviewProjectRequestId(
                project.reviewProjectRequestId());

    return new GetProjectDetailView(
        project.id(),
        project.type(),
        project.status(),
        project.createdDate(),
        project.dueDate(),
        project.closeReason(),
        project.textUnitCount(),
        project.wordCount(),
        new GetProjectDetailView.ReviewProjectRequest(
            project.reviewProjectRequestId(),
            project.reviewProjectRequestName(),
            project.reviewProjectRequestNotes(),
            project.reviewProjectRequestCreatedDate(),
            project.reviewProjectRequestCreatedByUsername(),
            screenshotImageIds),
        new GetProjectDetailView.Locale(project.localeId(), project.localeTag()),
        new GetProjectDetailView.Assignment(
            project.teamId(),
            project.teamName(),
            project.assignedPmUserId(),
            project.assignedPmUsername(),
            project.assignedTranslatorUserId(),
            project.assignedTranslatorUsername()),
        reviewProjectTextUnits);
  }

  @Transactional
  public GetProjectDetailView updateProjectStatus(
      Long projectId, ReviewProjectStatus status, String closeReason) {
    if (status == null) {
      throw new IllegalArgumentException("status must be provided");
    }

    ReviewProject reviewProject =
        reviewProjectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));
    assertCurrentUserCanReadProject(reviewProject);

    if (!userService.isCurrentUserAdmin()) {
      userService.checkUserCanEditLocale(reviewProject.getLocale().getId());
    }

    reviewProject.setStatus(status);
    if (status == ReviewProjectStatus.OPEN) {
      reviewProject.setCloseReason(null);
    } else if (closeReason != null) {
      String trimmed = closeReason.trim();
      reviewProject.setCloseReason(trimmed.isEmpty() ? null : trimmed);
    }

    reviewProjectRepository.save(reviewProject);
    return getProjectDetail(projectId);
  }

  @Transactional
  public GetProjectDetailView updateProjectRequest(
      Long projectId,
      String name,
      String notes,
      ReviewProjectType type,
      ZonedDateTime dueDate,
      List<String> screenshotImageIds) {
    String trimmedName = name == null ? null : name.trim();
    if (trimmedName == null || trimmedName.isEmpty()) {
      throw new IllegalArgumentException("name must be provided");
    }

    ReviewProject reviewProject =
        reviewProjectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));
    assertCurrentUserCanReadProject(reviewProject);

    if (!userService.isCurrentUserAdmin()) {
      userService.checkUserCanEditLocale(reviewProject.getLocale().getId());
    }

    ReviewProjectRequest request = reviewProject.getReviewProjectRequest();
    if (request == null) {
      throw new IllegalArgumentException(
          "reviewProject with id: " + projectId + " has no request to update");
    }

    String trimmedNotes = notes == null ? null : notes.trim();
    request.setName(trimmedName);
    request.setNotes(trimmedNotes == null || trimmedNotes.isEmpty() ? null : trimmedNotes);
    reviewProjectRequestRepository.save(request);

    if (type != null || dueDate != null) {
      reviewProjectRepository
          .findByRequestIdWithAssignment(request.getId())
          .forEach(
              project -> {
                if (type != null) {
                  project.setType(type);
                }
                if (dueDate != null) {
                  project.setDueDate(dueDate);
                }
              });
    }
    reviewProjectRepository.flush();

    if (screenshotImageIds != null) {
      reviewProjectScreenshotRepository.deleteByReviewProjectRequestId(request.getId());
      LinkedHashSet<String> dedupedImageIds =
          screenshotImageIds.stream()
              .filter(Objects::nonNull)
              .map(String::trim)
              .filter(id -> !id.isEmpty())
              .map(id -> id.length() > 255 ? id.substring(0, 255) : id)
              .collect(Collectors.toCollection(LinkedHashSet::new));

      for (String imageId : dedupedImageIds) {
        ReviewProjectRequestScreenshot screenshot = new ReviewProjectRequestScreenshot();
        screenshot.setReviewProjectRequest(request);
        screenshot.setImageName(imageId);
        reviewProjectScreenshotRepository.save(screenshot);
      }
    }

    return getProjectDetail(projectId);
  }

  @Transactional
  public GetProjectDetailView updateProjectAssignment(
      Long projectId,
      Long teamId,
      Long assignedPmUserId,
      Long assignedTranslatorUserId,
      String note) {
    ReviewProject reviewProject =
        reviewProjectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));
    assertCurrentUserCanReadProject(reviewProject);

    boolean isAdmin = userService.isCurrentUserAdmin();
    boolean isPm = userService.isCurrentUserPm();
    boolean isTranslator = userService.isCurrentUserTranslator();

    if (!isAdmin && !isPm && !isTranslator) {
      throw new AccessDeniedException("Assignment update is not allowed for current role");
    }

    if (isTranslator) {
      userService.checkUserCanEditLocale(reviewProject.getLocale().getId());
    }

    Team nextTeam = resolveTeam(teamId);
    User nextAssignedPm = resolveUser(assignedPmUserId, "assignedPmUser");
    User nextAssignedTranslator = resolveUser(assignedTranslatorUserId, "assignedTranslatorUser");

    Team previousTeam = reviewProject.getTeam();
    User previousAssignedPm = reviewProject.getAssignedPmUser();
    User previousAssignedTranslator = reviewProject.getAssignedTranslatorUser();

    validateAssignmentUpdatePermissions(
        reviewProject,
        isAdmin,
        isPm,
        isTranslator,
        previousTeam,
        nextTeam,
        previousAssignedPm,
        nextAssignedPm,
        previousAssignedTranslator,
        nextAssignedTranslator);

    boolean changed =
        !Objects.equals(getEntityId(previousTeam), getEntityId(nextTeam))
            || !Objects.equals(getEntityId(previousAssignedPm), getEntityId(nextAssignedPm))
            || !Objects.equals(
                getEntityId(previousAssignedTranslator), getEntityId(nextAssignedTranslator));

    if (!changed) {
      return getProjectDetail(projectId);
    }

    reviewProject.setTeam(nextTeam);
    reviewProject.setAssignedPmUser(nextAssignedPm);
    reviewProject.setAssignedTranslatorUser(nextAssignedTranslator);
    reviewProjectRepository.save(reviewProject);

    boolean teamChanged = !Objects.equals(getEntityId(previousTeam), getEntityId(nextTeam));
    boolean translatorChanged =
        !Objects.equals(
            getEntityId(previousAssignedTranslator), getEntityId(nextAssignedTranslator));

    boolean hadAssignment =
        previousTeam != null || previousAssignedPm != null || previousAssignedTranslator != null;
    boolean hasAssignment =
        reviewProject.getTeam() != null
            || reviewProject.getAssignedPmUser() != null
            || reviewProject.getAssignedTranslatorUser() != null;

    ReviewProjectAssignmentEventType eventType;
    if (!hadAssignment && hasAssignment) {
      eventType = ReviewProjectAssignmentEventType.ASSIGNED;
    } else if (hadAssignment && !hasAssignment) {
      eventType = ReviewProjectAssignmentEventType.UNASSIGNED;
    } else {
      eventType = ReviewProjectAssignmentEventType.REASSIGNED;
    }

    recordAssignmentHistory(reviewProject, eventType, note);
    if (teamChanged || translatorChanged) {
      teamSlackNotificationService.sendReviewProjectAssignmentNotification(
          reviewProject, eventType, note);
    }
    return getProjectDetail(projectId);
  }

  @Transactional
  public GetProjectDetailView updateProjectDueDate(Long projectId, ZonedDateTime dueDate) {
    if (dueDate == null) {
      throw new IllegalArgumentException("dueDate must be provided");
    }

    ReviewProject reviewProject =
        reviewProjectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));
    assertCurrentUserCanReadProject(reviewProject);

    if (!userService.isCurrentUserAdmin()) {
      userService.checkUserCanEditLocale(reviewProject.getLocale().getId());
    }

    reviewProject.setDueDate(dueDate);
    reviewProjectRepository.save(reviewProject);
    return getProjectDetail(projectId);
  }

  @Transactional
  public int updateRequestAssignedPm(Long requestId, Long assignedPmUserId, String note) {
    if (requestId == null) {
      throw new IllegalArgumentException("requestId is required");
    }

    boolean isAdmin = userService.isCurrentUserAdmin();
    boolean isPm = userService.isCurrentUserPm();
    if (!isAdmin && !isPm) {
      throw new AccessDeniedException("Only admins or PMs can reassign PM for a request");
    }

    List<ReviewProject> projects = reviewProjectRepository.findByRequestIdWithAssignment(requestId);
    if (projects.isEmpty()) {
      throw new IllegalArgumentException("Review request not found: " + requestId);
    }

    ReviewProjectRequest request = projects.get(0).getReviewProjectRequest();
    User nextAssignedPm = resolveUser(assignedPmUserId, "assignedPmUser");

    Set<Long> teamIds =
        projects.stream()
            .map(ReviewProject::getTeam)
            .map(this::getEntityId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (teamIds.size() != 1) {
      throw new IllegalArgumentException(
          "Request PM reassignment requires exactly one team across all projects");
    }
    Long teamId = teamIds.iterator().next();
    if (isPm) {
      teamService.assertCurrentUserCanAccessTeam(teamId);
    }
    if (nextAssignedPm != null
        && !teamService.isUserInTeamRole(teamId, nextAssignedPm.getId(), TeamUserRole.PM)) {
      throw new IllegalArgumentException(
          "Assigned PM is not a PM member of team " + teamId + ": " + nextAssignedPm.getId());
    }

    int changedCount = 0;
    for (ReviewProject project : projects) {
      User previousAssignedPm = project.getAssignedPmUser();
      if (Objects.equals(getEntityId(previousAssignedPm), getEntityId(nextAssignedPm))) {
        continue;
      }
      project.setAssignedPmUser(nextAssignedPm);
      reviewProjectRepository.save(project);

      boolean hadAssignment =
          project.getTeam() != null
              || previousAssignedPm != null
              || project.getAssignedTranslatorUser() != null;
      boolean hasAssignment =
          project.getTeam() != null
              || project.getAssignedPmUser() != null
              || project.getAssignedTranslatorUser() != null;
      ReviewProjectAssignmentEventType eventType;
      if (!hadAssignment && hasAssignment) {
        eventType = ReviewProjectAssignmentEventType.ASSIGNED;
      } else if (hadAssignment && !hasAssignment) {
        eventType = ReviewProjectAssignmentEventType.UNASSIGNED;
      } else {
        eventType = ReviewProjectAssignmentEventType.REASSIGNED;
      }
      recordAssignmentHistory(project, eventType, note);
      changedCount++;
    }

    if (changedCount > 0) {
      teamSlackNotificationService.sendReviewProjectRequestAssignmentNotification(
          request, projects);
    }

    return changedCount;
  }

  @Transactional(readOnly = true)
  public List<ReviewProjectAssignmentHistory> getProjectAssignmentHistory(Long projectId) {
    ReviewProject reviewProject =
        reviewProjectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));
    assertCurrentUserCanReadProject(reviewProject);
    return reviewProjectAssignmentHistoryRepository.findByProjectId(projectId);
  }

  @Transactional
  public int adminBatchUpdateStatus(
      List<Long> projectIds, ReviewProjectStatus status, String closeReason) {
    requireAdmin();
    if (CollectionUtils.isEmpty(projectIds)) {
      return 0;
    }
    if (status == null) {
      throw new IllegalArgumentException("status must be provided");
    }

    List<Long> distinctIds = projectIds.stream().filter(Objects::nonNull).distinct().toList();
    if (distinctIds.isEmpty()) {
      return 0;
    }

    List<ReviewProject> projects = reviewProjectRepository.findAllById(distinctIds);
    for (ReviewProject project : projects) {
      project.setStatus(status);
      if (status == ReviewProjectStatus.OPEN) {
        project.setCloseReason(null);
      } else if (closeReason != null) {
        String trimmed = closeReason.trim();
        project.setCloseReason(trimmed.isEmpty() ? null : trimmed);
      }
    }

    reviewProjectRepository.saveAll(projects);
    return projects.size();
  }

  @Transactional
  public int adminRecomputeRequestDecidedCounts(Long requestId) {
    requireAdmin();
    if (requestId == null) {
      throw new IllegalArgumentException("requestId is required");
    }
    return reviewProjectRepository.recomputeDecidedCountsByRequestId(requestId);
  }

  @Transactional
  public int adminBatchDeleteProjects(List<Long> projectIds) {
    requireAdmin();
    if (CollectionUtils.isEmpty(projectIds)) {
      return 0;
    }

    List<Long> distinctIds = projectIds.stream().filter(Objects::nonNull).distinct().toList();
    if (distinctIds.isEmpty()) {
      return 0;
    }

    List<Long> requestIds = reviewProjectRepository.findRequestIdsByProjectIds(distinctIds);

    reviewProjectAssignmentHistoryRepository.deleteByReviewProjectIds(distinctIds);
    reviewProjectTextUnitDecisionRepository.deleteByReviewProjectIds(distinctIds);
    reviewProjectTextUnitRepository.deleteByReviewProjectIds(distinctIds);
    int deletedProjects = reviewProjectRepository.deleteByProjectIds(distinctIds);

    if (!CollectionUtils.isEmpty(requestIds)) {
      List<Long> orphanRequestIds = reviewProjectRequestRepository.findOrphanRequestIds(requestIds);
      if (!CollectionUtils.isEmpty(orphanRequestIds)) {
        reviewProjectScreenshotRepository.deleteByReviewProjectRequestIdIn(orphanRequestIds);
        reviewProjectRequestSlackThreadRepository.deleteByReviewProjectRequestIdIn(
            orphanRequestIds);
        reviewProjectRequestRepository.deleteAllById(orphanRequestIds);
      }
    }

    return deletedProjects;
  }

  @Transactional
  public ReviewProjectTextUnitDetail saveDecision(
      Long reviewProjectTextUnitId,
      String target,
      String comment,
      String status,
      Boolean includedInLocalizedFile,
      DecisionState decisionState,
      Long expectedCurrentTmTextUnitVariantId,
      boolean overrideChangedCurrent,
      String decisionNotes) {

    boolean hasTarget = target != null;
    if (hasTarget) {
      if (status == null) {
        throw new IllegalArgumentException("Status is required");
      }
      if (includedInLocalizedFile == null) {
        throw new IllegalArgumentException("includedInLocalizedFile is required");
      }
    }

    if (decisionState == null) {
      throw new IllegalArgumentException("decisionState is required");
    }

    boolean requireCurrentVariantMatch = hasTarget || decisionState == DecisionState.DECIDED;

    ReviewProjectTextUnit textUnit =
        reviewProjectTextUnitRepository
            .findById(reviewProjectTextUnitId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProjectTextUnit with id: "
                            + reviewProjectTextUnitId
                            + " not found"));

    ReviewProject project = textUnit.getReviewProject();
    assertCurrentUserCanReadProject(project);
    userService.checkUserCanEditLocale(project.getLocale().getId());
    User currentUser =
        userRepository
            .findById(teamService.getCurrentUserIdOrThrow())
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

    TMTextUnitVariant baselineVariant = textUnit.getTmTextUnitVariant();
    TMTextUnit tmTextUnit = textUnit.getTmTextUnit();

    TMTextUnitCurrentVariant currentVariant =
        tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            project.getLocale().getId(), tmTextUnit.getId());
    TMTextUnitVariant currentTmTextUnitVariant =
        currentVariant != null ? currentVariant.getTmTextUnitVariant() : null;
    Long currentVariantId =
        currentTmTextUnitVariant != null ? currentTmTextUnitVariant.getId() : null;

    if (requireCurrentVariantMatch
        && !overrideChangedCurrent
        && expectedCurrentTmTextUnitVariantId != null
        && !expectedCurrentTmTextUnitVariantId.equals(currentVariantId)) {
      throw new ReviewProjectCurrentVariantConflictException(
          expectedCurrentTmTextUnitVariantId,
          currentVariantId,
          fetchReviewProjectTextUnitDetail(reviewProjectTextUnitId));
    }

    Optional<ReviewProjectTextUnitDecision> existingDecision =
        reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(
            reviewProjectTextUnitId);
    boolean wasDecided =
        existingDecision
            .map(decision -> decision.getDecisionState() == DecisionState.DECIDED)
            .orElse(false);
    if (!hasTarget && decisionState == DecisionState.PENDING && existingDecision.isEmpty()) {
      return fetchReviewProjectTextUnitDetail(reviewProjectTextUnitId);
    }

    ReviewProjectTextUnitDecision decision =
        existingDecision.orElseGet(
            () -> {
              ReviewProjectTextUnitDecision entity = new ReviewProjectTextUnitDecision();
              entity.setReviewProjectTextUnit(textUnit);
              return entity;
            });
    TMTextUnitVariant reviewedVariant =
        baselineVariant != null ? baselineVariant : currentTmTextUnitVariant;

    if (hasTarget) {
      String normalizedTarget = NormalizationUtils.normalize(target);
      TMTextUnitVariant.Status statusEnum = TMTextUnitVariant.Status.valueOf(status);

      AddTMTextUnitCurrentVariantResult addResult =
          tmService.addTMTextUnitCurrentVariantWithResult(
              currentVariant,
              tmTextUnit.getTm().getId(),
              tmTextUnit.getAsset().getId(),
              tmTextUnit.getId(),
              project.getLocale().getId(),
              normalizedTarget,
              comment,
              statusEnum,
              includedInLocalizedFile,
              null,
              currentUser);
      TMTextUnitVariant decidedVariant =
          addResult.getTmTextUnitCurrentVariant().getTmTextUnitVariant();

      decision.setReviewedVariant(reviewedVariant);
      decision.setDecisionVariant(decidedVariant);
      decision.setNotes(decisionNotes);
    } else if (decisionState == DecisionState.DECIDED && decision.getDecisionVariant() == null) {
      TMTextUnitVariant fallbackVariant =
          currentTmTextUnitVariant != null ? currentTmTextUnitVariant : baselineVariant;
      decision.setDecisionVariant(fallbackVariant);
      decision.setReviewedVariant(reviewedVariant);
    }

    decision.setDecisionState(decisionState);
    reviewProjectTextUnitDecisionRepository.saveAndFlush(decision);
    updateProjectDecidedCount(project.getId(), wasDecided, decisionState == DecisionState.DECIDED);
    return fetchReviewProjectTextUnitDetail(reviewProjectTextUnitId);
  }

  private void updateProjectDecidedCount(Long projectId, boolean wasDecided, boolean isDecided) {
    if (wasDecided == isDecided) {
      return;
    }
    if (isDecided) {
      reviewProjectRepository.incrementDecidedCount(projectId);
    } else {
      reviewProjectRepository.decrementDecidedCount(projectId);
    }
  }

  private ReviewProjectTextUnitDetail fetchReviewProjectTextUnitDetail(
      Long reviewProjectTextUnitId) {
    return reviewProjectTextUnitRepository
        .findDetailByReviewProjectTextUnitId(reviewProjectTextUnitId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "reviewProjectTextUnit with id: " + reviewProjectTextUnitId + " not found"));
  }

  private GetProjectDetailView.ReviewProjectTextUnit toReviewProjectTextUnit(
      ReviewProjectTextUnitDetail detail) {
    GetProjectDetailView.Asset.Repository repository =
        new GetProjectDetailView.Asset.Repository(detail.repositoryId(), detail.repositoryName());
    GetProjectDetailView.Asset assetView =
        new GetProjectDetailView.Asset(detail.assetPath(), repository);
    GetProjectDetailView.TmTextUnit tmTextUnitView =
        new GetProjectDetailView.TmTextUnit(
            detail.tmTextUnitId(),
            detail.tmTextUnitName(),
            detail.tmTextUnitContent(),
            detail.tmTextUnitComment(),
            detail.tmTextUnitCreatedDate(),
            assetView,
            detail.tmTextUnitWordCount() != null ? detail.tmTextUnitWordCount().longValue() : null);
    GetProjectDetailView.TmTextUnitVariant baselineTmTextUnitVariantView =
        detail.baselineTmTextUnitVariantId() == null
            ? new GetProjectDetailView.TmTextUnitVariant(null, null, null, null, null)
            : new GetProjectDetailView.TmTextUnitVariant(
                detail.baselineTmTextUnitVariantId(),
                detail.baselineTmTextUnitVariantContent(),
                detail.baselineTmTextUnitVariantStatus() != null
                    ? detail.baselineTmTextUnitVariantStatus().name()
                    : null,
                detail.baselineTmTextUnitVariantIncludedInLocalizedFile(),
                detail.baselineTmTextUnitVariantComment());
    GetProjectDetailView.TmTextUnitVariant currentTmTextUnitVariantView =
        detail.currentTmTextUnitVariantId() == null
            ? new GetProjectDetailView.TmTextUnitVariant(null, null, null, null, null)
            : new GetProjectDetailView.TmTextUnitVariant(
                detail.currentTmTextUnitVariantId(),
                detail.currentTmTextUnitVariantContent(),
                detail.currentTmTextUnitVariantStatus() != null
                    ? detail.currentTmTextUnitVariantStatus().name()
                    : null,
                detail.currentTmTextUnitVariantIncludedInLocalizedFile(),
                detail.currentTmTextUnitVariantComment());
    GetProjectDetailView.TmTextUnitVariant decisionTmTextUnitVariantView =
        detail.decisionVariantId() == null
            ? null
            : new GetProjectDetailView.TmTextUnitVariant(
                detail.decisionVariantId(),
                detail.decisionVariantContent(),
                detail.decisionVariantStatus() != null
                    ? detail.decisionVariantStatus().name()
                    : null,
                detail.decisionVariantIncludedInLocalizedFile(),
                detail.decisionVariantComment());
    boolean hasDecision =
        detail.decisionState() != null
            || detail.decisionVariantId() != null
            || detail.reviewedTmTextUnitVariantId() != null
            || detail.decisionNotes() != null;
    GetProjectDetailView.ReviewProjectTextUnitDecision reviewProjectTextUnitDecision =
        hasDecision
            ? new GetProjectDetailView.ReviewProjectTextUnitDecision(
                detail.reviewedTmTextUnitVariantId(),
                detail.decisionNotes(),
                detail.decisionState(),
                decisionTmTextUnitVariantView,
                detail.decisionLastModifiedDate(),
                detail.decisionLastModifiedByUsername())
            : null;
    return new GetProjectDetailView.ReviewProjectTextUnit(
        detail.reviewProjectTextUnitId(),
        tmTextUnitView,
        baselineTmTextUnitVariantView,
        currentTmTextUnitVariantView,
        reviewProjectTextUnitDecision);
  }

  private List<TextUnitDTO> searchReviewCandidates(
      List<Long> tmTextUnitIds, Locale locale, StatusFilter statusFilter) {
    if (tmTextUnitIds == null || tmTextUnitIds.isEmpty()) {
      throw new IllegalArgumentException("tmTextUnitIds must be provided");
    }

    TextUnitSearcherParameters params = new TextUnitSearcherParameters();
    params.setTmTextUnitIds(tmTextUnitIds);
    params.setLocaleId(locale.getId());
    params.setPluralFormsFiltered(false);
    params.setLimit(tmTextUnitIds.size());
    params.setStatusFilter(statusFilter);

    return textUnitSearcher.search(params);
  }

  private List<TextUnitDTO> searchReviewFeatureCandidates(
      ReviewFeature reviewFeature, Locale locale, StatusFilter statusFilter) {
    if (reviewFeature == null) {
      throw new IllegalArgumentException("reviewFeature must be provided");
    }

    List<Long> repositoryIds =
        reviewFeature.getRepositories().stream()
            .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
            .map(Repository::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

    if (repositoryIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Review feature has no active repositories: " + reviewFeature.getName());
    }

    TextUnitSearcherParameters params = new TextUnitSearcherParameters();
    params.setRepositoryIds(repositoryIds);
    params.setLocaleId(locale.getId());
    params.setPluralFormsFiltered(false);
    params.setStatusFilter(statusFilter);

    return textUnitSearcher.search(params);
  }

  private StatusFilter getManualReviewFeatureStatusFilter(StatusFilter statusFilter) {
    return statusFilter == null ? StatusFilter.REVIEW_NEEDED : statusFilter;
  }

  private List<TextUnitDTO> excludeOpenReviewProjectTextUnits(
      List<TextUnitDTO> candidates, Locale locale) {
    if (CollectionUtils.isEmpty(candidates) || locale == null || locale.getId() == null) {
      return candidates;
    }

    List<Long> tmTextUnitIds =
        candidates.stream()
            .map(TextUnitDTO::getTmTextUnitId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (tmTextUnitIds.isEmpty()) {
      return candidates;
    }

    Set<Long> excludedTmTextUnitIds =
        new HashSet<>(
            reviewProjectTextUnitRepository
                .findTmTextUnitIdsByReviewProjectStatusAndLocaleIdAndTmTextUnitIds(
                    ReviewProjectStatus.OPEN, locale.getId(), tmTextUnitIds));
    if (excludedTmTextUnitIds.isEmpty()) {
      return candidates;
    }

    return candidates.stream()
        .filter(candidate -> !excludedTmTextUnitIds.contains(candidate.getTmTextUnitId()))
        .toList();
  }

  private List<Locale> getReviewFeatureLocales(ReviewFeature reviewFeature) {
    if (reviewFeature == null || CollectionUtils.isEmpty(reviewFeature.getRepositories())) {
      return List.of();
    }

    return reviewFeature.getRepositories().stream()
        .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
        .flatMap(repository -> repository.getRepositoryLocales().stream())
        .filter(repositoryLocale -> repositoryLocale.getParentLocale() != null)
        .map(RepositoryLocale::getLocale)
        .filter(Objects::nonNull)
        .collect(
            Collectors.toMap(
                Locale::getId, locale -> locale, (left, right) -> left, LinkedHashMap::new))
        .values()
        .stream()
        .sorted(
            Comparator.comparing(
                    (Locale locale) -> locale.getBcp47Tag() == null ? "" : locale.getBcp47Tag(),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Locale::getId))
        .toList();
  }

  private List<List<TextUnitDTO>> splitCandidatesByMaxWordCount(
      List<TextUnitDTO> candidates, int maxWordCountPerProject) {
    if (CollectionUtils.isEmpty(candidates)) {
      return List.of();
    }
    if (maxWordCountPerProject == Integer.MAX_VALUE) {
      return List.of(candidates);
    }

    List<List<TextUnitDTO>> chunks = new ArrayList<>();
    List<TextUnitDTO> currentChunk = new ArrayList<>();
    int currentWordCount = 0;
    for (TextUnitDTO candidate : candidates) {
      int candidateWordCount = wordCountService.getEnglishWordCount(candidate.getSource());
      boolean wouldOverflow =
          !currentChunk.isEmpty() && currentWordCount + candidateWordCount > maxWordCountPerProject;
      if (wouldOverflow) {
        chunks.add(List.copyOf(currentChunk));
        currentChunk.clear();
        currentWordCount = 0;
      }
      currentChunk.add(candidate);
      currentWordCount += candidateWordCount;
    }
    if (!currentChunk.isEmpty()) {
      chunks.add(List.copyOf(currentChunk));
    }
    return chunks;
  }

  private LocalePlan preparedLocalePlan(
      String localeTag,
      Locale locale,
      List<TextUnitDTO> candidates,
      int projectCount,
      String message) {
    return new LocalePlan(
        localeTag,
        locale,
        List.copyOf(candidates),
        projectCount,
        LocalePlanStatus.PREPARED,
        message);
  }

  private LocalePlan skippedLocalePlan(String localeTag) {
    return new LocalePlan(
        localeTag,
        null,
        List.of(),
        0,
        LocalePlanStatus.SKIPPED_NO_TEXT_UNITS,
        "No text units matched for this locale");
  }

  private LocalePlan errorLocalePlan(String localeTag, String message) {
    return new LocalePlan(
        localeTag,
        null,
        List.of(),
        0,
        LocalePlanStatus.ERROR,
        message == null || message.isBlank() ? "Unexpected error while preparing locale" : message);
  }

  private List<LocaleCandidates> getPreparedLocales(List<LocalePlan> localePlans) {
    return localePlans.stream()
        .filter(localePlan -> localePlan.status() == LocalePlanStatus.PREPARED)
        .map(localePlan -> new LocaleCandidates(localePlan.locale(), localePlan.candidates()))
        .toList();
  }

  private List<LocaleCandidates> getPreparedLocalesWithChunks(
      List<LocalePlan> localePlans, int maxWordCountPerProject) {
    List<LocaleCandidates> localesToCreate = new ArrayList<>();
    for (LocalePlan localePlan : localePlans) {
      if (localePlan.status() != LocalePlanStatus.PREPARED || localePlan.locale() == null) {
        continue;
      }
      for (List<TextUnitDTO> chunk :
          splitCandidatesByMaxWordCount(localePlan.candidates(), maxWordCountPerProject)) {
        if (chunk.isEmpty()) {
          continue;
        }
        localesToCreate.add(new LocaleCandidates(localePlan.locale(), chunk));
      }
    }
    return localesToCreate;
  }

  private CreateReviewProjectRequestResult buildCreateReviewProjectRequestResult(
      PersistedReviewProjectRequest persistedReviewProjectRequest,
      List<String> requestedLocaleTags,
      List<LocalePlan> localePlans) {
    return buildCreateReviewProjectRequestResult(
        persistedReviewProjectRequest == null ? null : persistedReviewProjectRequest.requestId(),
        persistedReviewProjectRequest == null ? null : persistedReviewProjectRequest.requestName(),
        persistedReviewProjectRequest == null ? null : persistedReviewProjectRequest.dueDate(),
        persistedReviewProjectRequest == null
            ? List.of()
            : persistedReviewProjectRequest.projectIds(),
        requestedLocaleTags,
        localePlans);
  }

  private CreateReviewProjectRequestResult buildCreateReviewProjectRequestResult(
      Long requestId,
      String requestName,
      ZonedDateTime dueDate,
      List<Long> projectIds,
      List<String> requestedLocaleTags,
      List<LocalePlan> localePlans) {
    List<CreateReviewProjectRequestResult.LocaleResult> localeResults =
        localePlans.stream()
            .map(
                localePlan ->
                    new CreateReviewProjectRequestResult.LocaleResult(
                        localePlan.localeTag(),
                        switch (localePlan.status()) {
                          case PREPARED ->
                              CreateReviewProjectRequestResult.LocaleResultStatus.CREATED;
                          case SKIPPED_NO_TEXT_UNITS ->
                              CreateReviewProjectRequestResult.LocaleResultStatus
                                  .SKIPPED_NO_TEXT_UNITS;
                          case ERROR -> CreateReviewProjectRequestResult.LocaleResultStatus.ERROR;
                        },
                        localePlan.candidates().size(),
                        localePlan.projectCount(),
                        localePlan.message()))
            .toList();
    int createdLocaleCount =
        (int)
            localeResults.stream()
                .filter(
                    localeResult ->
                        localeResult.status()
                            == CreateReviewProjectRequestResult.LocaleResultStatus.CREATED)
                .count();
    int skippedLocaleCount =
        (int)
            localeResults.stream()
                .filter(
                    localeResult ->
                        localeResult.status()
                            == CreateReviewProjectRequestResult.LocaleResultStatus
                                .SKIPPED_NO_TEXT_UNITS)
                .count();
    int erroredLocaleCount =
        (int)
            localeResults.stream()
                .filter(
                    localeResult ->
                        localeResult.status()
                            == CreateReviewProjectRequestResult.LocaleResultStatus.ERROR)
                .count();
    List<String> createdLocaleTags =
        localeResults.stream()
            .filter(
                localeResult ->
                    localeResult.status()
                        == CreateReviewProjectRequestResult.LocaleResultStatus.CREATED)
            .map(CreateReviewProjectRequestResult.LocaleResult::localeTag)
            .toList();

    return new CreateReviewProjectRequestResult(
        requestId,
        requestName,
        createdLocaleTags,
        dueDate,
        projectIds,
        requestedLocaleTags == null ? 0 : new LinkedHashSet<>(requestedLocaleTags).size(),
        createdLocaleCount,
        skippedLocaleCount,
        erroredLocaleCount,
        localeResults);
  }

  private PersistedReviewProjectRequest persistPreparedReviewProjectRequest(
      String name,
      String notes,
      List<String> screenshotImageIds,
      ReviewProjectType type,
      ZonedDateTime dueDate,
      Long teamId,
      Long requestedByUserId,
      Boolean assignTranslator,
      List<LocaleCandidates> localesToCreate) {
    User requestedByUser = resolveUser(requestedByUserId, "requestedByUser");

    ReviewProjectRequest reviewProjectRequest = new ReviewProjectRequest();
    reviewProjectRequest.setName(name);
    reviewProjectRequest.setNotes(notes);
    reviewProjectRequest.setCreatedByUser(requestedByUser);
    reviewProjectRequest = reviewProjectRequestRepository.save(reviewProjectRequest);

    if (screenshotImageIds != null) {
      for (String screenshotImageId : screenshotImageIds) {
        ReviewProjectRequestScreenshot screenshot = new ReviewProjectRequestScreenshot();
        screenshot.setReviewProjectRequest(reviewProjectRequest);
        screenshot.setImageName(screenshotImageId);
        reviewProjectScreenshotRepository.save(screenshot);
      }
    }

    List<Long> projectIds = new ArrayList<>();
    List<String> createdLocaleTags = new ArrayList<>();
    List<ReviewProject> createdProjects = new ArrayList<>();
    CreateAssignmentDefaults assignmentDefaults =
        resolveCreateAssignmentDefaults(teamId, requestedByUserId);

    for (LocaleCandidates localeCandidates : localesToCreate) {
      Locale locale = localeCandidates.locale();
      ReviewProject reviewProject = new ReviewProject();
      reviewProject.setType(type);
      reviewProject.setStatus(ReviewProjectStatus.OPEN);
      reviewProject.setDueDate(dueDate);
      reviewProject.setLocale(locale);
      reviewProject.setReviewProjectRequest(reviewProjectRequest);
      reviewProject.setTeam(assignmentDefaults.team());
      reviewProject.setAssignedPmUser(assignmentDefaults.defaultPmUser());
      reviewProject.setCreatedByUser(requestedByUser);
      String localeTagKey =
          locale.getBcp47Tag() == null ? "" : locale.getBcp47Tag().trim().toLowerCase();
      if (assignTranslator == null || Boolean.TRUE.equals(assignTranslator)) {
        reviewProject.setAssignedTranslatorUser(
            assignmentDefaults.defaultTranslatorByLocaleTagLowercase().get(localeTagKey));
      }

      ReviewProject saved = reviewProjectRepository.save(reviewProject);

      int wordCount = 0;
      int textUnitCount = 0;

      for (TextUnitDTO textUnitDTO : localeCandidates.candidates()) {
        ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
        reviewProjectTextUnit.setReviewProject(reviewProject);
        reviewProjectTextUnit.setTmTextUnit(
            entityManager.getReference(TMTextUnit.class, textUnitDTO.getTmTextUnitId()));
        if (textUnitDTO.getTmTextUnitVariantId() != null) {
          reviewProjectTextUnit.setTmTextUnitVariant(
              entityManager.getReference(
                  TMTextUnitVariant.class, textUnitDTO.getTmTextUnitVariantId()));
        }
        reviewProjectTextUnitRepository.save(reviewProjectTextUnit);

        textUnitCount++;
        wordCount += wordCountService.getEnglishWordCount(textUnitDTO.getSource());
      }

      saved.setWordCount(wordCount);
      saved.setTextUnitCount(textUnitCount);
      recordAssignmentHistory(
          saved, ReviewProjectAssignmentEventType.CREATED_DEFAULT, null, requestedByUser);
      projectIds.add(saved.getId());
      createdLocaleTags.add(locale.getBcp47Tag());
      createdProjects.add(saved);
    }

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        reviewProjectRequest, createdProjects);

    List<String> distinctCreatedLocaleTags =
        new ArrayList<>(new LinkedHashSet<>(createdLocaleTags));
    logger.info(
        "Created review project request id={} name='{}' with {} locales {} and {} projects",
        reviewProjectRequest.getId(),
        reviewProjectRequest.getName(),
        distinctCreatedLocaleTags.size(),
        distinctCreatedLocaleTags,
        projectIds.size());

    return new PersistedReviewProjectRequest(
        reviewProjectRequest.getId(),
        reviewProjectRequest.getName(),
        distinctCreatedLocaleTags,
        dueDate,
        projectIds);
  }

  private ReviewFeature getReviewFeatureOrThrow(Long reviewFeatureId) {
    return reviewFeatureRepository
        .findByIdWithRepositories(reviewFeatureId)
        .orElseThrow(
            () -> new IllegalArgumentException("Review feature not found: " + reviewFeatureId));
  }

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  private void validateAssignmentUpdatePermissions(
      ReviewProject reviewProject,
      boolean isAdmin,
      boolean isPm,
      boolean isTranslator,
      Team previousTeam,
      Team nextTeam,
      User previousAssignedPm,
      User nextAssignedPm,
      User previousAssignedTranslator,
      User nextAssignedTranslator) {
    Long previousTeamId = getEntityId(previousTeam);
    Long nextTeamId = getEntityId(nextTeam);
    boolean teamChanged = !Objects.equals(previousTeamId, nextTeamId);
    boolean pmChanged =
        !Objects.equals(getEntityId(previousAssignedPm), getEntityId(nextAssignedPm));
    boolean translatorChanged =
        !Objects.equals(
            getEntityId(previousAssignedTranslator), getEntityId(nextAssignedTranslator));

    if (!isAdmin) {
      if (teamChanged) {
        throw new AccessDeniedException("Only admins can change assigned team");
      }
      if (isTranslator && pmChanged) {
        throw new AccessDeniedException("Translators can only reassign translator");
      }
      if (previousTeamId == null && (pmChanged || translatorChanged)) {
        throw new AccessDeniedException("Team must be assigned before non-admin reassignment");
      }
      if (previousTeamId != null) {
        if (isPm) {
          teamService.assertCurrentUserCanAccessTeam(previousTeamId);
        } else if (isTranslator) {
          teamService.assertCurrentUserCanReadTeam(previousTeamId);
        }
      }
    }

    Long effectiveTeamId = nextTeamId;
    if (effectiveTeamId != null) {
      if (nextAssignedPm != null
          && !teamService.isUserInTeamRole(
              effectiveTeamId, nextAssignedPm.getId(), TeamUserRole.PM)) {
        throw new IllegalArgumentException(
            "Assigned PM is not a PM member of team "
                + effectiveTeamId
                + ": "
                + nextAssignedPm.getId());
      }
      if (nextAssignedTranslator != null
          && !teamService.isUserInTeamRole(
              effectiveTeamId, nextAssignedTranslator.getId(), TeamUserRole.TRANSLATOR)) {
        throw new IllegalArgumentException(
            "Assigned translator is not a translator member of team "
                + effectiveTeamId
                + ": "
                + nextAssignedTranslator.getId());
      }
    } else if (nextAssignedPm != null || nextAssignedTranslator != null) {
      throw new IllegalArgumentException("Assigned PM/translator requires a team assignment");
    }
  }

  private Team resolveTeam(Long teamId) {
    if (teamId == null) {
      return null;
    }
    return teamRepository
        .findByIdAndEnabledTrue(teamId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown team: " + teamId));
  }

  private CreateAssignmentDefaults resolveCreateAssignmentDefaults(
      Long teamId, Long requestedByUserId) {
    Team team = resolveTeam(teamId);
    if (team == null) {
      return new CreateAssignmentDefaults(null, null, Map.of());
    }
    teamService.assertUserCanAccessTeam(team.getId(), requestedByUserId);

    // Reuse team service access checks and ordering semantics (PM pool / locale pools).
    List<Long> pmPoolUserIds = teamService.getPmPool(team.getId());
    User defaultPmUser =
        pmPoolUserIds.isEmpty() ? null : resolveUser(pmPoolUserIds.get(0), "defaultPmUser");

    Map<String, User> translatorsByLocaleTagLowercase = new LinkedHashMap<>();
    for (TeamService.LocalePoolEntry entry : teamService.getLocalePools(team.getId())) {
      if (entry == null || entry.localeTag() == null) {
        continue;
      }
      String localeTagKey = entry.localeTag().trim().toLowerCase();
      if (localeTagKey.isEmpty() || translatorsByLocaleTagLowercase.containsKey(localeTagKey)) {
        continue;
      }
      Long translatorUserId =
          (entry.translatorUserIds() == null ? List.<Long>of() : entry.translatorUserIds())
              .stream().filter(id -> id != null && id > 0).findFirst().orElse(null);
      if (translatorUserId == null) {
        continue;
      }
      translatorsByLocaleTagLowercase.put(
          localeTagKey, resolveUser(translatorUserId, "defaultTranslatorUser"));
    }

    return new CreateAssignmentDefaults(team, defaultPmUser, translatorsByLocaleTagLowercase);
  }

  private User resolveUser(Long userId, String fieldName) {
    if (userId == null) {
      return null;
    }
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown " + fieldName + ": " + userId));
  }

  private Long getEntityId(BaseEntity entity) {
    return entity == null ? null : entity.getId();
  }

  private void recordAssignmentHistory(
      ReviewProject reviewProject, ReviewProjectAssignmentEventType eventType, String note) {
    recordAssignmentHistory(reviewProject, eventType, note, null);
  }

  private void recordAssignmentHistory(
      ReviewProject reviewProject,
      ReviewProjectAssignmentEventType eventType,
      String note,
      User createdByUser) {
    ReviewProjectAssignmentHistory history = new ReviewProjectAssignmentHistory();
    history.setReviewProject(reviewProject);
    history.setTeam(reviewProject.getTeam());
    history.setAssignedPmUser(reviewProject.getAssignedPmUser());
    history.setAssignedTranslatorUser(reviewProject.getAssignedTranslatorUser());
    history.setEventType(eventType);
    history.setCreatedByUser(createdByUser);
    String normalizedNote = note == null ? null : note.trim();
    if (normalizedNote != null && normalizedNote.length() > 512) {
      normalizedNote = normalizedNote.substring(0, 512);
    }
    history.setNote(normalizedNote == null || normalizedNote.isEmpty() ? null : normalizedNote);
    reviewProjectAssignmentHistoryRepository.save(history);
  }
}
