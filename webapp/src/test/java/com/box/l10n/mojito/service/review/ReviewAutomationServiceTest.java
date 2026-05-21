package com.box.l10n.mojito.service.review;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class ReviewAutomationServiceTest {

  private final ReviewAutomationRepository reviewAutomationRepository =
      Mockito.mock(ReviewAutomationRepository.class);
  private final ReviewFeatureRepository reviewFeatureRepository =
      Mockito.mock(ReviewFeatureRepository.class);
  private final ReviewAutomationRunRepository reviewAutomationRunRepository =
      Mockito.mock(ReviewAutomationRunRepository.class);
  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
  private final UserService userService = Mockito.mock(UserService.class);
  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<ReviewAutomationCronSchedulerService>
      reviewAutomationCronSchedulerServiceProvider = Mockito.mock(ObjectProvider.class);

  private ReviewAutomationService reviewAutomationService;

  @Before
  public void setUp() {
    reviewAutomationService =
        new ReviewAutomationService(
            reviewAutomationRepository,
            reviewFeatureRepository,
            reviewAutomationRunRepository,
            teamRepository,
            userService,
            reviewAutomationCronSchedulerServiceProvider,
            transactionManager);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(transactionManager.getTransaction(Mockito.any())).thenReturn(transactionStatus);
  }

  @Test
  public void deleteReviewAutomationDeletesRunHistoryBeforeAutomation() {
    ReviewAutomation automation = new ReviewAutomation();
    automation.setId(7L);
    when(reviewAutomationRepository.findById(7L)).thenReturn(Optional.of(automation));

    reviewAutomationService.deleteReviewAutomation(7L);

    InOrder inOrder = inOrder(reviewAutomationRunRepository, reviewAutomationRepository);
    inOrder.verify(reviewAutomationRunRepository).deleteByReviewAutomationId(7L);
    inOrder.verify(reviewAutomationRepository).delete(automation);
  }

  @Test
  public void createReviewAutomationAllowsSharedReviewFeatures() {
    Team team = new Team();
    team.setId(2L);
    ReviewFeature reviewFeature = new ReviewFeature();
    reviewFeature.setId(5L);
    reviewFeature.setName("Product UI");
    AtomicReference<ReviewAutomation> savedAutomationReference = new AtomicReference<>();

    when(reviewAutomationRepository.findByNameIgnoreCase("Daily review"))
        .thenReturn(Optional.empty());
    when(teamRepository.findById(2L)).thenReturn(Optional.of(team));
    when(reviewFeatureRepository.findByIdInOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(reviewFeature));
    when(reviewAutomationRunRepository.findLatestRunTimestampsByAutomationIds(List.of(9L)))
        .thenReturn(List.of());
    when(reviewAutomationRunRepository.findLatestSuccessfulRunTimestampsByAutomationIds(
            Mockito.eq(List.of(9L)), Mockito.anyList()))
        .thenReturn(List.of());
    when(reviewAutomationRepository.save(Mockito.any(ReviewAutomation.class)))
        .thenAnswer(
            invocation -> {
              ReviewAutomation automation = invocation.getArgument(0);
              automation.setId(9L);
              savedAutomationReference.set(automation);
              return automation;
            });
    when(reviewAutomationRepository.findByIdWithFeatures(9L))
        .thenAnswer(invocation -> Optional.of(savedAutomationReference.get()));

    reviewAutomationService.createReviewAutomation(
        "Daily review", true, "0 0 9 ? * MON-FRI", "UTC", 2L, 1, 2000, true, List.of(5L));

    ArgumentCaptor<ReviewAutomation> automationCaptor =
        ArgumentCaptor.forClass(ReviewAutomation.class);
    verify(reviewAutomationRepository).save(automationCaptor.capture());
    ReviewAutomation savedAutomation = automationCaptor.getValue();
    org.junit.Assert.assertEquals("Daily review", savedAutomation.getName());
    org.junit.Assert.assertEquals(1, savedAutomation.getFeatures().size());
  }

  @Test
  public void batchUpsertReplaceModeDisablesEnabledAutomationsNotInBatch() {
    Team team = new Team();
    team.setId(2L);
    ReviewAutomation keptAutomation = new ReviewAutomation();
    keptAutomation.setId(7L);
    keptAutomation.setName("Kept");
    keptAutomation.setEnabled(true);
    keptAutomation.setAssignTranslator(true);
    ReviewAutomation omittedAutomation = new ReviewAutomation();
    omittedAutomation.setId(8L);
    omittedAutomation.setName("Omitted");
    omittedAutomation.setEnabled(true);

    when(reviewAutomationRepository.findByIdWithFeatures(7L))
        .thenReturn(Optional.of(keptAutomation));
    when(reviewAutomationRepository.findByNameIgnoreCase("Kept"))
        .thenReturn(Optional.of(keptAutomation));
    when(teamRepository.findById(2L)).thenReturn(Optional.of(team));
    when(reviewAutomationRepository.findAllEnabledWithTeam())
        .thenReturn(List.of(keptAutomation, omittedAutomation));
    when(reviewAutomationRepository.save(Mockito.any(ReviewAutomation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReviewAutomationService.BatchUpsertResult result =
        reviewAutomationService.batchUpsert(
            List.of(
                new ReviewAutomationService.BatchUpsertRow(
                    7L, "Kept", true, "0 0 9 ? * MON-FRI", "UTC", 2L, 1, 2000, true, List.of())),
            ReviewAutomationService.BatchUpsertMode.REPLACE_DISABLE_OMITTED);

    org.junit.Assert.assertEquals(0, result.createdCount());
    org.junit.Assert.assertEquals(1, result.updatedCount());
    org.junit.Assert.assertEquals(1, result.disabledCount());
    org.junit.Assert.assertTrue(keptAutomation.getEnabled());
    org.junit.Assert.assertFalse(omittedAutomation.getEnabled());
    verify(reviewAutomationRepository).save(omittedAutomation);
  }
}
