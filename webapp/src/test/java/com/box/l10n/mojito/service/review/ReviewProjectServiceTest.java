package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TM;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.TeamUser;
import com.box.l10n.mojito.entity.TeamUserRole;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexReview;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentEventType;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentHistory;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTerminologyPhase;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitFeedback;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
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
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class ReviewProjectServiceTest {

  private final ReviewProjectRepository reviewProjectRepository =
      Mockito.mock(ReviewProjectRepository.class);
  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository =
      Mockito.mock(ReviewProjectTextUnitRepository.class);
  private final ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository =
      Mockito.mock(ReviewProjectTextUnitDecisionRepository.class);
  private final ReviewProjectTextUnitFeedbackRepository reviewProjectTextUnitFeedbackRepository =
      Mockito.mock(ReviewProjectTextUnitFeedbackRepository.class);
  private final ReviewProjectRequestRepository reviewProjectRequestRepository =
      Mockito.mock(ReviewProjectRequestRepository.class);
  private final ReviewProjectRequestScreenshotRepository reviewProjectRequestScreenshotRepository =
      Mockito.mock(ReviewProjectRequestScreenshotRepository.class);
  private final ReviewProjectRequestSlackThreadRepository
      reviewProjectRequestSlackThreadRepository =
          Mockito.mock(ReviewProjectRequestSlackThreadRepository.class);
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository =
      Mockito.mock(GlossaryTermMetadataRepository.class);
  private final GlossaryTermEvidenceRepository glossaryTermEvidenceRepository =
      Mockito.mock(GlossaryTermEvidenceRepository.class);
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService =
      Mockito.mock(GlossaryTermIndexCurationService.class);
  private final GlossaryTermIndexLinkRepository glossaryTermIndexLinkRepository =
      Mockito.mock(GlossaryTermIndexLinkRepository.class);
  private final TermIndexCandidateRepository termIndexCandidateRepository =
      Mockito.mock(TermIndexCandidateRepository.class);
  private final TermIndexOccurrenceRepository termIndexOccurrenceRepository =
      Mockito.mock(TermIndexOccurrenceRepository.class);
  private final GlossaryTermService glossaryTermService = Mockito.mock(GlossaryTermService.class);
  private final LocaleService localeService = Mockito.mock(LocaleService.class);
  private final TextUnitSearcher textUnitSearcher = Mockito.mock(TextUnitSearcher.class);
  private final TMTextUnitRepository tmTextUnitRepository =
      Mockito.mock(TMTextUnitRepository.class);
  private final TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository =
      Mockito.mock(TMTextUnitCurrentVariantRepository.class);
  private final TMService tmService = Mockito.mock(TMService.class);
  private final TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService =
      Mockito.mock(TMTextUnitIntegrityCheckService.class);
  private final WordCountService wordCountService = Mockito.mock(WordCountService.class);
  private final UserService userService = Mockito.mock(UserService.class);
  private final UserRepository userRepository = Mockito.mock(UserRepository.class);
  private final TeamService teamService = Mockito.mock(TeamService.class);
  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
  private final TeamUserRepository teamUserRepository = Mockito.mock(TeamUserRepository.class);
  private final ReviewProjectAssignmentHistoryRepository reviewProjectAssignmentHistoryRepository =
      Mockito.mock(ReviewProjectAssignmentHistoryRepository.class);
  private final ReviewProjectAssignmentWindowRepository reviewProjectAssignmentWindowRepository =
      Mockito.mock(ReviewProjectAssignmentWindowRepository.class);
  private final ReviewProjectAssignmentWindowService reviewProjectAssignmentWindowService =
      Mockito.mock(ReviewProjectAssignmentWindowService.class);
  private final ReviewProjectTimeSpentStatService reviewProjectTimeSpentStatService =
      Mockito.mock(ReviewProjectTimeSpentStatService.class);
  private final TeamSlackNotificationService teamSlackNotificationService =
      Mockito.mock(TeamSlackNotificationService.class);
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler =
      Mockito.mock(QuartzPollableTaskScheduler.class);
  private final ReviewFeatureRepository reviewFeatureRepository =
      Mockito.mock(ReviewFeatureRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final EntityManager entityManager = Mockito.mock(EntityManager.class);

  private ReviewProjectService reviewProjectService;
  private User currentUser;

  @Before
  public void setUp() {
    reviewProjectService =
        Mockito.spy(
            new ReviewProjectService(
                reviewProjectRepository,
                reviewProjectTextUnitRepository,
                reviewProjectTextUnitDecisionRepository,
                reviewProjectTextUnitFeedbackRepository,
                reviewProjectRequestRepository,
                reviewProjectRequestScreenshotRepository,
                reviewProjectRequestSlackThreadRepository,
                glossaryTermMetadataRepository,
                glossaryTermEvidenceRepository,
                glossaryTermIndexCurationService,
                glossaryTermIndexLinkRepository,
                termIndexCandidateRepository,
                termIndexOccurrenceRepository,
                glossaryTermService,
                localeService,
                textUnitSearcher,
                tmTextUnitRepository,
                tmTextUnitCurrentVariantRepository,
                tmService,
                tmTextUnitIntegrityCheckService,
                wordCountService,
                userService,
                userRepository,
                teamService,
                teamRepository,
                teamUserRepository,
                reviewProjectAssignmentHistoryRepository,
                reviewProjectAssignmentWindowRepository,
                reviewProjectAssignmentWindowService,
                reviewProjectTimeSpentStatService,
                teamSlackNotificationService,
                quartzPollableTaskScheduler,
                reviewFeatureRepository,
                meterRegistry,
                transactionManager));
    ReflectionTestUtils.setField(reviewProjectService, "entityManager", entityManager);
    doReturn(null).when(reviewProjectService).getProjectDetailNoTx(anyLong());
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

    currentUser = user(99L, "admin");
    currentUser.setCanTranslateAllLocales(true);

    when(teamService.getCurrentUserIdOrThrow()).thenReturn(99L);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    when(userService.isCurrentUserPm()).thenReturn(false);
    when(userService.isCurrentUserTranslator()).thenReturn(false);
    when(userRepository.findById(99L)).thenReturn(Optional.of(currentUser));
  }

  @Test
  public void createGlossaryTermCandidateReviewProjectCommitsTransaction() {
    CreateGlossaryTermCandidateReviewProjectJobInput request =
        createGlossaryTermCandidateReviewProjectJobInput();
    CreateReviewProjectRequestResult result =
        new CreateReviewProjectRequestResult(
            1L, "name", List.of("en"), ZonedDateTime.now(), List.of(), 1, 1, 0, 0, List.of());
    doReturn(result)
        .when(reviewProjectService)
        .createGlossaryTermCandidateReviewProjectNoTx(request);

    assertEquals(result, reviewProjectService.createGlossaryTermCandidateReviewProject(request));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void createGlossaryTermCandidateReviewProjectRollsBackTransactionOnRuntimeException() {
    CreateGlossaryTermCandidateReviewProjectJobInput request =
        createGlossaryTermCandidateReviewProjectJobInput();
    RuntimeException failure = new RuntimeException("create failed");
    doThrow(failure)
        .when(reviewProjectService)
        .createGlossaryTermCandidateReviewProjectNoTx(request);

    try {
      reviewProjectService.createGlossaryTermCandidateReviewProject(request);
      fail("Expected createGlossaryTermCandidateReviewProject to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void createReviewProjectRequestCommitsTransaction() {
    CreateReviewProjectRequestCommand request = createReviewProjectRequestCommand();
    CreateReviewProjectRequestResult result =
        new CreateReviewProjectRequestResult(
            1L, "name", List.of("fr"), ZonedDateTime.now(), List.of(), 1, 1, 0, 0, List.of());
    doReturn(result).when(reviewProjectService).createReviewProjectRequestNoTx(request);

    assertEquals(result, reviewProjectService.createReviewProjectRequest(request));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void createReviewProjectRequestRollsBackTransactionOnRuntimeException() {
    CreateReviewProjectRequestCommand request = createReviewProjectRequestCommand();
    RuntimeException failure = new RuntimeException("create request failed");
    doThrow(failure).when(reviewProjectService).createReviewProjectRequestNoTx(request);

    try {
      reviewProjectService.createReviewProjectRequest(request);
      fail("Expected createReviewProjectRequest to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void createAutomatedReviewProjectRequestCommitsTransaction() {
    CreateAutomatedReviewProjectRequestCommand request =
        createAutomatedReviewProjectRequestCommand();
    CreateReviewProjectRequestResult result =
        new CreateReviewProjectRequestResult(
            1L, "name", List.of("fr"), ZonedDateTime.now(), List.of(), 1, 1, 0, 0, List.of());
    doReturn(result).when(reviewProjectService).createAutomatedReviewProjectRequestNoTx(request);

    assertEquals(result, reviewProjectService.createAutomatedReviewProjectRequest(request));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void createAutomatedReviewProjectRequestRollsBackTransactionOnRuntimeException() {
    CreateAutomatedReviewProjectRequestCommand request =
        createAutomatedReviewProjectRequestCommand();
    RuntimeException failure = new RuntimeException("create automated request failed");
    doThrow(failure).when(reviewProjectService).createAutomatedReviewProjectRequestNoTx(request);

    try {
      reviewProjectService.createAutomatedReviewProjectRequest(request);
      fail("Expected createAutomatedReviewProjectRequest to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void searchReviewProjectsCommitsReadOnlyTransaction() {
    SearchReviewProjectsCriteria request = searchReviewProjectsCriteria();
    SearchReviewProjectsView result = new SearchReviewProjectsView(List.of());
    doReturn(result).when(reviewProjectService).searchReviewProjectsNoTx(request);

    assertEquals(result, reviewProjectService.searchReviewProjects(request));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(true, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void searchReviewProjectRequestsRollsBackReadOnlyTransactionOnRuntimeException() {
    SearchReviewProjectsCriteria request = searchReviewProjectsCriteria();
    RuntimeException failure = new RuntimeException("search failed");
    doThrow(failure).when(reviewProjectService).searchReviewProjectRequestsNoTx(request);

    try {
      reviewProjectService.searchReviewProjectRequests(request);
      fail("Expected searchReviewProjectRequests to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(true, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void getProjectDetailCommitsReadOnlyTransaction() {
    GetProjectDetailView result =
        new GetProjectDetailView(
            1L, null, null, null, null, null, null, 0, 0, null, null, null, List.of());
    doReturn(result).when(reviewProjectService).getProjectDetailNoTx(1L);

    assertEquals(result, reviewProjectService.getProjectDetail(1L));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(true, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void getProjectDetailRollsBackReadOnlyTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("detail failed");
    doThrow(failure).when(reviewProjectService).getProjectDetailNoTx(1L);

    try {
      reviewProjectService.getProjectDetail(1L);
      fail("Expected getProjectDetail to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(true, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void updateProjectStatusCommitsTransaction() {
    GetProjectDetailView result =
        new GetProjectDetailView(
            1L, null, null, null, null, null, null, 0, 0, null, null, null, List.of());
    doReturn(result)
        .when(reviewProjectService)
        .updateProjectStatusNoTx(1L, ReviewProjectStatus.CLOSED, "done");

    assertEquals(
        result, reviewProjectService.updateProjectStatus(1L, ReviewProjectStatus.CLOSED, "done"));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateProjectStatusRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("status failed");
    doThrow(failure)
        .when(reviewProjectService)
        .updateProjectStatusNoTx(1L, ReviewProjectStatus.CLOSED, "done");

    try {
      reviewProjectService.updateProjectStatus(1L, ReviewProjectStatus.CLOSED, "done");
      fail("Expected updateProjectStatus to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void updateProjectRequestCommitsTransaction() {
    GetProjectDetailView result =
        new GetProjectDetailView(
            1L, null, null, null, null, null, null, 0, 0, null, null, null, List.of());
    doReturn(result)
        .when(reviewProjectService)
        .updateProjectRequestNoTx(1L, "name", "notes", null, null, null, null, false);

    assertEquals(
        result,
        reviewProjectService.updateProjectRequest(
            1L, "name", "notes", null, null, null, null, false));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateProjectRequestRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("request failed");
    doThrow(failure)
        .when(reviewProjectService)
        .updateProjectRequestNoTx(1L, "name", "notes", null, null, null, null, false);

    try {
      reviewProjectService.updateProjectRequest(1L, "name", "notes", null, null, null, null, false);
      fail("Expected updateProjectRequest to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void updateProjectAssignmentCommitsTransaction() {
    GetProjectDetailView result =
        new GetProjectDetailView(
            1L, null, null, null, null, null, null, 0, 0, null, null, null, List.of());
    doReturn(result)
        .when(reviewProjectService)
        .updateProjectAssignmentNoTx(1L, 2L, 3L, 4L, "assignment");

    assertEquals(
        result, reviewProjectService.updateProjectAssignment(1L, 2L, 3L, 4L, "assignment"));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateProjectAssignmentRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("assignment failed");
    doThrow(failure)
        .when(reviewProjectService)
        .updateProjectAssignmentNoTx(1L, 2L, 3L, 4L, "assignment");

    try {
      reviewProjectService.updateProjectAssignment(1L, 2L, 3L, 4L, "assignment");
      fail("Expected updateProjectAssignment to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void claimProjectTranslatorAssignmentCommitsTransaction() {
    GetProjectDetailView result =
        new GetProjectDetailView(
            1L, null, null, null, null, null, null, 0, 0, null, null, null, List.of());
    doReturn(result).when(reviewProjectService).claimProjectTranslatorAssignmentNoTx(1L);

    assertEquals(result, reviewProjectService.claimProjectTranslatorAssignment(1L));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void claimProjectTranslatorAssignmentRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("claim failed");
    doThrow(failure).when(reviewProjectService).claimProjectTranslatorAssignmentNoTx(1L);

    try {
      reviewProjectService.claimProjectTranslatorAssignment(1L);
      fail("Expected claimProjectTranslatorAssignment to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void updateProjectDueDateCommitsTransaction() {
    ZonedDateTime dueDate = ZonedDateTime.parse("2026-05-12T09:00:00Z");
    GetProjectDetailView result =
        new GetProjectDetailView(
            1L, null, null, null, null, null, null, 0, 0, null, null, null, List.of());
    doReturn(result).when(reviewProjectService).updateProjectDueDateNoTx(1L, dueDate);

    assertEquals(result, reviewProjectService.updateProjectDueDate(1L, dueDate));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateProjectDueDateRollsBackTransactionOnRuntimeException() {
    ZonedDateTime dueDate = ZonedDateTime.parse("2026-05-12T09:00:00Z");
    RuntimeException failure = new RuntimeException("due date failed");
    doThrow(failure).when(reviewProjectService).updateProjectDueDateNoTx(1L, dueDate);

    try {
      reviewProjectService.updateProjectDueDate(1L, dueDate);
      fail("Expected updateProjectDueDate to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void updateRequestAssignedPmCommitsTransaction() {
    doReturn(2).when(reviewProjectService).updateRequestAssignedPmNoTx(44L, 202L, "pm change");

    assertEquals(2, reviewProjectService.updateRequestAssignedPm(44L, 202L, "pm change"));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(false, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateRequestAssignedPmRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("pm failed");
    doThrow(failure).when(reviewProjectService).updateRequestAssignedPmNoTx(44L, 202L, "pm change");

    try {
      reviewProjectService.updateRequestAssignedPm(44L, 202L, "pm change");
      fail("Expected updateRequestAssignedPm to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private CreateGlossaryTermCandidateReviewProjectJobInput
      createGlossaryTermCandidateReviewProjectJobInput() {
    return new CreateGlossaryTermCandidateReviewProjectJobInput(
        1L,
        "name",
        null,
        ZonedDateTime.now(),
        null,
        false,
        List.of(2L),
        List.of(),
        null,
        null,
        null,
        99L);
  }

  private CreateReviewProjectRequestCommand createReviewProjectRequestCommand() {
    return new CreateReviewProjectRequestCommand(
        List.of("fr"),
        null,
        List.of(1001L),
        null,
        StatusFilter.ALL,
        false,
        ReviewProjectType.NORMAL,
        ZonedDateTime.parse("2026-03-30T12:00:00Z"),
        List.of(),
        "name",
        null,
        false,
        99L,
        null);
  }

  private CreateAutomatedReviewProjectRequestCommand createAutomatedReviewProjectRequestCommand() {
    return new CreateAutomatedReviewProjectRequestCommand(
        53L, "name", null, ZonedDateTime.parse("2026-03-30T12:00:00Z"), 7L, null, true, 99L);
  }

  private SearchReviewProjectsCriteria searchReviewProjectsCriteria() {
    return new SearchReviewProjectsCriteria(
        List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, 10, null, null,
        null);
  }

  @Test
  public void updateProjectAssignmentDoesNotNotifyForPmOnlyReassignment() {
    Team team = team(7L);
    User previousPm = user(101L, "pm-a");
    User nextPm = user(102L, "pm-b");
    User translator = user(103L, "translator-a");
    ReviewProject project = project(11L, team, locale(13L, "fr-FR"), previousPm, translator);

    when(reviewProjectRepository.findById(11L)).thenReturn(Optional.of(project));
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(userRepository.findById(102L)).thenReturn(Optional.of(nextPm));
    when(userRepository.findById(103L)).thenReturn(Optional.of(translator));
    when(teamService.isUserInTeamRole(7L, 102L, TeamUserRole.PM)).thenReturn(true);
    when(teamService.isUserInTeamRole(7L, 103L, TeamUserRole.TRANSLATOR)).thenReturn(true);

    reviewProjectService.updateProjectAssignment(11L, 7L, 102L, 103L, "pm only");

    verify(reviewProjectAssignmentHistoryRepository)
        .save(any(ReviewProjectAssignmentHistory.class));
    verify(teamSlackNotificationService, never())
        .sendReviewProjectAssignmentNotification(any(), any(), any());
  }

  @Test
  public void translatorCannotCloseIncompleteProject() {
    setCurrentUserRole(false, false, true);
    ReviewProject project = project(11L, team(7L), locale(13L, "fr-FR"), null, currentUser);
    project.setTextUnitCount(3);
    project.setDecidedCount(2L);
    when(reviewProjectRepository.findById(11L)).thenReturn(Optional.of(project));

    try {
      reviewProjectService.updateProjectStatus(11L, ReviewProjectStatus.CLOSED, null);
      fail("Expected AccessDeniedException");
    } catch (AccessDeniedException exception) {
      assertEquals(
          "Translators can only close projects after they are 100% complete",
          exception.getMessage());
    }

    verify(reviewProjectRepository, never()).save(project);
  }

  @Test
  public void translatorCanCloseCompleteProject() {
    setCurrentUserRole(false, false, true);
    ReviewProject project = project(11L, team(7L), locale(13L, "fr-FR"), null, currentUser);
    project.setTextUnitCount(3);
    project.setDecidedCount(3L);
    when(reviewProjectRepository.findById(11L)).thenReturn(Optional.of(project));

    reviewProjectService.updateProjectStatus(11L, ReviewProjectStatus.CLOSED, null);

    assertEquals(ReviewProjectStatus.CLOSED, project.getStatus());
    verify(reviewProjectRepository).save(project);
  }

  @Test
  public void pmCanCloseIncompleteProject() {
    setCurrentUserRole(false, true, false);
    ReviewProject project = project(11L, team(7L), locale(13L, "fr-FR"), currentUser, null);
    project.setTextUnitCount(3);
    project.setDecidedCount(2L);
    when(reviewProjectRepository.findById(11L)).thenReturn(Optional.of(project));

    reviewProjectService.updateProjectStatus(11L, ReviewProjectStatus.CLOSED, null);

    assertEquals(ReviewProjectStatus.CLOSED, project.getStatus());
    verify(reviewProjectRepository).save(project);
  }

  @Test
  public void updateProjectAssignmentNotifiesWhenTranslatorChanges() {
    Team team = team(7L);
    User pm = user(101L, "pm-a");
    User previousTranslator = user(103L, "translator-a");
    User nextTranslator = user(104L, "translator-b");
    ReviewProject project = project(12L, team, locale(14L, "ja-JP"), pm, previousTranslator);

    when(reviewProjectRepository.findById(12L)).thenReturn(Optional.of(project));
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(userRepository.findById(101L)).thenReturn(Optional.of(pm));
    when(userRepository.findById(104L)).thenReturn(Optional.of(nextTranslator));
    when(teamService.isUserInTeamRole(7L, 101L, TeamUserRole.PM)).thenReturn(true);
    when(teamService.isUserInTeamRole(7L, 104L, TeamUserRole.TRANSLATOR)).thenReturn(true);

    reviewProjectService.updateProjectAssignment(12L, 7L, 101L, 104L, "translator change");

    verify(teamSlackNotificationService)
        .sendReviewProjectAssignmentNotification(
            project, ReviewProjectAssignmentEventType.REASSIGNED, "translator change");
  }

  @Test
  public void updateProjectAssignmentTranslatorCanClaimUnassignedProject() {
    setCurrentUserRole(false, false, true);
    Team team = team(7L);
    User pm = user(101L, "pm-a");
    ReviewProject project = project(13L, team, locale(15L, "fr-FR"), pm, null);

    when(reviewProjectRepository.findById(13L)).thenReturn(Optional.of(project));
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(userRepository.findById(101L)).thenReturn(Optional.of(pm));
    when(teamUserRepository.findByUserIdAndRole(99L, TeamUserRole.TRANSLATOR))
        .thenReturn(List.of(teamUser(team, currentUser, TeamUserRole.TRANSLATOR)));
    when(teamService.isUserInTeamRole(7L, 101L, TeamUserRole.PM)).thenReturn(true);
    when(teamService.isUserInTeamRole(7L, 99L, TeamUserRole.TRANSLATOR)).thenReturn(true);

    reviewProjectService.updateProjectAssignment(13L, 7L, 101L, 99L, "claim");

    assertEquals(currentUser, project.getAssignedTranslatorUser());
    verify(reviewProjectAssignmentHistoryRepository)
        .save(any(ReviewProjectAssignmentHistory.class));
  }

  @Test
  public void claimProjectTranslatorAssignmentUsesCurrentUser() {
    setCurrentUserRole(false, false, true);
    Team team = team(7L);
    User pm = user(101L, "pm-a");
    ReviewProject project = project(16L, team, locale(18L, "fr-FR"), pm, null);

    when(reviewProjectRepository.findById(16L)).thenReturn(Optional.of(project));
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(userRepository.findById(101L)).thenReturn(Optional.of(pm));
    when(teamUserRepository.findByUserIdAndRole(99L, TeamUserRole.TRANSLATOR))
        .thenReturn(List.of(teamUser(team, currentUser, TeamUserRole.TRANSLATOR)));
    when(teamService.isUserInTeamRole(7L, 101L, TeamUserRole.PM)).thenReturn(true);
    when(teamService.isUserInTeamRole(7L, 99L, TeamUserRole.TRANSLATOR)).thenReturn(true);

    reviewProjectService.claimProjectTranslatorAssignment(16L);

    assertEquals(currentUser, project.getAssignedTranslatorUser());
    verify(reviewProjectAssignmentHistoryRepository)
        .save(any(ReviewProjectAssignmentHistory.class));
  }

  @Test
  public void updateProjectAssignmentTranslatorCannotClaimAssignedProject() {
    setCurrentUserRole(false, false, true);
    Team team = team(7L);
    User pm = user(101L, "pm-a");
    User previousTranslator = user(103L, "translator-a");
    ReviewProject project = project(14L, team, locale(16L, "fr-FR"), pm, previousTranslator);

    when(reviewProjectRepository.findById(14L)).thenReturn(Optional.of(project));
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(userRepository.findById(101L)).thenReturn(Optional.of(pm));
    when(teamUserRepository.findByUserIdAndRole(99L, TeamUserRole.TRANSLATOR))
        .thenReturn(List.of(teamUser(team, currentUser, TeamUserRole.TRANSLATOR)));

    try {
      reviewProjectService.updateProjectAssignment(14L, 7L, 101L, 99L, "steal");
      fail("Expected AccessDeniedException");
    } catch (AccessDeniedException e) {
      assertEquals("Translators can only claim unassigned projects", e.getMessage());
    }

    assertEquals(previousTranslator, project.getAssignedTranslatorUser());
    verify(reviewProjectRepository, never()).save(any(ReviewProject.class));
  }

  @Test
  public void updateProjectAssignmentTranslatorCanOnlyAssignThemselves() {
    setCurrentUserRole(false, false, true);
    Team team = team(7L);
    User pm = user(101L, "pm-a");
    User otherTranslator = user(104L, "translator-b");
    ReviewProject project = project(15L, team, locale(17L, "fr-FR"), pm, null);

    when(reviewProjectRepository.findById(15L)).thenReturn(Optional.of(project));
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(userRepository.findById(101L)).thenReturn(Optional.of(pm));
    when(userRepository.findById(104L)).thenReturn(Optional.of(otherTranslator));
    when(teamUserRepository.findByUserIdAndRole(99L, TeamUserRole.TRANSLATOR))
        .thenReturn(List.of(teamUser(team, currentUser, TeamUserRole.TRANSLATOR)));

    try {
      reviewProjectService.updateProjectAssignment(15L, 7L, 101L, 104L, "assign other");
      fail("Expected AccessDeniedException");
    } catch (AccessDeniedException e) {
      assertEquals("Translators can only assign themselves", e.getMessage());
    }

    assertNull(project.getAssignedTranslatorUser());
    verify(reviewProjectRepository, never()).save(any(ReviewProject.class));
  }

  @Test
  public void updateProjectRequestTeamAppliesToAllProjectsAndClearsAssignees() {
    Team previousTeam = team(7L);
    Team nextTeam = team(8L);
    ReviewProjectRequest request = reviewProjectRequest(44L, "Catalog refresh");
    ReviewProject projectA =
        project(21L, previousTeam, locale(31L, "fr-FR"), user(101L, "pm-a"), null);
    ReviewProject projectB =
        project(
            22L,
            previousTeam,
            locale(32L, "de-DE"),
            user(102L, "pm-b"),
            user(103L, "translator-a"));
    projectA.setReviewProjectRequest(request);
    projectB.setReviewProjectRequest(request);

    when(reviewProjectRepository.findById(21L)).thenReturn(Optional.of(projectA));
    when(reviewProjectRepository.findByRequestIdWithAssignment(44L))
        .thenReturn(List.of(projectA, projectB));
    when(teamRepository.findByIdAndEnabledTrue(8L)).thenReturn(Optional.of(nextTeam));

    reviewProjectService.updateProjectRequest(
        21L, "Catalog refresh", "notes", null, null, null, 8L, true);

    assertEquals(nextTeam, projectA.getTeam());
    assertEquals(nextTeam, projectB.getTeam());
    assertNull(projectA.getAssignedPmUser());
    assertNull(projectB.getAssignedPmUser());
    assertNull(projectB.getAssignedTranslatorUser());
    verify(reviewProjectAssignmentHistoryRepository, times(2))
        .save(any(ReviewProjectAssignmentHistory.class));
    verify(teamSlackNotificationService)
        .sendReviewProjectRequestAssignmentNotification(request, List.of(projectA, projectB));
  }

  @Test
  public void updateRequestAssignedPmKeepsBatchNotification() {
    Team team = team(7L);
    ReviewProjectRequest request = reviewProjectRequest(44L, "Catalog refresh");
    User nextPm = user(202L, "pm-b");
    ReviewProject projectA = project(21L, team, locale(31L, "fr-FR"), user(101L, "pm-a"), null);
    ReviewProject projectB = project(22L, team, locale(32L, "de-DE"), user(101L, "pm-a"), null);
    projectA.setReviewProjectRequest(request);
    projectB.setReviewProjectRequest(request);

    when(reviewProjectRepository.findByRequestIdWithAssignment(44L))
        .thenReturn(List.of(projectA, projectB));
    when(userRepository.findById(202L)).thenReturn(Optional.of(nextPm));
    when(teamService.isUserInTeamRole(7L, 202L, TeamUserRole.PM)).thenReturn(true);

    reviewProjectService.updateRequestAssignedPm(44L, 202L, "batch pm change");

    verify(reviewProjectAssignmentHistoryRepository, times(2))
        .save(any(ReviewProjectAssignmentHistory.class));
    verify(teamSlackNotificationService)
        .sendReviewProjectRequestAssignmentNotification(request, List.of(projectA, projectB));
  }

  @Test
  public void updateRequestAssignedPmAllowsTranslatorDeciderForTerminologyRequests() {
    Team team = team(7L);
    ReviewProjectRequest request = reviewProjectRequest(44L, "Terminology source review");
    User decider = user(202L, "translator-decider");
    ReviewProject projectA = project(21L, team, locale(31L, "en"), null, user(103L, "advisor-a"));
    ReviewProject projectB = project(22L, team, locale(31L, "en"), null, user(104L, "advisor-b"));
    projectA.setType(ReviewProjectType.TERMINOLOGY);
    projectB.setType(ReviewProjectType.TERMINOLOGY);
    projectA.setReviewProjectRequest(request);
    projectB.setReviewProjectRequest(request);

    when(reviewProjectRepository.findByRequestIdWithAssignment(44L))
        .thenReturn(List.of(projectA, projectB));
    when(userRepository.findById(202L)).thenReturn(Optional.of(decider));
    when(teamService.isUserInTeamRole(7L, 202L, TeamUserRole.PM)).thenReturn(false);
    when(teamService.isUserInTeamRole(7L, 202L, TeamUserRole.TRANSLATOR)).thenReturn(true);

    reviewProjectService.updateRequestAssignedPm(44L, 202L, "decider change");

    assertEquals(decider, projectA.getAssignedPmUser());
    assertEquals(decider, projectB.getAssignedPmUser());
    verify(teamSlackNotificationService)
        .sendReviewProjectRequestAssignmentNotification(request, List.of(projectA, projectB));
  }

  @Test
  public void updateProjectAssignmentAllowsTranslatorDeciderForTerminologyProject() {
    Team team = team(7L);
    User decider = user(202L, "translator-decider");
    User advisor = user(103L, "advisor-a");
    ReviewProject project = project(21L, team, locale(31L, "en"), null, advisor);
    project.setType(ReviewProjectType.TERMINOLOGY);

    when(reviewProjectRepository.findById(21L)).thenReturn(Optional.of(project));
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(userRepository.findById(202L)).thenReturn(Optional.of(decider));
    when(userRepository.findById(103L)).thenReturn(Optional.of(advisor));
    when(teamService.isUserInTeamRole(7L, 202L, TeamUserRole.PM)).thenReturn(false);
    when(teamService.isUserInTeamRole(7L, 202L, TeamUserRole.TRANSLATOR)).thenReturn(true);
    when(teamService.isUserInTeamRole(7L, 103L, TeamUserRole.TRANSLATOR)).thenReturn(true);

    reviewProjectService.updateProjectAssignment(21L, 7L, 202L, 103L, "decider change");

    assertEquals(decider, project.getAssignedPmUser());
    verify(reviewProjectAssignmentHistoryRepository)
        .save(any(ReviewProjectAssignmentHistory.class));
  }

  @Test
  public void adminBatchDeleteProjectsDeletesOrphanRequestSlackThreads() {
    when(reviewProjectRepository.findRequestIdsByProjectIds(List.of(11L, 12L)))
        .thenReturn(List.of(44L));
    when(reviewProjectRepository.deleteByProjectIds(List.of(11L, 12L))).thenReturn(2);
    when(reviewProjectRequestRepository.findOrphanRequestIds(List.of(44L)))
        .thenReturn(List.of(44L));

    reviewProjectService.adminBatchDeleteProjects(List.of(11L, 12L));

    verify(reviewProjectAssignmentHistoryRepository).deleteByReviewProjectIds(List.of(11L, 12L));
    verify(reviewProjectTimeSpentStatService).deleteByReviewProjectIds(List.of(11L, 12L));
    verify(reviewProjectAssignmentWindowRepository).deleteByReviewProjectIds(List.of(11L, 12L));
    verify(reviewProjectTextUnitDecisionRepository).deleteByReviewProjectIds(List.of(11L, 12L));
    verify(reviewProjectTextUnitFeedbackRepository).deleteByReviewProjectIds(List.of(11L, 12L));
    verify(reviewProjectTextUnitRepository).deleteByReviewProjectIds(List.of(11L, 12L));
    InOrder deleteOrder =
        Mockito.inOrder(
            reviewProjectTimeSpentStatService,
            reviewProjectAssignmentWindowRepository,
            reviewProjectRepository);
    deleteOrder
        .verify(reviewProjectTimeSpentStatService)
        .deleteByReviewProjectIds(List.of(11L, 12L));
    deleteOrder
        .verify(reviewProjectAssignmentWindowRepository)
        .deleteByReviewProjectIds(List.of(11L, 12L));
    deleteOrder.verify(reviewProjectRepository).deleteByProjectIds(List.of(11L, 12L));
    verify(reviewProjectRequestScreenshotRepository).deleteByReviewProjectRequestIdIn(List.of(44L));
    verify(reviewProjectRequestSlackThreadRepository)
        .deleteByReviewProjectRequestIdIn(List.of(44L));
    verify(reviewProjectRequestRepository).deleteAllById(List.of(44L));
  }

  @Test
  public void adminRecomputeRequestDecidedCountsDelegatesToRepository() {
    when(reviewProjectRepository.recomputeDecidedCountsByRequestId(44L)).thenReturn(2);

    int affected = reviewProjectService.adminRecomputeRequestDecidedCounts(44L);

    assertEquals(2, affected);
    verify(reviewProjectRepository).recomputeDecidedCountsByRequestId(44L);
  }

  @Test
  public void createReviewProjectRequestFiltersSelectedTextUnitsByStatus() {
    Locale locale = locale(41L, "ar");
    when(localeService.findByBcp47Tag("ar")).thenReturn(locale);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());

    reviewProjectService.createReviewProjectRequest(
        new CreateReviewProjectRequestCommand(
            List.of("ar"),
            null,
            List.of(1001L, 1002L),
            null,
            StatusFilter.NOT_ACCEPTED,
            false,
            ReviewProjectType.NORMAL,
            ZonedDateTime.parse("2026-03-30T12:00:00Z"),
            List.of(),
            "Manual selected ids",
            null,
            true,
            99L,
            null));

    ArgumentCaptor<TextUnitSearcherParameters> parametersCaptor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(parametersCaptor.capture());
    assertEquals(StatusFilter.NOT_ACCEPTED, parametersCaptor.getValue().getStatusFilter());
    assertEquals(List.of(1001L, 1002L), parametersCaptor.getValue().getTmTextUnitIds());
  }

  @Test
  public void createReviewProjectRequestSearchesSelectedRepositories() {
    Locale locale = locale(41L, "ar");
    when(localeService.findByBcp47Tag("ar")).thenReturn(locale);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());

    reviewProjectService.createReviewProjectRequest(
        new CreateReviewProjectRequestCommand(
            List.of("ar"),
            null,
            null,
            null,
            List.of(71L, 72L, 71L),
            null,
            false,
            ReviewProjectType.NORMAL,
            ZonedDateTime.parse("2026-03-30T12:00:00Z"),
            List.of(),
            "Manual repositories",
            null,
            true,
            99L,
            null));

    ArgumentCaptor<TextUnitSearcherParameters> parametersCaptor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(parametersCaptor.capture());
    assertEquals(StatusFilter.REVIEW_NEEDED, parametersCaptor.getValue().getStatusFilter());
    assertEquals(List.of(71L, 72L), parametersCaptor.getValue().getRepositoryIds());
  }

  @Test
  public void createReviewProjectRequestUsesSourceTextUnitsForTerminologyProjects() {
    Locale locale = locale(41L, "en");
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(1001L);
    tmTextUnit.setName("term.acme");
    tmTextUnit.setContent("Acme");
    tmTextUnit.setComment("Brand term");

    when(localeService.findByBcp47Tag("en")).thenReturn(locale);
    when(tmTextUnitRepository.findByIdIn(List.of(1001L))).thenReturn(List.of(tmTextUnit));
    when(entityManager.getReference(TMTextUnit.class, 1001L)).thenReturn(tmTextUnit);
    when(reviewProjectRequestRepository.save(any(ReviewProjectRequest.class)))
        .thenAnswer(
            invocation -> {
              ReviewProjectRequest request = invocation.getArgument(0);
              request.setId(44L);
              return request;
            });
    when(reviewProjectRepository.save(any(ReviewProject.class)))
        .thenAnswer(
            invocation -> {
              ReviewProject project = invocation.getArgument(0);
              project.setId(12L);
              return project;
            });
    when(wordCountService.getEnglishWordCount("Acme")).thenReturn(1);

    CreateReviewProjectRequestResult result =
        reviewProjectService.createReviewProjectRequest(
            new CreateReviewProjectRequestCommand(
                List.of("en"),
                null,
                List.of(1001L),
                null,
                StatusFilter.ALL,
                false,
                ReviewProjectType.TERMINOLOGY,
                ZonedDateTime.parse("2026-03-30T12:00:00Z"),
                List.of(),
                "Terminology source review",
                null,
                false,
                99L,
                null));

    assertEquals(List.of(12L), result.projectIds());
    assertEquals(1, result.createdLocaleCount());
    verify(textUnitSearcher, never()).search(any(TextUnitSearcherParameters.class));
    verify(reviewProjectTextUnitRepository).save(any(ReviewProjectTextUnit.class));
  }

  @Test
  public void createReviewProjectRequestCreatesTerminologyPhaseProjects() {
    Team team = team(7L);
    Locale locale = locale(41L, "en");
    User defaultPm = user(101L, "default-pm");
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(1001L);
    tmTextUnit.setName("term.acme");
    tmTextUnit.setContent("Acme");
    tmTextUnit.setComment("Brand term");
    ZonedDateTime specialistDueDate = ZonedDateTime.parse("2026-03-30T12:00:00Z");
    ZonedDateTime pmDueDate = ZonedDateTime.parse("2026-03-31T12:00:00Z");

    when(localeService.findByBcp47Tag("en")).thenReturn(locale);
    when(teamRepository.findByIdAndEnabledTrue(7L)).thenReturn(Optional.of(team));
    when(teamService.getPmPool(7L)).thenReturn(List.of(101L));
    when(userRepository.findById(101L)).thenReturn(Optional.of(defaultPm));
    when(tmTextUnitRepository.findByIdIn(List.of(1001L))).thenReturn(List.of(tmTextUnit));
    when(entityManager.getReference(TMTextUnit.class, 1001L)).thenReturn(tmTextUnit);
    when(reviewProjectRequestRepository.save(any(ReviewProjectRequest.class)))
        .thenAnswer(
            invocation -> {
              ReviewProjectRequest request = invocation.getArgument(0);
              request.setId(44L);
              return request;
            });
    long[] nextProjectId = {12L};
    when(reviewProjectRepository.save(any(ReviewProject.class)))
        .thenAnswer(
            invocation -> {
              ReviewProject project = invocation.getArgument(0);
              project.setId(nextProjectId[0]++);
              return project;
            });
    when(wordCountService.getEnglishWordCount("Acme")).thenReturn(1);

    CreateReviewProjectRequestResult result =
        reviewProjectService.createReviewProjectRequest(
            new CreateReviewProjectRequestCommand(
                List.of("en"),
                null,
                List.of(1001L),
                null,
                StatusFilter.ALL,
                false,
                ReviewProjectType.TERMINOLOGY,
                specialistDueDate,
                List.of(),
                "Terminology source review",
                7L,
                false,
                99L,
                List.of(
                    new CreateReviewProjectRequestCommand.ProjectSpec(
                        ReviewProjectTerminologyPhase.SPECIALIST_INPUT,
                        specialistDueDate,
                        null,
                        null),
                    new CreateReviewProjectRequestCommand.ProjectSpec(
                        ReviewProjectTerminologyPhase.PM_RESOLUTION, pmDueDate, null, null))));

    assertEquals(List.of(12L, 13L), result.projectIds());
    assertEquals(1, result.createdLocaleCount());
    assertEquals(2, result.localeResults().get(0).projectCount());
    ArgumentCaptor<ReviewProject> projectCaptor = ArgumentCaptor.forClass(ReviewProject.class);
    verify(reviewProjectRepository, times(2)).save(projectCaptor.capture());
    assertEquals(
        ReviewProjectTerminologyPhase.SPECIALIST_INPUT,
        projectCaptor.getAllValues().get(0).getTerminologyPhase());
    assertEquals(specialistDueDate, projectCaptor.getAllValues().get(0).getDueDate());
    assertNull(projectCaptor.getAllValues().get(0).getAssignedPmUser());
    assertEquals(
        ReviewProjectTerminologyPhase.PM_RESOLUTION,
        projectCaptor.getAllValues().get(1).getTerminologyPhase());
    assertEquals(pmDueDate, projectCaptor.getAllValues().get(1).getDueDate());
    assertNull(projectCaptor.getAllValues().get(1).getAssignedPmUser());
    verify(reviewProjectTextUnitRepository, times(2)).save(any(ReviewProjectTextUnit.class));
  }

  @Test
  public void createReviewProjectRequestUsesExplicitStatusFilterForReviewFeature() {
    Locale locale = locale(42L, "am");
    when(localeService.findByBcp47Tag("am")).thenReturn(locale);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());
    when(reviewFeatureRepository.findByIdWithRepositories(51L))
        .thenReturn(Optional.of(reviewFeature(51L, "Feature A", repository(71L))));

    reviewProjectService.createReviewProjectRequest(
        new CreateReviewProjectRequestCommand(
            List.of("am"),
            null,
            null,
            51L,
            StatusFilter.APPROVED_AND_NOT_REJECTED,
            false,
            ReviewProjectType.NORMAL,
            ZonedDateTime.parse("2026-03-30T12:00:00Z"),
            List.of(),
            "Manual feature",
            null,
            true,
            99L,
            null));

    ArgumentCaptor<TextUnitSearcherParameters> parametersCaptor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(parametersCaptor.capture());
    assertEquals(
        StatusFilter.APPROVED_AND_NOT_REJECTED, parametersCaptor.getValue().getStatusFilter());
  }

  @Test
  public void createReviewProjectRequestDefaultsReviewFeatureStatusFilterToReviewNeeded() {
    Locale locale = locale(43L, "fr-FR");
    when(localeService.findByBcp47Tag("fr-FR")).thenReturn(locale);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());
    when(reviewFeatureRepository.findByIdWithRepositories(52L))
        .thenReturn(Optional.of(reviewFeature(52L, "Feature B", repository(72L))));

    reviewProjectService.createReviewProjectRequest(
        new CreateReviewProjectRequestCommand(
            List.of("fr-FR"),
            null,
            null,
            52L,
            null,
            false,
            ReviewProjectType.NORMAL,
            ZonedDateTime.parse("2026-03-30T12:00:00Z"),
            List.of(),
            "Manual feature default",
            null,
            true,
            99L,
            null));

    ArgumentCaptor<TextUnitSearcherParameters> parametersCaptor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(parametersCaptor.capture());
    assertEquals(StatusFilter.REVIEW_NEEDED, parametersCaptor.getValue().getStatusFilter());
  }

  @Test
  public void createAutomatedReviewProjectRequestLoadsReviewFeatureWithRepositoryLocales() {
    Locale targetLocale = locale(42L, "fr-FR");
    when(reviewFeatureRepository.findByIdWithRepositories(53L))
        .thenReturn(Optional.of(reviewFeature(53L, "Feature C", repository(71L))));
    when(reviewFeatureRepository.findNonRootLocaleRowsByFeatureId(53L))
        .thenReturn(
            List.of(new ReviewFeatureLocaleRow(targetLocale.getId(), targetLocale.getBcp47Tag())));
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());

    CreateReviewProjectRequestResult result =
        reviewProjectService.createAutomatedReviewProjectRequest(
            new CreateAutomatedReviewProjectRequestCommand(
                53L,
                "Automated feature",
                null,
                ZonedDateTime.parse("2026-03-30T12:00:00Z"),
                7L,
                null,
                true,
                99L));

    assertEquals(1, result.requestedLocaleCount());
    assertEquals("fr-FR", result.localeResults().get(0).localeTag());
    verify(reviewFeatureRepository).findByIdWithRepositories(53L);
    verify(reviewFeatureRepository).findNonRootLocaleRowsByFeatureId(53L);
  }

  @Test
  public void saveDecisionBlocksTranslatorIntegrityBypass() {
    when(userService.isCurrentUserAdmin()).thenReturn(false);
    when(userService.isCurrentUserTranslator()).thenReturn(true);

    Team team = team(7L);
    Locale locale = locale(14L, "ja-JP");
    ReviewProject reviewProject = project(12L, team, locale, user(101L, "pm-a"), currentUser);
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(321L);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(tmTextUnit);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));

    try {
      Mockito.doThrow(new IntegrityCheckException("broken placeholder"))
          .when(tmTextUnitIntegrityCheckService)
          .checkTMTextUnitIntegrity(321L, "Bonjour %");
    } catch (IntegrityCheckException e) {
      throw new RuntimeException(e);
    }

    try {
      reviewProjectService.saveDecision(
          55L,
          "Bonjour %",
          null,
          "ACCEPTED",
          true,
          com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.DECIDED,
          null,
          false,
          null);
      fail("Expected AccessDeniedException");
    } catch (AccessDeniedException e) {
      assertEquals(
          "This translation failed the placeholder/integrity check. "
              + "Please fix the translation and try saving again.",
          e.getMessage());
    }

    verify(tmTextUnitIntegrityCheckService).checkTMTextUnitIntegrity(eq(321L), eq("Bonjour %"));
    verify(tmService, never())
        .addTMTextUnitCurrentVariantWithResult(
            any(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any());
    assertNotNull(
        meterRegistry
            .find("ReviewProjectService.saveDecisionDuration")
            .tag("phase", "initialRead")
            .tag("result", "success")
            .tag("hasTarget", "true")
            .timer());
    assertNotNull(
        meterRegistry
            .find("ReviewProjectService.saveDecisionDuration")
            .tag("phase", "integrityCheck")
            .tag("result", "failure")
            .tag("hasTarget", "true")
            .timer());
    assertNotNull(
        meterRegistry
            .find("ReviewProjectService.saveDecisionDuration")
            .tag("phase", "total")
            .tag("result", "forbidden")
            .tag("hasTarget", "true")
            .timer());
  }

  @Test
  public void saveDecisionRecordsConflictMetric() {
    Team team = team(7L);
    Locale locale = locale(14L, "ja-JP");
    ReviewProject reviewProject = project(12L, team, locale, user(101L, "pm-a"), currentUser);
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(321L);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(tmTextUnit);

    TMTextUnitVariant currentVariant = new TMTextUnitVariant();
    currentVariant.setId(777L);
    TMTextUnitCurrentVariant current = new TMTextUnitCurrentVariant();
    current.setTmTextUnitVariant(currentVariant);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(14L, 321L))
        .thenReturn(current);
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));

    try {
      reviewProjectService.saveDecision(
          55L,
          "Bonjour",
          null,
          "ACCEPTED",
          true,
          com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.DECIDED,
          776L,
          false,
          null);
      fail("Expected ReviewProjectCurrentVariantConflictException");
    } catch (ReviewProjectCurrentVariantConflictException e) {
      assertEquals(Long.valueOf(776L), e.getExpectedVariantId());
      assertEquals(Long.valueOf(777L), e.getCurrentVariantId());
    }

    assertNotNull(
        meterRegistry
            .find("ReviewProjectService.saveDecisionDuration")
            .tag("phase", "initialRead")
            .tag("result", "conflict")
            .tag("hasTarget", "true")
            .timer());
    assertNotNull(
        meterRegistry
            .find("ReviewProjectService.saveDecisionDuration")
            .tag("phase", "total")
            .tag("result", "conflict")
            .tag("hasTarget", "true")
            .timer());
    verify(tmService, never())
        .addTMTextUnitCurrentVariantWithResult(
            any(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any());
  }

  @Test
  public void saveDecisionRecordsSuccessPhaseMetrics() {
    Team team = team(7L);
    Locale locale = locale(14L, "ja-JP");
    ReviewProject reviewProject = project(12L, team, locale, user(101L, "pm-a"), currentUser);

    TM tm = new TM();
    tm.setId(91L);
    Asset asset = new Asset();
    asset.setId(81L);
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(321L);
    tmTextUnit.setTm(tm);
    tmTextUnit.setAsset(asset);
    tmTextUnit.setWordCount(7);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(tmTextUnit);

    TMTextUnitVariant decisionVariant = new TMTextUnitVariant();
    decisionVariant.setId(888L);
    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setTmTextUnitVariant(decisionVariant);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(tmService.addTMTextUnitCurrentVariantWithResult(
            any(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any()))
        .thenReturn(new AddTMTextUnitCurrentVariantResult(true, currentVariant));
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));

    ReviewProjectTextUnitDetail detail =
        reviewProjectService.saveDecision(
            55L,
            "Bonjour",
            "Accepted",
            "APPROVED",
            true,
            com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.DECIDED,
            null,
            false,
            "Looks good");

    assertEquals(Long.valueOf(55L), detail.reviewProjectTextUnitId());
    verify(reviewProjectTextUnitDecisionRepository).saveAndFlush(any());
    verify(reviewProjectRepository).incrementDecidedProgress(12L, 7L);
    assertEquals(1L, saveDecisionDurationCount("initialRead", "success", true));
    assertEquals(1L, saveDecisionDurationCount("currentVariantWrite", "success", true));
    assertEquals(1L, saveDecisionDurationCount("decisionWrite", "success", true));
    assertEquals(1L, saveDecisionDurationCount("decidedCountUpdate", "success", true));
    assertEquals(1L, saveDecisionDurationCount("detailReload", "success", true));
    assertEquals(1L, saveDecisionDurationCount("total", "success", true));
  }

  @Test
  public void saveDecisionRecordsFailedPhaseMetric() {
    Team team = team(7L);
    Locale locale = locale(14L, "ja-JP");
    ReviewProject reviewProject = project(12L, team, locale, user(101L, "pm-a"), currentUser);

    TM tm = new TM();
    tm.setId(91L);
    Asset asset = new Asset();
    asset.setId(81L);
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(321L);
    tmTextUnit.setTm(tm);
    tmTextUnit.setAsset(asset);
    tmTextUnit.setWordCount(7);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(tmTextUnit);

    TMTextUnitVariant decisionVariant = new TMTextUnitVariant();
    decisionVariant.setId(888L);
    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setTmTextUnitVariant(decisionVariant);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(tmService.addTMTextUnitCurrentVariantWithResult(
            any(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any()))
        .thenReturn(new AddTMTextUnitCurrentVariantResult(true, currentVariant));
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());

    try {
      reviewProjectService.saveDecision(
          55L,
          "Bonjour",
          "Accepted",
          "APPROVED",
          true,
          com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.DECIDED,
          null,
          false,
          "Looks good");
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertEquals("reviewProjectTextUnit with id: 55 not found", e.getMessage());
    }

    assertEquals(1L, saveDecisionDurationCount("initialRead", "success", true));
    assertEquals(1L, saveDecisionDurationCount("detailReload", "bad_request", true));
    assertEquals(1L, saveDecisionDurationCount("total", "bad_request", true));
  }

  @Test
  public void saveDecisionRecordsNoopDetailReloadMetric() {
    Team team = team(7L);
    Locale locale = locale(14L, "ja-JP");
    ReviewProject reviewProject = project(12L, team, locale, user(101L, "pm-a"), currentUser);

    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(321L);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(tmTextUnit);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));

    ReviewProjectTextUnitDetail detail =
        reviewProjectService.saveDecision(
            55L,
            null,
            null,
            null,
            null,
            com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.PENDING,
            null,
            false,
            null);

    assertEquals(Long.valueOf(55L), detail.reviewProjectTextUnitId());
    verify(reviewProjectTextUnitDecisionRepository, never()).saveAndFlush(any());
    verify(reviewProjectRepository, never()).incrementDecidedProgress(anyLong(), anyLong());
    verify(reviewProjectRepository, never()).decrementDecidedProgress(anyLong(), anyLong());
    assertEquals(1L, saveDecisionDurationCount("initialRead", "success", false));
    assertEquals(1L, saveDecisionDurationCount("detailReload", "success", false));
    assertEquals(1L, saveDecisionDurationCount("total", "noop", false));
  }

  @Test
  public void saveTerminologyFeedbackStoresCurrentUserInput() {
    ReviewProject reviewProject =
        project(12L, team(7L), locale(14L, "en"), user(101L, "pm-a"), currentUser);
    reviewProject.setType(ReviewProjectType.TERMINOLOGY);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(reviewProjectTextUnitFeedbackRepository.findByReviewProjectTextUnitIdAndReviewerUserId(
            55L, 99L))
        .thenReturn(Optional.empty());
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));
    when(reviewProjectTextUnitFeedbackRepository
            .findByReviewProjectTextUnitIdOrderByLastModifiedDateDesc(55L))
        .thenReturn(List.of());

    reviewProjectService.saveTerminologyFeedback(
        55L, ReviewProjectTextUnitFeedback.Recommendation.APPROVE, 5, " ship it ");

    ArgumentCaptor<ReviewProjectTextUnitFeedback> feedbackCaptor =
        ArgumentCaptor.forClass(ReviewProjectTextUnitFeedback.class);
    verify(reviewProjectTextUnitFeedbackRepository).saveAndFlush(feedbackCaptor.capture());
    ReviewProjectTextUnitFeedback feedback = feedbackCaptor.getValue();
    assertEquals(reviewProjectTextUnit, feedback.getReviewProjectTextUnit());
    assertEquals(currentUser, feedback.getReviewerUser());
    assertEquals(
        ReviewProjectTextUnitFeedback.Recommendation.APPROVE, feedback.getRecommendation());
    assertEquals(Integer.valueOf(5), feedback.getConfidence());
    assertEquals("ship it", feedback.getNotes());
  }

  @Test
  public void saveTerminologyResolutionUpdatesGlossaryStatusAndMarksDecided() {
    ReviewProject reviewProject =
        project(12L, team(7L), locale(14L, "en"), user(101L, "pm-a"), currentUser);
    reviewProject.setType(ReviewProjectType.TERMINOLOGY);
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(321L);
    tmTextUnit.setWordCount(7);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(tmTextUnit);
    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setStatus(GlossaryTermMetadata.STATUS_CANDIDATE);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(17L, 321L))
        .thenReturn(Optional.of(metadata));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));
    when(reviewProjectTextUnitFeedbackRepository
            .findByReviewProjectTextUnitIdOrderByLastModifiedDateDesc(55L))
        .thenReturn(List.of());

    reviewProjectService.saveTerminologyResolution(
        55L, 17L, GlossaryTermMetadata.STATUS_APPROVED, " final ", true);

    assertEquals(GlossaryTermMetadata.STATUS_APPROVED, metadata.getStatus());
    verify(glossaryTermMetadataRepository).saveAndFlush(metadata);
    ArgumentCaptor<ReviewProjectTextUnitDecision> decisionCaptor =
        ArgumentCaptor.forClass(ReviewProjectTextUnitDecision.class);
    verify(reviewProjectTextUnitDecisionRepository).saveAndFlush(decisionCaptor.capture());
    ReviewProjectTextUnitDecision decision = decisionCaptor.getValue();
    assertEquals(reviewProjectTextUnit, decision.getReviewProjectTextUnit());
    assertEquals(ReviewProjectTextUnitDecision.DecisionState.DECIDED, decision.getDecisionState());
    assertEquals("final", decision.getNotes());
    verify(reviewProjectRepository).incrementDecidedProgress(12L, 7L);
  }

  @Test
  public void saveTermCandidateResolutionWritesBackCandidateReview() {
    ReviewProject reviewProject =
        project(12L, team(7L), locale(14L, "en"), user(101L, "pm-a"), currentUser);
    reviewProject.setType(ReviewProjectType.TERM_CANDIDATE);
    reviewProject.setTerminologyPhase(ReviewProjectTerminologyPhase.PM_RESOLUTION);
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(321L);
    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(tmTextUnit);
    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setId(88L);
    metadata.setStatus(GlossaryTermMetadata.STATUS_CANDIDATE);
    TermIndexCandidate candidate = new TermIndexCandidate();
    candidate.setId(501L);
    GlossaryTermIndexLink link = new GlossaryTermIndexLink();
    link.setGlossaryTermMetadata(metadata);
    link.setTermIndexCandidate(candidate);
    link.setRelationType(GlossaryTermIndexLink.RELATION_TYPE_PRIMARY);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitId(17L, 321L))
        .thenReturn(Optional.of(metadata));
    when(glossaryTermIndexLinkRepository.findByGlossaryTermMetadataId(88L))
        .thenReturn(List.of(link));
    when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));
    when(reviewProjectTextUnitFeedbackRepository
            .findByReviewProjectTextUnitIdOrderByLastModifiedDateDesc(55L))
        .thenReturn(List.of());

    reviewProjectService.saveTerminologyResolution(
        55L, 17L, GlossaryTermMetadata.STATUS_REJECTED, " false positive ", true);

    assertEquals(TermIndexReview.STATUS_REJECTED, candidate.getReviewStatus());
    assertEquals(TermIndexReview.AUTHORITY_HUMAN, candidate.getReviewAuthority());
    assertEquals("false positive", candidate.getReviewRationale());
    assertEquals(currentUser, candidate.getReviewChangedByUser());
    verify(termIndexCandidateRepository).save(candidate);
  }

  @Test
  public void saveTermCandidateResolutionPromotesLinkedCandidateAtPmResolution() {
    ReviewProject reviewProject =
        project(12L, team(7L), locale(14L, "en"), user(101L, "pm-a"), currentUser);
    reviewProject.setType(ReviewProjectType.TERM_CANDIDATE);
    reviewProject.setTerminologyPhase(ReviewProjectTerminologyPhase.PM_RESOLUTION);
    ReviewProjectRequest request = new ReviewProjectRequest();
    request.setId(44L);
    reviewProject.setReviewProjectRequest(request);

    TMTextUnit representativeTextUnit = new TMTextUnit();
    representativeTextUnit.setId(321L);
    TMTextUnit promotedTextUnit = new TMTextUnit();
    promotedTextUnit.setId(654L);
    TermIndexCandidate candidate = new TermIndexCandidate();
    candidate.setId(501L);
    candidate.setTerm("Google Drive");
    candidate.setDefinition("Cloud storage service");
    candidate.setPartOfSpeech("proper noun");
    candidate.setTermType(GlossaryTermMetadata.TERM_TYPE_BRAND);
    candidate.setEnforcement(GlossaryTermMetadata.ENFORCEMENT_SOFT);
    candidate.setDoNotTranslate(null);
    candidate.setConfidence(98);
    candidate.setRationale("Known product name");

    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(representativeTextUnit);
    reviewProjectTextUnit.setTermIndexCandidate(candidate);
    Glossary targetGlossary = new Glossary();
    targetGlossary.setId(17L);
    reviewProjectTextUnit.setTargetGlossary(targetGlossary);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(glossaryTermIndexCurationService.acceptSuggestion(
            eq(17L), eq(501L), any(GlossaryTermIndexCurationService.AcceptSuggestionCommand.class)))
        .thenReturn(
            new GlossaryTermService.TermView(
                88L,
                null,
                null,
                654L,
                "google drive",
                "Google Drive",
                null,
                "Cloud storage service",
                "proper noun",
                GlossaryTermMetadata.TERM_TYPE_BRAND,
                GlossaryTermMetadata.ENFORCEMENT_SOFT,
                GlossaryTermMetadata.STATUS_APPROVED,
                GlossaryTermMetadata.PROVENANCE_AI_EXTRACTED,
                false,
                false,
                501L,
                null,
                null,
                null,
                List.of(),
                List.of()));
    when(tmTextUnitRepository.findById(654L)).thenReturn(Optional.of(promotedTextUnit));
    when(reviewProjectTextUnitRepository
            .findByReviewProject_ReviewProjectRequest_IdAndTermIndexCandidate_Id(44L, 501L))
        .thenReturn(List.of(reviewProjectTextUnit));
    when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));
    when(reviewProjectTextUnitFeedbackRepository
            .findByReviewProjectTextUnitIdOrderByLastModifiedDateDesc(55L))
        .thenReturn(List.of());

    reviewProjectService.saveTerminologyResolution(
        55L, null, GlossaryTermMetadata.STATUS_APPROVED, " approved ", true);

    ArgumentCaptor<GlossaryTermIndexCurationService.AcceptSuggestionCommand> commandCaptor =
        ArgumentCaptor.forClass(GlossaryTermIndexCurationService.AcceptSuggestionCommand.class);
    verify(glossaryTermIndexCurationService)
        .acceptSuggestion(eq(17L), eq(501L), commandCaptor.capture());
    GlossaryTermIndexCurationService.AcceptSuggestionCommand command = commandCaptor.getValue();
    assertEquals("Google Drive", command.source());
    assertEquals("Cloud storage service", command.definition());
    assertEquals(GlossaryTermMetadata.STATUS_APPROVED, command.status());
    assertNull(command.doNotTranslate());
    assertEquals(promotedTextUnit, reviewProjectTextUnit.getTmTextUnit());
    assertEquals(TermIndexReview.STATUS_ACCEPTED, candidate.getReviewStatus());
    assertEquals(TermIndexReview.AUTHORITY_HUMAN, candidate.getReviewAuthority());
    assertEquals("approved", candidate.getReviewRationale());
    verify(reviewProjectTextUnitRepository).saveAll(List.of(reviewProjectTextUnit));
    verify(termIndexCandidateRepository).save(candidate);
  }

  @Test
  public void saveTermCandidateResolutionCanAcceptWithoutGlossaryPromotion() {
    ReviewProject reviewProject =
        project(12L, team(7L), locale(14L, "en"), user(101L, "pm-a"), currentUser);
    reviewProject.setType(ReviewProjectType.TERM_CANDIDATE);
    reviewProject.setTerminologyPhase(ReviewProjectTerminologyPhase.PM_RESOLUTION);
    TMTextUnit representativeTextUnit = new TMTextUnit();
    representativeTextUnit.setId(321L);
    TermIndexCandidate candidate = new TermIndexCandidate();
    candidate.setId(501L);
    candidate.setTerm("Google Drive");

    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(representativeTextUnit);
    reviewProjectTextUnit.setTermIndexCandidate(candidate);
    Glossary targetGlossary = new Glossary();
    targetGlossary.setId(17L);
    reviewProjectTextUnit.setTargetGlossary(targetGlossary);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));
    when(reviewProjectTextUnitFeedbackRepository
            .findByReviewProjectTextUnitIdOrderByLastModifiedDateDesc(55L))
        .thenReturn(List.of());

    reviewProjectService.saveTerminologyResolution(
        55L, null, GlossaryTermMetadata.STATUS_APPROVED, " valid term ", false);

    verify(glossaryTermIndexCurationService, never())
        .acceptSuggestion(
            anyLong(),
            anyLong(),
            any(GlossaryTermIndexCurationService.AcceptSuggestionCommand.class));
    assertEquals(representativeTextUnit, reviewProjectTextUnit.getTmTextUnit());
    assertEquals(TermIndexReview.STATUS_ACCEPTED, candidate.getReviewStatus());
    assertEquals(TermIndexReview.AUTHORITY_HUMAN, candidate.getReviewAuthority());
    assertEquals("valid term", candidate.getReviewRationale());
    verify(termIndexCandidateRepository).save(candidate);
  }

  @Test
  public void saveTermCandidateResolutionRejectsLinkedCandidateWithoutPromoting() {
    ReviewProject reviewProject =
        project(12L, team(7L), locale(14L, "en"), user(101L, "pm-a"), currentUser);
    reviewProject.setType(ReviewProjectType.TERM_CANDIDATE);
    reviewProject.setTerminologyPhase(ReviewProjectTerminologyPhase.PM_RESOLUTION);
    TMTextUnit representativeTextUnit = new TMTextUnit();
    representativeTextUnit.setId(321L);
    TermIndexCandidate candidate = new TermIndexCandidate();
    candidate.setId(501L);

    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setId(55L);
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(representativeTextUnit);
    reviewProjectTextUnit.setTermIndexCandidate(candidate);
    Glossary targetGlossary = new Glossary();
    targetGlossary.setId(17L);
    reviewProjectTextUnit.setTargetGlossary(targetGlossary);

    when(reviewProjectTextUnitRepository.findById(55L))
        .thenReturn(Optional.of(reviewProjectTextUnit));
    when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));
    when(reviewProjectTextUnitDecisionRepository.findByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.empty());
    when(reviewProjectTextUnitRepository.findDetailByReviewProjectTextUnitId(55L))
        .thenReturn(Optional.of(reviewProjectTextUnitDetail(55L)));
    when(reviewProjectTextUnitFeedbackRepository
            .findByReviewProjectTextUnitIdOrderByLastModifiedDateDesc(55L))
        .thenReturn(List.of());

    reviewProjectService.saveTerminologyResolution(
        55L, null, GlossaryTermMetadata.STATUS_REJECTED, " noisy ", true);

    verify(glossaryTermIndexCurationService)
        .ignoreSuggestion(
            eq(17L), eq(501L), any(GlossaryTermIndexCurationService.IgnoreSuggestionCommand.class));
    verify(glossaryTermIndexCurationService, never())
        .acceptSuggestion(
            anyLong(),
            anyLong(),
            any(GlossaryTermIndexCurationService.AcceptSuggestionCommand.class));
    assertEquals(representativeTextUnit, reviewProjectTextUnit.getTmTextUnit());
    assertEquals(TermIndexReview.STATUS_REJECTED, candidate.getReviewStatus());
    assertEquals(TermIndexReview.AUTHORITY_HUMAN, candidate.getReviewAuthority());
    assertEquals("noisy", candidate.getReviewRationale());
    verify(termIndexCandidateRepository).save(candidate);
  }

  @Test
  public void createGlossaryTerminologyReviewProjectBuildsEnProjectFromReviewableTerms() {
    ZonedDateTime dueDate = ZonedDateTime.parse("2026-05-12T09:00:00Z");
    GlossaryTermMetadata candidate =
        glossaryTermMetadata(3L, GlossaryTermMetadata.STATUS_CANDIDATE);
    GlossaryTermMetadata approved = glossaryTermMetadata(2L, GlossaryTermMetadata.STATUS_APPROVED);
    GlossaryTermMetadata rejected = glossaryTermMetadata(4L, GlossaryTermMetadata.STATUS_REJECTED);

    when(glossaryTermMetadataRepository.findByGlossaryId(17L))
        .thenReturn(List.of(rejected, candidate, approved));

    reviewProjectService.createGlossaryTerminologyReviewProjectAsync(
        17L,
        new CreateGlossaryTerminologyReviewProjectCommand(
            "Glossary source review",
            "Review source terms",
            dueDate,
            7L,
            false,
            null,
            null,
            null,
            null,
            null));

    verify(teamService).assertUserCanAccessTeam(7L, 99L);
    ArgumentCaptor<QuartzJobInfo> jobCaptor = ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(quartzPollableTaskScheduler).scheduleJob(jobCaptor.capture());
    CreateReviewProjectRequestCommand command =
        (CreateReviewProjectRequestCommand) jobCaptor.getValue().getInput();

    assertEquals(List.of("en"), command.localeTags());
    assertEquals("Review source terms", command.notes());
    assertEquals(List.of(2L, 3L), command.tmTextUnitIds());
    assertEquals(StatusFilter.ALL, command.statusFilter());
    assertEquals(Boolean.FALSE, command.skipTextUnitsInOpenProjects());
    assertEquals(ReviewProjectType.TERMINOLOGY, command.type());
    assertEquals(dueDate, command.dueDate());
    assertEquals("Glossary source review", command.name());
    assertEquals(Long.valueOf(7L), command.teamId());
    assertEquals(Boolean.FALSE, command.assignTranslator());
    assertEquals(Long.valueOf(99L), command.requestedByUserId());
    assertEquals(1, command.projectSpecs().size());
    assertEquals(
        ReviewProjectTerminologyPhase.PM_RESOLUTION,
        command.projectSpecs().get(0).terminologyPhase());
    assertEquals(dueDate, command.projectSpecs().get(0).dueDate());
  }

  @Test
  public void createGlossaryTermCandidateReviewProjectSchedulesCandidateReviewJob() {
    ZonedDateTime dueDate = ZonedDateTime.parse("2026-05-12T09:00:00Z");

    reviewProjectService.createGlossaryTermCandidateReviewProjectAsync(
        17L,
        new CreateGlossaryTermCandidateReviewProjectCommand(
            "Candidate review",
            "Review candidates",
            dueDate,
            7L,
            false,
            List.of(501L, 502L),
            List.of(88L),
            77L,
            dueDate,
            dueDate.plusDays(1)));

    verify(teamService).assertUserCanAccessTeam(7L, 99L);
    verify(glossaryTermIndexCurationService, never())
        .acceptSuggestion(
            any(), any(), any(GlossaryTermIndexCurationService.AcceptSuggestionCommand.class));
    ArgumentCaptor<QuartzJobInfo> jobCaptor = ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(quartzPollableTaskScheduler).scheduleJob(jobCaptor.capture());
    CreateGlossaryTermCandidateReviewProjectJobInput command =
        (CreateGlossaryTermCandidateReviewProjectJobInput) jobCaptor.getValue().getInput();

    assertEquals(Long.valueOf(17L), command.glossaryId());
    assertEquals("Review candidates", command.notes());
    assertEquals(List.of(501L, 502L), command.termIndexCandidateIds());
    assertEquals("Candidate review", command.name());
    assertEquals(Long.valueOf(7L), command.teamId());
    assertEquals(Boolean.FALSE, command.assignTranslator());
    assertEquals(Long.valueOf(99L), command.requestedByUserId());
    assertEquals(List.of(88L), command.specialistUserIds());
    assertEquals(Long.valueOf(77L), command.pmUserId());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void getTerminologyTermsIncludesGlossaryEvidence() {
    ReviewProject reviewProject = new ReviewProject();
    reviewProject.setType(ReviewProjectType.TERMINOLOGY);
    ReviewProjectTextUnitDetail detail = reviewProjectTextUnitDetail(55L);

    Glossary glossary = new Glossary();
    glossary.setId(17L);
    glossary.setName("Product UI");
    GlossaryTermMetadata metadata =
        glossaryTermMetadata(321L, GlossaryTermMetadata.STATUS_APPROVED);
    metadata.setId(88L);
    metadata.setGlossary(glossary);

    GlossaryTermEvidence evidence = new GlossaryTermEvidence();
    evidence.setId(99L);
    evidence.setGlossaryTermMetadata(metadata);
    evidence.setEvidenceType(GlossaryTermEvidence.EVIDENCE_TYPE_SCREENSHOT);
    evidence.setCaption("Primary sidebar");
    evidence.setImageKey("glossary/screenshots/sidebar.png");
    evidence.setSortOrder(1);

    when(glossaryTermMetadataRepository.findByTmTextUnitIdIn(List.of(321L)))
        .thenReturn(List.of(metadata));
    when(glossaryTermIndexLinkRepository.findByGlossaryTermMetadataIdInAndRelationType(
            List.of(88L), GlossaryTermIndexLink.RELATION_TYPE_PRIMARY))
        .thenReturn(List.of());
    when(glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(
            List.of(88L)))
        .thenReturn(List.of(evidence));

    Map<Long, GetProjectDetailView.TerminologyTerm> termsByTextUnitId =
        ReflectionTestUtils.invokeMethod(
            reviewProjectService,
            "getTerminologyTermsByReviewProjectTextUnitId",
            reviewProject,
            List.of(detail));

    GetProjectDetailView.TerminologyTerm term = termsByTextUnitId.get(55L);
    assertEquals(17L, term.glossaryId().longValue());
    assertEquals(1, term.evidence().size());
    assertEquals("glossary/screenshots/sidebar.png", term.evidence().get(0).imageKey());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void getGlossaryTermEvidenceIncludesNormalReviewTextUnits() {
    ReviewProjectTextUnitDetail detail = reviewProjectTextUnitDetail(55L);

    GlossaryTermMetadata metadata =
        glossaryTermMetadata(321L, GlossaryTermMetadata.STATUS_APPROVED);
    metadata.setId(88L);

    GlossaryTermEvidence evidence = new GlossaryTermEvidence();
    evidence.setId(99L);
    evidence.setGlossaryTermMetadata(metadata);
    evidence.setEvidenceType(GlossaryTermEvidence.EVIDENCE_TYPE_SCREENSHOT);
    evidence.setImageKey("glossary/screenshots/sidebar.png");
    evidence.setSortOrder(1);

    when(glossaryTermMetadataRepository.findByTmTextUnitIdIn(List.of(321L)))
        .thenReturn(List.of(metadata));
    when(glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(
            List.of(88L)))
        .thenReturn(List.of(evidence));

    Map<Long, List<GetProjectDetailView.TerminologyTermEvidence>> evidenceByTextUnitId =
        ReflectionTestUtils.invokeMethod(
            reviewProjectService,
            "getGlossaryTermEvidenceByReviewProjectTextUnitId",
            List.of(detail));

    assertEquals(1, evidenceByTextUnitId.get(55L).size());
    assertEquals(
        "glossary/screenshots/sidebar.png", evidenceByTextUnitId.get(55L).get(0).imageKey());
  }

  private ReviewProject project(
      Long id, Team team, Locale locale, User assignedPmUser, User assignedTranslatorUser) {
    ReviewProject project = new ReviewProject();
    project.setId(id);
    project.setTeam(team);
    project.setLocale(locale);
    project.setAssignedPmUser(assignedPmUser);
    project.setAssignedTranslatorUser(assignedTranslatorUser);
    return project;
  }

  private ReviewProjectRequest reviewProjectRequest(Long id, String name) {
    ReviewProjectRequest request = new ReviewProjectRequest();
    request.setId(id);
    request.setName(name);
    return request;
  }

  private Team team(Long id) {
    Team team = new Team();
    team.setId(id);
    return team;
  }

  private TeamUser teamUser(Team team, User user, TeamUserRole role) {
    TeamUser teamUser = new TeamUser();
    teamUser.setTeam(team);
    teamUser.setUser(user);
    teamUser.setRole(role);
    return teamUser;
  }

  private void setCurrentUserRole(boolean isAdmin, boolean isPm, boolean isTranslator) {
    currentUser = user(99L, "current-user");
    currentUser.setCanTranslateAllLocales(true);
    when(userService.isCurrentUserAdmin()).thenReturn(isAdmin);
    when(userService.isCurrentUserPm()).thenReturn(isPm);
    when(userService.isCurrentUserTranslator()).thenReturn(isTranslator);
    when(userService.isCurrentUserAdminOrPm()).thenReturn(isAdmin || isPm);
    when(userRepository.findById(99L)).thenReturn(Optional.of(currentUser));
  }

  private long saveDecisionDurationCount(String phase, String result, boolean hasTarget) {
    return meterRegistry
        .find("ReviewProjectService.saveDecisionDuration")
        .tag("phase", phase)
        .tag("result", result)
        .tag("hasTarget", Boolean.toString(hasTarget))
        .timer()
        .count();
  }

  private ReviewFeature reviewFeature(Long id, String name, Repository... repositories) {
    ReviewFeature reviewFeature = new ReviewFeature();
    reviewFeature.setId(id);
    reviewFeature.setName(name);
    reviewFeature.setRepositories(Set.of(repositories));
    return reviewFeature;
  }

  private Repository repository(Long id) {
    Repository repository = new Repository();
    repository.setId(id);
    repository.setDeleted(false);
    return repository;
  }

  private Locale locale(Long id, String bcp47Tag) {
    Locale locale = new Locale();
    locale.setId(id);
    locale.setBcp47Tag(bcp47Tag);
    return locale;
  }

  private User user(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }

  private GlossaryTermMetadata glossaryTermMetadata(Long tmTextUnitId, String status) {
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(tmTextUnitId);
    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setTmTextUnit(tmTextUnit);
    metadata.setStatus(status);
    return metadata;
  }

  private ReviewProjectTextUnitDetail reviewProjectTextUnitDetail(Long reviewProjectTextUnitId) {
    return new ReviewProjectTextUnitDetail(
        reviewProjectTextUnitId,
        321L,
        "term.name",
        "Term",
        null,
        ZonedDateTime.parse("2026-03-30T12:00:00Z"),
        1,
        "glossary",
        7L,
        "repo",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
