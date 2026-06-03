package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobPermanentFailureException;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AssetLocalizeAsyncJobHandlerTest {

  @Mock PollableTaskService pollableTaskService;
  @Mock PollableTaskBlobStorage pollableTaskBlobStorage;
  @Mock PollableTaskExceptionUtils pollableTaskExceptionUtils;
  @Mock LocalizedAssetGenerationService localizedAssetGenerationService;
  @Mock AssetLocalizeAsyncJobOutputStorage outputStorage;

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
            meterRegistry,
            outputStorage);
  }

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void ambiguousPayloadCannotSelectTaskOrGenerateOutput() {
    for (String json : ambiguousPayloads()) {
      assertThatThrownBy(() -> handler.process(asyncJobRecord(json, 1)))
          .isExactlyInstanceOf(AsyncJobPermanentFailureException.class)
          .hasMessage("Invalid asset localize async job payload")
          .hasNoCause();
    }
    verifyNoInteractions(
        pollableTaskService,
        pollableTaskBlobStorage,
        localizedAssetGenerationService,
        outputStorage,
        pollableTaskExceptionUtils);
  }

  @Test
  public void ambiguousCompletionPayloadCannotPublishOrFinishTask() {
    objectMapper.disable(
        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    for (String json : ambiguousPayloads()) {
      assertThatThrownBy(
              () ->
                  handler.onJobDone(
                      asyncJobRecord(jobData(42L), 1), AsyncJobHandlerResult.done(json)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause();
      assertThatThrownBy(
              () -> handler.onJobDone(asyncJobRecord(json, 1), AsyncJobHandlerResult.done()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause();
    }
    verifyNoInteractions(
        pollableTaskService,
        pollableTaskBlobStorage,
        localizedAssetGenerationService,
        outputStorage,
        pollableTaskExceptionUtils);
    assertThat(
            objectMapper.isEnabled(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
        .isFalse();
  }

  @Test
  public void ambiguousFailurePayloadCannotFinishTask() {
    for (String json : ambiguousPayloads()) {
      assertThatThrownBy(
              () ->
                  handler.onJobFailedPermanently(
                      asyncJobRecord(json, 1), new IllegalStateException("failed"), "failed"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasNoCause();
    }
    verifyNoInteractions(
        pollableTaskService,
        pollableTaskBlobStorage,
        localizedAssetGenerationService,
        outputStorage,
        pollableTaskExceptionUtils);
  }

  private String[] ambiguousPayloads() {
    return new String[] {
      "{\"pollableTaskId\":42.9}",
      "{\"pollableTaskId\":\"42\"}",
      "{\"pollableTaskId\":41,\"pollableTaskId\":42}",
      "{\"pollableTaskId\":42} {\"pollableTaskId\":41}",
      "{\"pollableTaskId\":42,\"futureSecretField\":null}",
      "null"
    };
  }

  @Test
  public void processGeneratesLocalizedAssetAndStoresOutputWithoutFinishingPollableTask()
      throws Exception {
    String jobData = jobData(42L);
    PollableTask pollableTask = pollableTask(42L);
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();
    AssetLocalizeAsyncJobPayload completedPayload =
        new AssetLocalizeAsyncJobPayload(42L, "00000000-0000-0000-0000-000000000001");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(objectMapper.writeValueAsBytes(input));
    when(localizedAssetGenerationService.generate(refEq(input))).thenReturn(output);
    when(outputStorage.saveAttemptOutput(42L, output)).thenReturn(completedPayload);

    AsyncJobHandlerResult result = handler.process(asyncJobRecord(jobData, 1));

    assertThat(result.action()).isEqualTo(AsyncJobHandlerResult.Action.DONE);
    assertThat(
            objectMapper.readValueUnchecked(result.jobData(), AssetLocalizeAsyncJobPayload.class))
        .isEqualTo(completedPayload);
    verify(outputStorage).saveAttemptOutput(42L, output);
    verify(pollableTaskBlobStorage, times(0)).saveOutput(eq(42L), any());
    verify(outputStorage, times(0)).publishOutput(any());
    verify(pollableTaskService, times(0)).finishTask(eq(42L), isNull(), isNull(), isNull());
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.process")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "succeeded")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(meterRegistry.get("AssetLocalizeAsyncJobHandler.process").timer().count())
        .isEqualTo(1);
  }

  @Test
  public void processRejectsAlreadySuccessfulTaskBeforeReadingInput() {
    assertFinishedTaskCannotExecute(null);
  }

  @Test
  public void processRejectsAlreadyFailedTaskBeforeReadingInput() {
    assertFinishedTaskCannotExecute("original task failure");
  }

  private void assertFinishedTaskCannotExecute(String errorMessage) {
    PollableTask task = pollableTask(42L);
    ZonedDateTime finished = ZonedDateTime.parse("2026-09-01T12:00:00Z");
    task.setFinishedDate(finished);
    task.setErrorMessage(errorMessage);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(task);

    for (int attempt : new int[] {1, 5}) {
      assertThatThrownBy(() -> handler.process(asyncJobRecord(jobData(42L), attempt)))
          .isExactlyInstanceOf(AsyncJobPermanentFailureException.class)
          .hasMessage("Asset localize async queue cannot execute finished pollable task: 42")
          .hasNoCause();
    }

    verify(pollableTaskService, times(2)).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
    verifyNoInteractions(
        pollableTaskBlobStorage,
        localizedAssetGenerationService,
        outputStorage,
        pollableTaskExceptionUtils);
    assertThat(task.getFinishedDate()).isEqualTo(finished);
    assertThat(task.getErrorMessage()).isEqualTo(errorMessage);
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.process")
                .tag("result", "failed")
                .counter()
                .count())
        .isEqualTo(2);
  }

  @Test
  public void permanentFailureCallbackPreservesAlreadySuccessfulTask() {
    assertPermanentFailurePreservesFinishedTask(null);
  }

  @Test
  public void permanentFailureCallbackPreservesAlreadyFailedTask() {
    assertPermanentFailurePreservesFinishedTask("original task failure");
  }

  private void assertPermanentFailurePreservesFinishedTask(String errorMessage) {
    PollableTask task = pollableTask(42L);
    ZonedDateTime finished = ZonedDateTime.parse("2026-09-01T12:00:00Z");
    task.setFinishedDate(finished);
    task.setErrorMessage(errorMessage);
    task.setErrorStack(errorMessage == null ? null : "original stack");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(task);

    handler.onJobFailedPermanently(
        asyncJobRecord(jobData(42L), 1),
        new AsyncJobPermanentFailureException("replay rejected"),
        "replay rejected");

    verify(pollableTaskService).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
    verifyNoInteractions(
        pollableTaskBlobStorage,
        localizedAssetGenerationService,
        outputStorage,
        pollableTaskExceptionUtils);
    assertThat(task.getFinishedDate()).isEqualTo(finished);
    assertThat(task.getErrorMessage()).isEqualTo(errorMessage);
    assertThat(task.getErrorStack()).isEqualTo(errorMessage == null ? null : "original stack");
    assertThat(meterRegistry.find("assetLocalizeAsyncJob.pollableTask.finished").counter())
        .isNull();
    assertSkippedCounter("failed");
  }

  @Test
  public void doneCallbackPreservesAlreadySuccessfulTaskWithoutPublishing() {
    assertDonePreservesFinishedTask(null);
  }

  @Test
  public void doneCallbackPreservesTimedOutTaskWithoutPublishing() {
    assertDonePreservesFinishedTask("original timeout");
  }

  private void assertDonePreservesFinishedTask(String errorMessage) {
    PollableTask task = pollableTask(42L);
    ZonedDateTime finished = ZonedDateTime.parse("2026-09-01T12:00:00Z");
    task.setFinishedDate(finished);
    task.setErrorMessage(errorMessage);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(task);

    handler.onJobDone(asyncJobRecord(jobData(42L), 1), AsyncJobHandlerResult.done());

    verify(pollableTaskService).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
    verifyNoInteractions(outputStorage, pollableTaskBlobStorage, localizedAssetGenerationService);
    assertThat(task.getFinishedDate()).isEqualTo(finished);
    assertThat(task.getErrorMessage()).isEqualTo(errorMessage);
    assertThat(meterRegistry.find("assetLocalizeAsyncJob.pollableTask.finished").counter())
        .isNull();
    assertThat(meterRegistry.find("assetLocalizeAsyncJob.pollableTask.finish.failed").counter())
        .isNull();
    assertSkippedCounter("done");
  }

  @Test
  public void doneCallbackLookupFailureDoesNotPublishAndPreservesCauseWhenMetricsFail() {
    RuntimeException failure = new IllegalStateException("task lookup unavailable");
    when(pollableTaskService.getPollableTask(42L)).thenThrow(failure);
    conflictWithCounter("assetLocalizeAsyncJob.pollableTask.finish.failed", "callback", "done");

    assertThatThrownBy(
            () -> handler.onJobDone(asyncJobRecord(jobData(42L), 1), AsyncJobHandlerResult.done()))
        .isSameAs(failure);

    verify(pollableTaskService).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
    verifyNoInteractions(outputStorage, pollableTaskBlobStorage, localizedAssetGenerationService);
  }

  @Test
  public void doneCallbackMissingTaskDoesNotPublish() {
    assertThatThrownBy(
            () -> handler.onJobDone(asyncJobRecord(jobData(42L), 1), AsyncJobHandlerResult.done()))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("PollableTask not found: 42");

    verify(pollableTaskService).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
    verifyNoInteractions(outputStorage, pollableTaskBlobStorage, localizedAssetGenerationService);
    assertPollableTaskFinishFailureCounter("done", 1);
  }

  @Test
  public void skippedCallbackMetricFailuresCannotPublishOrChangeTerminalTask() {
    PollableTask task = pollableTask(42L);
    ZonedDateTime finished = ZonedDateTime.parse("2026-09-01T12:00:00Z");
    task.setFinishedDate(finished);
    task.setErrorMessage("original timeout");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(task);
    for (String callback : new String[] {"done", "failed"}) {
      meterRegistry.gauge(
          "assetLocalizeAsyncJob.pollableTask.finish.skipped",
          Tags.of("queueName", "assetlocalize", "callback", callback, "reason", "alreadyFinished"),
          1);
    }

    handler.onJobDone(asyncJobRecord(jobData(42L), 1), AsyncJobHandlerResult.done());
    handler.onJobFailedPermanently(
        asyncJobRecord(jobData(42L), 1),
        new IllegalStateException("later failure"),
        "later failure");

    verify(pollableTaskService, times(2)).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
    verifyNoInteractions(
        outputStorage,
        pollableTaskBlobStorage,
        localizedAssetGenerationService,
        pollableTaskExceptionUtils);
    assertThat(task.getFinishedDate()).isEqualTo(finished);
    assertThat(task.getErrorMessage()).isEqualTo("original timeout");
    assertThat(meterRegistry.find("assetLocalizeAsyncJob.pollableTask.finished").counter())
        .isNull();
    assertThat(meterRegistry.find("assetLocalizeAsyncJob.pollableTask.finish.failed").counter())
        .isNull();
  }

  private void assertSkippedCounter(String callback) {
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.pollableTask.finish.skipped")
                .tags(
                    "queueName", "assetlocalize", "callback", callback, "reason", "alreadyFinished")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void processRejectsEveryNonNullPullRunNameBeforeGenerationOnInitialAndLaterAttempts() {
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    for (String pullRunName : new String[] {"tracked-run", "", " \t\n "}) {
      LocalizedAssetBody input = new LocalizedAssetBody();
      input.setPullRunName(pullRunName);
      input.setOutputBcp47tag("fr-FR");
      when(pollableTaskBlobStorage.getInputBytes(42L))
          .thenReturn(objectMapper.writeValueAsBytes(input));

      for (int attempt : new int[] {1, 5}) {
        assertThatThrownBy(() -> handler.process(asyncJobRecord(jobData(42L), attempt)))
            .isInstanceOf(AsyncJobPermanentFailureException.class)
            .hasMessage("Asset localize async queue does not support pull-run tracking");
      }
      assertThat(input.getPullRunName()).isEqualTo(pullRunName);
    }

    verify(pollableTaskService, times(6)).getPollableTask(42L);
    verify(pollableTaskBlobStorage, times(6)).getInputBytes(42L);
    verifyNoMoreInteractions(pollableTaskService, pollableTaskBlobStorage);
    verifyNoInteractions(
        localizedAssetGenerationService, outputStorage, pollableTaskExceptionUtils);
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.process")
                .tag("result", "failed")
                .counter()
                .count())
        .isEqualTo(6);
  }

  @Test
  public void trackedInputRejectionSurvivesMetricFailuresWithoutGenerationOrTaskFinish() {
    LocalizedAssetBody input = new LocalizedAssetBody();
    input.setPullRunName("private-tracked-run");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(objectMapper.writeValueAsBytes(input));
    meterRegistry.gauge(
        "AssetLocalizeAsyncJobHandler.process", Tags.of("queueName", "assetlocalize"), 1);
    conflictWithCounter("assetLocalizeAsyncJob.process", "result", "failed");

    assertThatThrownBy(() -> handler.process(asyncJobRecord(jobData(42L), 1)))
        .isInstanceOf(AsyncJobPermanentFailureException.class)
        .hasMessage("Asset localize async queue does not support pull-run tracking");

    verify(pollableTaskService).getPollableTask(42L);
    verify(pollableTaskBlobStorage).getInputBytes(42L);
    verifyNoMoreInteractions(pollableTaskService, pollableTaskBlobStorage);
    verifyNoInteractions(
        localizedAssetGenerationService, outputStorage, pollableTaskExceptionUtils);
  }

  @Test
  public void processMetricFailuresDoNotMaskTheGenerationFailure() throws Exception {
    RuntimeException failure = new IllegalStateException("generation failed");
    meterRegistry.gauge(
        "AssetLocalizeAsyncJobHandler.process", Tags.of("queueName", "assetlocalize"), 1);
    conflictWithCounter("assetLocalizeAsyncJob.process", "result", "failed");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(objectMapper.writeValueAsBytes(new LocalizedAssetBody()));
    when(localizedAssetGenerationService.generate(any())).thenThrow(failure);

    assertThatThrownBy(() -> handler.process(asyncJobRecord(jobData(42L), 1))).isSameAs(failure);
    verifyNoInteractions(outputStorage);
  }

  @Test
  public void processNonFatalMetricErrorsDoNotDiscardSuccessfulOutput() throws Exception {
    AssetLocalizeAsyncJobPayload completed = stubSuccessfulProcess();
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("AssetLocalizeAsyncJobHandler.process")
                  || meter.getId().getName().equals("assetLocalizeAsyncJob.process")) {
                throw new AssertionError("metric provider failed");
              }
            });

    AsyncJobHandlerResult result = handler.process(asyncJobRecord(jobData(42L), 1));
    assertThat(result.action()).isEqualTo(AsyncJobHandlerResult.Action.DONE);
    assertThat(
            objectMapper.readValueUnchecked(result.jobData(), AssetLocalizeAsyncJobPayload.class))
        .isEqualTo(completed);
    verify(localizedAssetGenerationService).generate(any());
  }

  @Test
  public void processFatalMetricErrorsStillPropagate() throws Exception {
    stubSuccessfulProcess();
    OutOfMemoryError fatal = new OutOfMemoryError("synthetic metric failure");
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("assetLocalizeAsyncJob.process")) {
                throw fatal;
              }
            });

    assertThatThrownBy(() -> handler.process(asyncJobRecord(jobData(42L), 1))).isSameAs(fatal);
  }

  @Test
  public void successfulDoneCallbackIsNotFailedByMetrics() {
    conflictWithCounter("assetLocalizeAsyncJob.pollableTask.finished", "result", "succeeded");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    String jobData = jobData(42L);

    handler.onJobDone(asyncJobRecord(jobData, 1), AsyncJobHandlerResult.done(jobData));

    verify(outputStorage).publishOutput(new AssetLocalizeAsyncJobPayload(42L));
    verify(pollableTaskService).finishTask(42L, null, null, null);
    assertThat(meterRegistry.find("assetLocalizeAsyncJob.pollableTask.finish.failed").counter())
        .isNull();
  }

  @Test
  public void successfulPermanentFailureCallbackIsNotFailedByMetrics() {
    conflictWithCounter("assetLocalizeAsyncJob.pollableTask.finished", "result", "failed");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    RuntimeException failure = new IllegalStateException("business failure");

    handler.onJobFailedPermanently(asyncJobRecord(jobData(42L), 3), failure, "business failure");

    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    assertThat(meterRegistry.find("assetLocalizeAsyncJob.pollableTask.finish.failed").counter())
        .isNull();
  }

  @Test
  public void failedDoneCallbackPreservesItsFailureWhenMetricsFail() {
    conflictWithCounter("assetLocalizeAsyncJob.pollableTask.finish.failed", "callback", "done");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    RuntimeException failure = new IllegalStateException("publish failed");
    doThrow(failure).when(outputStorage).publishOutput(any());
    String jobData = jobData(42L);

    assertThatThrownBy(
            () ->
                handler.onJobDone(asyncJobRecord(jobData, 1), AsyncJobHandlerResult.done(jobData)))
        .isSameAs(failure);
    verify(pollableTaskService).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
  }

  @Test
  public void failedTerminalCallbackPreservesItsFailureWhenMetricsFail() {
    conflictWithCounter("assetLocalizeAsyncJob.pollableTask.finish.failed", "callback", "failed");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    RuntimeException failure = new IllegalStateException("finish failed");
    doThrow(failure).when(pollableTaskService).finishTask(eq(42L), isNull(), any(), isNull());

    assertThatThrownBy(
            () ->
                handler.onJobFailedPermanently(
                    asyncJobRecord(jobData(42L), 3),
                    new IllegalStateException("business failure"),
                    "failed"))
        .isSameAs(failure);
  }

  private AssetLocalizeAsyncJobPayload stubSuccessfulProcess() throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();
    AssetLocalizeAsyncJobPayload completed =
        new AssetLocalizeAsyncJobPayload(42L, "00000000-0000-0000-0000-000000000001");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(objectMapper.writeValueAsBytes(input));
    when(localizedAssetGenerationService.generate(refEq(input))).thenReturn(output);
    when(outputStorage.saveAttemptOutput(42L, output)).thenReturn(completed);
    return completed;
  }

  private void conflictWithCounter(String name, String tag, String value) {
    meterRegistry.gauge(name, Tags.of("queueName", "assetlocalize", tag, value), 1);
  }

  @Test
  public void processFailureDoesNotFinishPollableTaskBeforeRuntimeTerminalFailure()
      throws Exception {
    RuntimeException failure = new RuntimeException("generate failed");
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(objectMapper.writeValueAsBytes(new LocalizedAssetBody()));
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
  public void processMissingPollableTaskRecordsFailureBeforeRuntimeTerminalFailure()
      throws Exception {
    when(pollableTaskService.getPollableTask(42L)).thenReturn(null);

    assertThatThrownBy(() -> handler.process(asyncJobRecord(jobData(42L), 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PollableTask not found: 42");

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
  public void processMalformedPayloadRecordsFailureBeforePollableLookup() throws Exception {
    assertThatThrownBy(() -> handler.process(asyncJobRecord("{\"pollableTaskId\":0}", 1)))
        .isExactlyInstanceOf(AsyncJobPermanentFailureException.class);

    verifyNoInteractions(
        pollableTaskService, pollableTaskBlobStorage, localizedAssetGenerationService);
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
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));

    handler.onJobDone(asyncJobRecord(jobData, 1), AsyncJobHandlerResult.done(jobData));

    var inOrder = org.mockito.Mockito.inOrder(outputStorage, pollableTaskService);
    inOrder.verify(pollableTaskService).getPollableTask(42L);
    inOrder.verify(outputStorage).publishOutput(new AssetLocalizeAsyncJobPayload(42L));
    inOrder.verify(pollableTaskService).finishTask(42L, null, null, null);
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
  public void outputPublicationFailureDoesNotFinishPollableTask() {
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    RuntimeException failure = new IllegalStateException("output unavailable");
    String jobData = jobData(42L);
    doThrow(failure).when(outputStorage).publishOutput(new AssetLocalizeAsyncJobPayload(42L));

    assertThatThrownBy(
            () ->
                handler.onJobDone(asyncJobRecord(jobData, 1), AsyncJobHandlerResult.done(jobData)))
        .isSameAs(failure);

    verify(pollableTaskService).getPollableTask(42L);
    verifyNoMoreInteractions(pollableTaskService);
    assertPollableTaskFinishFailureCounter("done", 1);
  }

  @Test
  public void onJobDoneRecordsPollableTaskFinishFailureAndRethrows() {
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    RuntimeException failure = new RuntimeException("finish down");
    String jobData = jobData(42L);
    doThrow(failure).when(pollableTaskService).finishTask(42L, null, null, null);

    assertThatThrownBy(
            () ->
                handler.onJobDone(asyncJobRecord(jobData, 1), AsyncJobHandlerResult.done(jobData)))
        .isSameAs(failure);

    assertPollableTaskFinishFailureCounter("done", 1);
  }

  @Test
  public void unexpectedFailureCallbackDoesNotPublishRawDiagnosticGraph() {
    RuntimeException failure =
        new IllegalStateException("synthetic private failure", new Exception("private cause"));
    failure.addSuppressed(new IllegalStateException("private cleanup"));

    assertUnexpectedCallbackIsRedacted(failure);
  }

  @Test
  public void nonfatalErrorCallbackDoesNotPublishRawDiagnosticGraph() {
    AssertionError failure = new AssertionError("synthetic private error");
    failure.addSuppressed(new IllegalStateException("private cleanup"));

    assertUnexpectedCallbackIsRedacted(failure);
  }

  @Test
  public void permanentRetrySignalDoesNotBecomeUserFacingCheckedError() {
    AsyncJobPermanentFailureException failure =
        new AsyncJobPermanentFailureException("synthetic private permanent failure");
    failure.initCause(new IllegalStateException("private cause"));

    assertUnexpectedCallbackIsRedacted(failure);
  }

  @Test
  public void checkedBusinessFailureCallbackPreservesExistingUserErrorContract() {
    Exception failure = new Exception("known business error", new Exception("legacy context"));
    useRealExceptionClassifier();
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));

    handler.onJobFailedPermanently(asyncJobRecord(jobData(42L), 3), failure, "operator detail");

    ExceptionHolder holder = finishedException();
    assertThat(holder.isExpected()).isTrue();
    assertThat(holder.getException()).isSameAs(failure);
    assertThat(holder.getMessage()).isEqualTo("known business error");
    assertThat(holder.getType()).isEqualTo(Exception.class.getName());
    verifyNoInteractions(outputStorage, pollableTaskBlobStorage, localizedAssetGenerationService);
  }

  private void assertUnexpectedCallbackIsRedacted(Throwable failure) {
    useRealExceptionClassifier();
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));

    handler.onJobFailedPermanently(
        asyncJobRecord(jobData(42L), 3), failure, "private queue detail");

    ExceptionHolder holder = finishedException();
    assertThat(holder.isExpected()).isFalse();
    assertThat(holder.getException())
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("Asset localize async job failed permanently: 1")
        .hasNoCause();
    assertThat(holder.getException().getSuppressed()).isEmpty();
    assertThat(holder.getMessage()).isEqualTo("An unexpected error happened, task=42");
    assertThat(holder.getType()).isEqualTo("unexpected");
    verifyNoInteractions(outputStorage, pollableTaskBlobStorage, localizedAssetGenerationService);
  }

  private ExceptionHolder finishedException() {
    ArgumentCaptor<ExceptionHolder> holder = ArgumentCaptor.forClass(ExceptionHolder.class);
    verify(pollableTaskService).finishTask(eq(42L), isNull(), holder.capture(), isNull());
    return holder.getValue();
  }

  private void useRealExceptionClassifier() {
    handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            new PollableTaskExceptionUtils(),
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            outputStorage);
  }

  @Test
  public void onJobFailedPermanentlyFinishesPollableTaskWithExceptionAfterQueueFailedTransition() {
    RuntimeException failure = new RuntimeException("terminal");
    PollableTask pollableTask = pollableTask(42L);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);

    handler.onJobFailedPermanently(asyncJobRecord(jobData(42L), 3), failure, "terminal");

    verifyNoInteractions(pollableTaskExceptionUtils);
    assertThat(finishedException().getException())
        .hasMessage("Asset localize async job failed permanently: 1")
        .hasNoCause();
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.pollableTask.finished")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("result", "failed")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void onJobFailedPermanentlyRecordsPollableTaskFinishFailureAndRethrows() {
    RuntimeException failure = new RuntimeException("terminal");
    RuntimeException finishFailure = new RuntimeException("finish down");
    PollableTask pollableTask = pollableTask(42L);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    doThrow(finishFailure)
        .when(pollableTaskService)
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());

    assertThatThrownBy(
            () ->
                handler.onJobFailedPermanently(
                    asyncJobRecord(jobData(42L), 3), failure, "terminal"))
        .isSameAs(finishFailure);

    verifyNoInteractions(pollableTaskExceptionUtils);
    assertThat(finishedException().getException())
        .hasMessage("Asset localize async job failed permanently: 1")
        .hasNoCause();
    assertPollableTaskFinishFailureCounter("failed", 1);
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

  private void assertPollableTaskFinishFailureCounter(String callback, double expectedCount) {
    assertThat(
            meterRegistry
                .get("assetLocalizeAsyncJob.pollableTask.finish.failed")
                .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                .tag("callback", callback)
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }
}
