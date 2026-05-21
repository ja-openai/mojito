package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Locale_;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexReview;
import com.box.l10n.mojito.entity.review.*;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest_;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitFeedback.Recommendation;
import com.box.l10n.mojito.entity.review.ReviewProject_;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.entity.security.user.User_;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.WordCountService;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.glossary.GlossaryTermEvidenceRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexLinkRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermMetadataRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.glossary.TermIndexCandidateRepository;
import com.box.l10n.mojito.service.glossary.TermIndexOccurrenceRepository;
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
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
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
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.CollectionUtils;

@Service
public class ReviewProjectService {

  private static final Logger logger = LoggerFactory.getLogger(ReviewProjectService.class);
  private static final String METRIC_PREFIX = "ReviewProjectService";
  private static final String SEARCH_MODE_PROJECTS = "projects";
  private static final String SEARCH_MODE_REQUESTS = "requests";
  private static final long SEARCH_PHASE_SLOW_LOG_THRESHOLD_MS = 250;
  private static final long SEARCH_TOTAL_SLOW_LOG_THRESHOLD_MS = 1_000;
  private static final long SAVE_DECISION_PHASE_SLOW_LOG_THRESHOLD_MS = 250;
  private static final long SAVE_DECISION_TOTAL_SLOW_LOG_THRESHOLD_MS = 1_000;
  private static final String TRANSLATOR_INTEGRITY_BYPASS_DENIED_MESSAGE =
      "This translation failed the placeholder/integrity check. "
          + "Please fix the translation and try saving again.";

  private final ReviewProjectRepository reviewProjectRepository;
  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository;
  private final ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository;
  private final ReviewProjectTextUnitFeedbackRepository reviewProjectTextUnitFeedbackRepository;
  private final ReviewProjectRequestRepository reviewProjectRequestRepository;
  private final ReviewProjectRequestScreenshotRepository reviewProjectScreenshotRepository;
  private final ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository;
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  private final GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService;
  private final GlossaryTermIndexLinkRepository glossaryTermIndexLinkRepository;
  private final TermIndexCandidateRepository termIndexCandidateRepository;
  private final TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  private final GlossaryTermService glossaryTermService;
  private final LocaleService localeService;
  private final TextUnitSearcher textUnitSearcher;
  private final TMTextUnitRepository tmTextUnitRepository;
  private final TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;
  private final TMService tmService;
  private final TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService;
  private final WordCountService wordCountService;
  private final UserService userService;
  private final UserRepository userRepository;
  private final TeamService teamService;
  private final TeamRepository teamRepository;
  private final TeamUserRepository teamUserRepository;
  private final ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository;
  private final ReviewProjectAssignmentWindowRepository reviewProjectAssignmentWindowRepository;
  private final ReviewProjectAssignmentWindowService reviewProjectAssignmentWindowService;
  private final ReviewProjectTimeSpentStatService reviewProjectTimeSpentStatService;
  private final TeamSlackNotificationService teamSlackNotificationService;
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  private final ReviewFeatureRepository reviewFeatureRepository;
  private final MeterRegistry meterRegistry;
  private final PlatformTransactionManager transactionManager;

  @PersistenceContext private EntityManager entityManager;

  public ReviewProjectService(
      ReviewProjectRepository reviewProjectRepository,
      ReviewProjectTextUnitRepository reviewProjectTextUnitRepository,
      ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository,
      ReviewProjectTextUnitFeedbackRepository reviewProjectTextUnitFeedbackRepository,
      ReviewProjectRequestRepository reviewProjectRequestRepository,
      ReviewProjectRequestScreenshotRepository reviewProjectScreenshotRepository,
      ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      GlossaryTermEvidenceRepository glossaryTermEvidenceRepository,
      GlossaryTermIndexCurationService glossaryTermIndexCurationService,
      GlossaryTermIndexLinkRepository glossaryTermIndexLinkRepository,
      TermIndexCandidateRepository termIndexCandidateRepository,
      TermIndexOccurrenceRepository termIndexOccurrenceRepository,
      GlossaryTermService glossaryTermService,
      LocaleService localeService,
      TextUnitSearcher textUnitSearcher,
      TMTextUnitRepository tmTextUnitRepository,
      TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository,
      TMService tmService,
      TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService,
      WordCountService wordCountService,
      UserService userService,
      UserRepository userRepository,
      TeamService teamService,
      TeamRepository teamRepository,
      TeamUserRepository teamUserRepository,
      ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository,
      ReviewProjectAssignmentWindowRepository reviewProjectAssignmentWindowRepository,
      ReviewProjectAssignmentWindowService reviewProjectAssignmentWindowService,
      ReviewProjectTimeSpentStatService reviewProjectTimeSpentStatService,
      TeamSlackNotificationService teamSlackNotificationService,
      QuartzPollableTaskScheduler quartzPollableTaskScheduler,
      ReviewFeatureRepository reviewFeatureRepository,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager) {
    this.reviewProjectRepository = reviewProjectRepository;
    this.reviewProjectTextUnitRepository = reviewProjectTextUnitRepository;
    this.reviewProjectTextUnitDecisionRepository = reviewProjectTextUnitDecisionRepository;
    this.reviewProjectTextUnitFeedbackRepository = reviewProjectTextUnitFeedbackRepository;
    this.reviewProjectRequestRepository = reviewProjectRequestRepository;
    this.reviewProjectScreenshotRepository = reviewProjectScreenshotRepository;
    this.reviewProjectRequestSlackThreadRepository = reviewProjectRequestSlackThreadRepository;
    this.glossaryTermMetadataRepository = glossaryTermMetadataRepository;
    this.glossaryTermEvidenceRepository = glossaryTermEvidenceRepository;
    this.glossaryTermIndexCurationService = glossaryTermIndexCurationService;
    this.glossaryTermIndexLinkRepository = glossaryTermIndexLinkRepository;
    this.termIndexCandidateRepository = termIndexCandidateRepository;
    this.termIndexOccurrenceRepository = termIndexOccurrenceRepository;
    this.glossaryTermService = glossaryTermService;
    this.localeService = localeService;
    this.textUnitSearcher = textUnitSearcher;
    this.tmTextUnitRepository = tmTextUnitRepository;
    this.tmTextUnitCurrentVariantRepository = tmTextUnitCurrentVariantRepository;
    this.tmService = tmService;
    this.tmTextUnitIntegrityCheckService = tmTextUnitIntegrityCheckService;
    this.wordCountService = wordCountService;
    this.userService = userService;
    this.userRepository = userRepository;
    this.teamService = teamService;
    this.teamRepository = teamRepository;
    this.teamUserRepository = teamUserRepository;
    this.reviewProjectAssignmentHistoryRepository = reviewProjectAssignmentHistoryRepository;
    this.reviewProjectAssignmentWindowRepository = reviewProjectAssignmentWindowRepository;
    this.reviewProjectAssignmentWindowService = reviewProjectAssignmentWindowService;
    this.reviewProjectTimeSpentStatService = reviewProjectTimeSpentStatService;
    this.teamSlackNotificationService = teamSlackNotificationService;
    this.quartzPollableTaskScheduler = quartzPollableTaskScheduler;
    this.reviewFeatureRepository = reviewFeatureRepository;
    this.meterRegistry = meterRegistry;
    this.transactionManager = transactionManager;
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
            request.repositoryIds(),
            request.statusFilter(),
            request.skipTextUnitsInOpenProjects(),
            request.type(),
            request.dueDate(),
            request.screenshotImageIds(),
            request.name(),
            request.teamId(),
            request.assignTranslator(),
            requestedByUserId,
            request.projectSpecs());

    QuartzJobInfo<CreateReviewProjectRequestCommand, CreateReviewProjectRequestResult>
        quartzJobInfo =
            QuartzJobInfo.newBuilder(ReviewProjectCreateRequestJob.class)
                .withInlineInput(false)
                .withInput(asyncRequest)
                .withMessage("Create review project request")
                .build();
    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  public PollableFuture<CreateReviewProjectRequestResult>
      createGlossaryTerminologyReviewProjectAsync(
          Long glossaryId, CreateGlossaryTerminologyReviewProjectCommand request) {
    if (glossaryId == null) {
      throw new IllegalArgumentException("glossaryId must be provided");
    }
    CreateGlossaryTerminologyReviewProjectCommand resolvedRequest =
        request == null
            ? new CreateGlossaryTerminologyReviewProjectCommand(
                null, null, null, null, null, null, null, null, null, null)
            : request;

    List<Long> tmTextUnitIds =
        getReviewableGlossaryTermIds(glossaryId, resolvedRequest.tmTextUnitIds());
    if (tmTextUnitIds.isEmpty()) {
      throw new IllegalArgumentException("No reviewable glossary terms found");
    }

    String name =
        resolvedRequest.name() == null || resolvedRequest.name().trim().isEmpty()
            ? "Terminology review"
            : resolvedRequest.name().trim();
    boolean hasSpecialists = hasSpecialistUserIds(resolvedRequest.specialistUserIds());
    ZonedDateTime specialistDueDate =
        resolveSpecialistDueDate(
            hasSpecialists, resolvedRequest.specialistDueDate(), resolvedRequest.dueDate());
    if (hasSpecialists && specialistDueDate == null) {
      specialistDueDate = ZonedDateTime.now().plusDays(2);
    }
    ZonedDateTime pmDueDate =
        resolvePmDueDate(
            hasSpecialists,
            specialistDueDate,
            resolvedRequest.pmDueDate(),
            resolvedRequest.dueDate());
    String notes =
        resolvedRequest.notes() == null || resolvedRequest.notes().trim().isEmpty()
            ? "Source terminology review for glossary " + glossaryId + "."
            : resolvedRequest.notes().trim();
    List<CreateReviewProjectRequestCommand.ProjectSpec> projectSpecs =
        buildTerminologyProjectSpecs(
            resolvedRequest.specialistUserIds(),
            resolvedRequest.pmUserId(),
            specialistDueDate,
            pmDueDate);

    return createReviewProjectRequestAsync(
        new CreateReviewProjectRequestCommand(
            List.of("en"),
            notes,
            tmTextUnitIds,
            null,
            StatusFilter.ALL,
            false,
            ReviewProjectType.TERMINOLOGY,
            hasSpecialists ? specialistDueDate : pmDueDate,
            List.of(),
            name,
            resolvedRequest.teamId(),
            resolvedRequest.assignTranslator() == null ? false : resolvedRequest.assignTranslator(),
            null,
            projectSpecs));
  }

