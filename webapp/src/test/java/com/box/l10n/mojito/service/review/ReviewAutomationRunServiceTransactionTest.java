package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewAutomationRun;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserRepository;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class ReviewAutomationRunServiceTransactionTest {

  @Test
  public void createRunningRunCommitsTransaction() {
    ReviewAutomationRunRepository repository = mock(ReviewAutomationRunRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    User user = new User();
    ZonedDateTime startedAt = ZonedDateTime.now();
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(userRepository.getReferenceById(12L)).thenReturn(user);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    ReviewAutomationRunService service = service(repository, userRepository, transactionManager);

    ReviewAutomationRun run =
        service.createRunningRun(
            new ReviewAutomation(), ReviewAutomationRun.TriggerSource.MANUAL, 12L, 3, startedAt);

    assertEquals(ReviewAutomationRun.Status.RUNNING, run.getStatus());
    assertEquals(user, run.getRequestedByUser());
    assertEquals(startedAt, run.getStartedAt());
    assertEquals(3, run.getFeatureCount());
    verify(repository).save(run);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void markCompletedCommitsTransaction() {
    ReviewAutomationRunRepository repository = mock(ReviewAutomationRunRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    ReviewAutomationRun run = new ReviewAutomationRun();
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findById(3L)).thenReturn(Optional.of(run));
    ReviewAutomationRunService service =
        service(repository, mock(UserRepository.class), transactionManager);

    service.markCompleted(3L, 1, 2, 3, 4, 5);

    assertEquals(ReviewAutomationRun.Status.COMPLETED_WITH_ERRORS, run.getStatus());
    assertNotNull(run.getStartedAt());
    assertNotNull(run.getFinishedAt());
    assertEquals(1, run.getCreatedProjectRequestCount());
    assertEquals(5, run.getErroredLocaleCount());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void markFailedRollsBackTransaction() {
    ReviewAutomationRunRepository repository = mock(ReviewAutomationRunRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findById(3L)).thenThrow(new IllegalStateException("failed"));
    ReviewAutomationRunService service =
        service(repository, mock(UserRepository.class), transactionManager);

    try {
      service.markFailed(3L, 1, 2, 3, 4, 5, "error");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected markFailed to rethrow the repository failure");
  }

  @Test
  public void getRecentRunsUsesReadOnlyTransaction() {
    ReviewAutomationRunRepository repository = mock(ReviewAutomationRunRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    ZonedDateTime now = ZonedDateTime.now();
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findRecentRunRows(any()))
        .thenReturn(
            List.of(
                new ReviewAutomationRunSummaryRow(
                    1L,
                    2L,
                    "automation",
                    ReviewAutomationRun.TriggerSource.CRON,
                    3L,
                    "user",
                    ReviewAutomationRun.Status.COMPLETED,
                    now,
                    now,
                    now,
                    4,
                    5,
                    6,
                    7,
                    8,
                    9,
                    null)));
    ReviewAutomationRunService service =
        service(repository, mock(UserRepository.class), transactionManager);

    List<ReviewAutomationRunService.RunSummary> summaries = service.getRecentRuns(List.of(), 50);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    assertEquals("CRON", summaries.get(0).triggerSource());
    assertEquals("COMPLETED", summaries.get(0).status());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  private ReviewAutomationRunService service(
      ReviewAutomationRunRepository repository,
      UserRepository userRepository,
      PlatformTransactionManager transactionManager) {
    return new ReviewAutomationRunService(repository, userRepository, transactionManager);
  }
}
