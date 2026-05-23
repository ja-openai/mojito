package com.box.l10n.mojito.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobDetails;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobExpiredLeaseStatusSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobNotFoundException;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobReadyStatusSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobStatusCountSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobSummary;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
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

  @Test
  public void readyStatusReturnsServiceResult() {
    Instant now = Instant.now();
    AsyncJobReadyStatusSummary readyStatus =
        new AsyncJobReadyStatusSummary("assetlocalize", 2, now.minusSeconds(3), now, 3000);
    when(inspectionService.readyStatus("assetlocalize")).thenReturn(readyStatus);

    assertThat(ws.readyStatus("assetlocalize")).isEqualTo(readyStatus);
  }

  @Test
  public void mapsInvalidReadyStatusInputToBadRequest() {
    when(inspectionService.readyStatus("bad queue"))
        .thenThrow(new IllegalArgumentException("bad queue"));

    assertThatThrownBy(() -> ws.readyStatus("bad queue"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  public void expiredLeaseStatusReturnsServiceResult() {
    Instant now = Instant.now();
    AsyncJobExpiredLeaseStatusSummary expiredLeaseStatus =
        new AsyncJobExpiredLeaseStatusSummary("assetlocalize", 2, now.minusSeconds(3), now, 3000);
    when(inspectionService.expiredLeaseStatus("assetlocalize")).thenReturn(expiredLeaseStatus);

    assertThat(ws.expiredLeaseStatus("assetlocalize")).isEqualTo(expiredLeaseStatus);
  }

  @Test
  public void mapsInvalidExpiredLeaseStatusInputToBadRequest() {
    when(inspectionService.expiredLeaseStatus("bad queue"))
        .thenThrow(new IllegalArgumentException("bad queue"));

    assertThatThrownBy(() -> ws.expiredLeaseStatus("bad queue"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  public void findJobsReturnsPayloadRedactedSummaries() {
    Instant now = Instant.now();
    when(inspectionService.findJobs("assetlocalize", "failed", 50))
        .thenReturn(
            List.of(
                new AsyncJobSummary(
                    "1",
                    "assetlocalize",
                    "failed",
                    now.minusSeconds(30),
                    null,
                    null,
                    5,
                    "handler failed",
                    512,
                    "{\"secret\":\"not returned\"}",
                    now.minusSeconds(60),
                    now)));

    List<AsyncJobQueueAdminWS.AsyncJobRedactedSummary> jobs =
        ws.findJobs("assetlocalize", "failed", 50);

    assertThat(jobs).hasSize(1);
    AsyncJobQueueAdminWS.AsyncJobRedactedSummary job = jobs.get(0);
    assertThat(job.id()).isEqualTo("1");
    assertThat(job.status()).isEqualTo("failed");
    assertThat(job.lastError()).isEqualTo("handler failed");
    assertThat(job.jobDataLength()).isEqualTo(512);
  }

  @Test
  public void mapsInvalidFindJobsInputToBadRequest() {
    when(inspectionService.findJobs("assetlocalize", "broken", 10))
        .thenThrow(new IllegalArgumentException("bad status"));

    assertThatThrownBy(() -> ws.findJobs("assetlocalize", "broken", 10))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  public void getJobReturnsPayloadRedactedSummary() {
    Instant now = Instant.now();
    when(inspectionService.getJob("assetlocalize", "1"))
        .thenReturn(
            new AsyncJobDetails(
                "1",
                "assetlocalize",
                "failed",
                now.minusSeconds(30),
                null,
                null,
                5,
                "handler failed",
                "{\"secret\":\"not returned\"}",
                now.minusSeconds(60),
                now));

    AsyncJobQueueAdminWS.AsyncJobRedactedSummary job = ws.getJob("assetlocalize", "1");

    assertThat(job.id()).isEqualTo("1");
    assertThat(job.status()).isEqualTo("failed");
    assertThat(job.lastError()).isEqualTo("handler failed");
    assertThat(job.jobDataLength()).isEqualTo("{\"secret\":\"not returned\"}".length());
  }

  @Test
  public void redactedSummaryRecordDoesNotExposePayloadFields() {
    List<String> componentNames =
        Arrays.stream(AsyncJobQueueAdminWS.AsyncJobRedactedSummary.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

    assertThat(componentNames)
        .contains("jobDataLength")
        .doesNotContain("jobData", "jobDataPreview");
  }

  @Test
  public void mapsMissingJobToNotFound() {
    when(inspectionService.getJob("assetlocalize", "404"))
        .thenThrow(new AsyncJobNotFoundException("not found"));

    assertThatThrownBy(() -> ws.getJob("assetlocalize", "404"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  public void mapsInvalidGetJobInputToBadRequest() {
    when(inspectionService.getJob("assetlocalize", "bad"))
        .thenThrow(new IllegalArgumentException("bad id"));

    assertThatThrownBy(() -> ws.getJob("assetlocalize", "bad"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
