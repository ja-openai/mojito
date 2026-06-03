package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import io.micrometer.core.instrument.Tags;
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
    assertThat(service.producerEnabled).isTrue();
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
  public void scheduleJobRejectsDisabledProducerBeforeAnyWrites() {
    service.producerEnabled = false;
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withInput(new LocalizedAssetBody())
            .build();

    assertThatThrownBy(() -> service.scheduleJob(quartzJobInfo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("producer is disabled");

    verifyNoInteractions(
        pollableTaskService,
        pollableTaskBlobStorage,
        asyncJobQueueSubmissionService,
        pollableTaskExceptionUtils);
    assertScheduleCounter("failed", 1);
  }

  @Test
  public void acceptedSubmissionSurvivesSuccessMetricFailureWithoutFailingTask() {
    PollableTask task = pollableTask(42L);
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job = prepareSubmission(task);
    conflictWithScheduleCounter("succeeded");

    assertThat(service.scheduleJob(job).getPollableTask()).isSameAs(task);

    verify(asyncJobQueueSubmissionService).enqueueNow(eq("assetlocalize"), any(String.class));
    verifyNoInteractions(pollableTaskExceptionUtils);
    verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
    assertThat(
            meterRegistry.find("assetLocalizeAsyncJob.schedule").tag("result", "failed").counter())
        .isNull();
  }

  @Test
  public void fatalTelemetryErrorDoesNotCompensateAnAcceptedJob() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    OutOfMemoryError fatal = new OutOfMemoryError("synthetic telemetry failure");
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              if ("assetLocalizeAsyncJob.schedule".equals(meter.getId().getName())) {
                throw fatal;
              }
            });

    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(fatal);

    verify(asyncJobQueueSubmissionService).enqueueNow(eq("assetlocalize"), any(String.class));
    verifyNoInteractions(pollableTaskExceptionUtils);
    verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
  }

  @Test
  public void submissionFailureSurvivesFailureMetricFailureAndStillRunsCleanup() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    RuntimeException failure = new RuntimeException("enqueue failed");
    when(asyncJobQueueSubmissionService.enqueueNow(eq("assetlocalize"), any(String.class)))
        .thenThrow(failure);
    conflictWithScheduleCounter("failed");

    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);

    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
  }

  @Test
  public void submissionFailureSurvivesCleanupMetricFailure() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    RuntimeException failure = new RuntimeException("enqueue failed");
    when(asyncJobQueueSubmissionService.enqueueNow(eq("assetlocalize"), any(String.class)))
        .thenThrow(failure);
    doThrow(new IllegalStateException("cleanup failed"))
        .when(pollableTaskService)
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    meterRegistry.gauge(
        "assetLocalizeAsyncJob.submission.finish.failed", Tags.of("queueName", "assetlocalize"), 1);

    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);
  }

  private QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> prepareSubmission(
      PollableTask task) {
    when(pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600))
        .thenReturn(task);
    return QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
        .withInput(new LocalizedAssetBody())
        .build();
  }

  private void conflictWithScheduleCounter(String result) {
    meterRegistry.gauge(
        "assetLocalizeAsyncJob.schedule",
        Tags.of("queueName", "assetlocalize", "result", result),
        1);
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
    assertScheduleCounter("failed", 1);
  }

  @Test
  public void scheduleJobFinishesPollableTaskWhenInputSaveFails() {
    RuntimeException failure = new RuntimeException("blob store down");
    LocalizedAssetBody input = new LocalizedAssetBody();
    PollableTask pollableTask = pollableTask(42L);
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class).withInput(input).build();
    when(pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600))
        .thenReturn(pollableTask);
    doThrow(failure).when(pollableTaskBlobStorage).saveInput(42L, input);

    assertThatThrownBy(() -> service.scheduleJob(quartzJobInfo)).isSameAs(failure);

    verify(asyncJobQueueSubmissionService, never())
        .enqueueNow(any(String.class), any(String.class));
    verify(pollableTaskExceptionUtils).processException(eq(failure), any(ExceptionHolder.class));
    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    assertScheduleCounter("failed", 1);
  }

  @Test
  public void scheduleJobRecordsCleanupFailureAndPropagatesOriginalSubmissionFailure() {
    RuntimeException failure = new RuntimeException("enqueue down");
    RuntimeException cleanupFailure = new RuntimeException("finish down");
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
    doThrow(cleanupFailure)
        .when(pollableTaskService)
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());

    assertThatThrownBy(() -> service.scheduleJob(quartzJobInfo)).isSameAs(failure);

    verify(pollableTaskExceptionUtils).processException(eq(failure), any(ExceptionHolder.class));
    assertScheduleCounter("failed", 1);
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.submission.finish.failed")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .counter()
                .count())
        .isEqualTo(1);
  }

  private PollableTask pollableTask(long id) {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(id);
    return pollableTask;
  }

  private void assertScheduleCounter(String result, double expectedCount) {
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.schedule")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }
}
