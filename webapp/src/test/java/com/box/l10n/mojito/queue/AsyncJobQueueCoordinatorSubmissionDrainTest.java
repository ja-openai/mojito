package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class AsyncJobQueueCoordinatorSubmissionDrainTest {

  @Test
  public void handlerCanSubmitDuringStopAndRestartDrainsChild() throws Exception {
    assertSubmissionDuringDrain(false);
  }

  @Test
  public void handlerCanSubmitDuringStartupRollbackAndRestartDrainsChild() throws Exception {
    assertSubmissionDuringDrain(true);
  }

  private void assertSubmissionDuringDrain(boolean failStartup) throws Exception {
    String queueName = "submission-drain";
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobId parent = store.enqueueNow(queueName, "parent");
    CountDownLatch parentStarted = new CountDownLatch(1);
    CountDownLatch drainStarted = new CountDownLatch(1);
    CountDownLatch childCompleted = new CountDownLatch(1);
    AtomicReference<AsyncJobId> child = new AtomicReference<>();
    AtomicReference<AsyncJobQueueSubmissionService> submission = new AtomicReference<>();
    AtomicBoolean drainedBeforeShutdownReturned = new AtomicBoolean();
    AtomicBoolean failNextStartup = new AtomicBoolean(failStartup);
    IllegalStateException startupFailure = new IllegalStateException("second queue cannot start");
    List<ThreadPoolTaskExecutor> executors = new CopyOnWriteArrayList<>();
    List<ThreadPoolTaskScheduler> heartbeatSchedulers = new CopyOnWriteArrayList<>();
    AsyncJobHandler handler =
        new AsyncJobHandler() {
          @Override
          public String queueName() {
            return queueName;
          }

          @Override
          public AsyncJobHandlerResult process(AsyncJobRecord job) throws Exception {
            if (job.id().equals(parent)) {
              parentStarted.countDown();
              assertThat(drainStarted.await(15, TimeUnit.SECONDS)).isTrue();
              child.set(submission.get().enqueueNow(queueName, "child"));
            }
            return AsyncJobHandlerResult.done();
          }

          @Override
          public void onJobDone(AsyncJobRecord job, AsyncJobHandlerResult result) {
            if (job.id().equals(child.get())) {
              childCompleted.countDown();
            }
          }
        };
    AsyncJobHandler secondHandler =
        new AsyncJobHandler() {
          @Override
          public String queueName() {
            return "second-queue";
          }

          @Override
          public AsyncJobHandlerResult process(AsyncJobRecord job) {
            throw new AssertionError("No job submitted to second queue");
          }
        };
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setMaxAttempts(1);
    settings.setShutdownAwaitTerminationMs(5_000);
    properties.getQueues().put(queueName, settings);
    ThreadPoolTaskScheduler sharedScheduler = new ThreadPoolTaskScheduler();
    sharedScheduler.setDaemon(true);
    sharedScheduler.initialize();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AsyncJobQueueCoordinator coordinator =
        new AsyncJobQueueCoordinator(
            store,
            properties,
            failStartup ? List.of(handler, secondHandler) : List.of(handler),
            sharedScheduler,
            registry) {
          @Override
          ThreadPoolTaskExecutor queueExecutor(
              String name, AsyncJobQueueProperties.QueueSettings queueSettings) {
            if (name.equals(secondHandler.queueName()) && failNextStartup.getAndSet(false)) {
              try {
                assertThat(parentStarted.await(10, TimeUnit.SECONDS)).isTrue();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
              throw startupFailure;
            }
            boolean firstExecutor = executors.isEmpty();
            ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor() {
                  @Override
                  public void shutdown() {
                    if (firstExecutor) {
                      // The parent submits only after the coordinator begins worker drain.
                      drainStarted.countDown();
                    }
                    super.shutdown();
                    if (firstExecutor) {
                      drainedBeforeShutdownReturned.set(getThreadPoolExecutor().isTerminated());
                    }
                  }
                };
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(1);
            executor.setDaemon(true);
            executor.setWaitForTasksToCompleteOnShutdown(true);
            executor.setAwaitTerminationMillis(queueSettings.getShutdownAwaitTerminationMs());
            executor.initialize();
            executors.add(executor);
            return executor;
          }

          @Override
          ThreadPoolTaskScheduler queueHeartbeatScheduler(
              String name, AsyncJobQueueProperties.QueueSettings queueSettings) {
            ThreadPoolTaskScheduler scheduler = super.queueHeartbeatScheduler(name, queueSettings);
            scheduler.setDaemon(true);
            heartbeatSchedulers.add(scheduler);
            return scheduler;
          }
        };
    submission.set(new AsyncJobQueueSubmissionService(store, coordinator, registry));
    FutureTask<Void> lifecycle =
        new FutureTask<>(
            () -> {
              if (failStartup) {
                assertThat(assertThrows(IllegalStateException.class, coordinator::start))
                    .isSameAs(startupFailure);
              } else {
                coordinator.start();
                assertThat(parentStarted.await(10, TimeUnit.SECONDS)).isTrue();
                coordinator.stop();
              }
              return null;
            });
    Thread lifecycleThread = new Thread(lifecycle, "async-queue-submission-drain-test");
    lifecycleThread.setDaemon(true);
    try {
      lifecycleThread.start();
      lifecycle.get(20, TimeUnit.SECONDS);
      assertThat(executors.get(0).getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS))
          .isTrue();
      assertThat(drainedBeforeShutdownReturned)
          .as("The handler must finish before executor shutdown returns, not after its timeout")
          .isTrue();
      assertThat(coordinator.isRunning()).isFalse();
      assertThat(sharedScheduler.getScheduledExecutor().isShutdown()).isFalse();
      assertThat(store.getByIds(List.of(parent)).get(0).status()).isEqualTo(AsyncJobStatus.DONE);
      assertThat(child.get()).isNotNull();
      AsyncJobRecord acceptedChild = store.getByIds(List.of(child.get())).get(0);
      assertThat(acceptedChild.status()).isEqualTo(AsyncJobStatus.QUEUED);
      assertThat(acceptedChild.attemptCount()).isZero();
      assertThat(
              registry
                  .get("asyncJobQueue.trigger.missed")
                  .tags("queueName", queueName, "reason", "notRunning")
                  .counter()
                  .count())
          .isEqualTo(1);
      assertThat(registry.find("asyncJobQueue.inflight").gauge()).isNull();

      coordinator.start();
      assertThat(coordinator.isRunning()).isTrue();
      assertThat(childCompleted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(store.getByIds(List.of(parent, child.get())))
          .hasSize(2)
          .allSatisfy(
              job -> {
                assertThat(job.status()).isEqualTo(AsyncJobStatus.DONE);
                assertThat(job.attemptCount()).isEqualTo(1);
              });
    } finally {
      drainStarted.countDown();
      try {
        lifecycleThread.join(20_000);
        // A failed join must not block this thread on the same coordinator lock under test.
        if (!lifecycleThread.isAlive()) {
          coordinator.stop();
        }
      } finally {
        try {
          sharedScheduler.shutdown();
          for (ThreadPoolTaskScheduler scheduler : heartbeatSchedulers) {
            scheduler.shutdown();
          }
          for (ThreadPoolTaskExecutor executor : executors) {
            executor.getThreadPoolExecutor().shutdownNow();
          }
          boolean terminated = true;
          for (ThreadPoolTaskExecutor executor : executors) {
            terminated &= executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);
          }
          assertThat(lifecycleThread.isAlive()).as("Lifecycle thread must terminate").isFalse();
          assertThat(terminated).as("All worker executors must terminate").isTrue();
        } finally {
          registry.close();
        }
      }
    }
  }
}
