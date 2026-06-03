package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class AsyncJobQueueCoordinatorLifecycleTest {

  @Test
  public void contextCloseDrainsLastAttemptBufferedJobWithLiveHeartbeats() throws Exception {
    CountDownLatch firstReturned = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    CountDownLatch drainStarted = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondRenewed = new CountDownLatch(1);
    CountDownLatch finishSecond = new CountDownLatch(1);
    AtomicReference<AsyncJobId> secondId = new AtomicReference<>();
    InMemoryAsyncJobStore store =
        new InMemoryAsyncJobStore() {
          @Override
          public boolean heartbeat(
              String queueName, AsyncJobId id, String workerId, String token, Duration duration) {
            boolean renewed = super.heartbeat(queueName, id, workerId, token, duration);
            if (renewed && id.equals(secondId.get())) {
              secondRenewed.countDown();
            }
            return renewed;
          }
        };
    AsyncJobId firstId = store.enqueueNow("assetlocalize", "{}");
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setMaxAttempts(1);
    settings.setHeartbeatIntervalMs(20);
    settings.setShutdownAwaitTerminationMs(10_000);
    properties.getQueues().put("assetlocalize", settings);
    AsyncJobHandler handler =
        new AsyncJobHandler() {
          @Override
          public String queueName() {
            return "assetlocalize";
          }

          @Override
          public AsyncJobHandlerResult process(AsyncJobRecord job) throws Exception {
            if (!job.id().equals(firstId)) {
              secondStarted.countDown();
              assertThat(finishSecond.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return AsyncJobHandlerResult.done();
          }
        };
    ThreadPoolTaskScheduler sharedScheduler = new ThreadPoolTaskScheduler();
    sharedScheduler.setPoolSize(5);
    AtomicReference<ThreadPoolTaskExecutor> executor = new AtomicReference<>();
    AtomicReference<ThreadPoolTaskScheduler> heartbeatScheduler = new AtomicReference<>();
    FutureTask<Void> closeContext = null;
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (GenericApplicationContext context = new GenericApplicationContext()) {
      AsyncJobQueueCoordinator coordinator =
          new AsyncJobQueueCoordinator(
              store, properties, List.of(handler), sharedScheduler, registry) {
            @Override
            ThreadPoolTaskExecutor queueExecutor(
                String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
              ThreadPoolTaskExecutor result = super.queueExecutor(queueName, queueSettings);
              AtomicBoolean first = new AtomicBoolean(true);
              result.setTaskDecorator(
                  task ->
                      () -> {
                        task.run();
                        if (first.getAndSet(false)) {
                          firstReturned.countDown();
                          try {
                            assertThat(releaseWorker.await(10, TimeUnit.SECONDS)).isTrue();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                          }
                        }
                      });
              executor.set(result);
              return result;
            }

            @Override
            ThreadPoolTaskScheduler queueHeartbeatScheduler(
                String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
              ThreadPoolTaskScheduler result =
                  super.queueHeartbeatScheduler(queueName, queueSettings);
              heartbeatScheduler.set(result);
              return result;
            }

            @Override
            public void stop() {
              drainStarted.countDown();
              super.stop();
            }
          };
      context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class, () -> sharedScheduler);
      context.registerBean("coordinator", AsyncJobQueueCoordinator.class, () -> coordinator);
      context.refresh();
      try {
        assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(1);
        assertThat(firstReturned.await(5, TimeUnit.SECONDS)).isTrue();
        secondId.set(store.enqueueNow("assetlocalize", "{}"));
        coordinator.triggerPollNow("assetlocalize");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.get().getThreadPoolExecutor().getQueue().isEmpty()
            && System.nanoTime() < deadline) {
          Thread.sleep(5);
        }
        assertThat(executor.get().getThreadPoolExecutor().getQueue()).hasSize(1);
        assertThat(store.getByIds(List.of(secondId.get())).get(0).attemptCount()).isEqualTo(1);

        closeContext =
            new FutureTask<>(
                () -> {
                  context.close();
                  return null;
                });
        new Thread(closeContext, "async-queue-context-close-test").start();
        assertThat(drainStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sharedScheduler.getScheduledExecutor().isShutdown()).isTrue();
        releaseWorker.countDown();
        assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondRenewed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(heartbeatScheduler.get().getScheduledExecutor().isShutdown()).isFalse();
        assertThat(closeContext.isDone()).isFalse();
        finishSecond.countDown();
        closeContext.get(5, TimeUnit.SECONDS);

        assertThat(store.getByIds(List.of(firstId, secondId.get())))
            .allSatisfy(
                job -> {
                  assertThat(job.status()).isEqualTo(AsyncJobStatus.DONE);
                  assertThat(job.attemptCount()).isEqualTo(1);
                });
        assertThat(registry.find("asyncJobQueue.heartbeat.schedule.failed").counter()).isNull();
        assertThat(registry.find("asyncJobQueue.executor.rejected").counter()).isNull();
        assertThat(executor.get().getThreadPoolExecutor().isTerminated()).isTrue();
        assertThat(
                heartbeatScheduler
                    .get()
                    .getScheduledExecutor()
                    .awaitTermination(5, TimeUnit.SECONDS))
            .isTrue();
      } finally {
        releaseWorker.countDown();
        finishSecond.countDown();
        if (closeContext != null) {
          closeContext.get(5, TimeUnit.SECONDS);
        }
      }
    } finally {
      registry.close();
    }
  }

  @Test
  public void failedStartupClosesOwnedHeartbeatSchedulers() {
    TaskScheduler sharedScheduler = mock(TaskScheduler.class);
    when(sharedScheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenThrow(new IllegalStateException("cannot schedule initial poll"));
    List<ThreadPoolTaskScheduler> heartbeatSchedulers = new ArrayList<>();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      AsyncJobQueueCoordinator coordinator =
          new AsyncJobQueueCoordinator(
              new InMemoryAsyncJobStore(),
              new AsyncJobQueueProperties(),
              List.of(handler()),
              sharedScheduler,
              registry) {
            @Override
            ThreadPoolTaskScheduler queueHeartbeatScheduler(
                String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
              ThreadPoolTaskScheduler scheduler =
                  super.queueHeartbeatScheduler(queueName, queueSettings);
              heartbeatSchedulers.add(scheduler);
              return scheduler;
            }
          };
      assertThrows(IllegalStateException.class, coordinator::start);
      assertThat(heartbeatSchedulers).hasSize(1);
      assertThat(heartbeatSchedulers.get(0).getScheduledExecutor().isShutdown()).isTrue();
    } finally {
      registry.close();
    }
  }

  @Test
  public void heartbeatInitializationFailureClosesSchedulerAndExecutor() {
    ThreadPoolTaskScheduler heartbeatScheduler =
        new ThreadPoolTaskScheduler() {
          @Override
          public void initialize() {
            super.initialize();
            throw new IllegalStateException("heartbeat initialization failed");
          }
        };
    AtomicReference<ThreadPoolTaskExecutor> executor = new AtomicReference<>();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      AsyncJobQueueCoordinator coordinator =
          new AsyncJobQueueCoordinator(
              new InMemoryAsyncJobStore(),
              new AsyncJobQueueProperties(),
              List.of(handler()),
              mock(TaskScheduler.class),
              registry) {
            @Override
            ThreadPoolTaskScheduler queueHeartbeatScheduler(
                String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
              return heartbeatScheduler;
            }

            @Override
            ThreadPoolTaskExecutor queueExecutor(
                String queueName, AsyncJobQueueProperties.QueueSettings queueSettings) {
              ThreadPoolTaskExecutor result = super.queueExecutor(queueName, queueSettings);
              executor.set(result);
              return result;
            }
          };
      assertThat(assertThrows(IllegalStateException.class, coordinator::start))
          .hasMessage("heartbeat initialization failed");
      assertThat(heartbeatScheduler.getScheduledExecutor().isShutdown()).isTrue();
      assertThat(executor.get().getThreadPoolExecutor().isShutdown()).isTrue();
      assertThat(coordinator.isRunning()).isFalse();
    } finally {
      heartbeatScheduler.shutdown();
      if (executor.get() != null) {
        executor.get().shutdown();
      }
      registry.close();
    }
  }

  @Test
  public void executorShutdownFailureStillClosesOwnedHeartbeatScheduler() {
    ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    doThrow(new IllegalStateException("cannot shut down executor")).when(executor).shutdown();
    ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
    heartbeatScheduler.initialize();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              "assetlocalize",
              new InMemoryAsyncJobStore(),
              new AsyncJobQueueProperties.QueueSettings(),
              handler(),
              mock(TaskScheduler.class),
              executor,
              registry,
              "worker-test",
              null,
              heartbeatScheduler);
      assertThrows(IllegalStateException.class, runtime::stop);
      assertThat(heartbeatScheduler.getScheduledExecutor().isShutdown()).isTrue();
      assertThat(registry.find("asyncJobQueue.inflight").gauge()).isNull();
    } finally {
      heartbeatScheduler.shutdown();
      registry.close();
    }
  }

  private AsyncJobHandler handler() {
    return new AsyncJobHandler() {
      @Override
      public String queueName() {
        return "assetlocalize";
      }

      @Override
      public AsyncJobHandlerResult process(AsyncJobRecord job) {
        return AsyncJobHandlerResult.done();
      }
    };
  }
}
