package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobHandler;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobOutputStorage;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import com.box.l10n.mojito.service.tm.LocalizedAssetGenerationService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@RunWith(MockitoJUnitRunner.class)
public class AssetLocalizeAsyncJobQueueIntegrationTest {

  @Mock AsyncJobQueueCoordinator asyncJobQueueCoordinator;
  @Mock PollableTaskService pollableTaskService;
  @Mock PollableTaskBlobStorage pollableTaskBlobStorage;
  @Mock PollableTaskExceptionUtils pollableTaskExceptionUtils;
  @Mock LocalizedAssetGenerationService localizedAssetGenerationService;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ThreadPoolTaskExecutor executor = newExecutor();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private AssetLocalizeAsyncJobOutputStorage outputStorage;

  @Before
  public void setUpOutputStorage() {
    Map<String, String> blobs = new ConcurrentHashMap<>();
    StructuredBlobStorage structuredBlobStorage =
        new StructuredBlobStorage(null) {
          @Override
          public void put(Prefix prefix, String name, String content, Retention retention) {
            blobs.put(prefix + "/" + name, content);
          }

          @Override
          public Optional<byte[]> getBytes(Prefix prefix, String name) {
            return Optional.ofNullable(blobs.get(prefix + "/" + name))
                .map(json -> json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
        };
    outputStorage =
        new AssetLocalizeAsyncJobOutputStorage(
            structuredBlobStorage, pollableTaskBlobStorage, objectMapper);
  }

  @After
  public void tearDown() {
    executor.shutdown();
    meterRegistry.close();
  }

  @Test
  public void submittedAssetLocalizeJobRunsThroughDurableQueueAndFinishesPollableTask()
      throws Exception {
    assertSuccessfulSubmissionAndExecution();
  }

  @Test
  public void acceptedJobSurvivesSubmissionAndWakeupMetricFailures() throws Exception {
    meterRegistry.gauge(
        "asyncJobQueue.enqueue", Tags.of("queueName", "assetlocalize", "result", "succeeded"), 1);
    meterRegistry.gauge(
        "assetLocalizeAsyncJob.schedule",
        Tags.of("queueName", "assetlocalize", "result", "succeeded"),
        1);
    meterRegistry.gauge(
        "asyncJobQueue.enqueueWakeup.failed", Tags.of("queueName", "assetlocalize"), 1);
    org.mockito.Mockito.doThrow(new IllegalStateException("wakeup unavailable"))
        .when(asyncJobQueueCoordinator)
        .triggerPollNow("assetlocalize");

    assertSuccessfulSubmissionAndExecution();
  }

  @Test
  public void malformedIdentityTerminalizesWithoutGuessingTaskOrPublishingOutput()
      throws Exception {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobId id = store.enqueueNow("assetlocalize", "{\"pollableTaskId\":42.9}");
    var settings = queueSettings();
    settings.setMaxAttempts(5);
    var handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            outputStorage);
    var runtime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            store,
            settings,
            handler,
            org.mockito.Mockito.mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");
    runtime.pollOnce();
    waitForStatusCount(store, AsyncJobStatus.FAILED, 1);
    waitForCallbackCounter("asyncJobQueue.handler.completion.failed", "failed", 1);
    AsyncJobRecord failed = store.getByIds(List.of(id)).getFirst();
    assertThat(failed.attemptCount()).isEqualTo(1);
    assertThat(failed.lastError()).contains("Invalid asset localize async job payload");
    assertThat(failed.lastError()).doesNotContain("42.9");
    org.mockito.Mockito.verifyNoInteractions(
        pollableTaskService,
        pollableTaskBlobStorage,
        pollableTaskExceptionUtils,
        localizedAssetGenerationService);
    assertThat(store.claimNextJobs("assetlocalize", 1, "other", Duration.ofSeconds(10))).isEmpty();
  }

  @Test
  public void malformedStoredInputTerminalizesWithRedactedFailureWithoutGeneratingOutput()
      throws Exception {
    assertMalformedInputTerminalizes(
        "{\"content\":\"private-assetlocalize-runtime-source-marker\",\"bcp47Tag\":"
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void escapedUnpairedSurrogateTerminalizesOnFirstAttemptWithoutGeneratingOutput()
      throws Exception {
    assertMalformedInputTerminalizes(
        "{\"content\":\"private-assetlocalize-runtime-source-marker\\ud800\"}"
            .getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void malformedUtf8InputTerminalizesOnFirstAttemptWithoutGeneratingOutput()
      throws Exception {
    String json =
        "{\"content\":\"private-assetlocalize-runtime-source-marker!\",\"bcp47Tag\":\"fr\"}";
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    bytes[json.indexOf('!')] = (byte) 0x80;
    assertMalformedInputTerminalizes(bytes);
  }

  private void assertMalformedInputTerminalizes(byte[] inputBytes) throws Exception {
    assertPermanentInputFailure(inputBytes, "Invalid assetlocalize input for pollable task: 42");
  }

  @Test
  public void trackedInputTerminalizesOnFirstAttemptWithoutGeneratingOutput() throws Exception {
    assertTrackedInputTerminalizes("private-tracked-run");
  }

  @Test
  public void emptyTrackingNameTerminalizesOnFirstAttemptWithoutGeneratingOutput()
      throws Exception {
    assertTrackedInputTerminalizes("");
  }

  @Test
  public void whitespaceTrackingNameTerminalizesOnFirstAttemptWithoutGeneratingOutput()
      throws Exception {
    assertTrackedInputTerminalizes(" \t\n ");
  }

  private void assertTrackedInputTerminalizes(String pullRunName) throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    input.setPullRunName(pullRunName);
    input.setContent("private-assetlocalize-runtime-source-marker");
    assertPermanentInputFailure(
        objectMapper.writeValueAsBytes(input),
        "Asset localize async queue does not support pull-run tracking");
  }

  private void assertPermanentInputFailure(byte[] inputBytes, String redactedFailure)
      throws Exception {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    String jobData = "{\"pollableTaskId\":42}";
    // Model already-persisted work without weakening the current producer's eligibility guard.
    AsyncJobId id = store.enqueueNow("assetlocalize", jobData);
    byte[] originalInputBytes = inputBytes.clone();
    var settings = queueSettings();
    settings.setMaxAttempts(5);
    String privateSource = "private-assetlocalize-runtime-source-marker";
    PollableTask pollableTask = pollableTask(42L);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInputBytes(42L)).thenReturn(inputBytes);
    var unusedOutputStorage = org.mockito.Mockito.mock(AssetLocalizeAsyncJobOutputStorage.class);
    var handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            unusedOutputStorage);
    var runtime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            store,
            settings,
            handler,
            org.mockito.Mockito.mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");

    runtime.pollOnce();
    waitForStatusCount(store, AsyncJobStatus.FAILED, 1);
    verify(pollableTaskService, timeout(2_000))
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    Instant drainDeadline = Instant.now().plusSeconds(2);
    while (runtime.inFlightCount() != 0 && Instant.now().isBefore(drainDeadline)) {
      Thread.sleep(10);
    }
    assertThat(runtime.inFlightCount()).isZero();

    AsyncJobRecord failed = store.getByIds(List.of(id)).getFirst();
    assertThat(failed.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failed.attemptCount()).isEqualTo(1);
    assertThat(failed.jobData()).isEqualTo(jobData);
    assertThat(failed.lastError())
        .isEqualTo(AsyncJobPermanentFailureException.class.getName() + ": " + redactedFailure)
        .doesNotContain(privateSource, "private-tracked-run");
    assertThat(assertRedactedTaskFailure(id).getPollableTask()).isSameAs(pollableTask);
    assertThat(inputBytes).isEqualTo(originalInputBytes);
    verify(pollableTaskBlobStorage).getInputBytes(42L);
    org.mockito.Mockito.verifyNoMoreInteractions(pollableTaskBlobStorage);
    org.mockito.Mockito.verifyNoInteractions(localizedAssetGenerationService, unusedOutputStorage);
    assertThat(meterRegistry.find("asyncJobQueue.handler.completion.failed").counter()).isNull();
    assertThat(store.claimNextJobs("assetlocalize", 1, "other", Duration.ofSeconds(10))).isEmpty();
    assertThat(objectMapper.isEnabled(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
    assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
  }

  @Test
  public void acceptedJobDoesNotRetrySuccessfulGenerationWhenHandlerMetricsFail() throws Exception {
    meterRegistry.gauge(
        "AssetLocalizeAsyncJobHandler.process", Tags.of("queueName", "assetlocalize"), 1);
    meterRegistry.gauge(
        "assetLocalizeAsyncJob.process",
        Tags.of("queueName", "assetlocalize", "result", "succeeded"),
        1);
    meterRegistry.gauge(
        "assetLocalizeAsyncJob.pollableTask.finished",
        Tags.of("queueName", "assetlocalize", "result", "succeeded"),
        1);

    assertSuccessfulSubmissionAndExecution();
  }

  @Test
  public void missingInputCanRecoverOnTheNextAttempt() throws Exception {
    assertInputReadCanRecover(null);
  }

  @Test
  public void unavailableInputStorageCanRecoverOnTheNextAttempt() throws Exception {
    // Even an IllegalArgumentException from storage is not a payload parsing failure.
    assertInputReadCanRecover(new IllegalArgumentException("storage unavailable"));
  }

  private void assertInputReadCanRecover(RuntimeException storageFailure) throws Exception {
    // inFlight can reach zero just before the previous executor task returns. Buffer that
    // handoff so these input-recovery tests do not accidentally exercise executor rejection.
    ThreadPoolTaskExecutor recoveryExecutor = newExecutor(1);
    try {
      assertInputReadCanRecover(storageFailure, recoveryExecutor);
    } finally {
      recoveryExecutor.shutdown();
    }
  }

  private void assertInputReadCanRecover(
      RuntimeException storageFailure, ThreadPoolTaskExecutor recoveryExecutor) throws Exception {
    var recovered = new java.util.concurrent.atomic.AtomicBoolean();
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var inputs = new PollableTaskBlobStorage();
    org.springframework.test.util.ReflectionTestUtils.setField(
        inputs,
        "structuredBlobStorage",
        new StructuredBlobStorage(null) {
          @Override
          public Optional<byte[]> getBytes(Prefix prefix, String name) {
            assertThat(prefix).isEqualTo(Prefix.POLLABLE_TASK);
            assertThat(name).isEqualTo("42/input");
            reads.incrementAndGet();
            if (!recovered.get()) {
              if (storageFailure != null) {
                throw storageFailure;
              }
              return Optional.empty();
            }
            return Optional.of(
                "{\"assetId\":123,\"bcp47Tag\":\"fr\"}".getBytes(StandardCharsets.UTF_8));
          }
        });
    var store = new InMemoryAsyncJobStore();
    AsyncJobId id = store.enqueueNow("assetlocalize", "{\"pollableTaskId\":42}");
    var settings = queueSettings();
    settings.setMaxAttempts(5);
    settings.setMaxRetryDelayMs(1);
    settings.setRetryJitterPercent(0);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask(42L));
    var handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            inputs,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            outputStorage);
    var runtime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            store,
            settings,
            handler,
            org.mockito.Mockito.mock(TaskScheduler.class),
            recoveryExecutor,
            meterRegistry,
            "worker-a");

    runtime.pollOnce();
    waitForAttemptToDrain(runtime);
    AsyncJobRecord retry = store.getByIds(List.of(id)).getFirst();
    assertThat(retry.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(retry.attemptCount()).isEqualTo(1);
    assertThat(retry.lastError())
        .isEqualTo(
            storageFailure == null
                ? "java.lang.RuntimeException: Can't get the input json for: 42"
                : storageFailure.toString());
    assertThat(reads.get()).isEqualTo(1);
    org.mockito.Mockito.verifyNoInteractions(
        localizedAssetGenerationService, pollableTaskExceptionUtils);
    verify(pollableTaskService, org.mockito.Mockito.never())
        .finishTask(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());

    recovered.set(true);
    var output = new LocalizedAssetBody("fr", "recovered translation");
    when(localizedAssetGenerationService.generate(any())).thenReturn(output);
    Instant deadline = Instant.now().plusSeconds(2);
    while (store.getByIds(List.of(id)).getFirst().status() != AsyncJobStatus.DONE
        && Instant.now().isBefore(deadline)) {
      runtime.pollOnce();
      Thread.sleep(5);
    }
    waitForAttemptToDrain(runtime);
    AsyncJobRecord completed = store.getByIds(List.of(id)).getFirst();
    assertThat(completed.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(completed.attemptCount()).isEqualTo(2);
    assertThat(reads.get()).isEqualTo(2);
    verify(localizedAssetGenerationService).generate(any());
    verify(pollableTaskBlobStorage).saveOutput(eq(42L), refEq(output));
    verify(pollableTaskService).finishTask(42L, null, null, null);
    org.mockito.Mockito.verifyNoInteractions(pollableTaskExceptionUtils);
  }

  private void waitForAttemptToDrain(AsyncJobQueueRuntime runtime) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(2);
    while (runtime.inFlightCount() != 0 && Instant.now().isBefore(deadline)) {
      Thread.sleep(5);
    }
    assertThat(runtime.inFlightCount()).isZero();
  }

  private void assertSuccessfulSubmissionAndExecution() throws Exception {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    PollableTask pollableTask = pollableTask(42L);
    AtomicReference<String> storedInput = new AtomicReference<>();
    AtomicReference<LocalizedAssetBody> storedOutput = new AtomicReference<>();
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();

    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 0, 3600))
        .thenReturn(pollableTask);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenAnswer(invocation -> storedInput.get().getBytes(StandardCharsets.UTF_8));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              storedInput.set(objectMapper.writeValueAsStringUnchecked(invocation.getArgument(1)));
              return null;
            })
        .when(pollableTaskBlobStorage)
        .saveInput(eq(42L), any(LocalizedAssetBody.class));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              storedOutput.set(invocation.getArgument(1));
              return null;
            })
        .when(pollableTaskBlobStorage)
        .saveOutput(eq(42L), any(LocalizedAssetBody.class));
    when(localizedAssetGenerationService.generate(refEq(input))).thenReturn(output);

    AsyncJobQueueSubmissionService queueSubmissionService =
        new AsyncJobQueueSubmissionService(asyncJobStore, asyncJobQueueCoordinator, meterRegistry);
    AssetLocalizeAsyncJobSubmissionService assetSubmissionService =
        new AssetLocalizeAsyncJobSubmissionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            queueSubmissionService,
            objectMapper,
            meterRegistry);
    AssetLocalizeAsyncJobHandler handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            outputStorage);
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
            asyncJobStore,
            queueSettings(),
            handler,
            org.mockito.Mockito.mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");

    PollableFuture<LocalizedAssetBody> future =
        assetSubmissionService.scheduleJob(
            QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                .withParentId(7L)
                .withInput(input)
                .withMessage("message")
                .build());

    assertThat(future.getPollableTask()).isSameAs(pollableTask);
    verify(asyncJobQueueCoordinator)
        .triggerPollNow(AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME);

    runtime.pollOnce();
    waitForStatusCount(asyncJobStore, AsyncJobStatus.DONE, 1);
    verify(pollableTaskService, timeout(2_000)).finishTask(42L, null, null, null);
    Instant drainDeadline = Instant.now().plusSeconds(2);
    while (runtime.inFlightCount() != 0 && Instant.now().isBefore(drainDeadline)) {
      Thread.sleep(10);
    }
    assertThat(runtime.inFlightCount()).isZero();
    assertThat(meterRegistry.find("asyncJobQueue.handler.completion.failed").counter()).isNull();

    AsyncJobRecord completedJob =
        asyncJobStore.getByIds(List.of(new AsyncJobId("1"))).stream().findFirst().orElseThrow();
    assertThat(completedJob.attemptCount()).isEqualTo(1);
    AssetLocalizeAsyncJobPayload payload =
        objectMapper.readValueUnchecked(completedJob.jobData(), AssetLocalizeAsyncJobPayload.class);
    assertThat(payload.pollableTaskId()).isEqualTo(42L);
    assertThat(objectMapper.readValueUnchecked(storedInput.get(), LocalizedAssetBody.class))
        .usingRecursiveComparison()
        .isEqualTo(input);
    assertThat(storedOutput.get()).usingRecursiveComparison().isEqualTo(output);
    verify(pollableTaskService).finishTask(42L, null, null, null);
    verify(localizedAssetGenerationService).generate(refEq(input));
    verify(pollableTaskExceptionUtils, times(0))
        .processException(any(Throwable.class), any(ExceptionHolder.class));
  }

  @Test
  public void failedAssetLocalizeJobRunsThroughDurableQueueAndFinishesPollableTaskWithException()
      throws Exception {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    PollableTask pollableTask = pollableTask(42L);
    LocalizedAssetBody input = new LocalizedAssetBody();
    RuntimeException failure = new RuntimeException("generate failed");

    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 0, 3600))
        .thenReturn(pollableTask);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(objectMapper.writeValueAsBytes(input));
    when(localizedAssetGenerationService.generate(refEq(input))).thenThrow(failure);

    AsyncJobQueueSubmissionService queueSubmissionService =
        new AsyncJobQueueSubmissionService(asyncJobStore, asyncJobQueueCoordinator, meterRegistry);
    AssetLocalizeAsyncJobSubmissionService assetSubmissionService =
        new AssetLocalizeAsyncJobSubmissionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            queueSubmissionService,
            objectMapper,
            meterRegistry);
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings();
    queueSettings.setMaxAttempts(1);
    AssetLocalizeAsyncJobHandler handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            outputStorage);
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
            asyncJobStore,
            queueSettings,
            handler,
            org.mockito.Mockito.mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");

    assetSubmissionService.scheduleJob(
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withParentId(7L)
            .withInput(input)
            .withMessage("message")
            .build());

    runtime.pollOnce();
    waitForStatusCount(asyncJobStore, AsyncJobStatus.FAILED, 1);

    AsyncJobRecord failedJob =
        asyncJobStore.getByIds(List.of(new AsyncJobId("1"))).stream().findFirst().orElseThrow();
    assertThat(failedJob.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failedJob.lastError()).contains("generate failed");
    assertRedactedTaskFailure(failedJob.id());
  }

  @Test
  public void pollableDoneCallbackFailureLeavesQueueJobDoneAndCanBeRepaired() throws Exception {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    PollableTask pollableTask = pollableTask(42L);
    AtomicReference<String> storedInput = new AtomicReference<>();
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();
    RuntimeException finishFailure = new RuntimeException("finish down");

    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 0, 3600))
        .thenReturn(pollableTask);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenAnswer(invocation -> storedInput.get().getBytes(StandardCharsets.UTF_8));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              storedInput.set(objectMapper.writeValueAsStringUnchecked(invocation.getArgument(1)));
              return null;
            })
        .when(pollableTaskBlobStorage)
        .saveInput(eq(42L), any(LocalizedAssetBody.class));
    when(localizedAssetGenerationService.generate(refEq(input))).thenReturn(output);
    when(pollableTaskService.finishTask(42L, null, null, null))
        .thenThrow(finishFailure)
        .thenReturn(pollableTask);

    AsyncJobQueueSubmissionService queueSubmissionService =
        new AsyncJobQueueSubmissionService(asyncJobStore, asyncJobQueueCoordinator, meterRegistry);
    AssetLocalizeAsyncJobSubmissionService assetSubmissionService =
        new AssetLocalizeAsyncJobSubmissionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            queueSubmissionService,
            objectMapper,
            meterRegistry);
    AssetLocalizeAsyncJobHandler handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            outputStorage);
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
            asyncJobStore,
            queueSettings(),
            handler,
            org.mockito.Mockito.mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");

    assetSubmissionService.scheduleJob(
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withParentId(7L)
            .withInput(input)
            .withMessage("message")
            .build());

    runtime.pollOnce();
    waitForStatusCount(asyncJobStore, AsyncJobStatus.DONE, 1);
    waitForCallbackCounter("assetLocalizeAsyncJob.pollableTask.finish.failed", "done", 1);
    waitForCallbackCounter("asyncJobQueue.handler.completion.failed", "done", 1);

    AsyncJobRecord completedJob =
        asyncJobStore.getByIds(List.of(new AsyncJobId("1"))).stream().findFirst().orElseThrow();
    assertThat(completedJob.status()).isEqualTo(AsyncJobStatus.DONE);
    verify(pollableTaskBlobStorage).saveOutput(eq(42L), refEq(output));
    verify(pollableTaskExceptionUtils, times(0))
        .processException(any(Throwable.class), any(ExceptionHolder.class));

    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask);
    AssetLocalizeAsyncJobRepairService repairService =
        new AssetLocalizeAsyncJobRepairService(
            asyncJobStore, pollableTaskService, meterRegistry, outputStorage);

    AssetLocalizeAsyncJobRepairService.RepairResult repairResult =
        repairService.repairTerminalPollableTask(completedJob);

    assertThat(repairResult.result()).isEqualTo("repaired");
    assertThat(repairResult.status()).isEqualTo("done");
    verify(pollableTaskService, times(2)).finishTask(42L, null, null, null);
    assertRepairCounter("done", "repaired", 1);
  }

  @Test
  public void pollableFailedCallbackFailureLeavesQueueJobFailedAndCanBeRepaired() throws Exception {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    PollableTask pollableTask = pollableTask(42L);
    LocalizedAssetBody input = new LocalizedAssetBody();
    RuntimeException generationFailure = new RuntimeException("generate failed");
    RuntimeException finishFailure = new RuntimeException("finish down");

    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 0, 3600))
        .thenReturn(pollableTask);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInputBytes(42L))
        .thenReturn(objectMapper.writeValueAsBytes(input));
    when(localizedAssetGenerationService.generate(refEq(input))).thenThrow(generationFailure);
    when(pollableTaskService.finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull()))
        .thenThrow(finishFailure)
        .thenReturn(pollableTask);

    AsyncJobQueueSubmissionService queueSubmissionService =
        new AsyncJobQueueSubmissionService(asyncJobStore, asyncJobQueueCoordinator, meterRegistry);
    AssetLocalizeAsyncJobSubmissionService assetSubmissionService =
        new AssetLocalizeAsyncJobSubmissionService(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            queueSubmissionService,
            objectMapper,
            meterRegistry);
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings();
    queueSettings.setMaxAttempts(1);
    AssetLocalizeAsyncJobHandler handler =
        new AssetLocalizeAsyncJobHandler(
            pollableTaskService,
            pollableTaskBlobStorage,
            pollableTaskExceptionUtils,
            localizedAssetGenerationService,
            objectMapper,
            meterRegistry,
            outputStorage);
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
            asyncJobStore,
            queueSettings,
            handler,
            org.mockito.Mockito.mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");

    assetSubmissionService.scheduleJob(
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withParentId(7L)
            .withInput(input)
            .withMessage("message")
            .build());

    runtime.pollOnce();
    waitForStatusCount(asyncJobStore, AsyncJobStatus.FAILED, 1);
    waitForCallbackCounter("assetLocalizeAsyncJob.pollableTask.finish.failed", "failed", 1);
    waitForCallbackCounter("asyncJobQueue.handler.completion.failed", "failed", 1);

    AsyncJobRecord failedJob =
        asyncJobStore.getByIds(List.of(new AsyncJobId("1"))).stream().findFirst().orElseThrow();
    assertThat(failedJob.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failedJob.lastError()).contains("generate failed");
    assertRedactedTaskFailure(failedJob.id());

    when(pollableTaskService.getFreshPollableTask(42L)).thenReturn(pollableTask);
    AssetLocalizeAsyncJobRepairService repairService =
        new AssetLocalizeAsyncJobRepairService(
            asyncJobStore, pollableTaskService, meterRegistry, outputStorage);

    AssetLocalizeAsyncJobRepairService.RepairResult repairResult =
        repairService.repairTerminalPollableTask(failedJob);

    assertThat(repairResult.result()).isEqualTo("repaired");
    assertThat(repairResult.status()).isEqualTo("failed");
    verify(pollableTaskService, times(2))
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
    assertRepairCounter("failed", "repaired", 1);
  }

  private ExceptionHolder assertRedactedTaskFailure(AsyncJobId id) {
    var holder = org.mockito.ArgumentCaptor.forClass(ExceptionHolder.class);
    // FAILED commits before its callback, so observe task completion before inspecting its error.
    verify(pollableTaskService, timeout(2_000))
        .finishTask(eq(42L), isNull(), holder.capture(), isNull());
    org.mockito.Mockito.verifyNoInteractions(pollableTaskExceptionUtils);
    assertThat(holder.getValue().isExpected()).isFalse();
    assertThat(holder.getValue().getException())
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessage("Asset localize async job failed permanently: " + id.value())
        .hasNoCause();
    assertThat(holder.getValue().getException().getSuppressed()).isEmpty();
    return holder.getValue();
  }

  private AsyncJobQueueProperties.QueueSettings queueSettings() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollIntervalMs(1);
    queueSettings.setMaxPollIntervalMs(1_000);
    queueSettings.setClaimBatchSize(1);
    queueSettings.setMaxConcurrency(1);
    queueSettings.setLeaseDurationMs(10_000);
    queueSettings.setHeartbeatIntervalMs(0);
    return queueSettings;
  }

  private ThreadPoolTaskExecutor newExecutor() {
    return newExecutor(0);
  }

  private ThreadPoolTaskExecutor newExecutor(int queueCapacity) {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setCorePoolSize(1);
    threadPoolTaskExecutor.setMaxPoolSize(1);
    threadPoolTaskExecutor.setQueueCapacity(queueCapacity);
    threadPoolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    threadPoolTaskExecutor.initialize();
    return threadPoolTaskExecutor;
  }

  private void waitForStatusCount(
      InMemoryAsyncJobStore asyncJobStore, AsyncJobStatus status, int expectedCount)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
    while (Instant.now().isBefore(deadline)) {
      long count =
          asyncJobStore.countByStatus(AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME).stream()
              .filter(statusCount -> statusCount.status() == status)
              .mapToLong(AsyncJobStatusCount::count)
              .sum();
      if (count == expectedCount) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Timed out waiting for " + expectedCount + " " + status + " jobs");
  }

  private void waitForCallbackCounter(String counterName, String callback, int expectedCount)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
    while (Instant.now().isBefore(deadline)) {
      double count =
          java.util.Optional.ofNullable(
                  meterRegistry
                      .find(counterName)
                      .tag("queueName", AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME)
                      .tag("callback", callback)
                      .counter())
              .map(counter -> counter.count())
              .orElse(0.0);
      if (count == expectedCount) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Timed out waiting for " + expectedCount + " " + counterName + " callback=" + callback);
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

  private PollableTask pollableTask(long id) {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(id);
    return pollableTask;
  }
}
