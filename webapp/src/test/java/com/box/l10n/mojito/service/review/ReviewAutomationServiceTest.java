package com.box.l10n.mojito.service.review;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
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
}