  public PollableFuture<CreateReviewProjectRequestResult>
      createGlossaryTermCandidateReviewProjectAsync(
          Long glossaryId, CreateGlossaryTermCandidateReviewProjectCommand request) {
    CreateGlossaryTermCandidateReviewProjectCommand resolvedRequest =
        request == null
            ? new CreateGlossaryTermCandidateReviewProjectCommand(
                null, null, null, null, null, null, null, null, null, null)
            : request;
    Long requestedByUserId = teamService.getCurrentUserIdOrThrow();
    if (resolvedRequest.teamId() != null) {
      teamService.assertUserCanAccessTeam(resolvedRequest.teamId(), requestedByUserId);
    }

    CreateGlossaryTermCandidateReviewProjectJobInput asyncRequest =
        new CreateGlossaryTermCandidateReviewProjectJobInput(
            glossaryId,
            resolvedRequest.name(),
            resolvedRequest.notes(),
            resolvedRequest.dueDate(),
            resolvedRequest.teamId(),
            resolvedRequest.assignTranslator(),
            resolvedRequest.termIndexCandidateIds(),
            resolvedRequest.specialistUserIds(),
            resolvedRequest.pmUserId(),
            resolvedRequest.specialistDueDate(),
            resolvedRequest.pmDueDate(),
            requestedByUserId);
    QuartzJobInfo<
            CreateGlossaryTermCandidateReviewProjectJobInput, CreateReviewProjectRequestResult>
        quartzJobInfo =
            QuartzJobInfo.newBuilder(ReviewProjectCreateGlossaryTermCandidateRequestJob.class)
                .withInlineInput(false)
                .withInput(asyncRequest)
                .withMessage("Create term candidate review project request")
                .build();
    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  public CreateReviewProjectRequestResult createGlossaryTermCandidateReviewProject(
      CreateGlossaryTermCandidateReviewProjectJobInput request) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
      CreateReviewProjectRequestResult result =
          createGlossaryTermCandidateReviewProjectNoTx(request);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  CreateReviewProjectRequestResult createGlossaryTermCandidateReviewProjectNoTx(
      CreateGlossaryTermCandidateReviewProjectJobInput request) {
    if (request == null) {
      throw new IllegalArgumentException("request must be provided");
    }
    if (CollectionUtils.isEmpty(request.termIndexCandidateIds())) {
      throw new IllegalArgumentException("termIndexCandidateIds must be provided");
    }
    if (request.requestedByUserId() == null) {
      throw new IllegalArgumentException("requestedByUserId must be provided");
    }

    Set<Long> requestedCandidateIds =
        request.termIndexCandidateIds().stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (requestedCandidateIds.isEmpty()) {
      throw new IllegalArgumentException("termIndexCandidateIds must be provided");
    }

    Set<Long> linkedCandidateIds =
        request.glossaryId() == null
            ? Set.of()
            : new HashSet<>(
                glossaryTermIndexLinkRepository.findLinkedTermIndexCandidateIdsByGlossaryId(
                    request.glossaryId(), requestedCandidateIds));
    Map<Long, TermIndexCandidate> candidatesById =
        termIndexCandidateRepository.findAllById(requestedCandidateIds).stream()
            .collect(Collectors.toMap(TermIndexCandidate::getId, Function.identity(), (a, b) -> a));
    List<Long> extractedTermIds =
        candidatesById.values().stream()
            .map(TermIndexCandidate::getTermIndexExtractedTerm)
            .filter(Objects::nonNull)
            .map(extractedTerm -> extractedTerm.getId())
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<Long, Long> representativeTmTextUnitIdByExtractedTermId =
        extractedTermIds.isEmpty()
            ? Map.of()
            : termIndexOccurrenceRepository
                .findRepresentativeTextUnitIdsByExtractedTermIdIn(extractedTermIds)
                .stream()
                .filter(row -> row.getTermIndexExtractedTermId() != null)
                .collect(
                    Collectors.toMap(
                        TermIndexOccurrenceRepository.RepresentativeTextUnitRow
                            ::getTermIndexExtractedTermId,
                        TermIndexOccurrenceRepository.RepresentativeTextUnitRow::getTmTextUnitId,
                        (first, ignored) -> first,
                        LinkedHashMap::new));

    List<TextUnitDTO> reviewCandidates = new ArrayList<>();
    for (Long candidateId : requestedCandidateIds) {
      if (linkedCandidateIds.contains(candidateId)) {
        continue;
      }
      TermIndexCandidate candidate = candidatesById.get(candidateId);
      if (candidate == null || candidate.getTermIndexExtractedTerm() == null) {
        continue;
      }
      Long tmTextUnitId =
          representativeTmTextUnitIdByExtractedTermId.get(
              candidate.getTermIndexExtractedTerm().getId());
      if (tmTextUnitId == null) {
        continue;
      }
      CandidateReviewTextUnitDTO dto =
          new CandidateReviewTextUnitDTO(candidate.getId(), request.glossaryId());
      dto.setTmTextUnitId(tmTextUnitId);
      dto.setName(candidate.getNormalizedKey());
      dto.setSource(candidate.getTerm());
      dto.setComment(candidate.getDefinition());
      reviewCandidates.add(dto);
    }
    if (reviewCandidates.isEmpty()) {
      throw new IllegalArgumentException("No reviewable term candidates found");
    }

    String name =
        request.name() == null || request.name().trim().isEmpty()
            ? "Term candidate review"
            : request.name().trim();
    boolean hasSpecialists = hasSpecialistUserIds(request.specialistUserIds());
    ZonedDateTime specialistDueDate =
        resolveSpecialistDueDate(hasSpecialists, request.specialistDueDate(), request.dueDate());
    if (hasSpecialists && specialistDueDate == null) {
      specialistDueDate = ZonedDateTime.now().plusDays(2);
    }
    ZonedDateTime pmDueDate =
        resolvePmDueDate(hasSpecialists, specialistDueDate, request.pmDueDate(), request.dueDate());
    String notes =
        request.notes() == null || request.notes().trim().isEmpty()
            ? request.glossaryId() == null
                ? "Term candidate quality review."
                : "Term candidate review for glossary " + request.glossaryId() + "."
            : request.notes().trim();
    List<CreateReviewProjectRequestCommand.ProjectSpec> projectSpecs =
        buildTerminologyProjectSpecs(
            request.specialistUserIds(), request.pmUserId(), specialistDueDate, pmDueDate);
    Locale sourceLocale = localeService.findByBcp47Tag("en");
    if (sourceLocale == null) {
      throw new IllegalArgumentException("Unknown locale: en");
    }

    LocalePlan localePlan =
        preparedLocalePlan(
            "en",
            sourceLocale,
            reviewCandidates,
            CollectionUtils.isEmpty(projectSpecs) ? 1 : projectSpecs.size(),
            null);
    PersistedReviewProjectRequest persisted =
        persistPreparedReviewProjectRequest(
            name,
            notes,
            List.of(),
            ReviewProjectType.TERM_CANDIDATE,
            hasSpecialists ? specialistDueDate : pmDueDate,
            request.teamId(),
            request.requestedByUserId(),
            request.assignTranslator(),
            projectSpecs,
            List.of(new LocaleCandidates(sourceLocale, reviewCandidates)));
    return buildCreateReviewProjectRequestResult(persisted, List.of("en"), List.of(localePlan));
  }

  private ZonedDateTime firstNonNull(ZonedDateTime first, ZonedDateTime second) {
    return first != null ? first : second;
  }

  private ZonedDateTime resolveSpecialistDueDate(
      boolean hasSpecialists, ZonedDateTime specialistDueDate, ZonedDateTime fallbackDueDate) {
    return hasSpecialists ? firstNonNull(specialistDueDate, fallbackDueDate) : null;
  }

  private ZonedDateTime resolvePmDueDate(
      boolean hasSpecialists,
      ZonedDateTime specialistDueDate,
      ZonedDateTime pmDueDate,
      ZonedDateTime fallbackDueDate) {
    if (pmDueDate != null) {
      return pmDueDate;
    }
    if (hasSpecialists) {
      return specialistDueDate.plusDays(1);
    }
    return fallbackDueDate == null ? ZonedDateTime.now().plusDays(2) : fallbackDueDate;
  }

  private boolean isTerminologyWorkflowType(ReviewProjectType type) {
    return type == ReviewProjectType.TERMINOLOGY || type == ReviewProjectType.TERM_CANDIDATE;
  }

  private List<CreateReviewProjectRequestCommand.ProjectSpec> buildTerminologyProjectSpecs(
      List<Long> specialistUserIds,
      Long pmUserId,
      ZonedDateTime specialistDueDate,
      ZonedDateTime pmDueDate) {
    List<CreateReviewProjectRequestCommand.ProjectSpec> specs = new ArrayList<>();
    List<Long> distinctSpecialistUserIds =
        specialistUserIds == null
            ? List.of()
            : specialistUserIds.stream().filter(Objects::nonNull).distinct().toList();
    for (Long specialistUserId : distinctSpecialistUserIds) {
      specs.add(
          new CreateReviewProjectRequestCommand.ProjectSpec(
              ReviewProjectTerminologyPhase.SPECIALIST_INPUT,
              specialistDueDate,
              pmUserId,
              specialistUserId));
    }
    specs.add(
        new CreateReviewProjectRequestCommand.ProjectSpec(
            ReviewProjectTerminologyPhase.PM_RESOLUTION, pmDueDate, pmUserId, null));
    return specs;
  }

  private boolean hasSpecialistUserIds(List<Long> specialistUserIds) {
    return specialistUserIds != null && specialistUserIds.stream().anyMatch(Objects::nonNull);
  }

  private List<Long> getReviewableGlossaryTermIds(
      Long glossaryId, List<Long> requestedTmTextUnitIds) {
    List<GlossaryTermMetadata> metadata =
        CollectionUtils.isEmpty(requestedTmTextUnitIds)
            ? glossaryTermMetadataRepository.findByGlossaryId(glossaryId)
            : glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(
                glossaryId, requestedTmTextUnitIds);

    return metadata.stream()
        .filter(this::isReviewableGlossaryTerm)
        .map(GlossaryTermMetadata::getTmTextUnit)
        .filter(Objects::nonNull)
        .map(TMTextUnit::getId)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
  }

  private boolean isReviewableGlossaryTerm(GlossaryTermMetadata metadata) {
    String status =
        metadata.getStatus() == null
            ? GlossaryTermMetadata.STATUS_CANDIDATE
            : metadata.getStatus().trim().toUpperCase(java.util.Locale.ROOT);
    return !GlossaryTermMetadata.STATUS_REJECTED.equals(status)
        && !GlossaryTermMetadata.STATUS_DEPRECATED.equals(status);
  }

  public CreateReviewProjectRequestResult createReviewProjectRequest(
      CreateReviewProjectRequestCommand request) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
      CreateReviewProjectRequestResult result = createReviewProjectRequestNoTx(request);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  CreateReviewProjectRequestResult createReviewProjectRequestNoTx(
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
    boolean hasRepositoryIds =
        request.repositoryIds() != null && !request.repositoryIds().isEmpty();
    int sourceCount =
        (hasTmTextUnitIds ? 1 : 0) + (hasReviewFeatureId ? 1 : 0) + (hasRepositoryIds ? 1 : 0);
    if (sourceCount != 1) {
      throw new IllegalArgumentException(
          "Exactly one of tmTextUnitIds, reviewFeatureId, or repositoryIds must be provided");
    }

    if (request.type() == null) {
      throw new IllegalArgumentException("type must be provided");
    }

    if (request.requestedByUserId() == null) {
      throw new IllegalArgumentException("requestedByUserId must be provided");
    }

    logger.info(
        "Create review project request: name='{}', teamId={}, requestedLocales={}, tmTextUnitCount={}, reviewFeatureId={}, repositoryIdCount={}, statusFilter={}, skipTextUnitsInOpenProjects={}",
        request.name(),
        request.teamId(),
        request.localeTags(),
        hasTmTextUnitIds ? request.tmTextUnitIds().size() : null,
        request.reviewFeatureId(),
        hasRepositoryIds ? request.repositoryIds().size() : null,
        request.statusFilter(),
        Boolean.TRUE.equals(request.skipTextUnitsInOpenProjects()));

    int projectCountPerPreparedLocale =
        CollectionUtils.isEmpty(request.projectSpecs()) ? 1 : request.projectSpecs().size();
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
                ? getTextUnitReviewCandidates(request, locale)
                : hasReviewFeatureId
                    ? searchReviewFeatureCandidates(
                        reviewFeature,
                        locale,
                        getManualRepositoryScopeStatusFilter(request.statusFilter()))
                    : searchRepositoryReviewCandidates(
                        request.repositoryIds(),
                        locale,
                        getManualRepositoryScopeStatusFilter(request.statusFilter()));
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

        localePlans.add(
            preparedLocalePlan(
                locale.getBcp47Tag(), locale, candidates, projectCountPerPreparedLocale, null));
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
            request.projectSpecs(),
            localesToCreate);

    return buildCreateReviewProjectRequestResult(
        persistedReviewProjectRequest, request.localeTags(), localePlans);
  }

