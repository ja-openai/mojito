package com.box.l10n.mojito.service.pollableTask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class PollableTaskServiceTransactionTest {

  @Test
  public void getPollableTaskUsesReadOnlyRequiresNewTransaction() {
    PollableTaskService service = pollableTaskService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    PollableTask pollableTask = new PollableTask();
    pollableTask.setSubTasks(Set.of());
    when(service.pollableTaskRepository.findById(7L)).thenReturn(Optional.of(pollableTask));

    assertEquals(pollableTask, service.getPollableTask(7L));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(service.transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        transactionDefinitionCaptor.getValue().getPropagationBehavior());
    assertEquals(true, transactionDefinitionCaptor.getValue().isReadOnly());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createPollableTaskCommitsRequiresNewTransaction() {
    PollableTaskService service = pollableTaskService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.pollableTaskRepository.save(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    PollableTask pollableTask = service.createPollableTask(null, "name", "message", 2, 100L);

    assertEquals("name", pollableTask.getName());
    assertEquals("message", pollableTask.getMessage());
    assertEquals(2, pollableTask.getExpectedSubTaskNumber());
    assertEquals(Long.valueOf(100L), pollableTask.getTimeout());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateMessageRollsBackRequiresNewTransaction() {
    PollableTaskService service = pollableTaskService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.pollableTaskRepository.findById(7L))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.updateMessage(7L, "message");
      fail("Expected updateMessage to rethrow the update failure");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
    }

    verify(service.transactionManager).rollback(transaction);
    verify(service.transactionManager, never()).commit(transaction);
  }

  private PollableTaskService pollableTaskService() {
    PollableTaskService service = new PollableTaskService();
    service.objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
    service.pollableTaskRepository = mock(PollableTaskRepository.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }
}
