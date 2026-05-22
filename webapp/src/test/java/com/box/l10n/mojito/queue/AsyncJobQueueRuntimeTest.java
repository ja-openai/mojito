package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;
import org.junit.After;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsyncJobQueueRuntimeTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ThreadPoolTaskExecutor executor = newExecutor(4);

  @After
  public void tearDown() {
    executor.shutdown();
    meterRegistry.close();
  }

  @Test
  public void emptyPollsBackOffUntilMaxPollInterval() {
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            new InMemoryAsyncJobStore(),
            queueSettings(100, 400, 5, 2, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            executor);

    AsyncJobQueueRuntime.PollCycleResult first = asyncJobQueueRuntime.pollOnce();
    AsyncJobQueueRuntime.PollCycleResult second = asyncJobQueueRuntime.pollOnce();
    AsyncJobQueueRuntime.PollCycleResult third = asyncJobQueueRuntime.pollOnce();

    assertThat(first.nextDelayMs()).isEqualTo(200);
    assertThat(second.nextDelayMs()).isEqualTo(400);
    assertThat(third.nextDelayMs()).isEqualTo(400);
  }

  @Test
  public void pollSkipsStoreWhenLocalExecutorIsSaturated() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":2}", Instant.now().minusSeconds(1));

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings(100, 1000, 10, 1, 10_000, 0),
            handler(
                asyncJobRecord -> {
                  started.countDown();
                  release.await(5, TimeUnit.SECONDS);
                  return AsyncJobHandlerResult.done();
                }),
            mock(TaskScheduler.class),
            executor);

    AsyncJobQueueRuntime.PollCycleResult first = asyncJobQueueRuntime.pollOnce();
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    AsyncJobQueueRuntime.PollCycleResult second = asyncJobQueueRuntime.pollOnce();

    assertThat(first.claimedCount()).isEqualTo(1);
    assertThat(second.skippedSaturated()).isTrue();
    assertThat(inMemoryAsyncJobStore.countByStatus("assetlocalize"))
        .containsExactlyInAnyOrder(
            new AsyncJobStatusCount(AsyncJobStatus.QUEUED, 1),
            new AsyncJobStatusCount(AsyncJobStatus.RUNNING, 1));

    release.countDown();
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.DONE, 1);
  }

  @Test
  public void pollRequestsImmediateRepollWhenClaimBatchFillsAndCapacityRemains() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":2}", Instant.now().minusSeconds(1));
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":3}", Instant.now().minusSeconds(1));

    AtomicInteger processed = new AtomicInteger();

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings(100, 1_000, 1, 3, 10_000, 0),
            handler(
                asyncJobRecord -> {
                  processed.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            mock(TaskScheduler.class),
            executor);

    AsyncJobQueueRuntime.PollCycleResult pollCycleResult = asyncJobQueueRuntime.pollOnce();
    waitForAtomicValue(processed, 1);

    assertThat(pollCycleResult.claimedCount()).isEqualTo(1);
    assertThat(pollCycleResult.continueImmediately()).isTrue();
    assertThat(pollCycleResult.nextDelayMs()).isZero();
    assertThat(processed.get()).isEqualTo(1);
  }

  @Test
  public void pollRecordsClaimQueueWaitAndProcessingLatency() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    CountDownLatch processed = new CountDownLatch(1);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(
                asyncJobRecord -> {
                  processed.countDown();
                  return AsyncJobHandlerResult.done();
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();
    assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.DONE, 1);

    assertThat(
            meterRegistry
                .get("asyncJobQueue.claim.latency")
                .tag("queueName", "assetlocalize")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.queueWait.latency")
                .tag("queueName", "assetlocalize")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.processing.latency")
                .tag("queueName", "assetlocalize")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void transitionFailureCounterRecordsFailedDoneTransition() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenReturn(false);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("done", 1);
  }

  @Test
  public void transitionFailureCounterRecordsFailedHandlerRequestedRequeue() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeue(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Instant.class),
            any(),
            any()))
        .thenReturn(false);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.requeue(Instant.now())),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("requeue", 1);
  }

  @Test
  public void transitionFailureCounterRecordsFailedTerminalFailureTransition() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.markFailed(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any(), anyString()))
        .thenReturn(false);

    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setMaxAttempts(1);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  throw new IllegalStateException("poison");
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("failed", 1);
  }

  @Test
  public void transitionFailureCounterRecordsFailedRetryRequeue() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeue(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Instant.class),
            any(),
            anyString()))
        .thenReturn(false);

    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setMaxAttempts(2);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  throw new IllegalStateException("temporary");
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("retry", 1);
  }

  @Test
  public void transitionFailureCounterRecordsFailedExecutorRejectionRequeue() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeue(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Instant.class),
            any(),
            anyString()))
        .thenReturn(false);

    ThreadPoolTaskExecutor rejectingExecutor = newExecutor(1);
    rejectingExecutor.shutdown();
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            rejectingExecutor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("executorRejectedRequeue", 1);
  }

  @Test
  public void heartbeatRenewsLeaseWhileJobIsRunning() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));

    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    DummyScheduledFuture dummyScheduledFuture = new DummyScheduledFuture();
    AtomicInteger heartbeatInvocations = new AtomicInteger();
    Runnable[] heartbeatRunnable = new Runnable[1];
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(
            invocation -> {
              heartbeatRunnable[0] = invocation.getArgument(0);
              return dummyScheduledFuture;
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings(100, 1_000, 1, 1, 200, 25),
            handler(
                asyncJobRecord -> {
                  started.countDown();
                  release.await(5, TimeUnit.SECONDS);
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.pollOnce();
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    Instant initialLeaseUntil =
        inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0).leaseUntil();
    assertThat(heartbeatRunnable[0]).isNotNull();

    Thread.sleep(30);
    heartbeatRunnable[0].run();
    heartbeatInvocations.incrementAndGet();

    Instant renewedLeaseUntil =
        inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0).leaseUntil();
    assertThat(renewedLeaseUntil).isAfter(initialLeaseUntil);
    verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong());
    assertThat(heartbeatInvocations.get()).isEqualTo(1);

    release.countDown();
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.DONE, 1);
  }

  @Test
  public void handlerFailuresStopRetryingAfterMaxAttempts() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    AtomicInteger attempts = new AtomicInteger();
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 10_000, 0);
    queueSettings.setMaxAttempts(2);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  attempts.incrementAndGet();
                  throw new IllegalStateException("poison");
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();
    waitForAtomicValue(attempts, 1);
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.QUEUED, 1);

    Thread.sleep(10);
    asyncJobQueueRuntime.pollOnce();
    waitForAtomicValue(attempts, 2);
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.FAILED, 1);

    AsyncJobRecord failedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(failedJob.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failedJob.attemptCount()).isEqualTo(2);
    assertThat(failedJob.lastError()).contains("poison");
    assertThat(failedJob.workerId()).isNull();
    assertThat(failedJob.leaseToken()).isNull();
    assertThat(failedJob.leaseUntil()).isNull();
  }

  @Test
  public void cancelledScheduledPollBecomesNoOpIfItStillRuns() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of());

    RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.start();
    Runnable firstPoll = taskScheduler.scheduledTasks().get(0);

    asyncJobQueueRuntime.triggerPollNow();
    Runnable secondPoll = taskScheduler.scheduledTasks().get(1);

    firstPoll.run();
    secondPoll.run();

    verify(asyncJobStore, times(1))
        .claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class));
  }

  @Test
  public void scheduledBackoffPollsApplyConfiguredJitter() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of());

    RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
    AtomicInteger jitterInputMs = new AtomicInteger();
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor,
            delayMs -> {
              jitterInputMs.set((int) delayMs);
              return delayMs + 37;
            });

    asyncJobQueueRuntime.start();
    taskScheduler.scheduledTasks().get(0).run();

    assertThat(jitterInputMs.get()).isEqualTo(200);
    assertThat(taskScheduler.scheduledStartTimes()).hasSize(2);
    long scheduledDelayMs =
        taskScheduler.scheduledStartTimes().get(1).getTime()
            - taskScheduler.scheduleInvocationTimes().get(1);
    assertThat(scheduledDelayMs).isBetween(200L, 260L);
  }

  @Test
  public void heartbeatIntervalMustBeLessThanLeaseDuration() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollIntervalMs(100);
    queueSettings.setMaxPollIntervalMs(1_000);
    queueSettings.setClaimBatchSize(1);
    queueSettings.setMaxConcurrency(1);
    queueSettings.setLeaseDurationMs(100);
    queueSettings.setHeartbeatIntervalMs(100);

    org.junit.Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime(
                new InMemoryAsyncJobStore(),
                queueSettings,
                handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
                mock(TaskScheduler.class),
                executor));
  }

  @Test
  public void maxAttemptsMustBePositive() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setMaxAttempts(0);

    org.junit.Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime(
                new InMemoryAsyncJobStore(),
                queueSettings,
                handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
                mock(TaskScheduler.class),
                executor));
  }

  @Test
  public void pollJitterPercentMustBeBetweenZeroAndOneHundred() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollJitterPercent(101);

    org.junit.Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime(
                new InMemoryAsyncJobStore(),
                queueSettings,
                handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
                mock(TaskScheduler.class),
                executor));
  }

  private AsyncJobQueueRuntime runtime(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties.QueueSettings queueSettings,
      AsyncJobHandler asyncJobHandler,
      TaskScheduler taskScheduler,
      ThreadPoolTaskExecutor threadPoolTaskExecutor) {
    return runtime(
        asyncJobStore, queueSettings, asyncJobHandler, taskScheduler, threadPoolTaskExecutor, null);
  }

  private AsyncJobQueueRuntime runtime(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties.QueueSettings queueSettings,
      AsyncJobHandler asyncJobHandler,
      TaskScheduler taskScheduler,
      ThreadPoolTaskExecutor threadPoolTaskExecutor,
      LongUnaryOperator pollDelayJitter) {
    return new AsyncJobQueueRuntime(
        "assetlocalize",
        asyncJobStore,
        queueSettings,
        asyncJobHandler,
        taskScheduler,
        threadPoolTaskExecutor,
        meterRegistry,
        "worker-a",
        pollDelayJitter);
  }

  private AsyncJobHandler handler(ThrowingProcessor processor) {
    return new AsyncJobHandler() {
      @Override
      public String queueName() {
        return "assetlocalize";
      }

      @Override
      public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception {
        return processor.process(asyncJobRecord);
      }
    };
  }

  private AsyncJobQueueProperties.QueueSettings queueSettings(
      long pollIntervalMs,
      long maxPollIntervalMs,
      int claimBatchSize,
      int maxConcurrency,
      long leaseDurationMs,
      long heartbeatIntervalMs) {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollIntervalMs(pollIntervalMs);
    queueSettings.setMaxPollIntervalMs(maxPollIntervalMs);
    queueSettings.setClaimBatchSize(claimBatchSize);
    queueSettings.setMaxConcurrency(maxConcurrency);
    queueSettings.setLeaseDurationMs(leaseDurationMs);
    queueSettings.setHeartbeatIntervalMs(heartbeatIntervalMs);
    return queueSettings;
  }

  private ThreadPoolTaskExecutor newExecutor(int concurrency) {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setCorePoolSize(concurrency);
    threadPoolTaskExecutor.setMaxPoolSize(concurrency);
    threadPoolTaskExecutor.setQueueCapacity(0);
    threadPoolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    threadPoolTaskExecutor.initialize();
    return threadPoolTaskExecutor;
  }

  private AsyncJobRecord claimedJob(int attemptCount) {
    Instant now = Instant.now();
    return new AsyncJobRecord(
        new AsyncJobId("1"),
        "assetlocalize",
        AsyncJobStatus.RUNNING,
        now.minusSeconds(5),
        now.plusSeconds(30),
        "worker-a",
        "lease-token",
        "{}",
        attemptCount,
        null,
        now.minusSeconds(10),
        now);
  }

  private void waitForStatusCount(
      InMemoryAsyncJobStore inMemoryAsyncJobStore,
      String queueName,
      AsyncJobStatus asyncJobStatus,
      long expectedCount)
      throws InterruptedException {
    long timeoutAt = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < timeoutAt) {
      long actualCount =
          inMemoryAsyncJobStore.countByStatus(queueName).stream()
              .filter(statusCount -> statusCount.status() == asyncJobStatus)
              .mapToLong(AsyncJobStatusCount::count)
              .sum();
      if (actualCount == expectedCount) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Timed out waiting for status " + asyncJobStatus + " count " + expectedCount);
  }

  private void waitForTransitionFailure(String transition, double expectedCount)
      throws InterruptedException {
    long timeoutAt = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < timeoutAt) {
      io.micrometer.core.instrument.Counter counter =
          meterRegistry
              .find("asyncJobQueue.transition.failed")
              .tag("queueName", "assetlocalize")
              .tag("transition", transition)
              .counter();
      if (counter != null && counter.count() == expectedCount) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Timed out waiting for transition failure " + transition + " count " + expectedCount);
  }

  private void waitForAtomicValue(AtomicInteger atomicInteger, int expectedValue)
      throws InterruptedException {
    long timeoutAt = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < timeoutAt) {
      if (atomicInteger.get() == expectedValue) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out waiting for value " + expectedValue);
  }

  @FunctionalInterface
  private interface ThrowingProcessor {
    AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception;
  }

  private static class DummyScheduledFuture implements ScheduledFuture<Object> {

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return true;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return false;
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return null;
    }
  }

  private static class RecordingTaskScheduler implements TaskScheduler {

    private final List<Runnable> scheduledTasks = new ArrayList<>();
    private final List<Date> scheduledStartTimes = new ArrayList<>();
    private final List<Long> scheduleInvocationTimes = new ArrayList<>();

    List<Runnable> scheduledTasks() {
      return scheduledTasks;
    }

    List<Date> scheduledStartTimes() {
      return scheduledStartTimes;
    }

    List<Long> scheduleInvocationTimes() {
      return scheduleInvocationTimes;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Date startTime) {
      scheduleInvocationTimes.add(System.currentTimeMillis());
      scheduledTasks.add(task);
      scheduledStartTimes.add(startTime);
      return new DummyScheduledFuture();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      scheduledTasks.add(task);
      return new DummyScheduledFuture();
    }

    @Override
    public ScheduledFuture<?> schedule(
        Runnable task, org.springframework.scheduling.Trigger trigger) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Date startTime, long period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable task, Instant startTime, Duration period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Date startTime, long delay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable task, Instant startTime, Duration delay) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
      throw new UnsupportedOperationException();
    }
  }
}
