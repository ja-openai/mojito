package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobHandler;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import com.box.l10n.mojito.service.tm.LocalizedAssetGenerationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
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

  @After
  public void tearDown() {
    executor.shutdown();
    meterRegistry.close();
  }

  @Test
  public void submittedAssetLocalizeJobRunsThroughDurableQueueAndFinishesPollableTask()
      throws Exception {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    PollableTask pollableTask = pollableTask(42L);
    AtomicReference<LocalizedAssetBody> storedInput = new AtomicReference<>();
    AtomicReference<LocalizedAssetBody> storedOutput = new AtomicReference<>();
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();

    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 0, 3600))
        .thenReturn(pollableTask);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInput(42L, LocalizedAssetBody.class))
        .thenAnswer(invocation -> storedInput.get());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              storedInput.set(invocation.getArgument(1));
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
    when(localizedAssetGenerationService.generate(input)).thenReturn(output);

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
            meterRegistry);
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

    AsyncJobRecord completedJob =
        asyncJobStore.getByIds(List.of(new AsyncJobId("1"))).stream().findFirst().orElseThrow();
    AssetLocalizeAsyncJobPayload payload =
        objectMapper.readValueUnchecked(completedJob.jobData(), AssetLocalizeAsyncJobPayload.class);
    assertThat(payload.pollableTaskId()).isEqualTo(42L);
    assertThat(storedInput.get()).isSameAs(input);
    assertThat(storedOutput.get()).isSameAs(output);
    verify(pollableTaskService).finishTask(42L, null, null, null);
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
    when(pollableTaskBlobStorage.getInput(42L, LocalizedAssetBody.class)).thenReturn(input);
    when(localizedAssetGenerationService.generate(input)).thenThrow(failure);

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
            meterRegistry);
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
    verify(pollableTaskExceptionUtils).processException(eq(failure), any(ExceptionHolder.class));
    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
  }

  @Test
  public void pollableDoneCallbackFailureLeavesQueueJobDoneAndRecordsCallbackMetrics()
      throws Exception {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    PollableTask pollableTask = pollableTask(42L);
    AtomicReference<LocalizedAssetBody> storedInput = new AtomicReference<>();
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();
    RuntimeException finishFailure = new RuntimeException("finish down");

    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 0, 3600))
        .thenReturn(pollableTask);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInput(42L, LocalizedAssetBody.class))
        .thenAnswer(invocation -> storedInput.get());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              storedInput.set(invocation.getArgument(1));
              return null;
            })
        .when(pollableTaskBlobStorage)
        .saveInput(eq(42L), any(LocalizedAssetBody.class));
    when(localizedAssetGenerationService.generate(input)).thenReturn(output);
    doThrow(finishFailure).when(pollableTaskService).finishTask(42L, null, null, null);

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
            meterRegistry);
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
    verify(pollableTaskBlobStorage).saveOutput(42L, output);
    verify(pollableTaskExceptionUtils, times(0))
        .processException(any(Throwable.class), any(ExceptionHolder.class));
  }

  @Test
  public void pollableFailedCallbackFailureLeavesQueueJobFailedAndRecordsCallbackMetrics()
      throws Exception {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    PollableTask pollableTask = pollableTask(42L);
    LocalizedAssetBody input = new LocalizedAssetBody();
    RuntimeException generationFailure = new RuntimeException("generate failed");
    RuntimeException finishFailure = new RuntimeException("finish down");

    when(pollableTaskService.createPollableTask(
            7L, GenerateLocalizedAssetJob.class.getCanonicalName(), "message", 0, 3600))
        .thenReturn(pollableTask);
    when(pollableTaskService.getPollableTask(42L)).thenReturn(pollableTask);
    when(pollableTaskBlobStorage.getInput(42L, LocalizedAssetBody.class)).thenReturn(input);
    when(localizedAssetGenerationService.generate(input)).thenThrow(generationFailure);
    doThrow(finishFailure)
        .when(pollableTaskService)
        .finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());

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
            meterRegistry);
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
    verify(pollableTaskExceptionUtils)
        .processException(eq(generationFailure), any(ExceptionHolder.class));
    verify(pollableTaskService).finishTask(eq(42L), isNull(), any(ExceptionHolder.class), isNull());
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
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setCorePoolSize(1);
    threadPoolTaskExecutor.setMaxPoolSize(1);
    threadPoolTaskExecutor.setQueueCapacity(0);
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

  private PollableTask pollableTask(long id) {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(id);
    return pollableTask;
  }
}
