package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
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
  public void emptyPollBackoffCapsAtMaxPollInterval() {
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            new InMemoryAsyncJobStore(),
            queueSettings(
                AsyncJobQueueValidation.MAX_POLL_INTERVAL_MS_MAX / 2 + 1,
                AsyncJobQueueValidation.MAX_POLL_INTERVAL_MS_MAX,
                1,
                1,
                10_000,
                0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            executor);

    AsyncJobQueueRuntime.PollCycleResult pollCycleResult = asyncJobQueueRuntime.pollOnce();

    assertThat(pollCycleResult.nextDelayMs())
        .isEqualTo(AsyncJobQueueValidation.MAX_POLL_INTERVAL_MS_MAX);
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
  public void queueWaitLatencyUsesAvailabilityNotCreationTime() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    Instant claimedAt = Instant.EPOCH.plusSeconds(60);
    AsyncJobRecord claimedJob =
        new AsyncJobRecord(
            new AsyncJobId("1"),
            "assetlocalize",
            AsyncJobStatus.RUNNING,
            claimedAt.minusMillis(200),
            claimedAt.plusSeconds(30),
            "worker-a",
            "lease-token",
            "{\"id\":1}",
            1,
            null,
            Instant.EPOCH,
            claimedAt,
            false);
    when(asyncJobStore.claimNextJobs(
            anyString(), anyInt(), anyString(), any(java.time.Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenReturn(true);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.queueWait.latency")
                .tag("queueName", "assetlocalize")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS))
        .isBetween(199.0, 201.0);
  }

  @Test
  public void runtimePublishesExecutorLoadGauges() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(
                asyncJobRecord -> {
                  started.countDown();
                  release.await(5, TimeUnit.SECONDS);
                  return AsyncJobHandlerResult.done();
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    waitForGaugeValue("asyncJobQueue.executor.active", 1);
    assertGaugeValue("asyncJobQueue.executor.queued", 0);
    assertGaugeValue("asyncJobQueue.inflight", 1);

    release.countDown();
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.DONE, 1);
    waitForGaugeValue("asyncJobQueue.executor.active", 0);
    assertGaugeValue("asyncJobQueue.executor.queued", 0);
    assertGaugeValue("asyncJobQueue.inflight", 0);
  }

  @Test
  public void pollRecordsLeaseExpiredReclaims() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    CountDownLatch processed = new CountDownLatch(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob(2, true)));
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              processed.countDown();
              return true;
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.leaseExpiredReclaimed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void expiredLeaseFencesStaleDoneAndAllowsReclaim() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch firstAttemptStarted = new CountDownLatch(1);
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 25, 0);
    queueSettings.setMaxAttempts(3);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  int attempt = attempts.incrementAndGet();
                  if (attempt == 1) {
                    firstAttemptStarted.countDown();
                    Thread.sleep(100);
                    return AsyncJobHandlerResult.done("{\"stale\":true}");
                  }
                  return AsyncJobHandlerResult.done("{\"reclaimed\":true}");
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(firstAttemptStarted.await(2, TimeUnit.SECONDS)).isTrue();
    waitForTransitionFailure("done", 1);
    AsyncJobRecord expiredRunningJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(expiredRunningJob.status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(expiredRunningJob.attemptCount()).isEqualTo(1);
    assertThat(expiredRunningJob.jobData()).isEqualTo("{\"id\":1}");
    assertThat(expiredRunningJob.leaseUntil()).isBeforeOrEqualTo(Instant.now());
    assertThat(
            meterRegistry
                .find("asyncJobQueue.completed")
                .tag("queueName", "assetlocalize")
                .counter())
        .isNull();

    asyncJobQueueRuntime.pollOnce();

    waitForAtomicValue(attempts, 2);
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.DONE, 1);
    AsyncJobRecord completedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(completedJob.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(completedJob.attemptCount()).isEqualTo(2);
    assertThat(completedJob.jobData()).isEqualTo("{\"reclaimed\":true}");
    assertThat(completedJob.workerId()).isNull();
    assertThat(completedJob.leaseToken()).isNull();
    assertThat(completedJob.leaseUntil()).isNull();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.leaseExpiredReclaimed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.completed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void expiredLeaseReclaimsStopBeforeHandlerAfterMaxAttempts() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    AtomicInteger handlerInvocations = new AtomicInteger();
    CountDownLatch firstAttemptStarted = new CountDownLatch(1);
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 25, 0);
    queueSettings.setMaxAttempts(1);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  firstAttemptStarted.countDown();
                  Thread.sleep(100);
                  return AsyncJobHandlerResult.done("{\"stale\":true}");
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(firstAttemptStarted.await(2, TimeUnit.SECONDS)).isTrue();
    waitForTransitionFailure("done", 1);

    asyncJobQueueRuntime.pollOnce();

    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.FAILED, 1);
    AsyncJobRecord failedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(handlerInvocations.get()).isEqualTo(1);
    assertThat(failedJob.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failedJob.attemptCount()).isEqualTo(2);
    assertThat(failedJob.lastError()).contains("Attempt budget exhausted");
    assertThat(failedJob.workerId()).isNull();
    assertThat(failedJob.leaseToken()).isNull();
    assertThat(failedJob.leaseUntil()).isNull();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.attempt.exhausted")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.leaseExpiredReclaimed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void attemptBudgetExhaustionRecordsTransitionFailureWhenMarkFailedIsFenced()
      throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AtomicInteger handlerInvocations = new AtomicInteger();
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob(2, true)));
    when(asyncJobStore.markFailed(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any(), anyString()))
        .thenReturn(false);

    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 10_000, 0);
    queueSettings.setMaxAttempts(1);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("attemptBudgetExhausted", 1);
    assertThat(handlerInvocations.get()).isZero();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.attempt.exhausted")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.find("asyncJobQueue.failed").tag("queueName", "assetlocalize").counter())
        .isNull();
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
  public void transitionFailureCounterRecordsThrownDoneTransitionWithoutHandlerFailure()
      throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch doneAttempted = new CountDownLatch(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              doneAttempted.countDown();
              throw new IllegalStateException("database unavailable");
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(doneAttempted.await(2, TimeUnit.SECONDS)).isTrue();
    waitForTransitionFailure("done", 1);
    assertThat(
            meterRegistry
                .find("asyncJobQueue.handler.failed")
                .tag("queueName", "assetlocalize")
                .counter())
        .isNull();
    verify(asyncJobStore, times(0))
        .requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString());
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
  public void handlerRequestedRequeueWithoutTimestampUsesRelativeStoreDelay() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch requeued = new CountDownLatch(1);
    Duration[] observedDelay = new Duration[1];
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            any()))
        .thenAnswer(
            invocation -> {
              observedDelay[0] = invocation.getArgument(4);
              requeued.countDown();
              return true;
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettingsWithoutRetryJitter(125, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.requeue(null)),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(requeued.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(observedDelay[0]).isEqualTo(Duration.ofMillis(125));
  }

  @Test
  public void handlerRequestedRequeueWithoutTimestampAppliesRetryJitter() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch requeued = new CountDownLatch(1);
    Duration[] observedDelay = new Duration[1];
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            any()))
        .thenAnswer(
            invocation -> {
              observedDelay[0] = invocation.getArgument(4);
              requeued.countDown();
              return true;
            });

    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setRetryJitterPercent(50);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(asyncJobRecord -> AsyncJobHandlerResult.requeue(null)),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(requeued.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(observedDelay[0]).isBetween(Duration.ofMillis(50), Duration.ofMillis(150));
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
  public void transitionFailureCounterRecordsFailedRequeueExhaustedTransition() throws Exception {
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
            handler(asyncJobRecord -> AsyncJobHandlerResult.requeue(null)),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("requeueExhausted", 1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.requeue.exhausted")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.find("asyncJobQueue.failed").tag("queueName", "assetlocalize").counter())
        .isNull();
    verify(asyncJobStore, times(0))
        .requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString());
  }

  @Test
  public void transitionFailureCounterRecordsFailedRetryRequeue() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
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
  public void transitionFailureCounterRecordsThrownRetryRequeue() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch requeueAttempted = new CountDownLatch(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString()))
        .thenAnswer(
            invocation -> {
              requeueAttempted.countDown();
              throw new IllegalStateException("database unavailable");
            });

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

    assertThat(requeueAttempted.await(2, TimeUnit.SECONDS)).isTrue();
    waitForTransitionFailure("retry", 1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.handler.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void transitionFailureCounterRecordsFailedExecutorRejectionRequeue() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
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
    assertThat(
            meterRegistry
                .get("asyncJobQueue.executor.rejected")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void executorRejectionRequeuesAndRecordsRetryMetric() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    ThreadPoolTaskExecutor rejectingExecutor = newExecutor(1);
    rejectingExecutor.shutdown();

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            rejectingExecutor);

    asyncJobQueueRuntime.pollOnce();

    AsyncJobRecord requeuedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(requeuedJob.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(requeuedJob.attemptCount()).isEqualTo(1);
    assertThat(requeuedJob.lastError()).contains("TaskRejectedException");
    assertThat(
            meterRegistry
                .get("asyncJobQueue.executor.rejected")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.retried")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void executorSubmitRuntimeExceptionRequeuesAndReleasesCapacity() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch requeued = new CountDownLatch(1);
    AtomicInteger handlerInvocations = new AtomicInteger();

    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString()))
        .thenAnswer(
            invocation -> {
              requeued.countDown();
              return true;
            });

    ThreadPoolTaskExecutor failingExecutor =
        newFailingSubmitExecutor(new IllegalStateException("executor unavailable"));
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            mock(TaskScheduler.class),
            failingExecutor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(requeued.await(2, TimeUnit.SECONDS)).isTrue();
    waitForInFlightCount(asyncJobQueueRuntime, 0);
    assertThat(handlerInvocations.get()).isZero();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.executor.submit.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.retried")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    verify(asyncJobStore)
        .requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            contains("executor unavailable"));
    failingExecutor.shutdown();
  }

  @Test
  public void executorRejectionsStopRetryingAfterMaxAttempts() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 10_000, 0);
    queueSettings.setMaxAttempts(1);
    ThreadPoolTaskExecutor rejectingExecutor = newExecutor(1);
    rejectingExecutor.shutdown();
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings,
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            rejectingExecutor);

    asyncJobQueueRuntime.pollOnce();

    AsyncJobRecord failedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(failedJob.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failedJob.attemptCount()).isEqualTo(1);
    assertThat(failedJob.lastError()).contains("TaskRejectedException");
    assertThat(failedJob.workerId()).isNull();
    assertThat(failedJob.leaseToken()).isNull();
    assertThat(failedJob.leaseUntil()).isNull();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.executor.rejected")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
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
  public void heartbeatFailureRecordsCounterWithoutInterruptingJob() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    Runnable[] heartbeatRunnable = new Runnable[1];
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(
            invocation -> {
              heartbeatRunnable[0] = invocation.getArgument(0);
              return new DummyScheduledFuture();
            });
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.heartbeat(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any(Duration.class)))
        .thenReturn(false);
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              completed.countDown();
              return true;
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
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
    assertThat(heartbeatRunnable[0]).isNotNull();

    heartbeatRunnable[0].run();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.heartbeat.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);

    release.countDown();
    assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void heartbeatExceptionRecordsCounterWithoutInterruptingJob() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    Runnable[] heartbeatRunnable = new Runnable[1];
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(
            invocation -> {
              heartbeatRunnable[0] = invocation.getArgument(0);
              return new DummyScheduledFuture();
            });
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.heartbeat(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              completed.countDown();
              return true;
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
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
    assertThat(heartbeatRunnable[0]).isNotNull();

    heartbeatRunnable[0].run();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.heartbeat.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);

    release.countDown();
    assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  public void heartbeatScheduleFailureRequeuesWithoutProcessing() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch requeued = new CountDownLatch(1);
    Duration[] observedDelay = new Duration[1];
    AtomicInteger handlerInvocations = new AtomicInteger();

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenThrow(new IllegalStateException("scheduler unavailable"));
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString()))
        .thenAnswer(
            invocation -> {
              observedDelay[0] = invocation.getArgument(4);
              requeued.countDown();
              return true;
            });
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(100, 1_000, 1, 1, 200, 25);
    queueSettings.setRetryJitterPercent(0);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(requeued.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(observedDelay[0]).isEqualTo(Duration.ofMillis(100));
    waitForInFlightCount(asyncJobQueueRuntime, 0);
    assertThat(handlerInvocations.get()).isZero();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.heartbeat.schedule.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.retried")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void heartbeatScheduleNullFutureRequeuesWithoutProcessing() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch requeued = new CountDownLatch(1);
    AtomicInteger handlerInvocations = new AtomicInteger();

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenReturn(null);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString()))
        .thenAnswer(
            invocation -> {
              requeued.countDown();
              return true;
            });
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(100, 1_000, 1, 1, 200, 25);
    queueSettings.setRetryJitterPercent(0);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.pollOnce();

    assertThat(requeued.await(2, TimeUnit.SECONDS)).isTrue();
    waitForInFlightCount(asyncJobQueueRuntime, 0);
    assertThat(handlerInvocations.get()).isZero();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.heartbeat.schedule.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    verify(asyncJobStore)
        .requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            eq(Duration.ofMillis(100)),
            any(),
            contains("null heartbeat ScheduledFuture"));
  }

  @Test
  public void transitionFailureCounterRecordsFailedHeartbeatScheduleRequeue() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    AtomicInteger handlerInvocations = new AtomicInteger();

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenThrow(new IllegalStateException("scheduler unavailable"));
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString()))
        .thenReturn(false);

    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(100, 1_000, 1, 1, 200, 25);
    queueSettings.setRetryJitterPercent(0);
    queueSettings.setMaxAttempts(2);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("heartbeatScheduleRequeue", 1);
    waitForInFlightCount(asyncJobQueueRuntime, 0);
    assertThat(handlerInvocations.get()).isZero();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.heartbeat.schedule.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.find("asyncJobQueue.retried").tag("queueName", "assetlocalize").counter())
        .isNull();
  }

  @Test
  public void heartbeatScheduleFailuresStopRetryingAfterMaxAttempts() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    AtomicInteger handlerInvocations = new AtomicInteger();
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 200, 25);
    queueSettings.setMaxAttempts(1);

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenThrow(new IllegalStateException("scheduler unavailable"));
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.pollOnce();
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.FAILED, 1);

    AsyncJobRecord failedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(failedJob.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failedJob.attemptCount()).isEqualTo(1);
    assertThat(failedJob.lastError()).contains("scheduler unavailable");
    assertThat(failedJob.workerId()).isNull();
    assertThat(failedJob.leaseToken()).isNull();
    assertThat(failedJob.leaseUntil()).isNull();
    waitForInFlightCount(asyncJobQueueRuntime, 0);
    assertThat(handlerInvocations.get()).isZero();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.heartbeat.schedule.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void transitionFailureCounterRecordsFailedHeartbeatScheduleTerminalFailure()
      throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    AtomicInteger handlerInvocations = new AtomicInteger();

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenThrow(new IllegalStateException("scheduler unavailable"));
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.markFailed(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any(), anyString()))
        .thenReturn(false);

    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(100, 1_000, 1, 1, 200, 25);
    queueSettings.setMaxAttempts(1);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerInvocations.incrementAndGet();
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.pollOnce();

    waitForTransitionFailure("heartbeatScheduleFailed", 1);
    waitForInFlightCount(asyncJobQueueRuntime, 0);
    assertThat(handlerInvocations.get()).isZero();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.heartbeat.schedule.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry.find("asyncJobQueue.failed").tag("queueName", "assetlocalize").counter())
        .isNull();
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
  public void handlerRequestedRequeuesStopAfterMaxAttempts() throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"step\":0}", Instant.now().minusSeconds(1));
    AtomicInteger attempts = new AtomicInteger();
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 10_000, 0);
    queueSettings.setMaxAttempts(2);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord ->
                    AsyncJobHandlerResult.requeue(
                        null, "{\"step\":" + attempts.incrementAndGet() + "}")),
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
    assertThat(failedJob.jobData()).isEqualTo("{\"step\":2}");
    assertThat(failedJob.lastError()).contains("Handler requested requeue");
    assertThat(failedJob.workerId()).isNull();
    assertThat(failedJob.leaseToken()).isNull();
    assertThat(failedJob.leaseUntil()).isNull();
    assertThat(
            meterRegistry
                .get("asyncJobQueue.requeue.exhausted")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void handlerFailurePersistsBoundedErrorSummaryOnRetryAndTerminalFailure()
      throws Exception {
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    AsyncJobId asyncJobId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    AtomicInteger attempts = new AtomicInteger();
    String longMessage = "large-error-" + "x".repeat(4_100);
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings(1, 1_000, 1, 1, 10_000, 0);
    queueSettings.setMaxAttempts(2);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            inMemoryAsyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  attempts.incrementAndGet();
                  throw new IllegalStateException(longMessage);
                }),
            mock(TaskScheduler.class),
            executor);

    asyncJobQueueRuntime.pollOnce();
    waitForAtomicValue(attempts, 1);
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.QUEUED, 1);

    AsyncJobRecord retriedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(retriedJob.lastError()).hasSize(4_000);
    assertThat(retriedJob.lastError()).startsWith("java.lang.IllegalStateException: large-error-");

    Thread.sleep(10);
    asyncJobQueueRuntime.pollOnce();
    waitForAtomicValue(attempts, 2);
    waitForStatusCount(inMemoryAsyncJobStore, "assetlocalize", AsyncJobStatus.FAILED, 1);

    AsyncJobRecord failedJob = inMemoryAsyncJobStore.getByIds(List.of(asyncJobId)).get(0);
    assertThat(failedJob.lastError()).hasSize(4_000);
    assertThat(failedJob.lastError()).startsWith("java.lang.IllegalStateException: large-error-");
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
  public void immediateScheduledPollCanRunBeforeScheduleReturns() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of());
    InlineImmediateTaskScheduler taskScheduler = new InlineImmediateTaskScheduler();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setPollJitterPercent(0);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.start();

    assertThat(taskScheduler.scheduleInvocations()).isEqualTo(2);
    verify(asyncJobStore, times(1))
        .claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class));
  }

  @Test
  public void triggerPollNowRecordsFailureWhenImmediateScheduleFails() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(invocation -> new DummyScheduledFuture())
        .thenThrow(new IllegalStateException("scheduler unavailable"));

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            mock(AsyncJobStore.class),
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.start();
    asyncJobQueueRuntime.triggerPollNow();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.trigger.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void triggerPollNowPreservesExistingPollWhenImmediateScheduleFails() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of());

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    Runnable[] firstScheduledPoll = new Runnable[1];
    DummyScheduledFuture firstScheduledFuture = new DummyScheduledFuture();
    AtomicInteger scheduleInvocations = new AtomicInteger();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              int invocationCount = scheduleInvocations.incrementAndGet();
              if (invocationCount == 1) {
                firstScheduledPoll[0] = invocation.getArgument(0);
                return firstScheduledFuture;
              }
              if (invocationCount == 2) {
                throw new IllegalStateException("scheduler unavailable");
              }
              return new DummyScheduledFuture();
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor,
            delayMs -> delayMs);

    asyncJobQueueRuntime.start();
    asyncJobQueueRuntime.triggerPollNow();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.trigger.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(firstScheduledFuture.isCancelled()).isFalse();

    firstScheduledPoll[0].run();

    verify(asyncJobStore, times(1))
        .claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class));
    assertThat(scheduleInvocations.get()).isEqualTo(3);
  }

  @Test
  public void startRecordsFailureAndClearsStartedWhenInitialScheduleReturnsNull() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class))).thenReturn(null);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            mock(AsyncJobStore.class),
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor);

    IllegalStateException exception =
        org.junit.Assert.assertThrows(IllegalStateException.class, asyncJobQueueRuntime::start);

    assertThat(exception).hasMessageContaining("TaskScheduler returned null ScheduledFuture");
    assertThat(
            meterRegistry
                .get("asyncJobQueue.poll.schedule.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);

    asyncJobQueueRuntime.triggerPollNow();

    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void processingCompletionTriggerRecordsFailureWhenImmediateScheduleFails()
      throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobRecord claimedJob = claimedJob(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(1);

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    Runnable[] scheduledPoll = new Runnable[1];
    AtomicInteger scheduleInvocations = new AtomicInteger();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              int invocationCount = scheduleInvocations.incrementAndGet();
              if (invocationCount == 1) {
                scheduledPoll[0] = invocation.getArgument(0);
                return new DummyScheduledFuture();
              }
              if (invocationCount == 2) {
                return new DummyScheduledFuture();
              }
              throw new IllegalStateException("scheduler unavailable");
            });
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of(claimedJob));
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              completed.countDown();
              return true;
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(
                asyncJobRecord -> {
                  started.countDown();
                  release.await(5, TimeUnit.SECONDS);
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            executor);

    asyncJobQueueRuntime.start();
    assertThat(scheduledPoll[0]).isNotNull();
    scheduledPoll[0].run();
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(scheduleInvocations.get()).isEqualTo(2);

    release.countDown();

    assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
    waitForTriggerFailure(1);
  }

  @Test
  public void stopWaitsForActivePollBeforeShuttingDownExecutor() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    CountDownLatch claimStarted = new CountDownLatch(1);
    CountDownLatch allowClaimReturn = new CountDownLatch(1);
    CountDownLatch handlerStarted = new CountDownLatch(1);
    CountDownLatch doneMarked = new CountDownLatch(1);

    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              claimStarted.countDown();
              assertThat(allowClaimReturn.await(2, TimeUnit.SECONDS)).isTrue();
              return List.of(claimedJob(1));
            });
    when(asyncJobStore.markDone(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              doneMarked.countDown();
              return true;
            });

    RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setShutdownAwaitTerminationMs(1_000);
    ThreadPoolTaskExecutor gracefulExecutor = newGracefulExecutor(1, 1_000);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(
                asyncJobRecord -> {
                  handlerStarted.countDown();
                  return AsyncJobHandlerResult.done();
                }),
            taskScheduler,
            gracefulExecutor);

    try {
      asyncJobQueueRuntime.start();
      Thread pollThread =
          new Thread(taskScheduler.scheduledTasks().get(0), "async-queue-poll-test");
      pollThread.start();
      assertThat(claimStarted.await(2, TimeUnit.SECONDS)).isTrue();

      CountDownLatch stopStarted = new CountDownLatch(1);
      Thread stopThread =
          new Thread(
              () -> {
                stopStarted.countDown();
                asyncJobQueueRuntime.stop();
              },
              "async-queue-stop-test");
      stopThread.start();
      assertThat(stopStarted.await(1, TimeUnit.SECONDS)).isTrue();
      stopThread.join(100);
      assertThat(stopThread.isAlive()).isTrue();

      allowClaimReturn.countDown();

      assertThat(handlerStarted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(doneMarked.await(2, TimeUnit.SECONDS)).isTrue();
      pollThread.join(2_000);
      stopThread.join(2_000);
      assertThat(pollThread.isAlive()).isFalse();
      assertThat(stopThread.isAlive()).isFalse();
    } finally {
      gracefulExecutor.shutdown();
    }

    verify(asyncJobStore, times(0))
        .requeueAfter(
            anyString(),
            any(AsyncJobId.class),
            anyString(),
            anyString(),
            any(Duration.class),
            any(),
            anyString());
    verify(asyncJobStore, times(0))
        .markFailed(
            anyString(), any(AsyncJobId.class), anyString(), anyString(), any(), anyString());
    assertThat(
            meterRegistry
                .find("asyncJobQueue.executor.rejected")
                .tag("queueName", "assetlocalize")
                .counter())
        .isNull();
    assertThat(
            meterRegistry
                .find("asyncJobQueue.stop.pollTimeout")
                .tag("queueName", "assetlocalize")
                .counter())
        .isNull();
  }

  @Test
  public void stopTimesOutWaitingForStuckActivePoll() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    CountDownLatch claimStarted = new CountDownLatch(1);
    CountDownLatch allowClaimReturn = new CountDownLatch(1);

    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              claimStarted.countDown();
              assertThat(allowClaimReturn.await(2, TimeUnit.SECONDS)).isTrue();
              return List.of();
            });

    RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setShutdownAwaitTerminationMs(25);
    ThreadPoolTaskExecutor stopTimeoutExecutor = newExecutor(1);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            stopTimeoutExecutor);

    try {
      asyncJobQueueRuntime.start();
      Thread pollThread =
          new Thread(taskScheduler.scheduledTasks().get(0), "async-queue-stuck-poll-test");
      pollThread.start();
      assertThat(claimStarted.await(2, TimeUnit.SECONDS)).isTrue();

      Thread stopThread = new Thread(asyncJobQueueRuntime::stop, "async-queue-stop-timeout-test");
      stopThread.start();
      stopThread.join(1_000);

      assertThat(stopThread.isAlive()).isFalse();
      assertThat(pollThread.isAlive()).isTrue();
      assertThat(
              meterRegistry
                  .get("asyncJobQueue.stop.pollTimeout")
                  .tag("queueName", "assetlocalize")
                  .counter()
                  .count())
          .isEqualTo(1);

      allowClaimReturn.countDown();
      pollThread.join(2_000);
      assertThat(pollThread.isAlive()).isFalse();
    } finally {
      allowClaimReturn.countDown();
      stopTimeoutExecutor.shutdown();
    }
  }

  @Test
  public void stopRecordsInterruptedWaitForStuckActivePoll() throws Exception {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    CountDownLatch claimStarted = new CountDownLatch(1);
    CountDownLatch allowClaimReturn = new CountDownLatch(1);
    AtomicInteger stopThreadInterrupted = new AtomicInteger();

    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              claimStarted.countDown();
              assertThat(allowClaimReturn.await(2, TimeUnit.SECONDS)).isTrue();
              return List.of();
            });

    RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setShutdownAwaitTerminationMs(10_000);
    ThreadPoolTaskExecutor interruptedStopExecutor = newExecutor(1);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings,
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            interruptedStopExecutor);

    try {
      asyncJobQueueRuntime.start();
      Thread pollThread =
          new Thread(taskScheduler.scheduledTasks().get(0), "async-queue-interrupted-poll-test");
      pollThread.start();
      assertThat(claimStarted.await(2, TimeUnit.SECONDS)).isTrue();

      CountDownLatch stopStarted = new CountDownLatch(1);
      Thread stopThread =
          new Thread(
              () -> {
                stopStarted.countDown();
                asyncJobQueueRuntime.stop();
                stopThreadInterrupted.set(Thread.currentThread().isInterrupted() ? 1 : 0);
              },
              "async-queue-stop-interrupted-test");
      stopThread.start();
      assertThat(stopStarted.await(1, TimeUnit.SECONDS)).isTrue();

      stopThread.interrupt();
      stopThread.join(1_000);

      assertThat(stopThread.isAlive()).isFalse();
      assertThat(stopThreadInterrupted.get()).isEqualTo(1);
      assertThat(pollThread.isAlive()).isTrue();
      assertThat(
              meterRegistry
                  .get("asyncJobQueue.stop.pollInterrupted")
                  .tag("queueName", "assetlocalize")
                  .counter()
                  .count())
          .isEqualTo(1);
      assertThat(
              meterRegistry
                  .find("asyncJobQueue.stop.pollTimeout")
                  .tag("queueName", "assetlocalize")
                  .counter())
          .isNull();

      allowClaimReturn.countDown();
      pollThread.join(2_000);
      assertThat(pollThread.isAlive()).isFalse();
    } finally {
      allowClaimReturn.countDown();
      interruptedStopExecutor.shutdown();
    }
  }

  @Test
  public void wakeupDuringActivePollSchedulesImmediateFollowUpPoll() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
    AtomicInteger claimInvocations = new AtomicInteger();
    AsyncJobQueueRuntime[] runtime = new AsyncJobQueueRuntime[1];

    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              if (claimInvocations.incrementAndGet() == 1) {
                runtime[0].triggerPollNow();
              }
              return List.of();
            });

    runtime[0] =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor);

    runtime[0].start();
    taskScheduler.scheduledTasks().get(0).run();

    assertThat(taskScheduler.scheduledTasks()).hasSize(2);
    long immediateDelayMs =
        taskScheduler.scheduledStartTimes().get(1).getTime()
            - taskScheduler.scheduleInvocationTimes().get(1);
    assertThat(immediateDelayMs).isBetween(-50L, 50L);

    taskScheduler.scheduledTasks().get(1).run();
    assertThat(claimInvocations.get()).isEqualTo(2);
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
  public void scheduledBackoffSaturatesExtremeDelayDate() {
    assertThat(AsyncJobQueueRuntime.startDateAfterMillis(Long.MAX_VALUE).getTime())
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void scheduledPollRecordsFailureAndContinuesAfterClaimException() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("database unavailable"));

    RecordingTaskScheduler taskScheduler = new RecordingTaskScheduler();
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor,
            delayMs -> delayMs);

    asyncJobQueueRuntime.start();
    taskScheduler.scheduledTasks().get(0).run();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.poll.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    assertClaimFailureCounter("other", 1);
    assertThat(taskScheduler.scheduledTasks()).hasSize(2);
    long scheduledDelayMs =
        taskScheduler.scheduledStartTimes().get(1).getTime()
            - taskScheduler.scheduleInvocationTimes().get(1);
    assertThat(scheduledDelayMs).isBetween(50L, 150L);
  }

  @Test
  public void claimFailureCounterClassifiesDatabaseContention() {
    assertThat(AsyncJobQueueRuntime.claimFailureKind(new IllegalStateException("boom")))
        .isEqualTo("other");
    assertThat(
            AsyncJobQueueRuntime.claimFailureKind(
                new IllegalStateException(
                    "wrapped", new DeadlockLoserDataAccessException("deadlock", null))))
        .isEqualTo("deadlock");
    assertThat(AsyncJobQueueRuntime.claimFailureKind(new CannotAcquireLockException("busy")))
        .isEqualTo("lock");
    assertThat(
            AsyncJobQueueRuntime.claimFailureKind(
                new PessimisticLockingFailureException("lock wait timeout")))
        .isEqualTo("lock");
    assertThat(AsyncJobQueueRuntime.claimFailureKind(new QueryTimeoutException("timeout")))
        .isEqualTo("timeout");
  }

  @Test
  public void scheduledPollRecordsFailureWhenNextPollScheduleFails() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenReturn(List.of());

    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    Runnable[] scheduledPoll = new Runnable[1];
    AtomicInteger scheduleInvocations = new AtomicInteger();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              int invocationCount = scheduleInvocations.incrementAndGet();
              if (invocationCount == 1) {
                scheduledPoll[0] = invocation.getArgument(0);
                return new DummyScheduledFuture();
              }
              if (invocationCount == 2) {
                throw new IllegalStateException("scheduler unavailable");
              }
              return new DummyScheduledFuture();
            });

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            asyncJobStore,
            queueSettings(100, 1_000, 1, 1, 10_000, 0),
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            taskScheduler,
            executor,
            delayMs -> delayMs);

    asyncJobQueueRuntime.start();
    assertThat(scheduledPoll[0]).isNotNull();

    scheduledPoll[0].run();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.poll.schedule.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
    verify(asyncJobStore, times(1))
        .claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class));

    asyncJobQueueRuntime.triggerPollNow();
    assertThat(scheduleInvocations.get()).isEqualTo(3);
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
  public void coreRuntimeSettingsMustBePositiveAndConsistent() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setClaimBatchSize(0);
    assertInvalidQueueSettings(queueSettings);

    queueSettings = new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setMaxConcurrency(0);
    assertInvalidQueueSettings(queueSettings);

    queueSettings = new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setLeaseDurationMs(0);
    assertInvalidQueueSettings(queueSettings);

    queueSettings = new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollIntervalMs(1_000);
    queueSettings.setMaxPollIntervalMs(999);
    assertInvalidQueueSettings(queueSettings);

    queueSettings = new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setHeartbeatIntervalMs(-1);
    assertInvalidQueueSettings(queueSettings);
  }

  @Test
  public void pollIntervalsMustBePositive() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollIntervalMs(0);

    org.junit.Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime(
                new InMemoryAsyncJobStore(),
                queueSettings,
                handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
                mock(TaskScheduler.class),
                executor));

    queueSettings.setPollIntervalMs(1);
    queueSettings.setMaxPollIntervalMs(0);

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

  @Test
  public void retryDelayUsesExponentialBackoffUntilMaxRetryDelay() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(100, 1_000, 1, 1, 10_000, 0);
    queueSettings.setRetryJitterPercent(0);
    queueSettings.setMaxRetryDelayMs(500);

    AsyncJobQueueRuntime asyncJobQueueRuntime =
        runtime(
            new InMemoryAsyncJobStore(),
            queueSettings,
            handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
            mock(TaskScheduler.class),
            executor);

    assertThat(asyncJobQueueRuntime.retryDelayMs(0)).isEqualTo(100);
    assertThat(asyncJobQueueRuntime.retryDelayMs(1)).isEqualTo(100);
    assertThat(asyncJobQueueRuntime.retryDelayMs(2)).isEqualTo(200);
    assertThat(asyncJobQueueRuntime.retryDelayMs(3)).isEqualTo(400);
    assertThat(asyncJobQueueRuntime.retryDelayMs(4)).isEqualTo(500);
    assertThat(asyncJobQueueRuntime.retryDelayMs(20)).isEqualTo(500);
  }

  @Test
  public void delayJitterMathDoesNotOverflow() {
    assertThat(AsyncJobQueueRuntime.jitterRangeMs(100, 20)).isEqualTo(20);
    assertThat(AsyncJobQueueRuntime.jitterRangeMs(1, 100)).isEqualTo(1);
    assertThat(AsyncJobQueueRuntime.jitterRangeMs(Long.MAX_VALUE, 100))
        .isEqualTo(Long.MAX_VALUE - 1);
    long largeDelayMs = Long.MAX_VALUE - 7;
    assertThat(AsyncJobQueueRuntime.jitterRangeMs(largeDelayMs, 3))
        .isEqualTo(largeDelayMs / 100 * 3 + largeDelayMs % 100 * 3 / 100);

    assertThat(AsyncJobQueueRuntime.addJitter(Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
    assertThat(AsyncJobQueueRuntime.addJitter(Long.MAX_VALUE - 5, 10)).isEqualTo(Long.MAX_VALUE);
    assertThat(AsyncJobQueueRuntime.addJitter(5, -10)).isZero();
    assertThat(AsyncJobQueueRuntime.addJitter(100, -25)).isEqualTo(75);
  }

  @Test
  public void retrySettingsMustBePositiveAndBounded() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setMaxRetryDelayMs(0);

    org.junit.Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime(
                new InMemoryAsyncJobStore(),
                queueSettings,
                handler(asyncJobRecord -> AsyncJobHandlerResult.done()),
                mock(TaskScheduler.class),
                executor));

    queueSettings.setMaxRetryDelayMs(1);
    queueSettings.setRetryJitterPercent(101);

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

  private AsyncJobQueueProperties.QueueSettings queueSettingsWithoutRetryJitter(
      long pollIntervalMs,
      long maxPollIntervalMs,
      int claimBatchSize,
      int maxConcurrency,
      long leaseDurationMs,
      long heartbeatIntervalMs) {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        queueSettings(
            pollIntervalMs,
            maxPollIntervalMs,
            claimBatchSize,
            maxConcurrency,
            leaseDurationMs,
            heartbeatIntervalMs);
    queueSettings.setRetryJitterPercent(0);
    return queueSettings;
  }

  private ThreadPoolTaskExecutor newExecutor(int concurrency) {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    configureExecutor(threadPoolTaskExecutor, concurrency);
    return threadPoolTaskExecutor;
  }

  private ThreadPoolTaskExecutor newGracefulExecutor(int concurrency, long awaitTerminationMs) {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
    threadPoolTaskExecutor.setAwaitTerminationMillis(awaitTerminationMs);
    configureExecutor(threadPoolTaskExecutor, concurrency);
    return threadPoolTaskExecutor;
  }

  private ThreadPoolTaskExecutor newFailingSubmitExecutor(RuntimeException exception) {
    ThreadPoolTaskExecutor threadPoolTaskExecutor =
        new ThreadPoolTaskExecutor() {
          @Override
          public void execute(Runnable task) {
            throw exception;
          }
        };
    configureExecutor(threadPoolTaskExecutor, 1);
    return threadPoolTaskExecutor;
  }

  private void configureExecutor(ThreadPoolTaskExecutor threadPoolTaskExecutor, int concurrency) {
    threadPoolTaskExecutor.setCorePoolSize(concurrency);
    threadPoolTaskExecutor.setMaxPoolSize(concurrency);
    threadPoolTaskExecutor.setQueueCapacity(0);
    threadPoolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    threadPoolTaskExecutor.initialize();
  }

  private void assertInvalidQueueSettings(AsyncJobQueueProperties.QueueSettings queueSettings) {
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

  private AsyncJobRecord claimedJob(int attemptCount) {
    return claimedJob(attemptCount, false);
  }

  private AsyncJobRecord claimedJob(int attemptCount, boolean leaseReclaimed) {
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
        now,
        leaseReclaimed);
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

  private void waitForTriggerFailure(double expectedCount) throws InterruptedException {
    long timeoutAt = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < timeoutAt) {
      io.micrometer.core.instrument.Counter counter =
          meterRegistry
              .find("asyncJobQueue.trigger.failed")
              .tag("queueName", "assetlocalize")
              .counter();
      if (counter != null && counter.count() == expectedCount) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out waiting for trigger failure count " + expectedCount);
  }

  private void assertClaimFailureCounter(String failure, double expectedCount) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.claim.failed")
                .tag("queueName", "assetlocalize")
                .tag("failure", failure)
                .counter()
                .count())
        .isEqualTo(expectedCount);
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

  private void waitForInFlightCount(
      AsyncJobQueueRuntime asyncJobQueueRuntime, int expectedInFlightCount)
      throws InterruptedException {
    long timeoutAt = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < timeoutAt) {
      if (asyncJobQueueRuntime.inFlightCount() == expectedInFlightCount) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Timed out waiting for in-flight count " + expectedInFlightCount);
  }

  private void waitForGaugeValue(String meterName, double expectedValue)
      throws InterruptedException {
    long timeoutAt = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < timeoutAt) {
      if (gaugeValue(meterName) == expectedValue) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Timed out waiting for gauge " + meterName + " value " + expectedValue);
  }

  private void assertGaugeValue(String meterName, double expectedValue) {
    assertThat(gaugeValue(meterName)).isEqualTo(expectedValue);
  }

  private double gaugeValue(String meterName) {
    return meterRegistry.get(meterName).tag("queueName", "assetlocalize").gauge().value();
  }

  @FunctionalInterface
  private interface ThrowingProcessor {
    AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) throws Exception;
  }

  private static class InlineImmediateTaskScheduler implements TaskScheduler {

    private final AtomicInteger scheduleInvocations = new AtomicInteger();

    int scheduleInvocations() {
      return scheduleInvocations.get();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Date startTime) {
      scheduleInvocations.incrementAndGet();
      if (startTime.getTime() <= System.currentTimeMillis() + 10) {
        task.run();
      }
      return new DummyScheduledFuture();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      scheduleInvocations.incrementAndGet();
      if (!startTime.isAfter(Instant.now().plusMillis(10))) {
        task.run();
      }
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

  private static class DummyScheduledFuture implements ScheduledFuture<Object> {

    private boolean cancelled;

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
      cancelled = true;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
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
