package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class AsyncJobQueueCoordinatorQuiesceTest {

  @Test
  public void stopQuiescesAllQueuesBeforeDrainingTheFirstExecutor() throws Exception {
    assertQuiesceBeforeDrain(false, false);
  }

  @Test
  public void startupRollbackQuiescesAllStartedQueuesBeforeDraining() throws Exception {
    assertQuiesceBeforeDrain(true, false);
  }

  @Test
  public void pollCancellationFailureDoesNotLeaveLaterQueuesPollingDuringDrain() throws Exception {
    assertQuiesceBeforeDrain(false, true);
  }

  private void assertQuiesceBeforeDrain(boolean failStartup, boolean failCancellation)
      throws Exception {
    CountDownLatch handlersStarted = new CountDownLatch(2);
    CountDownLatch firstDrainStarted = new CountDownLatch(1);
    CountDownLatch allowExecutorShutdown = new CountDownLatch(1);
    CountDownLatch finishHandlers = new CountDownLatch(1);
    CountDownLatch firstRenewedDuringDrain = new CountDownLatch(1);
    CountDownLatch secondRenewedDuringDrain = new CountDownLatch(1);
    CountDownLatch lateCompleted = new CountDownLatch(1);
    AtomicBoolean observingDrain = new AtomicBoolean();
    AtomicBoolean failNextStartup = new AtomicBoolean(failStartup);
    AtomicInteger handlerCalls = new AtomicInteger();
    AtomicReference<AsyncJobId> lateId = new AtomicReference<>();
    List<ScheduledPoll> polls = new CopyOnWriteArrayList<>();
    List<ThreadPoolTaskExecutor> executors = new CopyOnWriteArrayList<>();
    List<ThreadPoolTaskScheduler> heartbeats = new CopyOnWriteArrayList<>();
    InMemoryAsyncJobStore store =
        new InMemoryAsyncJobStore() {
          @Override
          public boolean heartbeat(
              String queue, AsyncJobId id, String worker, String token, Duration lease) {
            boolean renewed = super.heartbeat(queue, id, worker, token, lease);
            if (renewed && observingDrain.get()) {
              (queue.equals("first") ? firstRenewedDuringDrain : secondRenewedDuringDrain)
                  .countDown();
            }
            return renewed;
          }
        };
    AsyncJobId first = store.enqueueNow("first", "existing-first");
    AsyncJobId second = store.enqueueNow("second", "existing-second");
    List<AsyncJobHandler> handlers =
        (failStartup ? List.of("first", "second", "third") : List.of("first", "second"))
            .stream()
                .map(
                    queue ->
                        (AsyncJobHandler)
                            new AsyncJobHandler() {
                              @Override
                              public String queueName() {
                                return queue;
                              }

                              @Override
                              public AsyncJobHandlerResult process(AsyncJobRecord job)
                                  throws Exception {
                                handlerCalls.incrementAndGet();
                                handlersStarted.countDown();
                                assertThat(finishHandlers.await(15, TimeUnit.SECONDS)).isTrue();
                                return AsyncJobHandlerResult.done();
                              }

                              @Override
                              public void onJobDone(
                                  AsyncJobRecord job, AsyncJobHandlerResult result) {
                                if (job.id().equals(lateId.get())) {
                                  lateCompleted.countDown();
                                }
                              }
                            })
                .toList();
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    for (AsyncJobHandler handler : handlers) {
      AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
      settings.setMaxConcurrency(2);
      settings.setMaxAttempts(1);
      settings.setHeartbeatIntervalMs(20);
      settings.setShutdownAwaitTerminationMs(5_000);
      properties.getQueues().put(handler.queueName(), settings);
    }
    TaskScheduler scheduler = mock(TaskScheduler.class);
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(
            invocation -> {
              ScheduledFuture<?> future = mock(ScheduledFuture.class);
              polls.add(new ScheduledPoll(invocation.getArgument(0), future));
              return future;
            });
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IllegalStateException startupFailure = new IllegalStateException("third queue startup failed");
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(store, properties, handlers, scheduler, registry) {
          @Override
          ThreadPoolTaskExecutor queueExecutor(
              String name, AsyncJobQueueProperties.QueueSettings settings) {
            if (name.equals("third") && failNextStartup.getAndSet(false)) {
              // Start both existing jobs before rollback reaches the first executor.
              polls.get(0).action().run();
              polls.get(1).action().run();
              awaitStarted(handlersStarted);
              throw startupFailure;
            }
            boolean firstExecutor = executors.isEmpty();
            ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor() {
                  @Override
                  public void shutdown() {
                    try {
                      if (firstExecutor) {
                        observingDrain.set(true);
                        firstDrainStarted.countDown();
                        // Observe polling/renewals before the bounded executor wait starts.
                        assertThat(allowExecutorShutdown.await(15, TimeUnit.SECONDS)).isTrue();
                      }
                    } catch (InterruptedException failure) {
                      Thread.currentThread().interrupt();
                      throw new AssertionError(failure);
                    } finally {
                      super.shutdown();
                    }
                  }
                };
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(2);
            executor.setQueueCapacity(2);
            executor.setDaemon(true);
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationMillis(settings.getShutdownAwaitTerminationMs());
            executor.initialize();
            executors.add(executor);
            return executor;
          }

          @Override
          ThreadPoolTaskScheduler queueHeartbeatScheduler(
              String name, AsyncJobQueueProperties.QueueSettings settings) {
            ThreadPoolTaskScheduler heartbeat = super.queueHeartbeatScheduler(name, settings);
            heartbeat.setDaemon(true);
            heartbeats.add(heartbeat);
            return heartbeat;
          }
        };
    FutureTask<Void> lifecycle =
        new FutureTask<>(
            () -> {
              if (failStartup) {
                assertThat(assertThrows(IllegalStateException.class, coordinator::start))
                    .isSameAs(startupFailure);
              } else {
                coordinator.start();
                polls.get(0).action().run();
                polls.get(1).action().run();
                awaitStarted(handlersStarted);
                if (failCancellation) {
                  doThrow(new IllegalStateException("cancel failed"))
                      .when(polls.get(2).future())
                      .cancel(false);
                }
                coordinator.stop();
              }
              return null;
            });
    Thread lifecycleThread = new Thread(lifecycle, "queue-quiesce-test");
    lifecycleThread.setDaemon(true);
    try {
      lifecycleThread.start();
      assertThat(firstDrainStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(lifecycle.isDone()).isFalse();
      assertThat(coordinator.isRunning()).isFalse();
      lateId.set(store.enqueueNow("second", "accepted-during-drain"));
      // Even a callback already handed off by the scheduler must respect stopped polling.
      polls.get(3).action().run();
      AsyncJobRecord late = store.getByIds(List.of(lateId.get())).getFirst();
      assertThat(late.status()).isEqualTo(AsyncJobStatus.QUEUED);
      assertThat(late.attemptCount()).isZero();
      assertThat(handlerCalls).hasValue(2);
      verify(polls.get(2).future()).cancel(false);
      verify(polls.get(3).future()).cancel(false);
      assertThat(firstRenewedDuringDrain.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(secondRenewedDuringDrain.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(heartbeats)
          .allSatisfy(h -> assertThat(h.getScheduledExecutor().isShutdown()).isFalse());

      finishHandlers.countDown();
      allowExecutorShutdown.countDown();
      lifecycle.get(10, TimeUnit.SECONDS);
      assertThat(store.getByIds(List.of(first, second)))
          .allSatisfy(job -> assertThat(job.status()).isEqualTo(AsyncJobStatus.DONE));
      assertThat(executors)
          .allSatisfy(e -> assertThat(e.getThreadPoolExecutor().isTerminated()).isTrue());
      for (ThreadPoolTaskScheduler heartbeat : heartbeats) {
        assertThat(heartbeat.getScheduledExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(registry.find("asyncJobQueue.inflight").gauges()).isEmpty();

      int restartPoll = polls.size();
      coordinator.start();
      polls.get(restartPoll + 1).action().run();
      assertThat(lateCompleted.await(5, TimeUnit.SECONDS)).isTrue();
      AsyncJobRecord completed = store.getByIds(List.of(lateId.get())).getFirst();
      assertThat(completed.status()).isEqualTo(AsyncJobStatus.DONE);
      assertThat(completed.attemptCount()).isEqualTo(1);
      assertThat(handlerCalls).hasValue(3);
    } finally {
      finishHandlers.countDown();
      allowExecutorShutdown.countDown();
      try {
        lifecycleThread.join(20_000);
        if (!lifecycleThread.isAlive()) {
          coordinator.stop();
        }
      } finally {
        try {
          for (ThreadPoolTaskScheduler heartbeat : heartbeats) {
            heartbeat.shutdown();
          }
          for (ThreadPoolTaskExecutor executor : executors) {
            executor.getThreadPoolExecutor().shutdownNow();
          }
          for (ThreadPoolTaskExecutor executor : executors) {
            assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS))
                .isTrue();
          }
          assertThat(lifecycleThread.isAlive()).isFalse();
        } finally {
          registry.close();
        }
      }
    }
  }

  private static void awaitStarted(CountDownLatch started) {
    try {
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  private record ScheduledPoll(Runnable action, ScheduledFuture<?> future) {}
}
