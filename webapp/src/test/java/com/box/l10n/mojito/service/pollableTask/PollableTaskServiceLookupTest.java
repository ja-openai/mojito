package com.box.l10n.mojito.service.pollableTask;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.Test;

public class PollableTaskServiceLookupTest {

  @Test
  public void missingTaskReturnsNullWithoutFetchingSubtasks() {
    PollableTaskService service = new PollableTaskService();
    service.pollableTaskRepository = mock(PollableTaskRepository.class);
    when(service.pollableTaskRepository.findById(404L)).thenReturn(Optional.empty());
    assertNull(service.getPollableTask(404L));
  }
}
