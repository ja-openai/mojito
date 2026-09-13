package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsyncJobQueueRuntimeHandlerAdmissionTest {

  private static final String QUEUE = "handler-admission";
  private final InMemoryAsyncJobStore store = spy(new InMemoryAsyncJobStore());
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AsyncJobHandler handler = mock(AsyncJobHandler.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
  private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
  private final CountDownLatch scheduled = new CountDownLatch(1);
  private final CountDownLatch returnFromScheduling = new CountDownLatch(1);
  private final AtomicReference<Runnable> heartbeat = new AtomicReference<>();
  private AsyncJobQueueRuntime runtime;

  @Before
  public void setUp() throws Exception {
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    when(handler.process(any())).thenReturn(AsyncJobHandlerResult.done());
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(
            invocation -> {
              heartbeat.set(invocation.getArgument(0));
              scheduled.countDown();
              // A scheduler can publish a timer before its caller resumes with the future.
              assertThat(returnFromScheduling.await(5, TimeUnit.SECONDS)).isTrue();
              return heartbeatFuture;
            });
  }

  @After
  public void tearDown() {
    returnFromScheduling.countDown();
    if (runtime != null) {
      runtime.stop();
    } else {
      executor.shutdown();
    }
    registry.close();
  }

  @Test
  public void knownLeaseLossBeforeHandlerEntrySkipsWorkAndPreservesReplacement() throws Exception {
    assertKnownLeaseLossSkipsWork(false);
  }

  @Test
  public void knownLeaseLossStillReleasesCapacityWhenHeartbeatCancellationFails() throws Exception {
    when(heartbeatFuture.cancel(false)).thenThrow(new IllegalStateException("cancel unavailable"));
    assertKnownLeaseLossSkipsWork(true);
  }

  private void assertKnownLeaseLossSkipsWork(boolean cancellationFails) throws Exception {
    AsyncJobId id = claimWithRuntime(200, 25);
    assertThat(scheduled.await(5, TimeUnit.SECONDS)).isTrue();
    AsyncJobRecord replacement = reclaimAfterNaturalExpiry(id);

    heartbeat.get().run();
    heartbeat.get().run();
    assertThat(heartbeatFailures()).isEqualTo(1);
    returnFromScheduling.countDown();
    awaitWorker();

    verifyNoInteractions(handler);
    assertThat(store.getByIds(List.of(id))).containsExactly(replacement);
    assertThat(runtime.inFlightCount()).isZero();
    verify(heartbeatFuture).cancel(false);
    verify(store, never()).markDone(anyString(), any(), anyString(), anyString(), any());
    verify(store, never()).markFailed(anyString(), any(), anyString(), anyString(), any(), any());
    verify(store, never())
        .requeue(anyString(), any(), anyString(), anyString(), any(), any(), any());
    verify(store, never())
        .requeueAfter(anyString(), any(), anyString(), anyString(), any(), any(), any());
    if (cancellationFails) {
      assertThat(registry.get("asyncJobQueue.heartbeat.cancel.failed").counter().count())
          .isEqualTo(1);
    }
    heartbeat.get().run();
    verify(store).heartbeat(anyString(), any(), anyString(), anyString(), any());

    AsyncJobId next = store.enqueueNow(QUEUE, "next");
    runtime.pollOnce();
    awaitWorker();
    verify(handler).process(argThat(job -> job.id().equals(next)));
    verify(handler).onJobDone(argThat(job -> job.id().equals(next)), any());
    assertThat(store.getByIds(List.of(next)).getFirst().status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(store.getByIds(List.of(id))).containsExactly(replacement);
  }

  @Test
  public void successfulRenewalBeforeHandlerEntryStillRunsWork() throws Exception {
    AsyncJobId id = claimWithRuntime(60_000, 100);
    assertThat(scheduled.await(5, TimeUnit.SECONDS)).isTrue();
    heartbeat.get().run();
    returnFromScheduling.countDown();
    awaitWorker();

    assertCompleted(id);
    assertThat(heartbeatFailures()).isZero();
  }

  @Test
  public void uncertainRenewalBeforeHandlerEntryDoesNotImplyLeaseLoss() throws Exception {
    doThrow(new IllegalStateException("heartbeat response unavailable"))
        .when(store)
        .heartbeat(anyString(), any(), anyString(), anyString(), any());
    AsyncJobId id = claimWithRuntime(60_000, 100);
    assertThat(scheduled.await(5, TimeUnit.SECONDS)).isTrue();
    heartbeat.get().run();
    returnFromScheduling.countDown();
    awaitWorker();

    assertCompleted(id);
    assertThat(heartbeatFailures()).isEqualTo(1);
  }

  @Test
  public void handlerDoesNotWaitForTheFirstHeartbeat() throws Exception {
    AsyncJobId id = claimWithRuntime(60_000, 100);
    assertThat(scheduled.await(5, TimeUnit.SECONDS)).isTrue();
    returnFromScheduling.countDown();
    awaitWorker();

    assertCompleted(id);
    verify(store, never()).heartbeat(anyString(), any(), anyString(), anyString(), any());
  }

  @Test
  public void disabledHeartbeatsDoNotPreventHandlerEntry() throws Exception {
    AsyncJobId id = claimWithRuntime(60_000, 0);
    awaitWorker();

    assertCompleted(id);
    verifyNoInteractions(scheduler, heartbeatFuture);
    assertThat(heartbeatFailures()).isZero();
  }

  private AsyncJobId claimWithRuntime(long leaseMs, long heartbeatMs) {
    var settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setLeaseDurationMs(leaseMs);
    settings.setHeartbeatIntervalMs(heartbeatMs);
    runtime =
        new AsyncJobQueueRuntime(
            QUEUE, store, settings, handler, scheduler, executor, registry, "worker");
    AsyncJobId id = store.enqueueNow(QUEUE, "input");
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);
    return id;
  }

  private AsyncJobRecord reclaimAfterNaturalExpiry(AsyncJobId id) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      List<AsyncJobRecord> claimed = store.claimNextJobs(QUEUE, 1, "peer", Duration.ofMinutes(1));
      if (!claimed.isEmpty()) {
        assertThat(claimed).hasSize(1);
        AsyncJobRecord replacement = claimed.getFirst();
        assertThat(replacement.id()).isEqualTo(id);
        assertThat(replacement.attemptCount()).isEqualTo(2);
        assertThat(replacement.leaseReclaimed()).isTrue();
        // leaseReclaimed describes this claim response, not a field retained by the store.
        return store.getByIds(List.of(id)).getFirst();
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Peer did not reclaim the naturally expired lease");
  }

  private void awaitWorker() throws Exception {
    executor.getThreadPoolExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
  }

  private void assertCompleted(AsyncJobId id) throws Exception {
    verify(handler).process(argThat(job -> job.id().equals(id)));
    verify(handler).onJobDone(argThat(job -> job.id().equals(id)), any());
    assertThat(store.getByIds(List.of(id)).getFirst().status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(runtime.inFlightCount()).isZero();
    verify(store, never()).markFailed(anyString(), any(), anyString(), anyString(), any(), any());
  }

  private double heartbeatFailures() {
    var counter = registry.find("asyncJobQueue.heartbeat.failed").counter();
    return counter == null ? 0 : counter.count();
  }
}
