package com.box.l10n.mojito.service.pollableTask;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class PollableTaskServiceLookupTest {

  @Test
  public void missingTaskReturnsNullWithoutFetchingSubtasks() {
    PollableTaskService service = new PollableTaskService();
    service.transactionManager = mock(PlatformTransactionManager.class);
    when(service.transactionManager.getTransaction(any()))
        .thenReturn(new SimpleTransactionStatus());
    service.pollableTaskArchiveStorage = mock(PollableTaskArchiveStorage.class);
    service.pollableTaskRepository = mock(PollableTaskRepository.class);
    when(service.pollableTaskRepository.findById(404L)).thenReturn(Optional.empty());
    assertNull(service.getPollableTask(404L));
  }
}
