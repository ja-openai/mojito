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
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.WordCountService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.team.TeamSlackNotificationService;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
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

  private final ReviewProjectRepository reviewProjectRepository;
  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository;
  private final ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository;
  private final ReviewProjectRequestRepository reviewProjectRequestRepository;
  private final ReviewProjectRequestScreenshotRepository reviewProjectScreenshotRepository;
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
  private final ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository;
  private final TeamSlackNotificationService teamSlackNotificationService;

  @PersistenceContext private EntityManager entityManager;

  public ReviewProjectService(
      ReviewProjectRepository reviewProjectRepository,
      ReviewProjectTextUnitRepository reviewProjectTextUnitRepository,
      ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository,
      ReviewProjectRequestRepository reviewProjectRequestRepository,
      ReviewProjectRequestScreenshotRepository reviewProjectScreenshotRepository,
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
      ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository,
      TeamSlackNotificationService teamSlackNotificationService) {
    this.reviewProjectRepository = reviewProjectRepository;
    this.reviewProjectTextUnitRepository = reviewProjectTextUnitRepository;
    this.reviewProjectTextUnitDecisionRepository = reviewProjectTextUnitDecisionRepository;
    this.reviewProjectRequestRepository = reviewProjectRequestRepository;
    this.reviewProjectScreenshotRepository = reviewProjectScreenshotRepository;
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
    this.reviewProjectAssignmentHistoryRepository = reviewProjectAssignmentHistoryRepository;
    this.teamSlackNotificationService = teamSlackNotificationService;
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

    if (request.tmTextUnitIds() == null || request.tmTextUnitIds().isEmpty()) {
      throw new IllegalArgumentException("tmTextUnitIds must be provided");
    }

    if (request.type() == null) {
      throw new IllegalArgumentException("type must be provided");
    }

    List<LocaleCandidates> localesToCreate = new ArrayList<>();
    for (String localeTag : new LinkedHashSet<>(request.localeTags())) {
      Locale locale = localeService.findByBcp47Tag(localeTag);

      if (locale == null) {
        throw new IllegalArgumentException("Unknown locale: " + localeTag);
      }

      List<TextUnitDTO> candidates = searchReviewCandidates(request.tmTextUnitIds(), locale);
      if (candidates.isEmpty()) {
        continue;
      }

      localesToCreate.add(new LocaleCandidates(locale, candidates));
    }

    if (localesToCreate.isEmpty()) {
      throw new IllegalArgumentException("No text units found for provided locales");
    }

    ReviewProjectRequest reviewProjectRequest = new ReviewProjectRequest();
    reviewProjectRequest.setName(request.name());
    reviewProjectRequest.setNotes(request.notes());
    reviewProjectRequest = reviewProjectRequestRepository.save(reviewProjectRequest);

    if (request.screenshotImageIds() != null) {
      for (String screenshotImageId : request.screenshotImageIds()) {
        ReviewProjectRequestScreenshot screenshot = new ReviewProjectRequestScreenshot();
        screenshot.setReviewProjectRequest(reviewProjectRequest);
        screenshot.setImageName(screenshotImageId);
        reviewProjectScreenshotRepository.save(screenshot);
      }
    }

    List<Long> projectIds = new ArrayList<>();
    List<String> createdLocaleTags = new ArrayList<>();
    List<ReviewProject> createdProjects = new ArrayList<>();
    CreateAssignmentDefaults assignmentDefaults = resolveCreateAssignmentDefaults(request.teamId());

    for (LocaleCandidates localeCandidates : localesToCreate) {
      Locale locale = localeCandidates.locale();
      ReviewProject reviewProject = new ReviewProject();
      reviewProject.setType(request.type());
      reviewProject.setStatus(ReviewProjectStatus.OPEN);
      reviewProject.setDueDate(request.dueDate());
      reviewProject.setLocale(locale);
      reviewProject.setReviewProjectRequest(reviewProjectRequest);
      reviewProject.setTeam(assignmentDefaults.team());
      reviewProject.setAssignedPmUser(assignmentDefaults.defaultPmUser());
      String localeTagKey =
          locale.getBcp47Tag() == null ? "" : locale.getBcp47Tag().trim().toLowerCase();
      reviewProject.setAssignedTranslatorUser(
          assignmentDefaults.defaultTranslatorByLocaleTagLowercase().get(localeTagKey));

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
      recordAssignmentHistory(saved, ReviewProjectAssignmentEventType.CREATED_DEFAULT, null);
      projectIds.add(saved.getId());
      createdLocaleTags.add(locale.getBcp47Tag());
      createdProjects.add(saved);
    }

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        reviewProjectRequest, createdProjects);

    return new CreateReviewProjectRequestResult(
        reviewProjectRequest.getId(),
        reviewProjectRequest.getName(),
        createdLocaleTags,
        request.dueDate(),
        projectIds);
  }

  private record LocaleCandidates(Locale locale, List<TextUnitDTO> candidates) {}

  private record CreateAssignmentDefaults(
      Team team, User defaultPmUser, Map<String, User> defaultTranslatorByLocaleTagLowercase) {}

  @Transactional(readOnly = true)
  public SearchReviewProjectsView searchReviewProjects(SearchReviewProjectsCriteria request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    List<SearchReviewProjectDetail> projectDetails = getProjectDetailsByCriteria(request);
    Map<Long, Long> acceptedCountByProjectId = getAcceptedCountByProjectId(projectDetails);
    List<SearchReviewProjectsView.ReviewProject> reviewProjects =
        toReviewProjectViews(projectDetails, acceptedCountByProjectId);

    return new SearchReviewProjectsView(reviewProjects);
  }

  @Transactional(readOnly = true)
  public SearchReviewProjectRequestsView searchReviewProjectRequests(
      SearchReviewProjectsCriteria request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }

    List<ReviewProjectStatus> requestedStatuses = getRequestModeStatuses(request.statuses());
    boolean includeOpen = requestedStatuses.contains(ReviewProjectStatus.OPEN);
    boolean includeClosed = requestedStatuses.contains(ReviewProjectStatus.CLOSED);

    List<Long> requestIds = findRequestIdsByStatuses(request, requestedStatuses);
    if (requestIds.isEmpty()) {
      return new SearchReviewProjectRequestsView(List.of());
    }

    List<SearchReviewProjectDetail> projectDetails = getProjectDetailsByRequestIds(requestIds);
    Map<Long, Long> acceptedCountByProjectId = getAcceptedCountByProjectId(projectDetails);
    List<SearchReviewProjectsView.ReviewProject> reviewProjects =
        toReviewProjectViews(projectDetails, acceptedCountByProjectId);

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
      long acceptedCount =
          sortedProjects.stream()
              .mapToLong(project -> Optional.ofNullable(project.acceptedCount()).orElse(0L))
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
              acceptedCount,
              dueDate,
              sortedProjects));
    }

    return new SearchReviewProjectRequestsView(groups);
  }

  private List<ReviewProjectStatus> getRequestModeStatuses(List<ReviewProjectStatus> statuses) {
    if (statuses == null || statuses.isEmpty()) {
      return List.of(ReviewProjectStatus.OPEN);
    }
    return statuses;
  }

  private List<SearchReviewProjectDetail> getProjectDetailsByCriteria(
      SearchReviewProjectsCriteria request) {
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
            cb, root, localeJoin, requestJoin, createdByUserJoin, request, request.statuses());

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
      SearchReviewProjectsCriteria request, List<ReviewProjectStatus> statuses) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<Long> cq = cb.createQuery(Long.class);
    Root<ReviewProject> root = cq.from(ReviewProject.class);
    Join<ReviewProject, Locale> localeJoin = root.join(ReviewProject_.locale, JoinType.LEFT);
    Join<ReviewProject, ReviewProjectRequest> requestJoin =
        root.join(ReviewProject_.reviewProjectRequest, JoinType.LEFT);
    Join<ReviewProject, User> createdByUserJoin =
        root.join(ReviewProject_.createdByUser, JoinType.LEFT);

    List<Predicate> predicates =
        buildProjectSearchPredicates(
            cb, root, localeJoin, requestJoin, createdByUserJoin, request, statuses);
    predicates.add(cb.isNotNull(requestJoin.get(ReviewProjectRequest_.id)));

    cq.where(predicates.toArray(Predicate[]::new))
        .select(requestJoin.get(ReviewProjectRequest_.id))
        .groupBy(requestJoin.get(ReviewProjectRequest_.id))
        .orderBy(cb.desc(requestJoin.get(ReviewProjectRequest_.id)));

    TypedQuery<Long> query = entityManager.createQuery(cq);
    query.setMaxResults(request.limit());
    return query.getResultList();
  }

  private List<SearchReviewProjectDetail> getProjectDetailsByRequestIds(List<Long> requestIds) {
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

    cq.where(requestJoin.get(ReviewProjectRequest_.id).in(requestIds))
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

  private Map<Long, Long> getAcceptedCountByProjectId(
      List<SearchReviewProjectDetail> projectDetails) {
    Map<Long, Long> acceptedCountByProjectId = new HashMap<>();
    if (projectDetails == null || projectDetails.isEmpty()) {
      return acceptedCountByProjectId;
    }

    List<Long> projectIds = projectDetails.stream().map(SearchReviewProjectDetail::id).toList();
    List<ReviewProjectAcceptedCountRow> acceptedCounts =
        reviewProjectTextUnitDecisionRepository.countAcceptedByProjectIdsAndDecisionState(
            projectIds, DecisionState.DECIDED);
    for (ReviewProjectAcceptedCountRow acceptedCount : acceptedCounts) {
      acceptedCountByProjectId.put(acceptedCount.projectId(), acceptedCount.acceptedCount());
    }
    return acceptedCountByProjectId;
  }

  private List<SearchReviewProjectsView.ReviewProject> toReviewProjectViews(
      List<SearchReviewProjectDetail> projectDetails, Map<Long, Long> acceptedCountByProjectId) {
    if (projectDetails == null || projectDetails.isEmpty()) {
      return List.of();
    }

    return projectDetails.stream()
        .map(
            detail -> {
              long acceptedCount = acceptedCountByProjectId.getOrDefault(detail.id(), 0L);
              return new SearchReviewProjectsView.ReviewProject(
                  detail.id(),
                  detail.createdDate(),
                  detail.lastModifiedDate(),
                  detail.dueDate(),
                  detail.closeReason(),
                  detail.textUnitCount(),
                  detail.wordCount(),
                  acceptedCount,
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
      SearchReviewProjectsCriteria request,
      List<ReviewProjectStatus> statuses) {
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

    return predicates;
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

    if (type != null) {
      reviewProject.setType(type);
    }
    if (dueDate != null) {
      reviewProject.setDueDate(dueDate);
    }
    reviewProjectRepository.save(reviewProject);

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
    teamSlackNotificationService.sendReviewProjectAssignmentNotification(
        reviewProject, eventType, note);
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
    if (!reviewProjectRepository.existsById(projectId)) {
      throw new IllegalArgumentException("reviewProject with id: " + projectId + " not found");
    }
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

    reviewProjectTextUnitDecisionRepository.deleteByReviewProjectIds(distinctIds);
    reviewProjectTextUnitRepository.deleteByReviewProjectIds(distinctIds);
    int deletedProjects = reviewProjectRepository.deleteByProjectIds(distinctIds);

    if (!CollectionUtils.isEmpty(requestIds)) {
      List<Long> orphanRequestIds = reviewProjectRequestRepository.findOrphanRequestIds(requestIds);
      if (!CollectionUtils.isEmpty(orphanRequestIds)) {
        reviewProjectScreenshotRepository.deleteByReviewProjectRequestIdIn(orphanRequestIds);
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
    userService.checkUserCanEditLocale(project.getLocale().getId());

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
              null);
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
    reviewProjectTextUnitDecisionRepository.save(decision);
    return fetchReviewProjectTextUnitDetail(reviewProjectTextUnitId);
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
                decisionTmTextUnitVariantView)
            : null;
    return new GetProjectDetailView.ReviewProjectTextUnit(
        detail.reviewProjectTextUnitId(),
        tmTextUnitView,
        baselineTmTextUnitVariantView,
        currentTmTextUnitVariantView,
        reviewProjectTextUnitDecision);
  }

  private List<TextUnitDTO> searchReviewCandidates(List<Long> tmTextUnitIds, Locale locale) {
    if (tmTextUnitIds == null || tmTextUnitIds.isEmpty()) {
      throw new IllegalArgumentException("tmTextUnitIds must be provided");
    }

    TextUnitSearcherParameters params = new TextUnitSearcherParameters();
    params.setTmTextUnitIds(tmTextUnitIds);
    params.setLocaleId(locale.getId());
    params.setPluralFormsFiltered(false);
    params.setLimit(tmTextUnitIds.size());

    return textUnitSearcher.search(params);
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

  private CreateAssignmentDefaults resolveCreateAssignmentDefaults(Long teamId) {
    Team team = resolveTeam(teamId);
    if (team == null) {
      return new CreateAssignmentDefaults(null, null, Map.of());
    }
    teamService.assertCurrentUserCanAccessTeam(team.getId());

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
    ReviewProjectAssignmentHistory history = new ReviewProjectAssignmentHistory();
    history.setReviewProject(reviewProject);
    history.setTeam(reviewProject.getTeam());
    history.setAssignedPmUser(reviewProject.getAssignedPmUser());
    history.setAssignedTranslatorUser(reviewProject.getAssignedTranslatorUser());
    history.setEventType(eventType);
    String normalizedNote = note == null ? null : note.trim();
    if (normalizedNote != null && normalizedNote.length() > 512) {
      normalizedNote = normalizedNote.substring(0, 512);
    }
    history.setNote(normalizedNote == null || normalizedNote.isEmpty() ? null : normalizedNote);
    reviewProjectAssignmentHistoryRepository.save(history);
  }
}