  public CreateReviewProjectRequestResult createAutomatedReviewProjectRequest(
      CreateAutomatedReviewProjectRequestCommand request) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
      CreateReviewProjectRequestResult result = createAutomatedReviewProjectRequestNoTx(request);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  CreateReviewProjectRequestResult createAutomatedReviewProjectRequestNoTx(
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
    List<ReviewFeatureLocaleRow> reviewFeatureLocales =
        getReviewFeatureLocales(reviewFeature.getId());
    int maxWordCountPerProject =
        request.maxWordCountPerProject() == null
            ? Integer.MAX_VALUE
            : request.maxWordCountPerProject();
    if (maxWordCountPerProject < 1) {
      throw new IllegalArgumentException("maxWordCountPerProject must be positive");
    }

    List<LocalePlan> localePlans = new ArrayList<>();
    for (ReviewFeatureLocaleRow locale : reviewFeatureLocales) {
      try {
        List<TextUnitDTO> candidates =
            searchReviewFeatureCandidates(reviewFeature, locale.id(), StatusFilter.REVIEW_NEEDED);
        candidates = excludeOpenReviewProjectTextUnits(candidates, locale.id());
        if (candidates.isEmpty()) {
          localePlans.add(skippedLocalePlan(locale.bcp47Tag()));
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
        Locale localeReference = entityManager.getReference(Locale.class, locale.id());
        localePlans.add(
            preparedLocalePlan(locale.bcp47Tag(), localeReference, candidates, chunkCount, null));
        logger.info(
            "Prepared automated review project locale '{}' for feature '{}' with {} candidates across {} chunk(s)",
            locale.bcp47Tag(),
            reviewFeature.getName(),
            candidates.size(),
            chunkCount);
      } catch (RuntimeException e) {
        logger.warn(
            "Failed to prepare automated review project locale '{}' for feature '{}': {}",
            locale.bcp47Tag(),
            reviewFeature.getName(),
            e.getMessage());
        localePlans.add(errorLocalePlan(locale.bcp47Tag(), e.getMessage()));
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
          reviewFeatureLocales.stream().map(ReviewFeatureLocaleRow::bcp47Tag).toList(),
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
            null,
            localesToCreate);

    return buildCreateReviewProjectRequestResult(
        persistedReviewProjectRequest,
        reviewFeatureLocales.stream().map(ReviewFeatureLocaleRow::bcp47Tag).toList(),
        localePlans);
  }

  private record LocaleCandidates(Locale locale, List<TextUnitDTO> candidates) {}

  private static class CandidateReviewTextUnitDTO extends TextUnitDTO {
    private final Long termIndexCandidateId;
    private final Long targetGlossaryId;

    CandidateReviewTextUnitDTO(Long termIndexCandidateId, Long targetGlossaryId) {
      this.termIndexCandidateId = termIndexCandidateId;
      this.targetGlossaryId = targetGlossaryId;
    }

    Long termIndexCandidateId() {
      return termIndexCandidateId;
    }

    Long targetGlossaryId() {
      return targetGlossaryId;
    }
  }

  private record ResolvedProjectSpec(
      ReviewProjectTerminologyPhase terminologyPhase,
      ZonedDateTime dueDate,
      User assignedPmUser,
      User assignedTranslatorUser) {}

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

  public SearchReviewProjectsView searchReviewProjects(SearchReviewProjectsCriteria request) {
    TransactionStatus transaction = transactionManager.getTransaction(readOnlyTransaction());
    try {
      SearchReviewProjectsView result = searchReviewProjectsNoTx(request);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  SearchReviewProjectsView searchReviewProjectsNoTx(SearchReviewProjectsCriteria request) {
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

  public SearchReviewProjectRequestsView searchReviewProjectRequests(
      SearchReviewProjectsCriteria request) {
    TransactionStatus transaction = transactionManager.getTransaction(readOnlyTransaction());
    try {
      SearchReviewProjectRequestsView result = searchReviewProjectRequestsNoTx(request);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  SearchReviewProjectRequestsView searchReviewProjectRequestsNoTx(
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
      long decidedWordCount =
          sortedProjects.stream()
              .mapToLong(project -> Optional.ofNullable(project.decidedWordCount()).orElse(0L))
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
              decidedWordCount,
              dueDate,
              sortedProjects));
    }

    recordSearchPhase(SEARCH_MODE_REQUESTS, "groupResponse", phaseStopwatch, groups.size());
    recordSearchPhase(SEARCH_MODE_REQUESTS, "total", totalStopwatch, groups.size());
    return new SearchReviewProjectRequestsView(groups);
  }

  private void recordSearchPhase(String mode, String phase, Stopwatch stopwatch, int resultCount) {
    Duration elapsed = stopwatch.elapsed();
    long elapsedMillis = elapsed.toMillis();
    Tags tags = Tags.of("mode", mode, "phase", phase);
    meterRegistry.timer(metricName("searchPhaseDuration"), tags).record(elapsed);
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

  private void recordSaveDecisionPhase(
      String phase,
      String result,
      Long reviewProjectTextUnitId,
      boolean hasTarget,
      Stopwatch stopwatch) {
    Duration elapsed = stopwatch.elapsed();
    long elapsedMillis = elapsed.toMillis();
    Tags tags = Tags.of("phase", phase, "result", result, "hasTarget", Boolean.toString(hasTarget));
    meterRegistry.timer(metricName("saveDecisionDuration"), tags).record(elapsed);

    long slowLogThresholdMillis =
        "total".equals(phase)
            ? SAVE_DECISION_TOTAL_SLOW_LOG_THRESHOLD_MS
            : SAVE_DECISION_PHASE_SLOW_LOG_THRESHOLD_MS;
    if (elapsedMillis >= slowLogThresholdMillis) {
      logger.info(
          "Review project save decision phase completed: phase={}, result={}, elapsedMs={}, reviewProjectTextUnitId={}",
          phase,
          result,
          elapsedMillis,
          reviewProjectTextUnitId);
    } else {
      logger.debug(
          "Review project save decision phase completed: phase={}, result={}, elapsedMs={}, reviewProjectTextUnitId={}",
          phase,
          result,
          elapsedMillis,
          reviewProjectTextUnitId);
    }
  }

  private String getSaveDecisionResultTag(RuntimeException exception) {
    if (exception instanceof ReviewProjectCurrentVariantConflictException) {
      return "conflict";
    }
    if (exception instanceof AccessDeniedException) {
      return "forbidden";
    }
    if (exception instanceof IllegalArgumentException) {
      return "bad_request";
    }
    return "error";
  }

  private <T> T timeSaveDecisionPhase(
      String phase, Long reviewProjectTextUnitId, boolean hasTarget, Supplier<T> operation) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    String result = "success";
    try {
      return operation.get();
    } catch (RuntimeException exception) {
      result = getSaveDecisionResultTag(exception);
      throw exception;
    } finally {
      recordSaveDecisionPhase(phase, result, reviewProjectTextUnitId, hasTarget, stopwatch);
    }
  }

  private void runSaveDecisionPhase(
      String phase, Long reviewProjectTextUnitId, boolean hasTarget, Runnable operation) {
    timeSaveDecisionPhase(
        phase,
        reviewProjectTextUnitId,
        hasTarget,
        () -> {
          operation.run();
          return null;
        });
  }

  private record SaveDecisionInitialRead(
      ReviewProjectTextUnit textUnit,
      ReviewProject project,
      User currentUser,
      boolean isTranslator,
      TMTextUnitVariant baselineVariant,
      TMTextUnit tmTextUnit,
      TMTextUnitCurrentVariant currentVariant,
      TMTextUnitVariant currentTmTextUnitVariant,
      Optional<ReviewProjectTextUnitDecision> existingDecision,
      boolean wasDecided) {}

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
                root.get(ReviewProject_.decidedWordCount),
                root.get(ReviewProject_.type),
                root.get(ReviewProject_.terminologyPhase),
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
        .orderBy(reviewProjectSearchOrder(cb, root, request, accessContext));

    TypedQuery<SearchReviewProjectDetail> query = entityManager.createQuery(cq);
    query.setMaxResults(request.limit());

    return query.getResultList();
  }

  private List<jakarta.persistence.criteria.Order> reviewProjectSearchOrder(
      CriteriaBuilder cb,
      Root<ReviewProject> root,
      SearchReviewProjectsCriteria request,
      ProjectAccessContext accessContext) {
    if (!shouldUseTranslatorPriorityOrder(request, accessContext)) {
      return List.of(cb.desc(root.get(ReviewProject_.id)));
    }
    return List.of(
        // PM request I18N-98 asks for type priority first, then due date. If overdue work needs to
        // outrank future emergency work later, add reviewProjectOverduePriority before type.
        cb.asc(reviewProjectTypePriority(cb, root)),
        cb.asc(cb.selectCase().when(cb.isNull(root.get(ReviewProject_.dueDate)), 1).otherwise(0)),
        cb.asc(root.get(ReviewProject_.dueDate)),
        cb.desc(root.get(ReviewProject_.id)));
  }

  private boolean shouldUseTranslatorPriorityOrder(
      SearchReviewProjectsCriteria request, ProjectAccessContext accessContext) {
    SearchReviewProjectsCriteria.AssignedScope assignedScope =
        request.assignedScope() != null
            ? request.assignedScope()
            : SearchReviewProjectsCriteria.AssignedScope.TO_ME;
    return accessContext.translator()
        && !accessContext.admin()
        && assignedScope == SearchReviewProjectsCriteria.AssignedScope.TO_ME;
  }

  private jakarta.persistence.criteria.Expression<Integer> reviewProjectTypePriority(
      CriteriaBuilder cb, Root<ReviewProject> root) {
    return cb.<ReviewProjectType, Integer>selectCase(root.get(ReviewProject_.type))
        .when(ReviewProjectType.EMERGENCY, 0)
        .when(ReviewProjectType.NORMAL, 1)
        .when(ReviewProjectType.BUG_FIXES, 2)
        .when(ReviewProjectType.TERMINOLOGY, 3)
        .when(ReviewProjectType.TERM_CANDIDATE, 3)
        .otherwise(4);
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
        .orderBy(reviewProjectRequestSearchOrder(cb, root, requestJoin, request, accessContext));

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
    if (request.hasTeamFilter()) {
      predicates.add(teamJoin.get(Team_.id).in(request.teamIds()));
    }
    predicates.add(
        buildScopePredicate(
            cb,
            root,
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
                root.get(ReviewProject_.decidedWordCount),
                root.get(ReviewProject_.type),
                root.get(ReviewProject_.terminologyPhase),
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

  private List<jakarta.persistence.criteria.Order> reviewProjectRequestSearchOrder(
      CriteriaBuilder cb,
      Root<ReviewProject> root,
      Join<ReviewProject, ReviewProjectRequest> requestJoin,
      SearchReviewProjectsCriteria request,
      ProjectAccessContext accessContext) {
    if (!shouldUseTranslatorPriorityOrder(request, accessContext)) {
      return List.of(cb.desc(requestJoin.get(ReviewProjectRequest_.id)));
    }
    return List.of(
        cb.asc(cb.min(reviewProjectTypePriority(cb, root))),
        cb.asc(
            cb.min(
                cb.<Integer>selectCase()
                    .when(cb.isNull(root.get(ReviewProject_.dueDate)), 1)
                    .otherwise(0))),
        cb.asc(cb.least(root.get(ReviewProject_.dueDate))),
        cb.desc(requestJoin.get(ReviewProjectRequest_.id)));
  }

  private List<SearchReviewProjectsView.ReviewProject> toReviewProjectViews(
      List<SearchReviewProjectDetail> projectDetails) {
    if (projectDetails == null || projectDetails.isEmpty()) {
      return List.of();
    }

    List<ReviewProjectAssignmentWindow> openWindows =
        Optional.ofNullable(
                reviewProjectAssignmentWindowRepository.findOpenWindowsByReviewProjectIds(
                    projectDetails.stream().map(SearchReviewProjectDetail::id).toList()))
            .orElse(List.of());
    Map<Long, ReviewProjectAssignmentWindow> openWindowsByProjectId =
        openWindows.stream()
            .collect(
                Collectors.toMap(
                    window -> window.getReviewProject().getId(),
                    Function.identity(),
                    (first, ignored) -> first));

    return projectDetails.stream()
        .map(
            detail -> {
              ReviewProjectAssignmentWindow assignmentWindow =
                  openWindowsByProjectId.get(detail.id());
              return new SearchReviewProjectsView.ReviewProject(
                  detail.id(),
                  detail.createdDate(),
                  detail.lastModifiedDate(),
                  detail.dueDate(),
                  detail.closeReason(),
                  detail.textUnitCount(),
                  detail.wordCount(),
                  Optional.ofNullable(detail.decidedCount()).orElse(0L),
                  Optional.ofNullable(detail.decidedWordCount()).orElse(0L),
                  detail.type(),
                  detail.terminologyPhase(),
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
                      detail.assignedTranslatorUsername(),
                      assignmentWindow == null ? null : assignmentWindow.getId(),
                      assignmentWindow == null ? null : assignmentWindow.getAcceptedAt()));
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
      predicates.add(buildLocaleFilterPredicate(cb, root, localeJoin, request.localeTags()));
    }

    if (request.hasTeamFilter()) {
      predicates.add(teamJoin.get(Team_.id).in(request.teamIds()));
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
            root,
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
      Root<ReviewProject> root,
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
      if (accessContext.pm() || accessContext.translator()) {
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
            buildTranslatorLocaleAccessPredicate(cb, root, localeJoin, accessContext);
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

  private Predicate buildLocaleFilterPredicate(
      CriteriaBuilder cb,
      Root<ReviewProject> root,
      Join<ReviewProject, Locale> localeJoin,
      List<String> localeTags) {
    return cb.or(
        localeJoin.get(Locale_.bcp47Tag).in(localeTags), buildTerminologyWorkflowPredicate(root));
  }

  private Predicate buildTranslatorLocaleAccessPredicate(
      CriteriaBuilder cb,
      Root<ReviewProject> root,
      Join<ReviewProject, Locale> localeJoin,
      ProjectAccessContext accessContext) {
    if (accessContext.canTranslateAllLocales()) {
      return cb.conjunction();
    }
    Predicate terminologyPredicate = buildTerminologyWorkflowPredicate(root);
    if (accessContext.editableLocaleIds().isEmpty()) {
      return terminologyPredicate;
    }
    return cb.or(
        localeJoin.get(Locale_.id).in(accessContext.editableLocaleIds()), terminologyPredicate);
  }

  private Predicate buildTerminologyWorkflowPredicate(Root<ReviewProject> root) {
    return root.get(ReviewProject_.type)
        .in(ReviewProjectType.TERMINOLOGY, ReviewProjectType.TERM_CANDIDATE);
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

    if (Objects.equals(assignedPmUserId, accessContext.userId())) {
      return true;
    }

    if (accessContext.pm()) {
      if (teamId != null && accessContext.pmTeamIds().contains(teamId)) {
        return true;
      }
    }

    if (accessContext.translator()) {
      if (Objects.equals(assignedTranslatorUserId, accessContext.userId())) {
        return true;
      }
      boolean localeAllowed =
          isTerminologyWorkflowType(reviewProject.getType())
              || accessContext.canTranslateAllLocales()
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

    return switch (matchType) {
      case EXACT -> cb.equal(expression, searchQuery);
      case CONTAINS -> {
        String pattern = "%" + escapeForLike(searchQuery) + "%";
        yield cb.like(expression, pattern, '\\');
      }
      case ILIKE -> cb.like(cb.lower(expression), searchQuery.toLowerCase(), '\\');
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

  public GetProjectDetailView getProjectDetail(Long projectId) {
    TransactionStatus transaction = transactionManager.getTransaction(readOnlyTransaction());
    try {
      GetProjectDetailView result = getProjectDetailNoTx(projectId);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  GetProjectDetailView getProjectDetailNoTx(Long projectId) {
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
    Map<Long, List<ReviewProjectTextUnitFeedback>> feedbacksByReviewProjectTextUnitId =
        getTerminologyFeedbacksByReviewProjectTextUnitId(reviewProject, textUnitDetails);
    Map<Long, GetProjectDetailView.TerminologyTerm> terminologyTermsByReviewProjectTextUnitId =
        getTerminologyTermsByReviewProjectTextUnitId(reviewProject, textUnitDetails);
    Map<Long, List<GetProjectDetailView.TerminologyTermEvidence>>
        glossaryTermEvidenceByReviewProjectTextUnitId =
            getGlossaryTermEvidenceByReviewProjectTextUnitId(textUnitDetails);

    List<GetProjectDetailView.ReviewProjectTextUnit> reviewProjectTextUnits =
        textUnitDetails.stream()
            .map(
                detail ->
                    toReviewProjectTextUnit(
                        detail,
                        feedbacksByReviewProjectTextUnitId.getOrDefault(
                            detail.reviewProjectTextUnitId(), List.of()),
                        terminologyTermsByReviewProjectTextUnitId.get(
                            detail.reviewProjectTextUnitId()),
                        glossaryTermEvidenceByReviewProjectTextUnitId.getOrDefault(
                            detail.reviewProjectTextUnitId(), List.of())))
            .toList();

    List<String> screenshotImageIds =
        project.reviewProjectRequestId() == null
            ? List.of()
            : reviewProjectScreenshotRepository.findImageNamesByReviewProjectRequestId(
                project.reviewProjectRequestId());

    return new GetProjectDetailView(
        project.id(),
        project.type(),
        project.terminologyPhase(),
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
            project.assignedTranslatorUsername(),
            project.assignmentWindowId(),
            project.assignmentAcceptedAt(),
            project.selfReportedMinutes(),
            project.selfReportedNote()),
        reviewProjectTextUnits);
  }

  public GetProjectDetailView updateProjectStatus(
      Long projectId, ReviewProjectStatus status, String closeReason) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
      GetProjectDetailView result = updateProjectStatusNoTx(projectId, status, closeReason);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  GetProjectDetailView updateProjectStatusNoTx(
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
    if (status == ReviewProjectStatus.CLOSED
        && reviewProject.getStatus() == ReviewProjectStatus.OPEN
        && !userService.isCurrentUserAdminOrPm()
        && userService.isCurrentUserTranslator()
        && isProjectIncomplete(reviewProject)) {
      throw new AccessDeniedException(
          "Translators can only close projects after they are 100% complete");
    }

    ReviewProjectStatus previousStatus = reviewProject.getStatus();
    reviewProject.setStatus(status);
    if (status == ReviewProjectStatus.OPEN) {
      reviewProject.setCloseReason(null);
    } else if (closeReason != null) {
      String trimmed = closeReason.trim();
      reviewProject.setCloseReason(trimmed.isEmpty() ? null : trimmed);
    }

    reviewProjectRepository.save(reviewProject);
    if (previousStatus == ReviewProjectStatus.OPEN && status == ReviewProjectStatus.CLOSED) {
      reviewProjectAssignmentWindowService.closeOpenWindow(
          reviewProject, ReviewProjectAssignmentWindowEndReason.PROJECT_CLOSED);
      reviewProjectTimeSpentStatService.computeProjectStats(reviewProject, ZonedDateTime.now());
    } else if (previousStatus == ReviewProjectStatus.CLOSED && status == ReviewProjectStatus.OPEN) {
      reviewProjectAssignmentWindowService.syncTranslatorAssignmentWindow(
          reviewProject, null, reviewProject.getAssignedTranslatorUser());
    }
    return getProjectDetailNoTx(projectId);
  }

  private boolean isProjectIncomplete(ReviewProject reviewProject) {
    long textUnitCount = Optional.ofNullable(reviewProject.getTextUnitCount()).orElse(0);
    long decidedCount = Optional.ofNullable(reviewProject.getDecidedCount()).orElse(0L);
    return decidedCount < textUnitCount;
  }

  public GetProjectDetailView updateProjectRequest(
      Long projectId,
      String name,
      String notes,
      ReviewProjectType type,
      ZonedDateTime dueDate,
      List<String> screenshotImageIds,
      Long teamId,
      Boolean updateTeam) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
      GetProjectDetailView result =
          updateProjectRequestNoTx(
              projectId, name, notes, type, dueDate, screenshotImageIds, teamId, updateTeam);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  GetProjectDetailView updateProjectRequestNoTx(
      Long projectId,
      String name,
      String notes,
      ReviewProjectType type,
      ZonedDateTime dueDate,
      List<String> screenshotImageIds,
      Long teamId,
      Boolean updateTeam) {
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

    boolean shouldUpdateTeam = Boolean.TRUE.equals(updateTeam);
    if (shouldUpdateTeam && !userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Only admins can change assigned team");
    }
    Team nextTeam = shouldUpdateTeam ? resolveTeam(teamId) : null;
    boolean assignmentChanged = false;

    if (type != null || dueDate != null || shouldUpdateTeam) {
      List<ReviewProject> projects =
          reviewProjectRepository.findByRequestIdWithAssignment(request.getId());
      for (ReviewProject project : projects) {
        if (type != null) {
          project.setType(type);
        }
        if (dueDate != null) {
          project.setDueDate(dueDate);
        }
        if (shouldUpdateTeam) {
          Team previousTeam = project.getTeam();
          User previousAssignedPm = project.getAssignedPmUser();
          User previousAssignedTranslator = project.getAssignedTranslatorUser();
          boolean teamChanged = !Objects.equals(getEntityId(previousTeam), getEntityId(nextTeam));
          if (teamChanged) {
            project.setTeam(nextTeam);
            project.setAssignedPmUser(null);
            project.setAssignedTranslatorUser(null);

            boolean hadAssignment =
                previousTeam != null
                    || previousAssignedPm != null
                    || previousAssignedTranslator != null;
            ReviewProjectAssignmentEventType eventType;
            if (!hadAssignment && nextTeam != null) {
              eventType = ReviewProjectAssignmentEventType.ASSIGNED;
            } else if (nextTeam == null) {
              eventType = ReviewProjectAssignmentEventType.UNASSIGNED;
            } else {
              eventType = ReviewProjectAssignmentEventType.REASSIGNED;
            }
            reviewProjectAssignmentWindowService.syncTranslatorAssignmentWindow(
                project, previousAssignedTranslator, project.getAssignedTranslatorUser());
            recordAssignmentHistory(project, eventType, "Request team changed");
            assignmentChanged = true;
          }
        }
      }
      if (assignmentChanged) {
        teamSlackNotificationService.sendReviewProjectRequestAssignmentNotification(
            request, projects);
      }
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

    return getProjectDetailNoTx(projectId);
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

    boolean isTranslatorOnly = !isAdmin && !isPm && isTranslator;

    if (isTranslatorOnly) {
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
        isTranslatorOnly,
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
    reviewProjectAssignmentWindowService.syncTranslatorAssignmentWindow(
        reviewProject, previousAssignedTranslator, nextAssignedTranslator);

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
  public GetProjectDetailView claimProjectTranslatorAssignment(Long projectId) {
    if (!userService.isCurrentUserTranslator()) {
      throw new AccessDeniedException("Translator role required to claim assignment");
    }

    ReviewProject reviewProject =
        reviewProjectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "reviewProject with id: " + projectId + " not found"));
    Long teamId = reviewProject.getTeam() != null ? reviewProject.getTeam().getId() : null;
    Long assignedPmUserId =
        reviewProject.getAssignedPmUser() != null
            ? reviewProject.getAssignedPmUser().getId()
            : null;
    return updateProjectAssignment(
        projectId, teamId, assignedPmUserId, teamService.getCurrentUserIdOrThrow(), null);
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
    boolean terminologyRequest =
        projects.stream().allMatch(project -> isTerminologyWorkflowType(project.getType()));
    if (nextAssignedPm != null) {
      boolean isPmMember =
          teamService.isUserInTeamRole(teamId, nextAssignedPm.getId(), TeamUserRole.PM);
      boolean isTranslatorMember =
          terminologyRequest
              && teamService.isUserInTeamRole(
                  teamId, nextAssignedPm.getId(), TeamUserRole.TRANSLATOR);
      if (!isPmMember && !isTranslatorMember) {
        throw new IllegalArgumentException(
            terminologyRequest
                ? "Assigned decider is not a PM or translator member of team "
                    + teamId
                    + ": "
                    + nextAssignedPm.getId()
                : "Assigned PM is not a PM member of team "
                    + teamId
                    + ": "
                    + nextAssignedPm.getId());
      }
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
    reviewProjectTimeSpentStatService.deleteByReviewProjectIds(distinctIds);
    reviewProjectAssignmentWindowRepository.deleteByReviewProjectIds(distinctIds);
    reviewProjectTextUnitDecisionRepository.deleteByReviewProjectIds(distinctIds);
    reviewProjectTextUnitFeedbackRepository.deleteByReviewProjectIds(distinctIds);
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

    Stopwatch totalStopwatch = Stopwatch.createStarted();
    String totalResult = "success";
    boolean hasTarget = target != null;
    try {
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

      SaveDecisionInitialRead initialRead =
          timeSaveDecisionPhase(
              "initialRead",
              reviewProjectTextUnitId,
              hasTarget,
              () -> {
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
                        .orElseThrow(
                            () -> new IllegalStateException("Authenticated user not found"));
                boolean isTranslator = userService.isCurrentUserTranslator();

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
                return new SaveDecisionInitialRead(
                    textUnit,
                    project,
                    currentUser,
                    isTranslator,
                    baselineVariant,
                    tmTextUnit,
                    currentVariant,
                    currentTmTextUnitVariant,
                    existingDecision,
                    wasDecided);
              });

      ReviewProjectTextUnit textUnit = initialRead.textUnit();
      ReviewProject project = initialRead.project();
      User currentUser = initialRead.currentUser();
      boolean isTranslator = initialRead.isTranslator();
      TMTextUnitVariant baselineVariant = initialRead.baselineVariant();
      TMTextUnit tmTextUnit = initialRead.tmTextUnit();
      TMTextUnitCurrentVariant currentVariant = initialRead.currentVariant();
      TMTextUnitVariant currentTmTextUnitVariant = initialRead.currentTmTextUnitVariant();
      Optional<ReviewProjectTextUnitDecision> existingDecision = initialRead.existingDecision();
      boolean wasDecided = initialRead.wasDecided();
      if (!hasTarget && decisionState == DecisionState.PENDING && existingDecision.isEmpty()) {
        totalResult = "noop";
        return timeSaveDecisionPhase(
            "detailReload",
            reviewProjectTextUnitId,
            hasTarget,
            () -> fetchReviewProjectTextUnitDetail(reviewProjectTextUnitId));
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
        if (isTranslator) {
          Stopwatch integrityCheckStopwatch = Stopwatch.createStarted();
          try {
            tmTextUnitIntegrityCheckService.checkTMTextUnitIntegrity(
                tmTextUnit.getId(), normalizedTarget);
            recordSaveDecisionPhase(
                "integrityCheck",
                "success",
                reviewProjectTextUnitId,
                hasTarget,
                integrityCheckStopwatch);
          } catch (IntegrityCheckException e) {
            recordSaveDecisionPhase(
                "integrityCheck",
                "failure",
                reviewProjectTextUnitId,
                hasTarget,
                integrityCheckStopwatch);
            throw new AccessDeniedException(TRANSLATOR_INTEGRITY_BYPASS_DENIED_MESSAGE);
          }
        }
        TMTextUnitVariant.Status statusEnum = TMTextUnitVariant.Status.valueOf(status);

        AddTMTextUnitCurrentVariantResult addResult =
            timeSaveDecisionPhase(
                "currentVariantWrite",
                reviewProjectTextUnitId,
                hasTarget,
                () ->
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
                        currentUser));
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
      runSaveDecisionPhase(
          "decisionWrite",
          reviewProjectTextUnitId,
          hasTarget,
          () -> {
            reviewProjectAssignmentWindowService.acceptCurrentAssignmentIfAssignedTranslator(
                project);
            reviewProjectTextUnitDecisionRepository.saveAndFlush(decision);
          });

      runSaveDecisionPhase(
          "decidedCountUpdate",
          reviewProjectTextUnitId,
          hasTarget,
          () ->
              updateProjectDecidedCount(
                  project.getId(),
                  getWordCount(textUnit),
                  wasDecided,
                  decisionState == DecisionState.DECIDED));

      ReviewProjectTextUnitDetail detail =
          timeSaveDecisionPhase(
              "detailReload",
              reviewProjectTextUnitId,
              hasTarget,
              () -> fetchReviewProjectTextUnitDetail(reviewProjectTextUnitId));
      return detail;
    } catch (RuntimeException exception) {
      totalResult = getSaveDecisionResultTag(exception);
      throw exception;
    } finally {
      recordSaveDecisionPhase(
          "total", totalResult, reviewProjectTextUnitId, hasTarget, totalStopwatch);
    }
  }

  @Transactional
  public GetProjectDetailView.ReviewProjectTextUnit saveTerminologyFeedback(
      Long reviewProjectTextUnitId,
      Recommendation recommendation,
      Integer confidence,
      String notes) {
    if (recommendation == null) {
      throw new IllegalArgumentException("recommendation is required");
    }
    if (confidence != null && (confidence < 1 || confidence > 5)) {
      throw new IllegalArgumentException("confidence must be between 1 and 5");
    }

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
    if (!isTerminologyWorkflowType(project.getType())) {
      throw new IllegalArgumentException(
          "Terminology feedback is only supported for terminology projects");
    }
    if (project.getTerminologyPhase() == ReviewProjectTerminologyPhase.PM_RESOLUTION) {
      throw new IllegalArgumentException(
          "Terminology feedback is only supported for specialist input projects");
    }

    Long currentUserId = teamService.getCurrentUserIdOrThrow();
    User currentUser =
        userRepository
            .findById(currentUserId)
            .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

    ReviewProjectTextUnitFeedback feedback =
        reviewProjectTextUnitFeedbackRepository
            .findByReviewProjectTextUnitIdAndReviewerUserId(reviewProjectTextUnitId, currentUserId)
            .orElseGet(
                () -> {
                  ReviewProjectTextUnitFeedback entity = new ReviewProjectTextUnitFeedback();
                  entity.setReviewProjectTextUnit(textUnit);
                  entity.setReviewerUser(currentUser);
                  return entity;
                });
    boolean wasDecided =
        project.getTerminologyPhase() == ReviewProjectTerminologyPhase.SPECIALIST_INPUT
            && reviewProjectTextUnitFeedbackRepository.existsByReviewProjectTextUnitId(
                reviewProjectTextUnitId);
    feedback.setRecommendation(recommendation);
    feedback.setConfidence(confidence);
    feedback.setNotes(truncate(normalizeOptional(notes), 4000));
    reviewProjectTextUnitFeedbackRepository.saveAndFlush(feedback);
    if (project.getTerminologyPhase() == ReviewProjectTerminologyPhase.SPECIALIST_INPUT) {
      reviewProjectAssignmentWindowService.acceptCurrentAssignmentIfAssignedTranslator(project);
      updateProjectDecidedCount(project.getId(), getWordCount(textUnit), wasDecided, true);
    }

    return fetchReviewProjectTextUnitWithFeedback(reviewProjectTextUnitId, project);
  }

  @Transactional
  public GetProjectDetailView.ReviewProjectTextUnit saveTerminologyResolution(
      Long reviewProjectTextUnitId,
      Long glossaryId,
      String status,
      String notes,
      Boolean promoteToGlossary) {
    String normalizedStatus = normalizeOptional(status);
    if (normalizedStatus == null) {
      throw new IllegalArgumentException("status is required");
    }
    normalizedStatus = normalizedStatus.toUpperCase(java.util.Locale.ROOT);
    if (!Set.of(
            GlossaryTermMetadata.STATUS_APPROVED,
            GlossaryTermMetadata.STATUS_CANDIDATE,
            GlossaryTermMetadata.STATUS_REJECTED)
        .contains(normalizedStatus)) {
      throw new IllegalArgumentException("Unsupported terminology status: " + status);
    }

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
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException("Only admins and PMs can resolve terminology");
    }
    if (!isTerminologyWorkflowType(project.getType())) {
      throw new IllegalArgumentException(
          "Terminology resolution is only supported for terminology projects");
    }
    if (project.getTerminologyPhase() == ReviewProjectTerminologyPhase.SPECIALIST_INPUT) {
      throw new IllegalArgumentException(
          "Terminology resolution is only supported for PM resolution projects");
    }

    Long resolvedGlossaryId =
        glossaryId != null
            ? glossaryId
            : textUnit.getTargetGlossary() == null ? null : textUnit.getTargetGlossary().getId();

    if (project.getType() == ReviewProjectType.TERM_CANDIDATE
        && textUnit.getTermIndexCandidate() != null) {
      resolveTermCandidateReview(
          textUnit,
          resolvedGlossaryId,
          normalizedStatus,
          notes,
          promoteToGlossary == null ? true : promoteToGlossary);
    } else {
      if (resolvedGlossaryId == null) {
        throw new IllegalArgumentException("glossaryId is required");
      }
      GlossaryTermMetadata metadata =
          glossaryTermMetadataRepository
              .findByGlossaryIdAndTmTextUnitId(resolvedGlossaryId, textUnit.getTmTextUnit().getId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Glossary term metadata not found for glossaryId: "
                              + resolvedGlossaryId
                              + ", tmTextUnitId: "
                              + textUnit.getTmTextUnit().getId()));
      metadata.setStatus(normalizedStatus);
      glossaryTermMetadataRepository.saveAndFlush(metadata);
      if (project.getType() == ReviewProjectType.TERM_CANDIDATE) {
        updateLinkedCandidateReview(metadata, normalizedStatus, notes);
      }
    }

    Optional<ReviewProjectTextUnitDecision> existingDecision =
        reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(
            reviewProjectTextUnitId);
    boolean wasDecided =
        existingDecision
            .map(decision -> decision.getDecisionState() == DecisionState.DECIDED)
            .orElse(false);
    ReviewProjectTextUnitDecision decision =
        existingDecision.orElseGet(
            () -> {
              ReviewProjectTextUnitDecision entity = new ReviewProjectTextUnitDecision();
              entity.setReviewProjectTextUnit(textUnit);
              return entity;
            });
    decision.setDecisionState(DecisionState.DECIDED);
    decision.setDecisionVariant(null);
    decision.setReviewedVariant(null);
    decision.setNotes(truncate(normalizeOptional(notes), 4000));
    reviewProjectTextUnitDecisionRepository.saveAndFlush(decision);
    updateProjectDecidedCount(project.getId(), getWordCount(textUnit), wasDecided, true);

    return fetchReviewProjectTextUnitWithFeedback(reviewProjectTextUnitId, project);
  }

  private void resolveTermCandidateReview(
      ReviewProjectTextUnit textUnit,
      Long glossaryId,
      String normalizedStatus,
      String notes,
      boolean promoteToGlossary) {
    TermIndexCandidate candidate = textUnit.getTermIndexCandidate();
    if (GlossaryTermMetadata.STATUS_REJECTED.equals(normalizedStatus)) {
      updateCandidateReview(candidate, TermIndexReview.STATUS_REJECTED, notes);
      if (glossaryId != null) {
        glossaryTermIndexCurationService.ignoreSuggestion(
            glossaryId,
            candidate.getId(),
            new GlossaryTermIndexCurationService.IgnoreSuggestionCommand(notes));
      }
      return;
    }

    updateCandidateReview(candidate, TermIndexReview.STATUS_ACCEPTED, notes);
    if (!promoteToGlossary || glossaryId == null) {
      return;
    }

    GlossaryTermService.TermView term =
        glossaryTermIndexCurationService.acceptSuggestion(
            glossaryId,
            candidate.getId(),
            new GlossaryTermIndexCurationService.AcceptSuggestionCommand(
                null,
                candidate.getTerm(),
                candidate.getDefinition(),
                candidate.getPartOfSpeech(),
                candidate.getTermType(),
                candidate.getEnforcement(),
                normalizedStatus,
                null,
                candidate.getDoNotTranslate(),
                candidate.getConfidence(),
                candidate.getRationale(),
                List.of()));
    if (term.tmTextUnitId() == null) {
      return;
    }

    TMTextUnit promotedTextUnit =
        tmTextUnitRepository
            .findById(term.tmTextUnitId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException("TM text unit not found: " + term.tmTextUnitId()));
    Long requestId =
        textUnit.getReviewProject().getReviewProjectRequest() == null
            ? null
            : textUnit.getReviewProject().getReviewProjectRequest().getId();
    List<ReviewProjectTextUnit> rowsToUpdate =
        requestId == null
            ? List.of(textUnit)
            : reviewProjectTextUnitRepository
                .findByReviewProject_ReviewProjectRequest_IdAndTermIndexCandidate_Id(
                    requestId, candidate.getId());
    if (rowsToUpdate.isEmpty()) {
      rowsToUpdate = List.of(textUnit);
    }
    rowsToUpdate.forEach(row -> row.setTmTextUnit(promotedTextUnit));
    reviewProjectTextUnitRepository.saveAll(rowsToUpdate);
    textUnit.setTmTextUnit(promotedTextUnit);
  }

  private void updateCandidateReview(
      TermIndexCandidate candidate, String reviewStatus, String reviewRationale) {
    candidate.setReviewStatus(reviewStatus);
    candidate.setReviewAuthority(TermIndexReview.AUTHORITY_HUMAN);
    candidate.setReviewReason(TermIndexReview.REASON_OTHER);
    candidate.setReviewRationale(truncate(normalizeOptional(reviewRationale), 2048));
    candidate.setReviewConfidence(null);
    candidate.setReviewChangedAt(ZonedDateTime.now());
    candidate.setReviewChangedByUser(userService.getCurrentUser().orElse(null));
    termIndexCandidateRepository.save(candidate);
  }

  @Transactional
  public GetProjectDetailView.ReviewProjectTextUnit updateTerminologyMetadata(
      Long reviewProjectTextUnitId, UpdateTerminologyMetadataCommand command) {
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
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException("Only admins and PMs can edit terminology metadata");
    }
    if (!isTerminologyWorkflowType(project.getType())) {
      throw new IllegalArgumentException(
          "Terminology metadata edits are only supported for terminology projects");
    }

    Long originalTmTextUnitId = textUnit.getTmTextUnit().getId();
    Long tmTextUnitId = originalTmTextUnitId;
    Optional<GlossaryTermMetadata> maybeMetadata =
        glossaryTermMetadataRepository.findByTmTextUnitIdIn(List.of(originalTmTextUnitId)).stream()
            .findFirst();
    if (maybeMetadata.isEmpty()) {
      if (project.getType() == ReviewProjectType.TERM_CANDIDATE
          && textUnit.getTermIndexCandidate() != null) {
        updateCandidateMetadata(textUnit.getTermIndexCandidate(), command);
        return fetchReviewProjectTextUnitWithFeedback(reviewProjectTextUnitId, project);
      }
      throw new IllegalArgumentException(
          "Glossary term metadata not found for tmTextUnitId: " + originalTmTextUnitId);
    }
    GlossaryTermMetadata metadata = maybeMetadata.get();
    Long glossaryId = metadata.getGlossary().getId();
    GlossaryTermService.TermView currentTerm =
        glossaryTermService.getTerm(glossaryId, tmTextUnitId, List.of());

    String definition =
        command == null ? currentTerm.definition() : normalizeOptional(command.definition());
    String rationale =
        command == null ? null : truncate(normalizeOptional(command.rationale()), 2048);
    String partOfSpeech =
        command == null ? currentTerm.partOfSpeech() : normalizeOptional(command.partOfSpeech());
    String termType =
        command == null || command.termType() == null
            ? currentTerm.termType()
            : normalizeKnownValue(command.termType(), GlossaryTermMetadata.TERM_TYPES, "term type");
    String enforcement =
        command == null || command.enforcement() == null
            ? currentTerm.enforcement()
            : normalizeKnownValue(
                command.enforcement(), GlossaryTermMetadata.ENFORCEMENTS, "enforcement");
    Boolean doNotTranslate =
        command == null || command.doNotTranslate() == null
            ? currentTerm.doNotTranslate()
            : command.doNotTranslate();

    GlossaryTermService.TermView updatedTerm =
        glossaryTermService.upsertTerm(
            glossaryId,
            tmTextUnitId,
            new GlossaryTermService.TermUpsertCommand(
                currentTerm.termKey(),
                currentTerm.source(),
                definition,
                null,
                partOfSpeech,
                termType,
                enforcement,
                currentTerm.status(),
                currentTerm.provenance(),
                currentTerm.caseSensitive(),
                doNotTranslate,
                true,
                true,
                "KEEP_CURRENT",
                null,
                currentTerm.evidence().stream()
                    .map(
                        evidence ->
                            new GlossaryTermService.EvidenceInput(
                                evidence.evidenceType(),
                                evidence.caption(),
                                evidence.imageKey(),
                                evidence.tmTextUnitId(),
                                evidence.cropX(),
                                evidence.cropY(),
                                evidence.cropWidth(),
                                evidence.cropHeight()))
                    .toList()));
    if (!Objects.equals(updatedTerm.tmTextUnitId(), tmTextUnitId)) {
      TMTextUnit refreshedTextUnit =
          tmTextUnitRepository
              .findById(updatedTerm.tmTextUnitId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "TM text unit not found: " + updatedTerm.tmTextUnitId()));
      Long requestId =
          project.getReviewProjectRequest() == null
              ? null
              : project.getReviewProjectRequest().getId();
      List<ReviewProjectTextUnit> rowsToUpdate =
          requestId == null
              ? List.of(textUnit)
              : reviewProjectTextUnitRepository
                  .findByReviewProject_ReviewProjectRequest_IdAndTmTextUnit_Id(
                      requestId, tmTextUnitId);
      if (rowsToUpdate.isEmpty()) {
        rowsToUpdate = List.of(textUnit);
      }
      rowsToUpdate.forEach(row -> row.setTmTextUnit(refreshedTextUnit));
      reviewProjectTextUnitRepository.saveAll(rowsToUpdate);
      textUnit.setTmTextUnit(refreshedTextUnit);
    }

    glossaryTermIndexLinkRepository.findByGlossaryTermMetadataId(updatedTerm.metadataId()).stream()
        .filter(
            link ->
                GlossaryTermIndexLink.RELATION_TYPE_PRIMARY.equals(link.getRelationType())
                    && link.getTermIndexCandidate() != null)
        .map(GlossaryTermIndexLink::getTermIndexCandidate)
        .findFirst()
        .ifPresent(
            candidate -> {
              candidate.setDefinition(definition);
              candidate.setRationale(rationale);
              candidate.setPartOfSpeech(partOfSpeech);
              candidate.setTermType(termType);
              candidate.setEnforcement(enforcement);
              candidate.setDoNotTranslate(doNotTranslate);
              termIndexCandidateRepository.save(candidate);
            });

    return fetchReviewProjectTextUnitWithFeedback(reviewProjectTextUnitId, project);
  }

  private void updateCandidateMetadata(
      TermIndexCandidate candidate, UpdateTerminologyMetadataCommand command) {
    if (command == null) {
      return;
    }
    candidate.setDefinition(normalizeOptional(command.definition()));
    candidate.setRationale(truncate(normalizeOptional(command.rationale()), 2048));
    candidate.setPartOfSpeech(normalizeOptional(command.partOfSpeech()));
    candidate.setTermType(
        command.termType() == null
            ? candidate.getTermType()
            : normalizeKnownValue(
                command.termType(), GlossaryTermMetadata.TERM_TYPES, "term type"));
    candidate.setEnforcement(
        command.enforcement() == null
            ? candidate.getEnforcement()
            : normalizeKnownValue(
                command.enforcement(), GlossaryTermMetadata.ENFORCEMENTS, "enforcement"));
    if (command.doNotTranslate() != null) {
      candidate.setDoNotTranslate(command.doNotTranslate());
    }
    termIndexCandidateRepository.save(candidate);
  }

  private void updateLinkedCandidateReview(
      GlossaryTermMetadata metadata, String terminologyStatus, String notes) {
    if (metadata.getId() == null) {
      return;
    }
    glossaryTermIndexLinkRepository.findByGlossaryTermMetadataId(metadata.getId()).stream()
        .filter(
            link ->
                GlossaryTermIndexLink.RELATION_TYPE_PRIMARY.equals(link.getRelationType())
                    && link.getTermIndexCandidate() != null)
        .map(GlossaryTermIndexLink::getTermIndexCandidate)
        .findFirst()
        .ifPresent(
            candidate -> {
              candidate.setReviewStatus(toCandidateReviewStatus(terminologyStatus));
              candidate.setReviewAuthority(TermIndexReview.AUTHORITY_HUMAN);
              candidate.setReviewReason(TermIndexReview.REASON_OTHER);
              candidate.setReviewRationale(truncate(normalizeOptional(notes), 2048));
              candidate.setReviewConfidence(null);
              candidate.setReviewChangedAt(ZonedDateTime.now());
              candidate.setReviewChangedByUser(userService.getCurrentUser().orElse(null));
              termIndexCandidateRepository.save(candidate);
            });
  }

  private String toCandidateReviewStatus(String terminologyStatus) {
    if (GlossaryTermMetadata.STATUS_REJECTED.equals(terminologyStatus)) {
      return TermIndexReview.STATUS_REJECTED;
    }
    return TermIndexReview.STATUS_ACCEPTED;
  }

  private void updateProjectDecidedCount(
      Long projectId, long wordCount, boolean wasDecided, boolean isDecided) {
    if (wasDecided == isDecided) {
      return;
    }
    if (isDecided) {
      reviewProjectRepository.incrementDecidedProgress(projectId, wordCount);
    } else {
      reviewProjectRepository.decrementDecidedProgress(projectId, wordCount);
    }
  }

  private long getWordCount(ReviewProjectTextUnit textUnit) {
    if (textUnit == null || textUnit.getTmTextUnit() == null) {
      return 0L;
    }
    return Optional.ofNullable(textUnit.getTmTextUnit().getWordCount()).orElse(0);
  }

  private Map<Long, List<ReviewProjectTextUnitFeedback>>
      getTerminologyFeedbacksByReviewProjectTextUnitId(
          ReviewProject reviewProject, List<ReviewProjectTextUnitDetail> textUnitDetails) {
    if (!isTerminologyWorkflowType(reviewProject.getType())) {
      return Map.of();
    }

    if (reviewProject.getTerminologyPhase() == ReviewProjectTerminologyPhase.PM_RESOLUTION
        && reviewProject.getReviewProjectRequest() != null
        && reviewProject.getReviewProjectRequest().getId() != null
        && !CollectionUtils.isEmpty(textUnitDetails)) {
      Map<Long, Long> reviewProjectTextUnitIdByTmTextUnitId =
          textUnitDetails.stream()
              .collect(
                  Collectors.toMap(
                      ReviewProjectTextUnitDetail::tmTextUnitId,
                      ReviewProjectTextUnitDetail::reviewProjectTextUnitId,
                      (left, right) -> left,
                      LinkedHashMap::new));
      return reviewProjectTextUnitFeedbackRepository
          .findSpecialistFeedbackByRequestIdAndTmTextUnitIds(
              reviewProject.getReviewProjectRequest().getId(),
              new ArrayList<>(reviewProjectTextUnitIdByTmTextUnitId.keySet()))
          .stream()
          .collect(
              Collectors.groupingBy(
                  feedback ->
                      reviewProjectTextUnitIdByTmTextUnitId.get(
                          feedback.getReviewProjectTextUnit().getTmTextUnit().getId()),
                  LinkedHashMap::new,
                  Collectors.toList()));
    }

    return reviewProjectTextUnitFeedbackRepository
        .findByReviewProjectIdOrderByTextUnitIdAndLastModified(reviewProject.getId())
        .stream()
        .collect(
            Collectors.groupingBy(
                feedback -> feedback.getReviewProjectTextUnit().getId(),
                LinkedHashMap::new,
                Collectors.toList()));
  }

  private Map<Long, GetProjectDetailView.TerminologyTerm>
      getTerminologyTermsByReviewProjectTextUnitId(
          ReviewProject reviewProject, List<ReviewProjectTextUnitDetail> textUnitDetails) {
    if (!isTerminologyWorkflowType(reviewProject.getType()) || textUnitDetails.isEmpty()) {
      return Map.of();
    }

    List<Long> tmTextUnitIds =
        textUnitDetails.stream()
            .map(ReviewProjectTextUnitDetail::tmTextUnitId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (tmTextUnitIds.isEmpty()) {
      return getCandidateTerminologyTermsByReviewProjectTextUnitId(textUnitDetails, Map.of());
    }

    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId =
        glossaryTermMetadataRepository.findByTmTextUnitIdIn(tmTextUnitIds).stream()
            .filter(metadata -> metadata.getTmTextUnit() != null)
            .collect(
                Collectors.toMap(
                    metadata -> metadata.getTmTextUnit().getId(),
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    List<Long> metadataIds =
        metadataByTmTextUnitId.values().stream()
            .map(GlossaryTermMetadata::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<Long, GlossaryTermIndexLink> primaryLinksByMetadataId =
        metadataIds.isEmpty()
            ? Map.of()
            : glossaryTermIndexLinkRepository
                .findByGlossaryTermMetadataIdInAndRelationType(
                    metadataIds, GlossaryTermIndexLink.RELATION_TYPE_PRIMARY)
                .stream()
                .filter(link -> link.getGlossaryTermMetadata() != null)
                .collect(
                    Collectors.toMap(
                        link -> link.getGlossaryTermMetadata().getId(),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    Map<Long, List<GetProjectDetailView.TerminologyTermEvidence>> evidenceByMetadataId =
        getGlossaryTermEvidenceByMetadataId(metadataIds);

    Map<Long, ReviewProjectTextUnitDetail> detailByTmTextUnitId =
        textUnitDetails.stream()
            .filter(detail -> detail.tmTextUnitId() != null)
            .collect(
                Collectors.toMap(
                    ReviewProjectTextUnitDetail::tmTextUnitId,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));

    Map<Long, GetProjectDetailView.TerminologyTerm> result = new LinkedHashMap<>();
    for (Map.Entry<Long, GlossaryTermMetadata> entry : metadataByTmTextUnitId.entrySet()) {
      Long tmTextUnitId = entry.getKey();
      ReviewProjectTextUnitDetail detail = detailByTmTextUnitId.get(tmTextUnitId);
      if (detail == null) {
        continue;
      }
      GlossaryTermMetadata metadata = entry.getValue();
      GlossaryTermIndexLink primaryLink = primaryLinksByMetadataId.get(metadata.getId());
      result.put(
          detail.reviewProjectTextUnitId(),
          toTerminologyTerm(
              detail,
              metadata,
              primaryLink,
              evidenceByMetadataId.getOrDefault(metadata.getId(), List.of())));
    }
    result.putAll(getCandidateTerminologyTermsByReviewProjectTextUnitId(textUnitDetails, result));
    return result;
  }

  private Map<Long, List<GetProjectDetailView.TerminologyTermEvidence>>
      getGlossaryTermEvidenceByReviewProjectTextUnitId(
          List<ReviewProjectTextUnitDetail> textUnitDetails) {
    if (textUnitDetails.isEmpty()) {
      return Map.of();
    }

    Map<Long, ReviewProjectTextUnitDetail> detailByTmTextUnitId =
        textUnitDetails.stream()
            .filter(detail -> detail.tmTextUnitId() != null)
            .collect(
                Collectors.toMap(
                    ReviewProjectTextUnitDetail::tmTextUnitId,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    if (detailByTmTextUnitId.isEmpty()) {
      return Map.of();
    }

    Map<Long, Long> reviewProjectTextUnitIdByMetadataId =
        glossaryTermMetadataRepository
            .findByTmTextUnitIdIn(new ArrayList<>(detailByTmTextUnitId.keySet()))
            .stream()
            .filter(
                metadata ->
                    metadata.getId() != null
                        && metadata.getTmTextUnit() != null
                        && metadata.getTmTextUnit().getId() != null
                        && detailByTmTextUnitId.containsKey(metadata.getTmTextUnit().getId()))
            .collect(
                Collectors.toMap(
                    GlossaryTermMetadata::getId,
                    metadata ->
                        detailByTmTextUnitId
                            .get(metadata.getTmTextUnit().getId())
                            .reviewProjectTextUnitId(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    if (reviewProjectTextUnitIdByMetadataId.isEmpty()) {
      return Map.of();
    }

    Map<Long, List<GetProjectDetailView.TerminologyTermEvidence>> evidenceByMetadataId =
        getGlossaryTermEvidenceByMetadataId(
            new ArrayList<>(reviewProjectTextUnitIdByMetadataId.keySet()));
    Map<Long, List<GetProjectDetailView.TerminologyTermEvidence>> evidenceByReviewTextUnitId =
        new LinkedHashMap<>();
    for (Map.Entry<Long, List<GetProjectDetailView.TerminologyTermEvidence>> entry :
        evidenceByMetadataId.entrySet()) {
      Long reviewProjectTextUnitId = reviewProjectTextUnitIdByMetadataId.get(entry.getKey());
      if (reviewProjectTextUnitId != null) {
        evidenceByReviewTextUnitId.put(reviewProjectTextUnitId, entry.getValue());
      }
    }
    return evidenceByReviewTextUnitId;
  }

  private Map<Long, List<GetProjectDetailView.TerminologyTermEvidence>>
      getGlossaryTermEvidenceByMetadataId(Collection<Long> metadataIds) {
    if (metadataIds == null || metadataIds.isEmpty()) {
      return Map.of();
    }

    return glossaryTermEvidenceRepository
        .findByGlossaryTermMetadataIdInOrderBySortOrderAsc(metadataIds)
        .stream()
        .filter(
            evidence ->
                evidence.getGlossaryTermMetadata() != null
                    && evidence.getGlossaryTermMetadata().getId() != null)
        .collect(
            Collectors.groupingBy(
                evidence -> evidence.getGlossaryTermMetadata().getId(),
                LinkedHashMap::new,
                Collectors.mapping(this::toTerminologyTermEvidence, Collectors.toList())));
  }

  private Map<Long, GetProjectDetailView.TerminologyTerm>
      getCandidateTerminologyTermsByReviewProjectTextUnitId(
          List<ReviewProjectTextUnitDetail> textUnitDetails,
          Map<Long, GetProjectDetailView.TerminologyTerm> existingTermsByReviewProjectTextUnitId) {
    List<Long> candidateIds =
        textUnitDetails.stream()
            .filter(
                detail ->
                    detail.termIndexCandidateId() != null
                        && !existingTermsByReviewProjectTextUnitId.containsKey(
                            detail.reviewProjectTextUnitId()))
            .map(ReviewProjectTextUnitDetail::termIndexCandidateId)
            .distinct()
            .toList();
    if (candidateIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, TermIndexCandidate> candidateById =
        termIndexCandidateRepository.findAllById(candidateIds).stream()
            .collect(
                Collectors.toMap(
                    TermIndexCandidate::getId,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    Map<Long, GetProjectDetailView.TerminologyTerm> result = new LinkedHashMap<>();
    for (ReviewProjectTextUnitDetail detail : textUnitDetails) {
      if (detail.termIndexCandidateId() == null
          || existingTermsByReviewProjectTextUnitId.containsKey(detail.reviewProjectTextUnitId())) {
        continue;
      }
      TermIndexCandidate candidate = candidateById.get(detail.termIndexCandidateId());
      if (candidate != null) {
        result.put(detail.reviewProjectTextUnitId(), toCandidateTerminologyTerm(detail, candidate));
      }
    }
    return result;
  }

  private GetProjectDetailView.TerminologyTerm toTerminologyTerm(
      ReviewProjectTextUnitDetail detail,
      GlossaryTermMetadata metadata,
      GlossaryTermIndexLink primaryLink,
      List<GetProjectDetailView.TerminologyTermEvidence> evidenceItems) {
    TermIndexCandidate candidate = primaryLink == null ? null : primaryLink.getTermIndexCandidate();
    var extractedTerm = candidate == null ? null : candidate.getTermIndexExtractedTerm();
    List<GetProjectDetailView.TerminologyTermExample> examples =
        extractedTerm == null || extractedTerm.getId() == null
            ? List.of()
            : termIndexOccurrenceRepository
                .findDetailsByTermIndexExtractedTermId(
                    extractedTerm.getId(), true, List.of(-1L), null, PageRequest.of(0, 5))
                .stream()
                .map(
                    row ->
                        new GetProjectDetailView.TerminologyTermExample(
                            row.getId(),
                            row.getRepositoryId(),
                            row.getRepositoryName(),
                            row.getAssetId(),
                            row.getAssetPath(),
                            row.getTmTextUnitId(),
                            row.getTextUnitName(),
                            row.getSourceText(),
                            row.getMatchedText(),
                            row.getStartIndex(),
                            row.getEndIndex(),
                            row.getExtractionMethod(),
                            row.getConfidence()))
                .toList();
    List<GetProjectDetailView.TerminologyTermSource> sources =
        candidate == null
            ? List.of()
            : List.of(
                new GetProjectDetailView.TerminologyTermSource(
                    candidate.getId(),
                    candidate.getSourceType(),
                    candidate.getSourceName(),
                    candidate.getSourceExternalId()));
    List<GetProjectDetailView.TerminologyTermEvidence> evidence =
        evidenceItems == null ? List.of() : evidenceItems;

    return new GetProjectDetailView.TerminologyTerm(
        metadata.getGlossary() == null ? null : metadata.getGlossary().getId(),
        metadata.getGlossary() == null ? null : metadata.getGlossary().getName(),
        metadata.getId(),
        detail.tmTextUnitId(),
        detail.tmTextUnitName(),
        detail.tmTextUnitContent(),
        detail.tmTextUnitComment(),
        candidate == null ? null : candidate.getRationale(),
        metadata.getPartOfSpeech(),
        metadata.getTermType(),
        metadata.getEnforcement(),
        metadata.getStatus(),
        metadata.getProvenance(),
        metadata.getCaseSensitive(),
        metadata.getDoNotTranslate(),
        candidate == null ? null : candidate.getId(),
        extractedTerm == null ? null : extractedTerm.getId(),
        extractedTerm == null ? null : extractedTerm.getOccurrenceCount(),
        extractedTerm == null ? null : extractedTerm.getRepositoryCount(),
        candidate == null ? null : candidate.getReviewStatus(),
        candidate == null ? null : candidate.getReviewAuthority(),
        candidate == null ? null : candidate.getReviewReason(),
        candidate == null ? null : candidate.getReviewRationale(),
        candidate == null ? null : candidate.getReviewConfidence(),
        candidate == null ? null : candidate.getReviewChangedAt(),
        candidate == null || candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getUsername(),
        sources,
        examples,
        evidence);
  }

  private GetProjectDetailView.TerminologyTermEvidence toTerminologyTermEvidence(
      GlossaryTermEvidence evidence) {
    return new GetProjectDetailView.TerminologyTermEvidence(
        evidence.getId(),
        evidence.getEvidenceType(),
        evidence.getCaption(),
        evidence.getImageKey(),
        evidence.getTmTextUnit() == null ? null : evidence.getTmTextUnit().getId(),
        evidence.getCropX(),
        evidence.getCropY(),
        evidence.getCropWidth(),
        evidence.getCropHeight(),
        evidence.getSortOrder());
  }

  private GetProjectDetailView.TerminologyTerm toCandidateTerminologyTerm(
      ReviewProjectTextUnitDetail detail, TermIndexCandidate candidate) {
    var extractedTerm = candidate.getTermIndexExtractedTerm();
    List<GetProjectDetailView.TerminologyTermExample> examples =
        extractedTerm == null || extractedTerm.getId() == null
            ? List.of()
            : termIndexOccurrenceRepository
                .findDetailsByTermIndexExtractedTermId(
                    extractedTerm.getId(), true, List.of(-1L), null, PageRequest.of(0, 5))
                .stream()
                .map(
                    row ->
                        new GetProjectDetailView.TerminologyTermExample(
                            row.getId(),
                            row.getRepositoryId(),
                            row.getRepositoryName(),
                            row.getAssetId(),
                            row.getAssetPath(),
                            row.getTmTextUnitId(),
                            row.getTextUnitName(),
                            row.getSourceText(),
                            row.getMatchedText(),
                            row.getStartIndex(),
                            row.getEndIndex(),
                            row.getExtractionMethod(),
                            row.getConfidence()))
                .toList();
    List<GetProjectDetailView.TerminologyTermSource> sources =
        List.of(
            new GetProjectDetailView.TerminologyTermSource(
                candidate.getId(),
                candidate.getSourceType(),
                candidate.getSourceName(),
                candidate.getSourceExternalId()));

    return new GetProjectDetailView.TerminologyTerm(
        detail.targetGlossaryId(),
        detail.targetGlossaryName(),
        null,
        null,
        candidate.getNormalizedKey(),
        candidate.getTerm(),
        candidate.getDefinition(),
        candidate.getRationale(),
        candidate.getPartOfSpeech(),
        candidate.getTermType(),
        candidate.getEnforcement(),
        GlossaryTermMetadata.STATUS_CANDIDATE,
        GlossaryTermMetadata.PROVENANCE_AI_EXTRACTED,
        false,
        candidate.getDoNotTranslate(),
        candidate.getId(),
        extractedTerm == null ? null : extractedTerm.getId(),
        extractedTerm == null ? null : extractedTerm.getOccurrenceCount(),
        extractedTerm == null ? null : extractedTerm.getRepositoryCount(),
        candidate.getReviewStatus(),
        candidate.getReviewAuthority(),
        candidate.getReviewReason(),
        candidate.getReviewRationale(),
        candidate.getReviewConfidence(),
        candidate.getReviewChangedAt(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getUsername(),
        sources,
        examples,
        List.of());
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

  private GetProjectDetailView.ReviewProjectTextUnit fetchReviewProjectTextUnitWithFeedback(
      Long reviewProjectTextUnitId, ReviewProject reviewProject) {
    ReviewProjectTextUnitDetail detail = fetchReviewProjectTextUnitDetail(reviewProjectTextUnitId);
    return toReviewProjectTextUnit(
        detail,
        getTerminologyFeedbacksByReviewProjectTextUnitId(reviewProject, List.of(detail))
            .getOrDefault(reviewProjectTextUnitId, List.of()),
        getTerminologyTermsByReviewProjectTextUnitId(reviewProject, List.of(detail))
            .get(detail.reviewProjectTextUnitId()),
        getGlossaryTermEvidenceByReviewProjectTextUnitId(List.of(detail))
            .getOrDefault(detail.reviewProjectTextUnitId(), List.of()));
  }

  private GetProjectDetailView.ReviewProjectTextUnit toReviewProjectTextUnit(
      ReviewProjectTextUnitDetail detail,
      List<ReviewProjectTextUnitFeedback> feedbacks,
      GetProjectDetailView.TerminologyTerm terminologyTerm,
      List<GetProjectDetailView.TerminologyTermEvidence> glossaryTermEvidence) {
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
        reviewProjectTextUnitDecision,
        terminologyTerm,
        glossaryTermEvidence == null ? List.of() : glossaryTermEvidence,
        feedbacks.stream().map(this::toReviewProjectTextUnitFeedback).toList());
  }

  private GetProjectDetailView.ReviewProjectTextUnitFeedback toReviewProjectTextUnitFeedback(
      ReviewProjectTextUnitFeedback feedback) {
    User reviewer = feedback.getReviewerUser();
    return new GetProjectDetailView.ReviewProjectTextUnitFeedback(
        feedback.getId(),
        feedback.getRecommendation() != null ? feedback.getRecommendation().name() : null,
        feedback.getConfidence(),
        feedback.getNotes(),
        feedback.getCreatedDate(),
        feedback.getLastModifiedDate(),
        reviewer != null ? reviewer.getId() : null,
        reviewer != null ? reviewer.getUsername() : null);
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

  private List<TextUnitDTO> getTextUnitReviewCandidates(
      CreateReviewProjectRequestCommand request, Locale locale) {
    if (isTerminologyWorkflowType(request.type())) {
      return getSourceTerminologyReviewCandidates(request.tmTextUnitIds());
    }
    return searchReviewCandidates(request.tmTextUnitIds(), locale, request.statusFilter());
  }

  private List<TextUnitDTO> getSourceTerminologyReviewCandidates(List<Long> tmTextUnitIds) {
    if (tmTextUnitIds == null || tmTextUnitIds.isEmpty()) {
      throw new IllegalArgumentException("tmTextUnitIds must be provided");
    }
    Map<Long, TMTextUnit> textUnitsById =
        tmTextUnitRepository.findByIdIn(tmTextUnitIds).stream()
            .collect(Collectors.toMap(TMTextUnit::getId, Function.identity(), (a, b) -> a));

    return tmTextUnitIds.stream()
        .distinct()
        .map(textUnitsById::get)
        .filter(Objects::nonNull)
        .map(this::toSourceTerminologyReviewCandidate)
        .toList();
  }

  private TextUnitDTO toSourceTerminologyReviewCandidate(TMTextUnit tmTextUnit) {
    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setTmTextUnitId(tmTextUnit.getId());
    textUnitDTO.setName(tmTextUnit.getName());
    textUnitDTO.setSource(tmTextUnit.getContent());
    textUnitDTO.setComment(tmTextUnit.getComment());
    textUnitDTO.setAssetId(tmTextUnit.getAsset() == null ? null : tmTextUnit.getAsset().getId());
    textUnitDTO.setTmTextUnitCreatedDate(tmTextUnit.getCreatedDate());
    return textUnitDTO;
  }

  private List<TextUnitDTO> searchReviewFeatureCandidates(
      ReviewFeature reviewFeature, Locale locale, StatusFilter statusFilter) {
    if (locale == null) {
      throw new IllegalArgumentException("locale must be provided");
    }
    return searchReviewFeatureCandidates(reviewFeature, locale.getId(), statusFilter);
  }

  private List<TextUnitDTO> searchReviewFeatureCandidates(
      ReviewFeature reviewFeature, Long localeId, StatusFilter statusFilter) {
    if (reviewFeature == null) {
      throw new IllegalArgumentException("reviewFeature must be provided");
    }
    if (localeId == null) {
      throw new IllegalArgumentException("localeId must be provided");
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
    params.setLocaleId(localeId);
    params.setPluralFormsFiltered(false);
    params.setStatusFilter(statusFilter);

    return textUnitSearcher.search(params);
  }

  private List<TextUnitDTO> searchRepositoryReviewCandidates(
      List<Long> repositoryIds, Locale locale, StatusFilter statusFilter) {
    if (locale == null) {
      throw new IllegalArgumentException("locale must be provided");
    }
    List<Long> activeRepositoryIds =
        repositoryIds == null
            ? List.of()
            : repositoryIds.stream().filter(Objects::nonNull).distinct().toList();

    if (activeRepositoryIds.isEmpty()) {
      throw new IllegalArgumentException("repositoryIds must be provided");
    }

    TextUnitSearcherParameters params = new TextUnitSearcherParameters();
    params.setRepositoryIds(activeRepositoryIds);
    params.setLocaleId(locale.getId());
    params.setPluralFormsFiltered(false);
    params.setStatusFilter(statusFilter);

    return textUnitSearcher.search(params);
  }

  private StatusFilter getManualRepositoryScopeStatusFilter(StatusFilter statusFilter) {
    return statusFilter == null ? StatusFilter.REVIEW_NEEDED : statusFilter;
  }

  private List<TextUnitDTO> excludeOpenReviewProjectTextUnits(
      List<TextUnitDTO> candidates, Locale locale) {
    return excludeOpenReviewProjectTextUnits(candidates, locale == null ? null : locale.getId());
  }

  private List<TextUnitDTO> excludeOpenReviewProjectTextUnits(
      List<TextUnitDTO> candidates, Long localeId) {
    if (CollectionUtils.isEmpty(candidates) || localeId == null) {
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
                    ReviewProjectStatus.OPEN, localeId, tmTextUnitIds));
    if (excludedTmTextUnitIds.isEmpty()) {
      return candidates;
    }

    return candidates.stream()
        .filter(candidate -> !excludedTmTextUnitIds.contains(candidate.getTmTextUnitId()))
        .toList();
  }

  private List<ReviewFeatureLocaleRow> getReviewFeatureLocales(Long reviewFeatureId) {
    if (reviewFeatureId == null) {
      return List.of();
    }

    return reviewFeatureRepository.findNonRootLocaleRowsByFeatureId(reviewFeatureId).stream()
        .filter(locale -> locale != null && locale.id() != null)
        .collect(
            Collectors.toMap(
                ReviewFeatureLocaleRow::id,
                locale -> locale,
                (left, right) -> left,
                LinkedHashMap::new))
        .values()
        .stream()
        .sorted(
            Comparator.comparing(
                    (ReviewFeatureLocaleRow locale) ->
                        locale.bcp47Tag() == null ? "" : locale.bcp47Tag(),
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReviewFeatureLocaleRow::id))
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
      List<CreateReviewProjectRequestCommand.ProjectSpec> projectSpecs,
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
      String localeTagKey =
          locale.getBcp47Tag() == null ? "" : locale.getBcp47Tag().trim().toLowerCase();
      List<ResolvedProjectSpec> resolvedProjectSpecs =
          resolveProjectSpecs(
              type, dueDate, assignTranslator, projectSpecs, assignmentDefaults, localeTagKey);
      for (ResolvedProjectSpec projectSpec : resolvedProjectSpecs) {
        ReviewProject reviewProject = new ReviewProject();
        reviewProject.setType(type);
        reviewProject.setTerminologyPhase(projectSpec.terminologyPhase());
        reviewProject.setStatus(ReviewProjectStatus.OPEN);
        reviewProject.setDueDate(projectSpec.dueDate());
        reviewProject.setLocale(locale);
        reviewProject.setReviewProjectRequest(reviewProjectRequest);
        reviewProject.setTeam(assignmentDefaults.team());
        reviewProject.setAssignedPmUser(projectSpec.assignedPmUser());
        reviewProject.setAssignedTranslatorUser(projectSpec.assignedTranslatorUser());
        reviewProject.setCreatedByUser(requestedByUser);

        ReviewProject saved = reviewProjectRepository.save(reviewProject);

        int wordCount = 0;
        int textUnitCount = 0;

        for (TextUnitDTO textUnitDTO : localeCandidates.candidates()) {
          ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
          reviewProjectTextUnit.setReviewProject(reviewProject);
          reviewProjectTextUnit.setTmTextUnit(
              entityManager.getReference(TMTextUnit.class, textUnitDTO.getTmTextUnitId()));
          if (textUnitDTO instanceof CandidateReviewTextUnitDTO candidateReviewTextUnitDTO) {
            reviewProjectTextUnit.setTermIndexCandidate(
                entityManager.getReference(
                    TermIndexCandidate.class, candidateReviewTextUnitDTO.termIndexCandidateId()));
            if (candidateReviewTextUnitDTO.targetGlossaryId() != null) {
              reviewProjectTextUnit.setTargetGlossary(
                  entityManager.getReference(
                      Glossary.class, candidateReviewTextUnitDTO.targetGlossaryId()));
            }
          }
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
        reviewProjectAssignmentWindowService.ensureOpenWindow(
            saved, saved.getAssignedTranslatorUser());
        recordAssignmentHistory(
            saved, ReviewProjectAssignmentEventType.CREATED_DEFAULT, null, requestedByUser);
        projectIds.add(saved.getId());
        createdLocaleTags.add(locale.getBcp47Tag());
        createdProjects.add(saved);
      }
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
      boolean isTranslatorOnly,
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
      if (isTranslatorOnly && pmChanged) {
        throw new AccessDeniedException("Translators can only reassign translator");
      }
      if (previousTeamId == null && (pmChanged || translatorChanged)) {
        throw new AccessDeniedException("Team must be assigned before non-admin reassignment");
      }
      if (previousTeamId != null) {
        if (isPm) {
          teamService.assertCurrentUserCanAccessTeam(previousTeamId);
        } else if (isTranslatorOnly) {
          teamService.assertCurrentUserCanReadTeam(previousTeamId);
        }
      }
    }

    if (isTranslatorOnly && translatorChanged) {
      Long previousAssignedTranslatorId = getEntityId(previousAssignedTranslator);
      Long nextAssignedTranslatorId = getEntityId(nextAssignedTranslator);
      if (previousAssignedTranslatorId != null) {
        throw new AccessDeniedException("Translators can only claim unassigned projects");
      }
      Long currentUserId = teamService.getCurrentUserIdOrThrow();
      if (!Objects.equals(nextAssignedTranslatorId, currentUserId)) {
        throw new AccessDeniedException("Translators can only assign themselves");
      }
    }

    Long effectiveTeamId = nextTeamId;
    if (effectiveTeamId != null) {
      if (nextAssignedPm != null) {
        boolean isPmMember =
            teamService.isUserInTeamRole(effectiveTeamId, nextAssignedPm.getId(), TeamUserRole.PM);
        boolean isTranslatorDecider =
            isTerminologyWorkflowType(reviewProject.getType())
                && teamService.isUserInTeamRole(
                    effectiveTeamId, nextAssignedPm.getId(), TeamUserRole.TRANSLATOR);
        if (!isPmMember && !isTranslatorDecider) {
          throw new IllegalArgumentException(
              isTerminologyWorkflowType(reviewProject.getType())
                  ? "Assigned decider is not a PM or translator member of team "
                      + effectiveTeamId
                      + ": "
                      + nextAssignedPm.getId()
                  : "Assigned PM is not a PM member of team "
                      + effectiveTeamId
                      + ": "
                      + nextAssignedPm.getId());
        }
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

  private List<ResolvedProjectSpec> resolveProjectSpecs(
      ReviewProjectType type,
      ZonedDateTime defaultDueDate,
      Boolean assignTranslator,
      List<CreateReviewProjectRequestCommand.ProjectSpec> projectSpecs,
      CreateAssignmentDefaults assignmentDefaults,
      String localeTagKey) {
    if (CollectionUtils.isEmpty(projectSpecs)) {
      User assignedTranslatorUser = null;
      if (assignTranslator == null || Boolean.TRUE.equals(assignTranslator)) {
        assignedTranslatorUser =
            assignmentDefaults.defaultTranslatorByLocaleTagLowercase().get(localeTagKey);
      }
      return List.of(
          new ResolvedProjectSpec(
              null, defaultDueDate, assignmentDefaults.defaultPmUser(), assignedTranslatorUser));
    }

    if (!isTerminologyWorkflowType(type)) {
      throw new IllegalArgumentException(
          "projectSpecs are only supported for terminology projects");
    }

    List<ResolvedProjectSpec> resolvedProjectSpecs = new ArrayList<>();
    for (CreateReviewProjectRequestCommand.ProjectSpec projectSpec : projectSpecs) {
      if (projectSpec == null) {
        continue;
      }
      resolvedProjectSpecs.add(
          new ResolvedProjectSpec(
              projectSpec.terminologyPhase(),
              projectSpec.dueDate() == null ? defaultDueDate : projectSpec.dueDate(),
              projectSpec.assignedPmUserId() == null
                  ? null
                  : resolveUser(projectSpec.assignedPmUserId(), "assignedPmUser"),
              projectSpec.assignedTranslatorUserId() == null
                  ? null
                  : resolveUser(projectSpec.assignedTranslatorUserId(), "assignedTranslatorUser")));
    }
    if (resolvedProjectSpecs.isEmpty()) {
      throw new IllegalArgumentException("At least one projectSpec must be provided");
    }
    return resolvedProjectSpecs;
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

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private String normalizeKnownValue(String value, Set<String> allowedValues, String fieldName) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      return null;
    }
    normalized = normalized.toUpperCase(java.util.Locale.ROOT);
    if (!allowedValues.contains(normalized)) {
      throw new IllegalArgumentException("Unknown glossary " + fieldName + ": " + value);
    }
    return normalized;
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
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

  private DefaultTransactionDefinition readOnlyTransaction() {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transactionDefinition.setReadOnly(true);
    return transactionDefinition;
  }

  public record UpdateTerminologyMetadataCommand(
      String definition,
      String rationale,
      String partOfSpeech,
      String termType,
      String enforcement,
      Boolean doNotTranslate) {}
}
