package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AssetLocalizeAsyncJobHandlerTest {

  @Mock PollableTaskService pollableTaskService;
  @Mock PollableTaskBlobStorage pollableTaskBlobStorage;
  @Mock PollableTaskExceptionUtils pollableTaskExceptionUtils;
  @Mock LocalizedAssetGenerationService localizedAssetGenerationService;

  ObjectMapper objectMapper = new ObjectMapper();
  SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  AssetLocalizeAsyncJobHandler handler;

  @Before
  public void setUp() {
    handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry);
  }

  @Test
  public void processGeneratesLocalizedAssetAndStoresOutputWithoutFinishingPollableTask()
      throws Exception {
    String jobData = jobData(42L);
    PollableTask pollableTask = pollableTask(42L);
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInput(42L, LocalizedAssetBody.class)).thenReturn(input);
    when(localizedAssetGenerationService.generate(input)).thenReturn(output);

    AsyncJobHandlerResult result = handler.process(asyncJobRecord(jobData, 1));

    assertThat(result.action()).isEqualTo(AsyncJobHandlerResult.Action.DONE);
    assertThat(result.jobData()).isEqualTo(jobData);
    verify(pollableTaskBlobStorage).saveOutput(42L, output);
    verify(pollableTaskService, times(0)).finishTask(eq(42L), isNull(), isNull(), isNull());
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.process")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "succeeded")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void processFailureDoesNotFinishPollableTaskBeforeRuntimeTerminalFailure()
      throws Exception {
    RuntimeException failure = new RuntimeException("generate failed");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    when(pollableTaskBlobStorage.getInput(42L, LocalizedAssetBody.class))
        .thenReturn(new LocalizedAssetBody());
    when(localizedAssetGenerationService.generate(any(LocalizedAssetBody.class)))
        .thenThrow(failure);

    assertThatThrownBy(() -> handler.process(asyncJobRecord(jobData(42L), 1))).isSameAs(failure);

    verify(pollableTaskService, times(0)).finishTask(eq(42L), isNull(), any(), isNull());
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.process")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "failed")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void onJobDoneFinishesPollableTaskAfterQueueDoneTransition() {
    String jobData = jobData(42L);

    handler.onJobDone(asyncJobRecord(jobData, 1), AsyncJobHandlerResult.done(jobData));

    verify(pollableTaskService).finishTask(42L, null, null, null);
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.pollableTask.finished")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "succeeded")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void onJobFailedPermanentlyFinishesPollableTaskWithExceptionAfterQueueFailedTransition() {
    RuntimeException failure = new RuntimeException("terminal");
    PollableTask pollableTask = pollableTask(42L);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);

    handler.onJobFailedPermanently(asyncJobRecord(jobData(42L), 3), failure, "terminal");

    verify(pollableTaskExceptionUtils).processException(eq(failure), any(ExceptionHolder.class));
    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.pollableTask.finished")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "failed")
                .counter()
                .count())
        .isEqualTo(1);
  }

  private String jobData(long pollableTaskId) {
    return objectMapper.writeValueAsStringUnchecked(
        new AssetLocalizeAsyncJobPayload(pollableTaskId));
  }

  private AsyncJobRecord asyncJobRecord(String jobData, int attemptCount) {
    Instant now = Instant.now();
    return new AsyncJobRecord(
        new AsyncJobId("1"),
        AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
        AsyncJobStatus.RUNNING,
        now.minusSeconds(1),
        now.plusSeconds(30),
        "worker-a",
        "lease-token",
        jobData,
        attemptCount,
        null,
        now.minusSeconds(5),
        now,
        false);
  }

  private PollableTask pollableTask(long id) {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(id);
    return pollableTask;
  }
}
