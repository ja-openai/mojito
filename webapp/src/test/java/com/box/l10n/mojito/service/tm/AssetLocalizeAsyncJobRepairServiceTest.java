package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.AsyncJobStore;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobInvalidPayloadException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobLookupException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskLookupException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskRepairException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AssetLocalizeAsyncJobRepairServiceTest {

  @Mock AsyncJobStore asyncJobStore;
  @Mock PollableTaskService pollableTaskService;
  @Captor ArgumentCaptor<ExceptionHolder> exceptionHolderCaptor;

  ObjectMapper objectMapper = new ObjectMapper();
  SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  AssetLocalizeAsyncJobRepairService repairService;

  @Before
  public void setUp() {
    repairService =
        new AssetLocalizeAsyncJobRepairService(
            asyncJobStore, pollableTaskService, objectMapper, meterRegistry);
  }

  @Test
  public void repairsUnfinishedDonePollableTask() {
    PollableTask pollableTask = pollableTask(42L, false);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);

    AssetLocalizeAsyncJobRepairService.RepairResult result =
        repairService.repairTerminalPollableTask(asyncJobRecord(AsyncJobStatus.DONE, "1", null));

    verify(pollableTaskService).finishTask(42L, null, null, null);
    assertThat(result.result()).isEqualTo("repaired");
    assertThat(result.status()).isEqualTo("done");
    assertRepairCounter("done", "repaired", 1);
  }

  @Test
  public void repairsUnfinishedFailedPollableTaskWithLastError() {
    PollableTask pollableTask = pollableTask(42L, false);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);

    AssetLocalizeAsyncJobRepairService.RepairResult result =
        repairService.repairTerminalPollableTask(
            asyncJobRecord(AsyncJobStatus.FAILED, "1", "handler failed"));

    verify(pollableTaskService)
        .finishTask(eq(42L), isNull(), exceptionHolderCaptor.capture(), isNull());
    ExceptionHolder exceptionHolder = exceptionHolderCaptor.getValue();
    assertThat(exceptionHolder.isExpected()).isTrue();
    assertThat(exceptionHolder.getException()).hasMessage("handler failed");
    assertThat(result.result()).isEqualTo("repaired");
    assertThat(result.status()).isEqualTo("failed");
    assertRepairCounter("failed", "repaired", 1);
  }

  @Test
  public void alreadyFinishedPollableTaskIsSkipped() {
    PollableTask pollableTask = pollableTask(42L, true);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);

    AssetLocalizeAsyncJobRepairService.RepairResult result =
        repairService.repairTerminalPollableTask(asyncJobRecord(AsyncJobStatus.DONE, "1", null));

    verify(pollableTaskService, times(0)).finishTask(eq(42L), any(), any(), any());
    assertThat(result.result()).isEqualTo("alreadyFinished");
    assertRepairCounter("done", "alreadyFinished", 1);
  }

  @Test
  public void rejectsNonTerminalJobs() {
    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.QUEUED, "1", null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be terminal");
    assertRepairCounter("queued", "nonTerminal", 1);
  }

  @Test
  public void repairsByAsyncJobId() {
    AsyncJobRecord asyncJobRecord = asyncJobRecord(AsyncJobStatus.DONE, "1", null);
    when(asyncJobStore.getByIds(List.of(new AsyncJobId("1")))).thenReturn(List.of(asyncJobRecord));
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L, false));

    AssetLocalizeAsyncJobRepairService.RepairResult result =
        repairService.repairTerminalPollableTask("1");

    assertThat(result.asyncJobId()).isEqualTo("1");
    assertThat(result.pollableTaskId()).isEqualTo(42L);
    verify(pollableTaskService).finishTask(42L, null, null, null);
  }

  @Test
  public void rejectsMissingAsyncJobId() {
    when(asyncJobStore.getByIds(List.of(new AsyncJobId("1")))).thenReturn(List.of());

    assertThatThrownBy(() -> repairService.repairTerminalPollableTask("1"))
        .isInstanceOf(AssetLocalizeAsyncJobNotFoundException.class)
        .hasMessageContaining("not found");
    assertRepairCounter("unknown", "jobNotFound", 1);
  }

  @Test
  public void recordsInvalidAsyncJobId() {
    assertThatThrownBy(() -> repairService.repairTerminalPollableTask("bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical positive numeric string");

    verify(asyncJobStore, never()).getByIds(any());
    assertRepairCounter("unknown", "invalidJobId", 1);
  }

  @Test
  public void recordsAsyncJobLookupFailure() {
    when(asyncJobStore.getByIds(List.of(new AsyncJobId("1"))))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(() -> repairService.repairTerminalPollableTask("1"))
        .isInstanceOf(AssetLocalizeAsyncJobLookupException.class)
        .hasMessageContaining("Failed to look up asset localize async job")
        .hasCauseInstanceOf(IllegalStateException.class);
    assertRepairCounter("unknown", "jobLookupFailed", 1);
  }

  @Test
  public void recordsMissingPollableTask() {
    when(pollableTaskService.getPollableTask(42L)).thenReturn(null);

    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.FAILED, "1", "handler failed")))
        .isInstanceOf(AssetLocalizePollableTaskNotFoundException.class)
        .hasMessageContaining("PollableTask not found");
    assertRepairCounter("failed", "pollableTaskNotFound", 1);
  }

  @Test
  public void recordsPollableTaskLookupFailure() {
    when(pollableTaskService.getPollableTask(42L))
        .thenThrow(new IllegalStateException("database unavailable"));

    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.FAILED, "1", "handler failed")))
        .isInstanceOf(AssetLocalizePollableTaskLookupException.class)
        .hasMessageContaining("Failed to look up pollable task: 42")
        .hasCauseInstanceOf(IllegalStateException.class);
    assertRepairCounter("failed", "pollableTaskLookupFailed", 1);
  }

  @Test
  public void recordsInvalidPayload() {
    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.FAILED, "1", "handler failed", "{bad-json")))
        .isInstanceOf(AssetLocalizeAsyncJobInvalidPayloadException.class)
        .hasMessageContaining("Invalid asset localize async job payload");
    assertRepairCounter("failed", "invalidPayload", 1);
  }

  @Test
  public void recordsPollableTaskFinishFailure() {
    PollableTask pollableTask = pollableTask(42L, false);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    doThrow(new IllegalStateException("finish failed"))
        .when(pollableTaskService)
        .finishTask(eq(42L), isNull(), isNull(), isNull());

    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.DONE, "1", null)))
        .isInstanceOf(AssetLocalizePollableTaskRepairException.class)
        .hasMessageContaining("Failed to repair pollable task 42")
        .hasCauseInstanceOf(IllegalStateException.class);
    assertRepairCounter("done", "finishFailed", 1);
  }

  private AsyncJobRecord asyncJobRecord(AsyncJobStatus status, String id, String lastError) {
    return asyncJobRecord(
        status,
        id,
        lastError,
        objectMapper.writeValueAsStringUnchecked(new AssetLocalizeAsyncJobPayload(42L)));
  }

  private AsyncJobRecord asyncJobRecord(
      AsyncJobStatus status, String id, String lastError, String jobData) {
    Instant now = Instant.now();
    boolean running = status == AsyncJobStatus.RUNNING;
    return new AsyncJobRecord(
        new AsyncJobId(id),
        AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
        status,
        now.minusSeconds(1),
        running ? now.plusSeconds(30) : null,
        running ? "worker-a" : null,
        running ? "lease-token" : null,
        jobData,
        1,
        lastError,
        now.minusSeconds(5),
        now,
        false);
  }

  private PollableTask pollableTask(long id, boolean finished) {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(id);
    if (finished) {
      pollableTask.setFinishedDate(ZonedDateTime.now());
    }
    return pollableTask;
  }

  private void assertRepairCounter(String status, String result, double expectedCount) {
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.repair")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("status", status)
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }
}
