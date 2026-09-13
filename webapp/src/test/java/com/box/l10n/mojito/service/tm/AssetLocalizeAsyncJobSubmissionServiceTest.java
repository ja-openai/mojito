package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import org.junit.After;
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

  @After
  public void closeMeters() {
    meterRegistry.close();
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
  public void scheduleJobRejectsEveryNonNullPullRunNameBeforeAnyWrites() {
    for (String pullRunName : new String[] {"tracked-run", "", " \t\n "}) {
      assertTrackedSubmissionRejected(pullRunName);
    }
    assertScheduleCounter("failed", 3);
  }

  @Test
  public void trackedSubmissionRejectionSurvivesMetricFailureWithoutWrites() {
    conflictWithScheduleCounter("failed");

    assertTrackedSubmissionRejected("private-tracked-run");
  }

  private void assertTrackedSubmissionRejected(String pullRunName) {
    LocalizedAssetBody input = new LocalizedAssetBody();
    input.setPullRunName(pullRunName);
    input.setOutputBcp47tag("fr-FR");
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class).withInput(input).build();

    assertThatThrownBy(() -> service.scheduleJob(job))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Asset localize async queue does not support pull-run tracking");

    assertThat(input.getPullRunName()).isEqualTo(pullRunName);
    verifyNoInteractions(
        pollableTaskService,
        pollableTaskBlobStorage,
        asyncJobQueueSubmissionService,
        pollableTaskExceptionUtils);
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
  public void inputFailureSurvivesFailureMetricFailureAndStillRunsCleanup() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    RuntimeException failure = new RuntimeException("input save failed");
    doThrow(failure).when(pollableTaskBlobStorage).saveInput(42L, job.getInput());
    conflictWithScheduleCounter("failed");

    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);

    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
  }

  @Test
  public void inputFailureSurvivesCleanupMetricFailure() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    RuntimeException failure = new RuntimeException("input save failed");
    doThrow(failure).when(pollableTaskBlobStorage).saveInput(42L, job.getInput());
    doThrow(new IllegalStateException("cleanup failed"))
        .when(pollableTaskService)
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    meterRegistry.gauge(
        "assetLocalizeAsyncJob.submission.finish.failed", Tags.of("queueName", "assetlocalize"), 1);

    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);
  }

  @Test
  public void preparationErrorStaysRedactedWhenSubmissionLoggerFails() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    RuntimeException failure =
        new IllegalStateException("private preparation detail", new Exception("private source"));
    failure.addSuppressed(new IllegalStateException("private cleanup detail"));
    doThrow(failure).when(pollableTaskBlobStorage).saveInput(42L, job.getInput());
    service =
        new AssetLocalizeAsyncJobSubmissionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            new PollableTaskExceptionUtils(),
            asyncJobQueueSubmissionService,
            objectMapper,
            meterRegistry);
    org.slf4j.Logger originalLogger = AssetLocalizeAsyncJobSubmissionService.logger;
    org.slf4j.Logger failingLogger = mock(org.slf4j.Logger.class);
    doThrow(new AssertionError("logging unavailable"))
        .when(failingLogger)
        .warn(any(String.class), any(Object[].class));
    AssetLocalizeAsyncJobSubmissionService.logger = failingLogger;
    try {
      assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);
      ArgumentCaptor<ExceptionHolder> holder = ArgumentCaptor.forClass(ExceptionHolder.class);
      verify(pollableTaskService).finishTask(eq(42L), isNull(), holder.capture(), isNull());
      assertThat(holder.getValue().isExpected()).isFalse();
      assertThat(holder.getValue().getException())
          .isExactlyInstanceOf(IllegalStateException.class)
          .hasMessage("Asset localization preparation failed for pollable task: 42")
          .hasNoCause();
      assertThat(holder.getValue().getException().getSuppressed()).isEmpty();
      ArgumentCaptor<Object[]> diagnostics = ArgumentCaptor.forClass(Object[].class);
      verify(failingLogger)
          .warn(eq("Assetlocalize preparation failed for pollable task {}"), diagnostics.capture());
      assertThat(diagnostics.getValue()).containsExactly(42L, failure);
      verifyNoInteractions(asyncJobQueueSubmissionService);
    } finally {
      AssetLocalizeAsyncJobSubmissionService.logger = originalLogger;
    }
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
  public void scheduleJobLeavesPollableTaskUnresolvedWhenEnqueueOutcomeIsUnknown() {
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

    verifyNoInteractions(pollableTaskExceptionUtils);
    verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
    verify(asyncJobQueueSubmissionService).enqueueNow(eq("assetlocalize"), any(String.class));
    assertScheduleCounter("outcomeUnknown", 1);
    assertThat(
            meterRegistry.find("assetLocalizeAsyncJob.schedule").tag("result", "failed").counter())
        .isNull();
  }

  @Test
  public void unknownOutcomeSurvivesBrokenMetricsAndLoggerWithoutTaskCompensation() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    RuntimeException failure = new RuntimeException("commit acknowledgement lost");
    when(asyncJobQueueSubmissionService.enqueueNow(eq("assetlocalize"), any(String.class)))
        .thenThrow(failure);
    conflictWithScheduleCounter("outcomeUnknown");
    org.slf4j.Logger originalLogger = AssetLocalizeAsyncJobSubmissionService.logger;
    org.slf4j.Logger failingLogger = mock(org.slf4j.Logger.class);
    doThrow(new AssertionError("logger unavailable"))
        .when(failingLogger)
        .warn(any(String.class), any(Object[].class));
    AssetLocalizeAsyncJobSubmissionService.logger = failingLogger;
    try {
      assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);
      verifyNoInteractions(pollableTaskExceptionUtils);
      verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
      verify(asyncJobQueueSubmissionService).enqueueNow(eq("assetlocalize"), any(String.class));
    } finally {
      AssetLocalizeAsyncJobSubmissionService.logger = originalLogger;
    }
  }

  @Test
  public void nonfatalEnqueueErrorDoesNotCompensateTask() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    AssertionError failure = new AssertionError("enqueue provider error");
    when(asyncJobQueueSubmissionService.enqueueNow(eq("assetlocalize"), any(String.class)))
        .thenThrow(failure);
    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);
    verifyNoInteractions(pollableTaskExceptionUtils);
    verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
    assertScheduleCounter("outcomeUnknown", 1);
  }

  @Test
  public void fatalUnknownOutcomeLoggerFailurePropagatesWithoutTaskCompensation() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    when(asyncJobQueueSubmissionService.enqueueNow(eq("assetlocalize"), any(String.class)))
        .thenThrow(new IllegalStateException("commit acknowledgement lost"));
    org.slf4j.Logger originalLogger = AssetLocalizeAsyncJobSubmissionService.logger;
    org.slf4j.Logger failingLogger = mock(org.slf4j.Logger.class);
    Error fatal = new ThreadDeath() {};
    doThrow(fatal).when(failingLogger).warn(any(String.class), any(Object[].class));
    AssetLocalizeAsyncJobSubmissionService.logger = failingLogger;
    try {
      assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(fatal);
      verifyNoInteractions(pollableTaskExceptionUtils);
      verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
      assertScheduleCounter("outcomeUnknown", 1);
    } finally {
      AssetLocalizeAsyncJobSubmissionService.logger = originalLogger;
    }
  }

  @Test
  public void fatalEnqueueErrorDoesNotCompensateTaskOrRecordUnknownOutcome() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    OutOfMemoryError fatal = new OutOfMemoryError("synthetic enqueue fatal error");
    when(asyncJobQueueSubmissionService.enqueueNow(eq("assetlocalize"), any(String.class)))
        .thenThrow(fatal);
    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(fatal);
    verifyNoInteractions(pollableTaskExceptionUtils);
    verify(pollableTaskService, never()).finishTask(eq(42L), any(), any(), any());
    assertThat(meterRegistry.getMeters()).isEmpty();
  }

  @Test
  public void payloadSerializationFailureCompensatesWithoutAttemptingEnqueue() {
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
        prepareSubmission(pollableTask(42L));
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    RuntimeException failure = new IllegalStateException("payload serialization failed");
    when(failingMapper.writeValueAsStringUnchecked(any())).thenThrow(failure);
    AssetLocalizeAsyncJobSubmissionService failingService =
        new AssetLocalizeAsyncJobSubmissionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            asyncJobQueueSubmissionService,
            failingMapper,
            meterRegistry);

    assertThatThrownBy(() -> failingService.scheduleJob(job)).isSameAs(failure);

    verify(pollableTaskBlobStorage).saveInput(42L, job.getInput());
    verifyNoInteractions(asyncJobQueueSubmissionService);
    verifyRedactedPreparationException(42L);
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
    verifyRedactedPreparationException(42L);
    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    assertScheduleCounter("failed", 1);
  }

  @Test
  public void scheduleJobRecordsCleanupFailureAndPropagatesOriginalInputFailure() {
    RuntimeException failure = new RuntimeException("input write failed");
    RuntimeException cleanupFailure = new RuntimeException("finish down");
    PollableTask pollableTask = pollableTask(42L);
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withInput(new LocalizedAssetBody())
            .build();
    when(pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600))
        .thenReturn(pollableTask);
    doThrow(failure).when(pollableTaskBlobStorage).saveInput(42L, quartzJobInfo.getInput());
    doThrow(cleanupFailure)
        .when(pollableTaskService)
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());

    assertThatThrownBy(() -> service.scheduleJob(quartzJobInfo)).isSameAs(failure);

    verifyRedactedPreparationException(42L);
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

  private void verifyRedactedPreparationException(long taskId) {
    ArgumentCaptor<Throwable> diagnostic = ArgumentCaptor.forClass(Throwable.class);
    verify(pollableTaskExceptionUtils)
        .processException(diagnostic.capture(), any(ExceptionHolder.class));
    assertThat(diagnostic.getValue())
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("Asset localization preparation failed for pollable task: " + taskId)
        .hasNoCause();
    assertThat(diagnostic.getValue().getSuppressed()).isEmpty();
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
