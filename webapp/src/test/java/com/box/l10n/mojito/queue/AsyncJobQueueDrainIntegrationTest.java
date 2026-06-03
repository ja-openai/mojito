package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class AsyncJobQueueDrainIntegrationTest {

  @Test
  public void producerOnlyNodeCanSubmitAndSeparateConsumerDrainsReadyAndDelayedWork()
      throws Exception {
    assertProducerOnlyNodeCanDrain(new InMemoryAsyncJobStore());
  }

  static void assertProducerOnlyNodeCanDrain(AsyncJobStore store) throws Exception {
    String queueName = "producer-drain";
    SimpleMeterRegistry producerRegistry = new SimpleMeterRegistry();
    SimpleMeterRegistry consumerRegistry = new SimpleMeterRegistry();
    TaskScheduler producerScheduler = mock(TaskScheduler.class);
    ThreadPoolTaskScheduler consumerScheduler = new ThreadPoolTaskScheduler();
    consumerScheduler.initialize();
    CountDownLatch completed = new CountDownLatch(2);
    AsyncJobHandler producerHandler =
        new AsyncJobHandler() {
          @Override
          public String queueName() {
            return queueName;
          }

          @Override
          public AsyncJobHandlerResult process(AsyncJobRecord job) {
            throw new AssertionError("A producer-only node must not execute business work");
          }
        };
    AsyncJobHandler consumerHandler =
        new AsyncJobHandler() {
          @Override
          public String queueName() {
            return queueName;
          }

          @Override
          public AsyncJobHandlerResult process(AsyncJobRecord job) {
            return AsyncJobHandlerResult.done();
          }

          @Override
          public void onJobDone(AsyncJobRecord job, AsyncJobHandlerResult result) {
            completed.countDown();
          }
        };
    AsyncJobQueueCoordinator producer =
        new AsyncJobQueueCoordinator(
            store,
            properties(queueName, false),
            List.of(producerHandler),
            producerScheduler,
            producerRegistry);
    AsyncJobQueueCoordinator consumer =
        new AsyncJobQueueCoordinator(
            store,
            properties(queueName, true),
            List.of(consumerHandler),
            consumerScheduler,
            consumerRegistry);
    AsyncJobQueueWakeupNotifier notifier = mock(AsyncJobQueueWakeupNotifier.class);
    AsyncJobQueueSubmissionService submission =
        new AsyncJobQueueSubmissionService(
            store, producer, notifier, producerRegistry, Clock.systemUTC());
    try {
      producer.start();
      AsyncJobId ready = submission.enqueueNow(queueName, "{}");
      AsyncJobId delayed = submission.enqueue(queueName, "{}", Instant.now().plusSeconds(1));
      assertThat(store.getByIds(List.of(ready, delayed)))
          .hasSize(2)
          .allSatisfy(
              job -> {
                assertThat(job.status()).isEqualTo(AsyncJobStatus.QUEUED);
                assertThat(job.attemptCount()).isZero();
              });
      verify(notifier).notifyJobAvailable(queueName, ready);
      verifyNoInteractions(producerScheduler);

      // Producer-local consumption stays disabled while another node drains accepted work.
      consumer.start();
      assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(store.getByIds(List.of(ready, delayed)))
          .hasSize(2)
          .allSatisfy(
              job -> {
                assertThat(job.status()).isEqualTo(AsyncJobStatus.DONE);
                assertThat(job.attemptCount()).isEqualTo(1);
              });
      verifyNoInteractions(producerScheduler);
      assertThat(producerRegistry.find("asyncJobQueue.inflight").gauge()).isNull();
      assertThat(consumerRegistry.get("asyncJobQueue.completed").counter().count()).isEqualTo(2);
    } finally {
      consumer.stop();
      producer.stop();
      consumerScheduler.shutdown();
      consumerRegistry.close();
      producerRegistry.close();
    }
  }

  private static AsyncJobQueueProperties properties(String queueName, boolean consumerEnabled) {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    // The unit test uses a shared double; the database contract exercises this with JDBC too.
    properties.setStore("jdbc");
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setConsumerEnabled(consumerEnabled);
    settings.setMaxConcurrency(1);
    settings.setPollIntervalMs(10);
    settings.setMaxPollIntervalMs(50);
    settings.setHeartbeatIntervalMs(20);
    settings.setLeaseDurationMs(1000);
    properties.getQueues().put(queueName, settings);
    return properties;
  }
}
