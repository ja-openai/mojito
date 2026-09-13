package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsyncJobQueueRuntimeTelemetryTest {

  private static final String QUEUE = "assetlocalize";
  private static final AsyncJobHandlerResult DONE =
      AsyncJobHandlerResult.done("{\"output\":\"saved\"}");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final InMemoryAsyncJobStore store = spy(new InMemoryAsyncJobStore());
  private final AsyncJobHandler handler = mock(AsyncJobHandler.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
  private final AsyncJobQueueProperties.QueueSettings settings =
      new AsyncJobQueueProperties.QueueSettings();
  private final Deque<Runnable> acceptedJobs = new ArrayDeque<>();

  @Before
  public void setUp() throws Exception {
    settings.setPollIntervalMs(100);
    settings.setMaxPollIntervalMs(1_000);
    settings.setClaimBatchSize(2);
    settings.setMaxConcurrency(2);
    settings.setLeaseDurationMs(10_000);
    settings.setHeartbeatIntervalMs(0);
    settings.setMaxAttempts(2);
    settings.setRetryJitterPercent(0);
    when(handler.process(any())).thenReturn(DONE);
    // Execute accepted jobs separately from submit, as a real worker would. Inline execution
    // would incorrectly route worker exceptions through the executor-submission failure path.
    doAnswer(
            invocation -> {
              acceptedJobs.addLast(invocation.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
  }

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void claimedCounterCollisionDoesNotStrandClaimedBatch() throws Exception {
    collide("claimed");
    assertBatchDispatched();
  }

  @Test
  public void claimLatencyCollisionDoesNotStrandClaimedBatch() throws Exception {
    collide("claim.latency");
    assertBatchDispatched();
  }

  @Test
  public void queueWaitLatencyCollisionDoesNotStrandClaimedBatch() throws Exception {
    collide("queueWait.latency");
    assertBatchDispatched();
  }

  @Test
  public void claimedMeterAddedErrorDoesNotStrandClaimedBatch() throws Exception {
    AtomicInteger failures = failOnMeterAdded("claimed", new AssertionError("telemetry failed"));
    assertBatchDispatched();
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void completedCounterCollisionDoesNotSuppressDoneCallbackOrManufactureRetry()
      throws Exception {
    collide("completed");
    assertSuccessfulCompletion();
  }

  @Test
  public void completedMeterAddedErrorDoesNotSuppressDoneCallbackOrManufactureRetry()
      throws Exception {
    AtomicInteger failures = failOnMeterAdded("completed", new AssertionError("telemetry failed"));
    assertSuccessfulCompletion();
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void doneCallbackFailureMetricCollisionDoesNotRetryCommittedJob() throws Exception {
    Gauge.builder("asyncJobQueue.handler.completion.failed", () -> 0)
        .tags("queueName", QUEUE, "callback", "done")
        .register(meterRegistry);
    doThrow(new IllegalStateException("completion callback unavailable"))
        .when(handler)
        .onJobDone(any(), any());

    assertSuccessfulCompletion();
  }

  @Test
  public void failedCallbackFailureMeterAddedErrorPreservesOriginalTerminalFailure()
      throws Exception {
    AtomicInteger failures =
        failOnMeterAdded("handler.completion.failed", new AssertionError("telemetry failed"));
    doThrow(new AssertionError("failure callback unavailable"))
        .when(handler)
        .onJobFailedPermanently(any(), any(), any());

    assertHandlerFailure();
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void handlerFailedMeterAddedErrorPreservesTerminalFailureAndOriginalCause()
      throws Exception {
    AtomicInteger failures =
        failOnMeterAdded("handler.failed", new AssertionError("telemetry failed"));
    assertHandlerFailure();
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void failedCounterCollisionPreservesTerminalFailureAndOriginalCause() throws Exception {
    collide("failed");
    assertHandlerFailure();
  }

  @Test
  public void executorRejectedCounterCollisionStillRequeuesEntireBatch() throws Exception {
    collide("executor.rejected");
    assertExecutorRejection(false);
  }

  @Test
  public void executorRejectedMeterAddedErrorStillTerminalizesEntireBatch() throws Exception {
    AtomicInteger failures =
        failOnMeterAdded("executor.rejected", new AssertionError("telemetry failed"));
    assertExecutorRejection(true);
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void failedCounterCollisionDoesNotSuppressExecutorRejectionCallback() throws Exception {
    collide("failed");
    assertExecutorRejection(true);
  }

  @Test
  public void heartbeatScheduleCounterCollisionStillRetriesOriginalFailure() throws Exception {
    collide("heartbeat.schedule.failed");
    assertHeartbeatScheduleFailure(false);
  }

  @Test
  public void heartbeatScheduleMeterAddedErrorStillTerminalizesOriginalFailure() throws Exception {
    AtomicInteger failures =
        failOnMeterAdded("heartbeat.schedule.failed", new AssertionError("telemetry failed"));
    assertHeartbeatScheduleFailure(true);
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void failedCounterCollisionDoesNotSuppressHeartbeatScheduleFailureCallback()
      throws Exception {
    collide("failed");
    assertHeartbeatScheduleFailure(true);
  }

  @Test
  public void heartbeatCancelCounterCollisionDoesNotLeakCapacity() throws Exception {
    collide("heartbeat.cancel.failed");
    assertHeartbeatCancellationReleasesCapacity();
  }

  @Test
  public void heartbeatCancelMeterAddedErrorDoesNotLeakCapacity() throws Exception {
    AtomicInteger failures =
        failOnMeterAdded("heartbeat.cancel.failed", new AssertionError("telemetry failed"));
    assertHeartbeatCancellationReleasesCapacity();
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void processingLatencyCollisionDoesNotSuppressCompletionWakeup() throws Exception {
    collide("processing.latency");
    assertCompletionWakesPoller();
  }

  @Test
  public void processingLatencyMeterAddedErrorDoesNotSuppressCompletionWakeup() throws Exception {
    AtomicInteger failures =
        failOnMeterAdded("processing.latency", new AssertionError("telemetry failed"));
    assertCompletionWakesPoller();
    assertThat(failures.get()).isPositive();
  }

  @Test
  public void fatalCompletedTelemetryStillPropagatesWithoutRetryingCommittedJob() throws Exception {
    VirtualMachineError fatal = new VirtualMachineError("fatal telemetry") {};
    AtomicInteger failures = failOnMeterAdded("completed", fatal);
    AsyncJobId id = enqueue();
    AsyncJobQueueRuntime runtime = runtime();
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);

    assertThat(assertThrows(VirtualMachineError.class, acceptedJobs.removeFirst()::run))
        .isSameAs(fatal);

    assertThat(failures.get()).isEqualTo(1);
    assertPersisted(id, AsyncJobStatus.DONE);
    assertThat(runtime.inFlightCount()).isZero();
    verify(handler, never()).onJobDone(any(), any());
    assertNoRetryOrFailure();
  }

  private void assertBatchDispatched() throws Exception {
    List<AsyncJobId> ids = List.of(enqueue(), enqueue());
    AsyncJobQueueRuntime runtime = runtime();

    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(2);
    assertThat(acceptedJobs).hasSize(2);
    assertThat(runtime.inFlightCount()).isEqualTo(2);
    for (AsyncJobId id : ids) {
      assertThat(store.getByIds(List.of(id)).get(0).status()).isEqualTo(AsyncJobStatus.RUNNING);
    }
    runAcceptedJobs();

    assertThat(runtime.inFlightCount()).isZero();
    verify(handler, times(2)).process(any());
    assertDoneCallbacks(ids);
    assertNoRetryOrFailure();
  }

  private void assertSuccessfulCompletion() throws Exception {
    AsyncJobId id = enqueue();
    AsyncJobQueueRuntime runtime = runtime();
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);

    runAcceptedJobs();

    assertDoneCallbacks(List.of(id));
    assertThat(runtime.inFlightCount()).isZero();
    assertNoRetryOrFailure();
    assertThat(meterRegistry.find("asyncJobQueue.handler.failed").counter()).isNull();
  }

  private void assertHandlerFailure() throws Exception {
    settings.setMaxAttempts(1);
    IllegalStateException original =
        new IllegalStateException("handler unavailable", new IllegalArgumentException("bad input"));
    when(handler.process(any())).thenThrow(original);
    AsyncJobId id = enqueue();
    AsyncJobQueueRuntime runtime = runtime();
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);

    runAcceptedJobs();

    assertFailureCallbacks(List.of(id), original);
    assertThat(store.getByIds(List.of(id)).get(0).lastError()).contains("bad input");
    assertThat(runtime.inFlightCount()).isZero();
    verify(handler).process(any());
  }

  private void assertExecutorRejection(boolean terminal) throws Exception {
    settings.setMaxAttempts(terminal ? 1 : 2);
    RejectedExecutionException original = new RejectedExecutionException("executor unavailable");
    doThrow(original).when(executor).execute(any(Runnable.class));
    List<AsyncJobId> ids = List.of(enqueue(), enqueue());
    AsyncJobQueueRuntime runtime = runtime();

    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(2);

    assertThat(acceptedJobs).isEmpty();
    assertThat(runtime.inFlightCount()).isZero();
    verify(executor, times(2)).execute(any(Runnable.class));
    verify(handler, never()).process(any());
    if (terminal) {
      assertFailureCallbacks(ids, original);
    } else {
      assertRetried(ids, original);
    }
  }

  private void assertHeartbeatScheduleFailure(boolean terminal) throws Exception {
    settings.setHeartbeatIntervalMs(100);
    settings.setMaxAttempts(terminal ? 1 : 2);
    IllegalStateException original = new IllegalStateException("heartbeat scheduler unavailable");
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenThrow(original);
    AsyncJobId id = enqueue();
    AsyncJobQueueRuntime runtime = runtime();
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);

    runAcceptedJobs();

    assertThat(runtime.inFlightCount()).isZero();
    verify(handler, never()).process(any());
    if (terminal) {
      assertFailureCallbacks(List.of(id), original);
    } else {
      assertRetried(List.of(id), original);
    }
    assertThat(meterRegistry.find("asyncJobQueue.handler.failed").counter()).isNull();
  }

  private void assertHeartbeatCancellationReleasesCapacity() throws Exception {
    settings.setMaxConcurrency(1);
    settings.setHeartbeatIntervalMs(100);
    ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    when(heartbeat.cancel(false)).thenThrow(new IllegalStateException("cancel unavailable"));
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(invocation -> heartbeat);
    List<AsyncJobId> ids = List.of(enqueue(), enqueue());
    AsyncJobQueueRuntime runtime = runtime();
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);
    assertThat(runtime.pollOnce().skippedSaturated()).isTrue();

    runAcceptedJobs();

    assertThat(runtime.inFlightCount()).isZero();
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);
    runAcceptedJobs();
    assertThat(runtime.inFlightCount()).isZero();
    verify(heartbeat, times(2)).cancel(false);
    assertDoneCallbacks(ids);
    assertNoRetryOrFailure();
    assertThat(meterRegistry.get("asyncJobQueue.processing.latency").timer().count()).isEqualTo(2);
  }

  private void assertCompletionWakesPoller() throws Exception {
    settings.setMaxConcurrency(1);
    List<Runnable> polls = new ArrayList<>();
    List<Date> pollStartTimes = new ArrayList<>();
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              polls.add(invocation.getArgument(0));
              pollStartTimes.add(invocation.getArgument(1));
              return mock(ScheduledFuture.class);
            });
    AsyncJobId id = enqueue();
    AsyncJobQueueRuntime runtime = runtime();
    runtime.start();
    polls.get(0).run();
    assertThat(polls).hasSize(2);
    assertThat(runtime.inFlightCount()).isEqualTo(1);
    long beforeCompletion = System.currentTimeMillis();

    runAcceptedJobs();

    assertDoneCallbacks(List.of(id));
    assertThat(runtime.inFlightCount()).isZero();
    assertThat(polls).hasSize(3);
    assertThat(pollStartTimes.get(2).getTime())
        .isBetween(beforeCompletion, System.currentTimeMillis());
    AsyncJobId nextId = enqueue();
    polls.get(2).run();
    assertThat(acceptedJobs).hasSize(1);
    runAcceptedJobs();
    assertDoneCallbacks(List.of(id, nextId));
    assertNoRetryOrFailure();
    runtime.stop();
  }

  private void assertDoneCallbacks(List<AsyncJobId> ids) throws Exception {
    ArgumentCaptor<AsyncJobRecord> callbacks = ArgumentCaptor.forClass(AsyncJobRecord.class);
    verify(handler, times(ids.size())).onJobDone(callbacks.capture(), same(DONE));
    assertThat(callbacks.getAllValues())
        .extracting(AsyncJobRecord::id)
        .containsExactlyElementsOf(ids);
    for (AsyncJobRecord callback : callbacks.getAllValues()) {
      AsyncJobRecord persisted = assertPersisted(callback.id(), AsyncJobStatus.DONE);
      assertThat(persisted.jobData()).isEqualTo(DONE.jobData());
      assertThat(persisted.lastError()).isNull();
      assertThat(callback)
          .usingRecursiveComparison()
          .ignoringFields("updatedDate")
          .isEqualTo(persisted);
    }
  }

  private void assertFailureCallbacks(List<AsyncJobId> ids, Throwable original) throws Exception {
    ArgumentCaptor<AsyncJobRecord> callbacks = ArgumentCaptor.forClass(AsyncJobRecord.class);
    verify(handler, times(ids.size()))
        .onJobFailedPermanently(callbacks.capture(), same(original), any());
    assertThat(callbacks.getAllValues())
        .extracting(AsyncJobRecord::id)
        .containsExactlyElementsOf(ids);
    for (AsyncJobRecord callback : callbacks.getAllValues()) {
      AsyncJobRecord persisted = assertPersisted(callback.id(), AsyncJobStatus.FAILED);
      assertOriginalFailure(persisted, original);
      assertThat(callback)
          .usingRecursiveComparison()
          .ignoringFields("updatedDate")
          .isEqualTo(persisted);
      verify(handler)
          .onJobFailedPermanently(eq(callback), same(original), eq(persisted.lastError()));
    }
    verify(handler, never()).onJobDone(any(), any());
    verify(store, never()).requeueAfter(any(), any(), any(), any(), any(), any(), any());
  }

  private void assertRetried(List<AsyncJobId> ids, Throwable original) throws Exception {
    for (AsyncJobId id : ids) {
      AsyncJobRecord retried = assertPersisted(id, AsyncJobStatus.QUEUED);
      assertOriginalFailure(retried, original);
      verify(store)
          .requeueAfter(
              eq(QUEUE),
              eq(id),
              eq("worker-a"),
              any(),
              eq(Duration.ofMillis(100)),
              any(),
              eq(retried.lastError()));
    }
    verify(store, never()).markFailed(any(), any(), any(), any(), any(), any());
    verify(handler, never()).onJobFailedPermanently(any(), any(), any());
    verify(handler, never()).onJobDone(any(), any());
  }

  private AsyncJobRecord assertPersisted(AsyncJobId id, AsyncJobStatus status) {
    List<AsyncJobRecord> jobs = store.getByIds(List.of(id));
    assertThat(jobs).hasSize(1);
    AsyncJobRecord job = jobs.get(0);
    assertThat(job.status()).isEqualTo(status);
    assertThat(job.attemptCount()).isEqualTo(1);
    assertThat(job.leaseUntil()).isNull();
    assertThat(job.workerId()).isNull();
    assertThat(job.leaseToken()).isNull();
    return job;
  }

  private void assertOriginalFailure(AsyncJobRecord job, Throwable original) {
    assertThat(job.lastError())
        .contains(original.getClass().getName(), original.getMessage())
        .doesNotContain("telemetry", "already registered");
  }

  private void assertNoRetryOrFailure() throws Exception {
    verify(store, never()).requeueAfter(any(), any(), any(), any(), any(), any(), any());
    verify(store, never()).requeue(any(), any(), any(), any(), any(), any(), any());
    verify(store, never()).markFailed(any(), any(), any(), any(), any(), any());
    verify(handler, never()).onJobFailedPermanently(any(), any(), any());
  }

  private void runAcceptedJobs() {
    while (!acceptedJobs.isEmpty()) {
      acceptedJobs.removeFirst().run();
    }
  }

  private AsyncJobId enqueue() {
    return store.enqueue(QUEUE, "{}", Instant.now().minusSeconds(1));
  }

  private AsyncJobQueueRuntime runtime() {
    return new AsyncJobQueueRuntime(
        QUEUE,
        store,
        settings,
        handler,
        scheduler,
        executor,
        meterRegistry,
        "worker-a",
        delay -> delay);
  }

  private void collide(String metric) {
    Gauge.builder("asyncJobQueue." + metric, () -> 0)
        .tag("queueName", QUEUE)
        .register(meterRegistry);
  }

  private AtomicInteger failOnMeterAdded(String metric, Error failure) {
    AtomicInteger failures = new AtomicInteger();
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue." + metric)) {
                failures.incrementAndGet();
                throw failure;
              }
            });
    return failures;
  }
}
