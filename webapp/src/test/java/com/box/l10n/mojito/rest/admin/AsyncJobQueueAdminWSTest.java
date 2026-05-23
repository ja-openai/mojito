package com.box.l10n.mojito.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobStatusCountSummary;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class AsyncJobQueueAdminWSTest {

  @Mock AsyncJobQueueInspectionService inspectionService;

  AsyncJobQueueAdminWS ws;

  @Before
  public void setUp() {
    ws = new AsyncJobQueueAdminWS(inspectionService);
  }

  @Test
  public void countJobsByStatusReturnsServiceResult() {
    List<AsyncJobStatusCountSummary> counts = List.of(new AsyncJobStatusCountSummary("queued", 1L));
    when(inspectionService.countJobsByStatus("assetlocalize")).thenReturn(counts);

    assertThat(ws.countJobsByStatus("assetlocalize")).isEqualTo(counts);
  }

  @Test
  public void mapsInvalidQueueNameToBadRequest() {
    when(inspectionService.countJobsByStatus("bad queue"))
        .thenThrow(new IllegalArgumentException("bad queue"));

    assertThatThrownBy(() -> ws.countJobsByStatus("bad queue"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
