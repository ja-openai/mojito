package com.box.l10n.mojito.rest.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveService.BatchResult;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PollableTaskArchiveWSTest {
  @Test
  public void disabledArchivalRejectsManualExecution() {
    ObjectProvider<PollableTaskArchiveService> provider = mock(ObjectProvider.class);
    assertThatThrownBy(() -> new PollableTaskArchiveWS(provider).batch())
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  public void manualRequestExecutesExactlyOneBatch() {
    ObjectProvider<PollableTaskArchiveService> provider = mock(ObjectProvider.class);
    PollableTaskArchiveService service = mock(PollableTaskArchiveService.class);
    BatchResult result = new BatchResult(100, 90, 0, 10, 0, 0);
    when(provider.getIfAvailable()).thenReturn(service);
    when(service.archiveFinishedTasks()).thenReturn(result);

    assertThat(new PollableTaskArchiveWS(provider).batch()).isSameAs(result);
    verify(service, times(1)).archiveFinishedTasks();
  }
}
