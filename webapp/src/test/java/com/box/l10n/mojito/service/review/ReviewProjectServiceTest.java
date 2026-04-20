package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.TeamUserRole;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentEventType;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentHistory;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.WordCountService;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.team.TeamSlackNotificationService;
import com.box.l10n.mojito.service.team.TeamUserRepository;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

public class ReviewProjectServiceTest {

  private final ReviewProjectRepository reviewProjectRepository =
      Mockito.mock(ReviewProjectRepository.class);
  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository =
      Mockito.mock(ReviewProjectTextUnitRepository.class);
  private final ReviewProjectTextUnitDecisionRepository reviewProjectTextUnitDecisionRepository =
      Mockito.mock(ReviewProjectTextUnitDecisionRepository.class);
  private final ReviewProjectRequestRepository reviewProjectRequestRepository =
      Mockito.mock(ReviewProjectRequestRepository.class);
  private final ReviewProjectRequestScreenshotRepository reviewProjectRequestScreenshotRepository =
      Mockito.mock(ReviewProjectRequestScreenshotRepository.class);
  private final ReviewProjectRequestSlackThreadRepository
      reviewProjectRequestSlackThreadRepository =
          Mockito.mock(ReviewProjectRequestSlackThreadRepository.class);
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
  private final TeamSlackNotificationService teamSlackNotificationService =
      Mockito.mock(TeamSlackNotificationService.class);
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler =
      Mockito.mock(QuartzPollableTaskScheduler.class);
  private final ReviewFeatureRepository reviewFeatureRepository =
      Mockito.mock(ReviewFeatureRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

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
                reviewProjectRequestRepository,
                reviewProjectRequestScreenshotRepository,
                reviewProjectRequestSlackThreadRepository,
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
                teamSlackNotificationService,
                quartzPollableTaskScheduler,
                reviewFeatureRepository,
                meterRegistry));
    doReturn(null).when(reviewProjectService).getProjectDetail(anyLong());

    currentUser = user(99L, "admin");
    currentUser.setCanTranslateAllLocales(true);

    when(teamService.getCurrentUserIdOrThrow()).thenReturn(99L);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(userService.isCurrentUserPm()).thenReturn(false);
    when(userService.isCurrentUserTranslator()).thenReturn(false);
    when(userRepository.findById(99L)).thenReturn(Optional.of(currentUser));
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
  public void adminBatchDeleteProjectsDeletesOrphanRequestSlackThreads() {
    when(reviewProjectRepository.findRequestIdsByProjectIds(List.of(11L, 12L)))
        .thenReturn(List.of(44L));
    when(reviewProjectRepository.deleteByProjectIds(List.of(11L, 12L))).thenReturn(2);
    when(reviewProjectRequestRepository.findOrphanRequestIds(List.of(44L)))
        .thenReturn(List.of(44L));

    reviewProjectService.adminBatchDeleteProjects(List.of(11L, 12L));

    verify(reviewProjectAssignmentHistoryRepository).deleteByReviewProjectIds(List.of(11L, 12L));
    verify(reviewProjectTextUnitDecisionRepository).deleteByReviewProjectIds(List.of(11L, 12L));
    verify(reviewProjectTextUnitRepository).deleteByReviewProjectIds(List.of(11L, 12L));
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
            99L));

    ArgumentCaptor<TextUnitSearcherParameters> parametersCaptor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(parametersCaptor.capture());
    assertEquals(StatusFilter.NOT_ACCEPTED, parametersCaptor.getValue().getStatusFilter());
    assertEquals(List.of(1001L, 1002L), parametersCaptor.getValue().getTmTextUnitIds());
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
            99L));

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
            99L));

    ArgumentCaptor<TextUnitSearcherParameters> parametersCaptor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    verify(textUnitSearcher).search(parametersCaptor.capture());
    assertEquals(StatusFilter.REVIEW_NEEDED, parametersCaptor.getValue().getStatusFilter());
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
          "You're not authorized to bypass integrity check, please reach out to your PM or admin",
          e.getMessage());
    }

    verify(tmTextUnitIntegrityCheckService).checkTMTextUnitIntegrity(eq(321L), eq("Bonjour %"));
    verify(tmService, never())
        .addTMTextUnitCurrentVariantWithResult(
            any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(),
            any());
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
}
