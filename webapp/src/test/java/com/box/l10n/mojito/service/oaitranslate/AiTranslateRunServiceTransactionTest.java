package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserRepository;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class AiTranslateRunServiceTransactionTest {

  @Test
  public void createScheduledRunCommitsTransaction() {
    AiTranslateRunRepository repository = mock(AiTranslateRunRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    User user = new User();
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(userRepository.getReferenceById(12L)).thenReturn(user);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AiTranslateRunService service = service(repository, userRepository, transactionManager);

    AiTranslateRun run =
        service.createScheduledRun(
            AiTranslateRun.TriggerSource.MANUAL,
            new Repository(),
            12L,
            new PollableTask(),
            "model",
            "FOR_REVIEW",
            "ALL",
            10);

    assertEquals(AiTranslateRun.Status.SCHEDULED, run.getStatus());
    assertEquals(user, run.getRequestedByUser());
    verify(repository).save(run);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void markCompletedCommitsTransaction() {
    AiTranslateRunRepository repository = mock(AiTranslateRunRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    AiTranslateRun run = new AiTranslateRun();
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findByPollableTask_Id(3L)).thenReturn(Optional.of(run));
    AiTranslateRunService service =
        service(repository, mock(UserRepository.class), transactionManager);

    service.markCompleted(
        3L, new AiTranslateService.AiTranslateRunTotals(1, 2, 3, 4, BigDecimal.valueOf(0.12)));

    assertEquals(AiTranslateRun.Status.COMPLETED, run.getStatus());
    assertNotNull(run.getStartedAt());
    assertNotNull(run.getFinishedAt());
    assertEquals(1, run.getInputTokens());
    assertEquals(BigDecimal.valueOf(0.12), run.getEstimatedCostUsd());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void markRunningRollsBackTransaction() {
    AiTranslateRunRepository repository = mock(AiTranslateRunRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findByPollableTask_Id(3L)).thenThrow(new IllegalStateException("failed"));
    AiTranslateRunService service =
        service(repository, mock(UserRepository.class), transactionManager);

    try {
      service.markRunning(3L);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected markRunning to rethrow the repository failure");
  }

  @Test
  public void getRecentRunsUsesReadOnlyTransaction() {
    AiTranslateRunRepository repository = mock(AiTranslateRunRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findRecentRunRows(any()))
        .thenReturn(
            List.of(
                new AiTranslateRunSummaryRow(
                    1L,
                    AiTranslateRun.TriggerSource.CRON,
                    2L,
                    "repo",
                    3L,
                    4L,
                    "model",
                    "FOR_REVIEW",
                    "ALL",
                    10,
                    AiTranslateRun.Status.COMPLETED,
                    ZonedDateTime.now(),
                    ZonedDateTime.now(),
                    ZonedDateTime.now(),
                    1,
                    2,
                    3,
                    4,
                    BigDecimal.valueOf(0.12))));
    AiTranslateRunService service =
        service(repository, mock(UserRepository.class), transactionManager);

    List<AiTranslateRunService.RunSummary> summaries = service.getRecentRuns(List.of(), 50);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    assertEquals("CRON", summaries.get(0).triggerSource());
    assertEquals("COMPLETED", summaries.get(0).status());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  private AiTranslateRunService service(
      AiTranslateRunRepository repository,
      UserRepository userRepository,
      PlatformTransactionManager transactionManager) {
    return new AiTranslateRunService(repository, userRepository, transactionManager);
  }
}
