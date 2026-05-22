package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;

public class AsyncJobQueueCoordinatorTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
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

    coordinator.triggerPollNow("assetlocalize");
    assertThat(scheduledPolls).hasSize(2);
    assertThat(scheduledPolls.get(0).isCancelled()).isTrue();

    coordinator.stop();
    assertThat(scheduledPolls.get(1).isCancelled()).isTrue();

    coordinator.triggerPollNow("assetlocalize");
    assertThat(scheduledPolls).hasSize(2);
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
