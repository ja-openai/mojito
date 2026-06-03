package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class AsyncJobQueueCoordinatorTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void disabledConsumerDoesNotAllocateWorkersOrRespondToWakeups() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStore("jdbc");
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setConsumerEnabled(false);
    properties.getQueues().put("assetlocalize", settings);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            store, properties, List.of(handler("assetlocalize")), scheduler, meterRegistry) {
          @Override
          ThreadPoolTaskExecutor queueExecutor(
              String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
            throw new AssertionError("Disabled consumer must not allocate an executor");
          }

          @Override
          ThreadPoolTaskScheduler queueHeartbeatScheduler(
              String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
            throw new AssertionError("Disabled consumer must not allocate a heartbeat scheduler");
          }
        };
    try {
      coordinator.start();
      assertThat(coordinator.hasEnabledConsumers()).isFalse();
      coordinator.triggerPollNow("assetlocalize");
      assertThat(coordinator.isRunning()).isTrue();
      assertTriggerMissedCounter("assetlocalize", "consumerDisabled", 1);
      verifyNoInteractions(store, scheduler);
    } finally {
      coordinator.stop();
    }
    coordinator.triggerPollNow("assetlocalize");
    assertTriggerMissedCounter("assetlocalize", "notRunning", 1);
  }

  @Test
  public void disabledConsumerDoesNotPreventAnotherQueueFromStarting() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStore("jdbc");
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setConsumerEnabled(false);
    properties.getQueues().put("assetlocalize", settings);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    TestScheduledFuture scheduledPoll = new TestScheduledFuture();
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(ignored -> scheduledPoll);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            properties,
            List.of(handler("assetlocalize"), handler("stats")),
            scheduler,
            meterRegistry);
    try {
      coordinator.start();
      assertThat(coordinator.hasEnabledConsumers()).isTrue();
      coordinator.triggerPollNow("assetlocalize");
      verify(scheduler).schedule(any(Runnable.class), any(Date.class));
      assertThat(
              meterRegistry
                  .find("asyncJobQueue.inflight")
                  .tag("queueName", "assetlocalize")
                  .gauge())
          .isNull();
      assertThat(meterRegistry.find("asyncJobQueue.inflight").tag("queueName", "stats").gauge())
          .isNotNull();
    } finally {
      coordinator.stop();
    }
    assertThat(scheduledPoll.isCancelled()).isTrue();
  }

  @Test
  public void startStopsAlreadyStartedRuntimesWhenLaterRuntimeFails() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    TestScheduledFuture firstScheduledPoll = new TestScheduledFuture();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(invocation -> firstScheduledPoll)
        .thenThrow(new IllegalStateException("scheduler unavailable"));
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize"), handler("stats")),
            taskScheduler,
            meterRegistry);

    IllegalStateException exception = assertThrows(IllegalStateException.class, coordinator::start);

    assertThat(exception).hasMessageContaining("scheduler unavailable");
    assertThat(coordinator.isRunning()).isFalse();
    assertThat(firstScheduledPoll.isCancelled()).isTrue();
    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void startStopsAlreadyStartedRuntimesWhenLaterRuntimeThrowsNonFatalError() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    TestScheduledFuture firstScheduledPoll = new TestScheduledFuture();
    NonFatalTestError nonFatalTestError = new NonFatalTestError("scheduler invariant");
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(invocation -> firstScheduledPoll)
        .thenThrow(nonFatalTestError);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize"), handler("stats")),
            taskScheduler,
            meterRegistry);

    NonFatalTestError exception = assertThrows(NonFatalTestError.class, coordinator::start);

    assertThat(exception).isSameAs(nonFatalTestError);
    assertThat(coordinator.isRunning()).isFalse();
    assertThat(firstScheduledPoll.isCancelled()).isTrue();
    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void startRecordsRuntimeStopFailureDuringRollback() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    TestScheduledFuture firstScheduledPoll = new TestScheduledFuture();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(invocation -> firstScheduledPoll)
        .thenThrow(new IllegalStateException("scheduler unavailable"));
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize"), handler("stats")),
            taskScheduler,
            meterRegistry) {
          @Override
          ThreadPoolTaskExecutor queueExecutor(
              String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
            if ("assetlocalize".equals(queueName)) {
              return initializedExecutor(new ThrowingShutdownThreadPoolTaskExecutor());
            }
            return initializedExecutor(new ThreadPoolTaskExecutor());
          }
        };

    IllegalStateException exception = assertThrows(IllegalStateException.class, coordinator::start);

    assertThat(exception).hasMessageContaining("scheduler unavailable");
    assertThat(coordinator.isRunning()).isFalse();
    assertThat(firstScheduledPoll.isCancelled()).isTrue();
    assertThat(
            meterRegistry
                .counter("asyncJobQueue.runtime.stop.failed", "queueName", "assetlocalize")
                .count())
        .isEqualTo(1);
    verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void triggerPollNowRespectsLifecycleAndQueueName() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    List<TestScheduledFuture> scheduledPolls = new ArrayList<>();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              TestScheduledFuture scheduledFuture = new TestScheduledFuture();
              scheduledPolls.add(scheduledFuture);
              return scheduledFuture;
            });
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize")),
            taskScheduler,
            meterRegistry);

    coordinator.start();
    assertThat(scheduledPolls).hasSize(1);

    coordinator.triggerPollNow("unknown");
    assertThat(scheduledPolls).hasSize(1);
    assertTriggerMissedCounter("unknown", "missingRuntime", 1);

    coordinator.triggerPollNow("assetlocalize");
    assertThat(scheduledPolls).hasSize(2);
    assertThat(scheduledPolls.get(0).isCancelled()).isTrue();
    assertNoTriggerMissedCounter("assetlocalize", "missingRuntime");

    coordinator.stop();
    assertThat(scheduledPolls.get(1).isCancelled()).isTrue();

    coordinator.triggerPollNow("assetlocalize");
    assertThat(scheduledPolls).hasSize(2);
    assertTriggerMissedCounter("assetlocalize", "notRunning", 1);
  }

  @Test
  public void triggerPollNowRejectsInvalidQueueName() {
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize")),
            mock(TaskScheduler.class),
            meterRegistry);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> coordinator.triggerPollNow(" "));

    assertThat(exception).hasMessageContaining("queueName must not be blank");
  }

  @Test
  public void stopContinuesStoppingRuntimesWhenOneRuntimeStopFails() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    List<TestScheduledFuture> scheduledPolls = new ArrayList<>();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              TestScheduledFuture scheduledFuture = new TestScheduledFuture();
              scheduledPolls.add(scheduledFuture);
              return scheduledFuture;
            });
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize"), handler("stats")),
            taskScheduler,
            meterRegistry) {
          @Override
          ThreadPoolTaskExecutor queueExecutor(
              String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
            if ("assetlocalize".equals(queueName)) {
              return initializedExecutor(new ThrowingShutdownThreadPoolTaskExecutor());
            }
            return initializedExecutor(new ThreadPoolTaskExecutor());
          }
        };

    coordinator.start();
    assertThat(scheduledPolls).hasSize(2);

    coordinator.stop();

    assertThat(coordinator.isRunning()).isFalse();
    assertThat(scheduledPolls).allMatch(TestScheduledFuture::isCancelled);
    assertThat(
            meterRegistry
                .counter("asyncJobQueue.runtime.stop.failed", "queueName", "assetlocalize")
                .count())
        .isEqualTo(1);
  }

  @Test
  public void startHandlesNullConfiguredQueues() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    List<TestScheduledFuture> scheduledPolls = new ArrayList<>();
    when(taskScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              TestScheduledFuture scheduledFuture = new TestScheduledFuture();
              scheduledPolls.add(scheduledFuture);
              return scheduledFuture;
            });
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    asyncJobQueueProperties.setQueues(null);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            asyncJobQueueProperties,
            List.of(handler("assetlocalize")),
            taskScheduler,
            meterRegistry);

    coordinator.start();

    assertThat(coordinator.isRunning()).isTrue();
    assertThat(scheduledPolls).hasSize(1);

    coordinator.stop();
  }

  @Test
  public void startDoesNotScheduleRuntimeForConfiguredQueueWithoutHandler() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    asyncJobQueueProperties
        .getQueues()
        .put("assetlocalize", new AsyncJobQueueProperties.QueueSettings());
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            asyncJobQueueProperties,
            List.of(),
            taskScheduler,
            meterRegistry);

    coordinator.start();

    assertThat(coordinator.isRunning()).isTrue();
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Date.class));

    coordinator.stop();
  }

  @Test
  public void startRejectsInvalidHandlerQueueNameBeforeScheduling() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler(" ")),
            taskScheduler,
            meterRegistry);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, coordinator::start);

    assertThat(exception).hasMessageContaining("queueName must not be blank");
    assertThat(coordinator.isRunning()).isFalse();
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void startRejectsDuplicateHandlersForOneQueueBeforeScheduling() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize"), handler("assetlocalize")),
            taskScheduler,
            meterRegistry);

    IllegalStateException exception = assertThrows(IllegalStateException.class, coordinator::start);

    assertThat(exception)
        .hasMessageContaining("Multiple AsyncJobHandler beans registered for queue: assetlocalize");
    assertThat(coordinator.isRunning()).isFalse();
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void startRejectsInvalidConfiguredQueueNameBeforeStartingHandlers() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    asyncJobQueueProperties
        .getQueues()
        .put("x".repeat(AsyncJobQueueValidation.QUEUE_NAME_MAX_LENGTH + 1), null);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            asyncJobQueueProperties,
            List.of(handler("assetlocalize")),
            taskScheduler,
            meterRegistry);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, coordinator::start);

    assertThat(exception).hasMessageContaining("queueName must be at most 64 characters");
    assertThat(coordinator.isRunning()).isFalse();
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void startRejectsInvalidConfiguredQueueSettingsBeforeCreatingExecutor() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setMaxConcurrency(0);
    asyncJobQueueProperties.getQueues().put("assetlocalize", queueSettings);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            asyncJobQueueProperties,
            List.of(handler("assetlocalize")),
            taskScheduler,
            meterRegistry);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, coordinator::start);

    assertThat(exception).hasMessageContaining("maxConcurrency must be > 0");
    assertThat(coordinator.isRunning()).isFalse();
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void startRejectsInvalidHandlerlessQueueSettingsBeforeScheduling() {
    TaskScheduler taskScheduler = mock(TaskScheduler.class);
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setMaxConcurrency(0);
    asyncJobQueueProperties.getQueues().put("stats", queueSettings);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            asyncJobQueueProperties,
            List.of(),
            taskScheduler,
            meterRegistry);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, coordinator::start);

    assertThat(exception).hasMessageContaining("maxConcurrency must be > 0");
    assertThat(coordinator.isRunning()).isFalse();
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Date.class));
  }

  @Test
  public void completedHandlerHandoffDoesNotConsumeNextJobsAttemptBudget() throws Exception {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setMaxAttempts(1);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(invocation -> new TestScheduledFuture());
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            store, new AsyncJobQueueProperties(), List.of(), scheduler, meterRegistry);
    ThreadPoolTaskExecutor executor = coordinator.queueExecutor("assetlocalize", settings);
    CountDownLatch firstHandlerReturned = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    CountDownLatch secondHandlerReturned = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    executor.setTaskDecorator(
        task ->
            () -> {
              task.run();
              if (first.getAndSet(false)) {
                firstHandlerReturned.countDown();
                try {
                  if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Worker handoff was not released");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              } else {
                secondHandlerReturned.countDown();
              }
            });
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            store,
            settings,
            handler("assetlocalize"),
            scheduler,
            executor,
            meterRegistry,
            "worker-handoff");
    try {
      AsyncJobId firstId = store.enqueue("assetlocalize", "{}", Instant.now());
      runtime.pollOnce();
      assertThat(firstHandlerReturned.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(store.getByIds(List.of(firstId)).get(0).status()).isEqualTo(AsyncJobStatus.DONE);
      assertThat(runtime.inFlightCount()).isZero();
      assertThat(executor.getActiveCount()).isEqualTo(1);

      AsyncJobId secondId = store.enqueue("assetlocalize", "{}", Instant.now());
      runtime.pollOnce();
      assertThat(store.getByIds(List.of(secondId)).get(0).status())
          .isEqualTo(AsyncJobStatus.RUNNING);
      assertThat(runtime.inFlightCount()).isEqualTo(1);
      assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);

      AsyncJobId thirdId = store.enqueue("assetlocalize", "{}", Instant.now());
      runtime.pollOnce();
      assertThat(store.getByIds(List.of(thirdId)).get(0).attemptCount()).isZero();
      releaseWorker.countDown();
      assertThat(secondHandlerReturned.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(store.getByIds(List.of(firstId, secondId)))
          .allSatisfy(
              job -> {
                assertThat(job.status()).isEqualTo(AsyncJobStatus.DONE);
                assertThat(job.attemptCount()).isEqualTo(1);
              });
      assertThat(meterRegistry.find("asyncJobQueue.executor.rejected").counter()).isNull();
    } finally {
      releaseWorker.countDown();
      runtime.stop();
    }
  }

  @Test
  public void queueExecutorGracefullyWaitsForInFlightWorkOnShutdown() throws Exception {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setMaxConcurrency(1);
    queueSettings.setShutdownAwaitTerminationMs(1_000);
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            mock(AsyncJobStore.class),
            new AsyncJobQueueProperties(),
            List.of(handler("assetlocalize")),
            mock(TaskScheduler.class),
            meterRegistry);
    ThreadPoolTaskExecutor executor = coordinator.queueExecutor("assetlocalize", queueSettings);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean(false);

    executor.execute(
        () -> {
          started.countDown();
          try {
            Thread.sleep(100);
          } catch (InterruptedException e) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
          } finally {
            finished.countDown();
          }
        });

    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    assertThat(finished.getCount()).isEqualTo(0);
    assertThat(interrupted.get()).isFalse();
  }

  private ThreadPoolTaskExecutor initializedExecutor(ThreadPoolTaskExecutor executor) {
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.initialize();
    return executor;
  }

  private void assertTriggerMissedCounter(String queueName, String reason, double expectedCount) {
    assertThat(
            meterRegistry
                .counter("asyncJobQueue.trigger.missed", "queueName", queueName, "reason", reason)
                .count())
        .isEqualTo(expectedCount);
  }

  private void assertNoTriggerMissedCounter(String queueName, String reason) {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.trigger.missed")
                .tag("queueName", queueName)
                .tag("reason", reason)
                .counter())
        .isNull();
  }

  private AsyncJobHandler handler(String queueName) {
    return new AsyncJobHandler() {
      @Override
      public String queueName() {
        return queueName;
      }

      @Override
      public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) {
        return AsyncJobHandlerResult.done();
      }
    };
  }

  private static class NonFatalTestError extends Error {
    NonFatalTestError(String message) {
      super(message);
    }
  }

  private static class ThrowingShutdownThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
    @Override
    public void shutdown() {
      throw new IllegalStateException("executor shutdown unavailable");
    }
  }

  private static class TestScheduledFuture implements ScheduledFuture<Object> {

    private boolean cancelled;

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
      return cancelled;
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

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }
  }
}
