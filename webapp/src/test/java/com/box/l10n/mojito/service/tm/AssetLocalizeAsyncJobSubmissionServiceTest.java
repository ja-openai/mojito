package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.queue.AsyncJobQueueSubmissionService;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AssetLocalizeAsyncJobSubmissionServiceTest {

  @Mock PollableTaskService pollableTaskService;
  @Mock PollableTaskBlobStorage pollableTaskBlobStorage;
  @Mock PollableTaskExceptionUtils pollableTaskExceptionUtils;
  @Mock AsyncJobQueueSubmissionService asyncJobQueueSubmissionService;

  @Captor ArgumentCaptor<String> payloadCaptor;

  ObjectMapper objectMapper = new ObjectMapper();
  SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  AssetLocalizeAsyncJobSubmissionService service;

  @Before
  public void setUp() {
    service =
        new AssetLocalizeAsyncJobSubmissionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            asyncJobQueueSubmissionService,
            objectMapper,
            meterRegistry);
  }

  @Test
  public void scheduleJobCreatesPollableTaskStoresInputAndEnqueuesPayload() {
    LocalizedAssetBody input = new LocalizedAssetBody();
    PollableTask pollableTask = pollableTask(42L);
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withParentId(7L)
            .withInput(input)
            .withMessage("message")
            .withExpectedSubTaskNumber(3)
            .withTimeout(123)
            .build();
    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 3, 123))
        .thenReturn(pollableTask);

    PollableFuture<LocalizedAssetBody> future = service.scheduleJob(quartzJobInfo);

    assertThat(future.getPollableTask()).isSameAs(pollableTask);
    verify(pollableTaskBlobStorage).saveInput(42L, input);
    verify(asyncJobQueueSubmissionService)
        .enqueueNow(eq(AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME), payloadCaptor.capture());
    AssetLocalizeAsyncJobPayload payload =
        objectMapper.readValueUnchecked(
            payloadCaptor.getValue(), AssetLocalizeAsyncJobPayload.class);
    assertThat(payload.pollableTaskId()).isEqualTo(42L);
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.schedule")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "succeeded")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void scheduleJobFinishesPollableTaskWhenEnqueueFails() {
    RuntimeException failure = new RuntimeException("enqueue down");
    PollableTask pollableTask = pollableTask(42L);
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withInput(new LocalizedAssetBody())
            .build();
    when(pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600))
        .thenReturn(pollableTask);
    when(asyncJobQueueSubmissionService.enqueueNow(
            eq(AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME), any(String.class)))
        .thenThrow(failure);

    assertThatThrownBy(() -> service.scheduleJob(quartzJobInfo)).isSameAs(failure);

    verify(pollableTaskExceptionUtils).processException(eq(failure), any(ExceptionHolder.class));
    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.schedule")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "failed")
                .counter()
                .count())
        .isEqualTo(1);
  }

  private PollableTask pollableTask(long id) {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(id);
    return pollableTask;
  }
}
