package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsyncJobQueueLoadTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ThreadPoolTaskExecutor executor = newExecutor(8);
  private Logger originalRuntimeLogger;

  @Before
  public void muteExpectedFailureLogs() {
    originalRuntimeLogger = AsyncJobQueueRuntime.logger;
    AsyncJobQueueRuntime.logger = mock(Logger.class);
  }

  @After
  public void tearDown() {
    AsyncJobQueueRuntime.logger = originalRuntimeLogger;
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

  @Test
  public void stressRetriesTransientFailuresWithinBoundedTimeWithoutDuplicates() throws Exception {
    int jobCount = 120;
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    for (int i = 0; i < jobCount; i++) {
      inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":" + i + "}", Instant.EPOCH);
    }

    ConcurrentHashMap<AsyncJobId, AtomicInteger> attemptsById = new ConcurrentHashMap<>();
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings();
    queueSettings.setMaxAttempts(3);
    queueSettings.setRetryJitterPercent(0);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            inMemoryAsyncJobStore,
            queueSettings,
            new AsyncJobHandler() {
              @Override
              public String queueName() {
                return "assetlocalize";
              }

              @Override
              public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) {
                int attempt =
                    attemptsById
                        .computeIfAbsent(asyncJobRecord.id(), ignored -> new AtomicInteger())
                        .incrementAndGet();
                if (attempt == 1) {
                  throw new IllegalStateException("transient");
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
    while (statusCount(inMemoryAsyncJobStore, AsyncJobStatus.DONE) < jobCount
        && System.currentTimeMillis() < timeoutAt) {
      asyncJobQueueRuntime.pollOnce();
      Thread.sleep(1);
    }
    long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    waitForCounter("asyncJobQueue.completed", jobCount);
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.DONE)).isEqualTo(jobCount);
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.QUEUED)).isZero();
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.RUNNING)).isZero();
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.FAILED)).isZero();
    assertThat(attemptsById).hasSize(jobCount);
    assertThat(attemptsById.values())
        .allSatisfy(attempts -> assertThat(attempts.get()).isEqualTo(2));
    assertThat(counterCount("asyncJobQueue.handler.failed")).isEqualTo(jobCount);
    assertThat(counterCount("asyncJobQueue.retried")).isEqualTo(jobCount);
    assertThat(elapsedMs).isLessThan(10_000);
  }

  @Test
  public void stressFailsPoisonJobsTerminallyWithinAttemptBudget() throws Exception {
    int jobCount = 80;
    InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();
    for (int i = 0; i < jobCount; i++) {
      inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"id\":" + i + "}", Instant.EPOCH);
    }

    ConcurrentHashMap<AsyncJobId, AtomicInteger> attemptsById = new ConcurrentHashMap<>();
    AsyncJobQueueProperties.QueueSettings queueSettings = queueSettings();
    queueSettings.setMaxAttempts(2);
    queueSettings.setRetryJitterPercent(0);
    AsyncJobQueueRuntime asyncJobQueueRuntime =
        new AsyncJobQueueRuntime(
            "assetlocalize",
            inMemoryAsyncJobStore,
            queueSettings,
            new AsyncJobHandler() {
              @Override
              public String queueName() {
                return "assetlocalize";
              }

              @Override
              public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) {
                attemptsById
                    .computeIfAbsent(asyncJobRecord.id(), ignored -> new AtomicInteger())
                    .incrementAndGet();
                throw new IllegalStateException("poison");
              }
            },
            mock(TaskScheduler.class),
            executor,
            meterRegistry,
            "worker-a");

    long startedAt = System.nanoTime();
    long timeoutAt = System.currentTimeMillis() + 10_000;
    while (statusCount(inMemoryAsyncJobStore, AsyncJobStatus.FAILED) < jobCount
        && System.currentTimeMillis() < timeoutAt) {
      asyncJobQueueRuntime.pollOnce();
      Thread.sleep(1);
    }
    long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

    waitForCounter("asyncJobQueue.failed", jobCount);
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.FAILED)).isEqualTo(jobCount);
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.QUEUED)).isZero();
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.RUNNING)).isZero();
    assertThat(statusCount(inMemoryAsyncJobStore, AsyncJobStatus.DONE)).isZero();
    assertThat(attemptsById).hasSize(jobCount);
    assertThat(attemptsById.values())
        .allSatisfy(attempts -> assertThat(attempts.get()).isEqualTo(2));
    assertThat(counterCount("asyncJobQueue.handler.failed")).isEqualTo(jobCount * 2);
    assertThat(counterCount("asyncJobQueue.retried")).isEqualTo(jobCount);
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
    return statusCount(inMemoryAsyncJobStore, AsyncJobStatus.DONE);
  }

  private long statusCount(
      InMemoryAsyncJobStore inMemoryAsyncJobStore, AsyncJobStatus asyncJobStatus) {
    return inMemoryAsyncJobStore.countByStatus("assetlocalize").stream()
        .filter(statusCount -> statusCount.status() == asyncJobStatus)
        .mapToLong(AsyncJobStatusCount::count)
        .sum();
  }

  private void waitForCounter(String meterName, double expectedCount) throws InterruptedException {
    long timeoutAt = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < timeoutAt) {
      if (counterCount(meterName) == expectedCount) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "Timed out waiting for counter " + meterName + " count " + expectedCount);
  }

  private double counterCount(String meterName) {
    Counter counter = meterRegistry.find(meterName).tag("queueName", "assetlocalize").counter();
    return counter == null ? 0 : counter.count();
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
