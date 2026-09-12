package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class AsyncJobQueueRuntimeTelemetryLifecycleTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
  private final ThreadPoolTaskScheduler heartbeatScheduler = mock(ThreadPoolTaskScheduler.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ScheduledFuture<?> pollFuture = mock(ScheduledFuture.class);
  private final List<Runnable> polls = new ArrayList<>();
  private final Logger originalLogger = AsyncJobQueueRuntime.logger;

  @After
  public void closeRegistry() {
    AsyncJobQueueRuntime.logger = originalLogger;
    registry.close();
  }

  @Test
  public void claimAndPollFailureMetricsDoNotStrandThePollLoop() {
    failLogging(new AssertionError("log backend failed"));
    collide("asyncJobQueue.claim.latency");
    collide("asyncJobQueue.claim.failed", "failure", "other");
    collide("asyncJobQueue.poll.failed");
    collide("asyncJobQueue.poll.failed.byFailure", "failure", "other");
    AsyncJobStore store = mock(AsyncJobStore.class);
    when(store.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(List.of());
    recordScheduledPolls();
    AsyncJobQueueRuntime runtime = runtime(store);

    runtime.start();
    polls.get(0).run();

    assertThat(polls).hasSize(2);
    assertThat(registry.get("asyncJobQueue.poll.active").gauge().value()).isZero();
    polls.get(1).run();
    assertThat(polls).hasSize(3);
    verify(store, times(2)).claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class));
    runtime.stop();
    verify(executor).shutdown();
  }

  @Test
  public void initialSchedulingFailureRetainsItsCauseWhenMetricsFail() {
    collide("asyncJobQueue.poll.schedule.failed");
    IllegalStateException failure = new IllegalStateException("scheduler unavailable");
    when(scheduler.schedule(any(Runnable.class), any(Date.class))).thenThrow(failure);
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());

    assertThatThrownBy(runtime::start).isSameAs(failure);
    assertThat(registry.get("asyncJobQueue.poll.started").gauge().value()).isZero();
    runtime.stop();
    verify(executor).shutdown();
  }

  @Test
  public void fatalPollFailureMetricPropagatesAfterReleasingThePollLatch() {
    InternalError fatal = new InternalError("synthetic fatal metric provider failure");
    registry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.poll.failed")) {
                throw fatal;
              }
            });
    AsyncJobStore store = mock(AsyncJobStore.class);
    when(store.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("database unavailable"));
    recordScheduledPolls();
    AsyncJobQueueRuntime runtime = runtime(store);
    runtime.start();

    assertThatThrownBy(polls.get(0)::run).isSameAs(fatal);

    assertThat(registry.get("asyncJobQueue.poll.active").gauge().value()).isZero();
    assertThat(polls).hasSize(1);
    runtime.stop();
    verify(executor).shutdown();
  }

  @Test
  public void nextPollSchedulingFailureStillAttemptsRecoveryWhenMetricsFail() {
    failLogging(new IllegalStateException("log backend failed"));
    collide("asyncJobQueue.poll.schedule.failed");
    AtomicInteger calls = new AtomicInteger();
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("temporary scheduling failure");
              }
              polls.add(invocation.getArgument(0));
              return pollFuture;
            });
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());

    runtime.start();
    polls.get(0).run();

    assertThat(calls.get()).isEqualTo(3);
    assertThat(polls).hasSize(2);
    assertThat(registry.get("asyncJobQueue.poll.scheduled").gauge().value()).isEqualTo(1);
    runtime.stop();
  }

  @Test
  public void claimErrorLoggingFailureDoesNotStrandThePollLoop() {
    assertClaimLoggingIsolation(new IllegalStateException("database unavailable"), "other");
  }

  @Test(timeout = 15000)
  public void realPollerCompletesQueuedWorkAfterClaimAndLoggingFailure() throws Exception {
    failLogging(new AssertionError("log backend failed"));
    InMemoryAsyncJobStore store = spy(new InMemoryAsyncJobStore());
    AsyncJobId id = store.enqueueNow("assetlocalize", "{}");
    when(store.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("temporary database failure"))
        .thenCallRealMethod();
    CountDownLatch completed = new CountDownLatch(1);
    AsyncJobHandler handler = mock(AsyncJobHandler.class);
    when(handler.process(any(AsyncJobRecord.class))).thenReturn(AsyncJobHandlerResult.done());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              completed.countDown();
              return null;
            })
        .when(handler)
        .onJobDone(any(AsyncJobRecord.class), any(AsyncJobHandlerResult.class));
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setPollIntervalMs(10);
    settings.setMaxPollIntervalMs(100);
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setHeartbeatIntervalMs(0);
    settings.setShutdownAwaitTerminationMs(2000);
    ThreadPoolTaskExecutor realExecutor = new ThreadPoolTaskExecutor();
    realExecutor.setCorePoolSize(1);
    realExecutor.setMaxPoolSize(1);
    realExecutor.setWaitForTasksToCompleteOnShutdown(true);
    realExecutor.setAwaitTerminationMillis(2000);
    realExecutor.setThreadNamePrefix("queue-poll-log-test-worker-");
    realExecutor.initialize();
    ThreadPoolTaskScheduler realScheduler = new ThreadPoolTaskScheduler();
    realScheduler.setThreadNamePrefix("queue-poll-log-test-scheduler-");
    realScheduler.initialize();
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            store,
            settings,
            handler,
            realScheduler,
            realExecutor,
            registry,
            "worker-a",
            delay -> delay);
    try {
      runtime.start();

      assertThat(completed.await(5, TimeUnit.SECONDS))
          .as("poll recovery completes queued work without an external hint")
          .isTrue();

      AsyncJobRecord done = store.getByIds(List.of(id)).getFirst();
      assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
      assertThat(done.attemptCount()).isEqualTo(1);
      assertThat(registry.get("asyncJobQueue.poll.failed").counter().count()).isEqualTo(1);
      verify(handler).process(any(AsyncJobRecord.class));
    } finally {
      try {
        runtime.stop();
      } finally {
        realScheduler.shutdown();
        assertThat(realExecutor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS))
            .isTrue();
        assertThat(
                realScheduler
                    .getScheduledThreadPoolExecutor()
                    .awaitTermination(5, TimeUnit.SECONDS))
            .isTrue();
      }
    }
  }

  @Test
  public void transientClaimWarningFailureDoesNotStrandThePollLoop() {
    assertClaimLoggingIsolation(new QueryTimeoutException("query timed out"), "timeout");
  }

  @Test
  public void failedRecoveryStillRecordsUnscheduledLoopDespiteBrokenLogging() {
    failLogging(new AssertionError("log backend failed"));
    AtomicInteger calls = new AtomicInteger();
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() > 1) {
                throw new IllegalStateException("scheduler unavailable");
              }
              polls.add(invocation.getArgument(0));
              return pollFuture;
            });
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    runtime.start();

    polls.get(0).run();

    assertThat(calls.get()).isEqualTo(3);
    assertThat(polls).hasSize(1);
    assertThat(registry.get("asyncJobQueue.poll.active").gauge().value()).isZero();
    assertThat(registry.get("asyncJobQueue.poll.scheduled").gauge().value()).isZero();
    assertThat(registry.get("asyncJobQueue.poll.schedule.failed").counter().count()).isEqualTo(2);
    assertThat(registry.get("asyncJobQueue.poll.unscheduled").counter().count()).isEqualTo(1);

    // A later external hint can recover the loop once the scheduler is usable again.
    recordScheduledPolls();
    runtime.triggerPollNow();
    polls.get(1).run();
    assertThat(polls).hasSize(3);
    runtime.stop();
  }

  @Test
  public void wrappedFatalPollLoggingStopsAfterReleasingThePollLatch() {
    InternalError fatal = new InternalError("fatal log backend");
    RuntimeException wrapper = new IllegalStateException("logger wrapper");
    wrapper.addSuppressed(fatal);
    failLogging(wrapper);
    AsyncJobStore store = mock(AsyncJobStore.class);
    when(store.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("database unavailable"));
    recordScheduledPolls();
    AsyncJobQueueRuntime runtime = runtime(store);
    runtime.start();

    assertThatThrownBy(polls.get(0)::run).isSameAs(fatal);

    assertThat(registry.get("asyncJobQueue.poll.active").gauge().value()).isZero();
    assertThat(polls).hasSize(1);
    assertThat(registry.find("asyncJobQueue.poll.failed").counter()).isNull();
    assertThat(wrapper.getSuppressed()).containsExactly(fatal);
    runtime.stop();
    verify(executor).shutdown();
    verify(heartbeatScheduler).shutdown();
  }

  private void assertClaimLoggingIsolation(RuntimeException failure, String failureKind) {
    failLogging(new AssertionError("log backend failed"));
    AsyncJobStore store = mock(AsyncJobStore.class);
    when(store.claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class)))
        .thenThrow(failure)
        .thenReturn(List.of());
    recordScheduledPolls();
    AsyncJobQueueRuntime runtime = runtime(store);
    runtime.start();

    polls.get(0).run();

    assertThat(polls).hasSize(2);
    assertThat(registry.get("asyncJobQueue.poll.active").gauge().value()).isZero();
    assertThat(registry.get("asyncJobQueue.poll.failed").counter().count()).isEqualTo(1);
    assertThat(
            registry
                .get("asyncJobQueue.poll.failed.byFailure")
                .tag("failure", failureKind)
                .counter()
                .count())
        .isEqualTo(1);
    polls.get(1).run();
    assertThat(polls).hasSize(3);
    verify(store, times(2)).claimNextJobs(anyString(), anyInt(), anyString(), any(Duration.class));
    runtime.stop();
  }

  private void failLogging(Throwable failure) {
    AsyncJobQueueRuntime.logger =
        mock(
            Logger.class,
            invocation -> {
              if (List.of("warn", "error").contains(invocation.getMethod().getName())) {
                throw failure;
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
  }

  @Test
  public void triggerFailureMetricDoesNotInvalidatePreviouslyScheduledPoll() {
    collide("asyncJobQueue.trigger.failed");
    recordScheduledPolls();
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    runtime.start();
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenThrow(new IllegalStateException("temporary scheduling failure"));

    runtime.triggerPollNow();

    assertThat(registry.get("asyncJobQueue.poll.scheduled").gauge().value()).isEqualTo(1);
    recordScheduledPolls();
    polls.get(0).run();
    assertThat(polls).hasSize(2);
    runtime.stop();
  }

  @Test
  public void cancellationFailureMetricDoesNotPreventResourceShutdown() {
    collide("asyncJobQueue.poll.cancel.failed");
    doThrow(new IllegalStateException("cancel unavailable")).when(pollFuture).cancel(false);
    recordScheduledPolls();
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    runtime.start();

    runtime.stop();

    verify(executor).shutdown();
    verify(heartbeatScheduler).shutdown();
    assertThat(registry.find("asyncJobQueue.inflight").gauge()).isNull();
    assertThat(registry.find("asyncJobQueue.poll.active").gauge()).isNull();
    polls.get(0).run();
    assertThat(polls).hasSize(1);
  }

  @Test
  public void removalListenerFailureDoesNotLeaveOtherRuntimeGaugesRegistered() {
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    registry
        .config()
        .onMeterRemoved(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.inflight")) {
                throw new AssertionError("removal listener unavailable");
              }
            });

    runtime.stop();

    assertThat(registry.getMeters()).isEmpty();
    verify(executor).shutdown();
    verify(heartbeatScheduler).shutdown();
  }

  @Test
  public void fatalPollCancellationStillShutsDownOwnedResources() {
    InternalError fatal = new InternalError("fatal cancellation");
    doThrow(fatal).when(pollFuture).cancel(false);
    assertFatalStopCleansUp(fatal);
  }

  @Test
  public void fatalCancellationMetricStillShutsDownOwnedResources() {
    InternalError fatal = new InternalError("fatal cancellation metric");
    doThrow(new IllegalStateException("cancel failed")).when(pollFuture).cancel(false);
    registry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.poll.cancel.failed")) {
                throw fatal;
              }
            });
    assertFatalStopCleansUp(fatal);
  }

  @Test
  public void nonfatalShutdownFailureDoesNotReplaceFatalCancellation() {
    InternalError fatal = new InternalError("fatal cancellation");
    IllegalStateException shutdownFailure = new IllegalStateException("shutdown unavailable");
    doThrow(fatal).when(pollFuture).cancel(false);
    doThrow(shutdownFailure).when(executor).shutdown();

    assertFatalStopCleansUp(fatal);

    assertThat(fatal.getSuppressed()).containsExactly(shutdownFailure);
  }

  @Test
  public void fatalHeartbeatShutdownTakesPrecedenceOverNonfatalExecutorShutdown() {
    InternalError fatal = new InternalError("fatal heartbeat shutdown");
    IllegalStateException shutdownFailure =
        new IllegalStateException("executor shutdown unavailable");
    doThrow(shutdownFailure).when(executor).shutdown();
    doThrow(fatal).when(heartbeatScheduler).shutdown();

    assertFatalStopCleansUp(fatal);

    assertThat(fatal.getSuppressed()).containsExactly(shutdownFailure);
  }

  private void assertFatalStopCleansUp(InternalError fatal) {
    recordScheduledPolls();
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    runtime.start();

    assertThatThrownBy(runtime::stop).isSameAs(fatal);

    verify(executor).shutdown();
    verify(heartbeatScheduler).shutdown();
    assertThat(registry.getMeters()).isEmpty();
    polls.get(0).run();
    assertThat(polls).hasSize(1);
  }

  @Test
  public void fatalGaugeRegistrationRollsBackEarlierRuntimeGauges() {
    InternalError fatal = new InternalError("fatal gauge registration");
    registry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.executor.active")) {
                throw fatal;
              }
            });

    assertThatThrownBy(() -> runtime(new InMemoryAsyncJobStore())).isSameAs(fatal);

    assertThat(registry.getMeters()).isEmpty();
  }

  @Test
  public void fatalRemovalListenerPropagatesAfterRemovingOtherRuntimeGauges() {
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    InternalError fatal = new InternalError("fatal gauge removal");
    registry
        .config()
        .onMeterRemoved(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.inflight")) {
                throw fatal;
              }
            });

    assertThatThrownBy(runtime::stop).isSameAs(fatal);

    assertThat(registry.getMeters()).isEmpty();
    verify(executor).shutdown();
    verify(heartbeatScheduler).shutdown();
  }

  @Test
  public void brokenTelemetryWarningDoesNotEscapeMetricGuard() {
    collide("asyncJobQueue.claim.latency");
    org.slf4j.Logger original = AsyncJobQueueRuntime.logger;
    org.slf4j.Logger failingLogger = mock(org.slf4j.Logger.class);
    doThrow(new AssertionError("log backend failed"))
        .when(failingLogger)
        .warn(anyString(), any(), any());
    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    try {
      AsyncJobQueueRuntime.logger = failingLogger;

      assertThat(runtime.pollOnce().claimedCount()).isZero();

      verify(failingLogger).warn(anyString(), any(), any());
    } finally {
      AsyncJobQueueRuntime.logger = original;
      runtime.stop();
    }
  }

  @Test
  public void oneGaugeCollisionDoesNotPreventRuntimeCreationOrRemoveAnotherOwnersMeter() {
    var otherMeter = registry.counter("asyncJobQueue.inflight", "queueName", "assetlocalize");
    recordScheduledPolls();

    AsyncJobQueueRuntime runtime = runtime(new InMemoryAsyncJobStore());
    runtime.start();
    polls.get(0).run();

    assertThat(polls).hasSize(2);
    assertThat(registry.find("asyncJobQueue.poll.active").gauge()).isNotNull();
    runtime.stop();
    assertThat(registry.getMeters())
        .containsExactlyInAnyOrder(
            otherMeter,
            registry.get("asyncJobQueue.claim.latency").timer(),
            registry.get("asyncJobQueue.poll.empty").counter());
  }

  private void collide(String name, String... extraTags) {
    registry.gauge(name, Tags.of("queueName", "assetlocalize").and(extraTags), 1);
  }

  private void recordScheduledPolls() {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              polls.add(invocation.getArgument(0));
              return pollFuture;
            })
        .when(scheduler)
        .schedule(any(Runnable.class), any(Date.class));
  }

  private AsyncJobQueueRuntime runtime(AsyncJobStore store) {
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setHeartbeatIntervalMs(0);
    settings.setShutdownAwaitTerminationMs(0);
    return new AsyncJobQueueRuntime(
        "assetlocalize",
        store,
        settings,
        mock(AsyncJobHandler.class),
        scheduler,
        executor,
        registry,
        "worker-a",
        delay -> delay,
        heartbeatScheduler);
  }
}
