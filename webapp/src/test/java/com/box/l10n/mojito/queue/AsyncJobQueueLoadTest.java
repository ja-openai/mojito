package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsyncJobQueueLoadTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ThreadPoolTaskExecutor executor = newExecutor(8);

  @After
  public void tearDown() {
    executor.shutdown();
    meterRegistry.close();
  }

  @Test
  public void stressProcessesHundredsOfJobsWithinBoundedTimeWithoutDuplicates() throws Exception {
    int jobCount = 250;
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    for (int i = 0; i < jobCount; i++) {
      inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":" + i + "}", Instant.EPOCH);
    }

    Set<AsyncJobId> processedJobIds = ConcurrentHashMap.newKeySet();
    AtomicInteger duplicateExecutions = new AtomicInteger();
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            inMemoryAsyncJobStore,
            queueSettings(),
            new AsyncJobHandler() {
              @Override
              public String queueName() {
                return "assetlocalize";
              }

              @Override
              public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) {
                if (!processedJobIds.add(asyncJobRecord.id())) {
                  duplicateExecutions.incrementAndGet();
                }
                return AsyncJobHandlerResult.done();
              }
            },
            mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");

    long startedAt = System.nanoTime();
    long timeoutAt = System.currentTimeMillis() + 10_000;
    while (doneCount(inMemoryAsyncJobStore) < jobCount && System.currentTimeMillis() < timeoutAt) {
      asyncJobQueueRuntime.pollOnce();
      Thread.sleep(1);
    }
    long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    assertThat(doneCount(inMemoryAsyncJobStore)).isEqualTo(jobCount);
    assertThat(processedJobIds).hasSize(jobCount);
    assertThat(duplicateExecutions.get()).isZero();
    assertThat(elapsedMs).isLessThan(10_000);
  }

  private AsyncJobQueueProperties.QueueSettings queueSettings() {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollIntervalMs(1);
    queueSettings.setMaxPollIntervalMs(100);
    queueSettings.setClaimBatchSize(16);
    queueSettings.setMaxConcurrency(8);
    queueSettings.setLeaseDurationMs(10_000);
    queueSettings.setHeartbeatIntervalMs(0);
    queueSettings.setMaxAttempts(2);
    return queueSettings;
  }

  private long doneCount(InMemoryAsyncJobStore inMemoryAsyncJobStore) {
    return inMemoryAsyncJobStore.countByStatus("assetlocalize").stream()
        .filter(statusCount -> statusCount.status() == AsyncJobStatus.DONE)
        .mapToLong(AsyncJobStatusCount::count)
        .sum();
  }

  private ThreadPoolTaskExecutor newExecutor(int concurrency) {
    ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
    threadPoolTaskExecutor.setCorePoolSize(concurrency);
    threadPoolTaskExecutor.setMaxPoolSize(concurrency);
    threadPoolTaskExecutor.setQueueCapacity(0);
    threadPoolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    threadPoolTaskExecutor.initialize();
    return threadPoolTaskExecutor;
  }
}
