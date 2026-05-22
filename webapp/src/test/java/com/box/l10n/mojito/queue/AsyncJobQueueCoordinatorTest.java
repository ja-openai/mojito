package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
