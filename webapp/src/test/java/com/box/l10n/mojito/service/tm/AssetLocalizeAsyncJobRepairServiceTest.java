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
import static org.mockito.Mockito.verifyNoInteractions;
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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.After;
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
  @Mock AssetLocalizeAsyncJobOutputStorage outputStorage;
  @Captor ArgumentCaptor<ExceptionHolder> exceptionHolderCaptor;

  ObjectMapper objectMapper = new ObjectMapper();
  SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  AssetLocalizeAsyncJobRepairService repairService;

  @Before
  public void setUp() {
    repairService =
        new AssetLocalizeAsyncJobRepairService(
            asyncJobStore, pollableTaskService, meterRegistry, outputStorage);
  }

  @After
  public void closeMeterRegistry() {
    meterRegistry.close();
  }

  @Test
  public void repairsUnfinishedDonePollableTask() {
    PollableTask pollableTask = pollableTask(42L, false);
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask);

    AssetLocalizeAsyncJobRepairService.RepairResult result =
        repairService.repairTerminalPollableTask(asyncJobRecord(AsyncJobStatus.DONE, "1", null));

    var inOrder = org.mockito.Mockito.inOrder(outputStorage, pollableTaskService);
    inOrder.verify(outputStorage).publishOutput(new AssetLocalizeAsyncJobPayload(42L));
    inOrder.verify(pollableTaskService).finishTask(42L, null, null, null);
    assertThat(result.result()).isEqualTo("repaired");
    assertThat(result.status()).isEqualTo("done");
    assertRepairCounter("done", "repaired", 1);
  }

  @Test
  public void missingOutputDoesNotRepairDonePollableTaskAsSuccessful() {
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, false));
    doThrow(new IllegalStateException("output expired"))
        .when(outputStorage)
        .publishOutput(new AssetLocalizeAsyncJobPayload(42L));

    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.DONE, "1", null)))
        .isInstanceOf(AssetLocalizePollableTaskRepairException.class)
        .hasRootCauseMessage("output expired");

    verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
    assertRepairCounter("done", "finishFailed", 1);
  }

  @Test
  public void repairPublishesThePersistedWinningAttempt() {
    AssetLocalizeAsyncJobPayload payload =
        new AssetLocalizeAsyncJobPayload(42L, "00000000-0000-0000-0000-000000000002");
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, false));

    repairService.repairTerminalPollableTask(
        asyncJobRecord(
            AsyncJobStatus.DONE, "1", null, objectMapper.writeValueAsStringUnchecked(payload)));

    var inOrder = org.mockito.Mockito.inOrder(outputStorage, pollableTaskService);
    inOrder.verify(outputStorage).publishOutput(payload);
    inOrder.verify(pollableTaskService).finishTask(42L, null, null, null);
  }

  @Test
  public void repairsUnfinishedFailedPollableTaskWithoutPublishingLastError() {
    PollableTask pollableTask = pollableTask(42L, false);
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask);

    AssetLocalizeAsyncJobRepairService.RepairResult result =
        repairService.repairTerminalPollableTask(
            asyncJobRecord(
                AsyncJobStatus.FAILED, "1", "java.sql.SQLException: private diagnostic"));

    verify(pollableTaskService)
        .finishTask(eq(42L), isNull(), exceptionHolderCaptor.capture(), isNull());
    ExceptionHolder exceptionHolder = exceptionHolderCaptor.getValue();
    assertThat(exceptionHolder.isExpected()).isFalse();
    assertThat(exceptionHolder.getException())
        .hasMessage("Asset localize async job failed permanently: 1")
        .hasNoCause();
    assertThat(exceptionHolder.getException().getSuppressed()).isEmpty();
    assertThat(objectMapper.writeValueAsStringUnchecked(exceptionHolder))
        .isEqualTo(
            "{\"expected\":false,\"message\":\"An unexpected error happened, task=42\","
                + "\"type\":\"unexpected\"}");
    assertThat(result.result()).isEqualTo("repaired");
    assertThat(result.status()).isEqualTo("failed");
    assertRepairCounter("failed", "repaired", 1);
  }

  @Test
  public void alreadyFinishedPollableTaskIsSkipped() {
    PollableTask pollableTask = pollableTask(42L, true);
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask);

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
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, false));

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
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(null);

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
    when(pollableTaskService.getFreshPollableTask(42L))
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
  public void ambiguousPayloadIsInvalidBeforeTaskLookupOrPublication() {
    String[] malformed = {
      "{\"pollableTaskId\":42.9}",
      "{\"pollableTaskId\":\"42\"}",
      "{\"pollableTaskId\":41,\"pollableTaskId\":42}",
      "{\"pollableTaskId\":42} {\"pollableTaskId\":41}",
      "null"
    };
    for (AsyncJobStatus status : List.of(AsyncJobStatus.DONE, AsyncJobStatus.FAILED)) {
      for (String json : malformed) {
        assertThatThrownBy(
                () ->
                    repairService.repairTerminalPollableTask(
                        asyncJobRecord(
                            status, "1", status == AsyncJobStatus.FAILED ? "failed" : null, json)))
            .isInstanceOf(AssetLocalizeAsyncJobInvalidPayloadException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
      }
      assertRepairCounter(status.getDatabaseValue(), "invalidPayload", malformed.length);
    }
    verifyNoInteractions(pollableTaskService, outputStorage);
  }

  @Test
  public void recordsPollableTaskFinishFailure() {
    PollableTask pollableTask = pollableTask(42L, false);
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask);
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

  @Test
  public void doneRepairSurvivesCounterTypeCollisionAfterFinishing() {
    collideRepairCounter("done", "repaired");
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, false));

    assertThat(
            repairService.repairTerminalPollableTask(
                asyncJobRecord(AsyncJobStatus.DONE, "1", null)))
        .isEqualTo(
            new AssetLocalizeAsyncJobRepairService.RepairResult("1", 42L, "done", "repaired"));

    var inOrder = org.mockito.Mockito.inOrder(outputStorage, pollableTaskService);
    inOrder.verify(outputStorage).publishOutput(new AssetLocalizeAsyncJobPayload(42L));
    inOrder.verify(pollableTaskService).finishTask(42L, null, null, null);
  }

  @Test
  public void failedRepairSurvivesCounterTypeCollisionAfterFinishing() {
    collideRepairCounter("failed", "repaired");
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, false));

    assertThat(
            repairService.repairTerminalPollableTask(
                asyncJobRecord(AsyncJobStatus.FAILED, "1", "handler failed")))
        .isEqualTo(
            new AssetLocalizeAsyncJobRepairService.RepairResult("1", 42L, "failed", "repaired"));

    verify(pollableTaskService)
        .finishTask(eq(42L), isNull(), exceptionHolderCaptor.capture(), isNull());
    assertThat(exceptionHolderCaptor.getValue().isExpected()).isFalse();
    assertThat(exceptionHolderCaptor.getValue().getException())
        .hasMessage("Asset localize async job failed permanently: 1")
        .hasNoCause();
    verifyNoInteractions(outputStorage);
  }

  @Test
  public void alreadyFinishedRemainsANoOpDespiteCounterTypeCollision() {
    collideRepairCounter("done", "alreadyFinished");
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, true));

    assertThat(
            repairService.repairTerminalPollableTask(
                asyncJobRecord(AsyncJobStatus.DONE, "1", null)))
        .isEqualTo(
            new AssetLocalizeAsyncJobRepairService.RepairResult(
                "1", 42L, "done", "alreadyFinished"));

    verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
    verifyNoInteractions(outputStorage);
  }

  @Test
  public void lookupFailureRetainsItsClassificationAndCauseDespiteCounterTypeCollision() {
    collideRepairCounter("unknown", "jobLookupFailed");
    IllegalStateException original = new IllegalStateException("database unavailable");
    when(asyncJobStore.getByIds(List.of(new AsyncJobId("1")))).thenThrow(original);

    assertThatThrownBy(() -> repairService.repairTerminalPollableTask("1"))
        .isExactlyInstanceOf(AssetLocalizeAsyncJobLookupException.class)
        .hasMessage("Failed to look up asset localize async job: 1")
        .hasCause(original);
    verifyNoInteractions(pollableTaskService, outputStorage);
  }

  @Test
  public void finishFailureRetainsItsClassificationAndCauseDespiteCounterTypeCollision() {
    collideRepairCounter("done", "finishFailed");
    IllegalStateException original = new IllegalStateException("finish failed");
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, false));
    doThrow(original).when(pollableTaskService).finishTask(42L, null, null, null);

    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.DONE, "1", null)))
        .isExactlyInstanceOf(AssetLocalizePollableTaskRepairException.class)
        .hasMessage("Failed to repair pollable task 42 for asset localize async job 1")
        .hasCause(original);
    verify(outputStorage).publishOutput(new AssetLocalizeAsyncJobPayload(42L));
    verify(pollableTaskService).finishTask(42L, null, null, null);
  }

  @Test
  public void lookupFailureSurvivesNonfatalMetricError() {
    failMetricRegistration(new AssertionError("metric listener failed"));
    IllegalStateException original = new IllegalStateException("database unavailable");
    when(asyncJobStore.getByIds(List.of(new AsyncJobId("1")))).thenThrow(original);

    assertThatThrownBy(() -> repairService.repairTerminalPollableTask("1"))
        .isExactlyInstanceOf(AssetLocalizeAsyncJobLookupException.class)
        .hasCause(original);
    verifyNoInteractions(pollableTaskService, outputStorage);
  }

  @Test
  public void virtualMachineErrorFromTelemetryStillPropagates() {
    assertFatalMetricErrorPropagates(new InternalError("fatal metric failure"));
  }

  @Test
  public void threadDeathFromTelemetryStillPropagates() throws Exception {
    assertFatalMetricErrorPropagates(
        (Error) Class.forName("java.lang.ThreadDeath").getDeclaredConstructor().newInstance());
  }

  private void assertFatalMetricErrorPropagates(Error failure) {
    failMetricRegistration(failure);
    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask(42L, false));

    assertThatThrownBy(
            () ->
                repairService.repairTerminalPollableTask(
                    asyncJobRecord(AsyncJobStatus.DONE, "1", null)))
        .isSameAs(failure);
    verify(outputStorage).publishOutput(new AssetLocalizeAsyncJobPayload(42L));
    verify(pollableTaskService).finishTask(42L, null, null, null);
  }

  private void collideRepairCounter(String status, String result) {
    Gauge.builder("assetLocalizeAsyncJob.repair", () -> 1.0)
        .tags(
            "queueName",
            AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
            "status",
            status,
            "result",
            result)
        .register(meterRegistry);
  }

  private void failMetricRegistration(Error failure) {
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              throw failure;
            });
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
