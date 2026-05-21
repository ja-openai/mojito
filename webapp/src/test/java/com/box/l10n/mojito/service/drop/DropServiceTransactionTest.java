package com.box.l10n.mojito.service.drop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Drop;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import java.util.Optional;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class DropServiceTransactionTest {

  @Test
  public void getDropInTxCommitsTransactionAndFetchesSubTasks() {
    DropService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    Drop drop = new Drop();
    PollableTask exportTask = new PollableTask();
    PollableTask importTask = new PollableTask();
    drop.setExportPollableTask(exportTask);
    drop.setImportPollableTask(importTask);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.dropRepository.findById(1L)).thenReturn(Optional.of(drop));

    Drop loadedDrop = service.getDropInTX(1L);

    assertEquals(drop, loadedDrop);
    verify(service.pollableTaskService).fetchSubTasks(exportTask);
    verify(service.pollableTaskService).fetchSubTasks(importTask);
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void getDropInTxRollsBackTransaction() {
    DropService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.dropRepository.findById(1L)).thenThrow(new IllegalStateException("failed"));

    try {
      service.getDropInTX(1L);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected getDropInTX to rethrow the repository failure");
  }

  @Test
  public void completeDropCommitsTransaction() {
    DropService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    Drop drop = new Drop();
    drop.setPartiallyImported(Boolean.TRUE);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);

    service.completeDrop(drop);

    assertFalse(drop.getPartiallyImported());
    verify(service.dropRepository).save(drop);
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  private DropService service() {
    DropService service = new DropService();
    service.dropRepository = mock(DropRepository.class);
    service.pollableTaskService = mock(PollableTaskService.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }
}
