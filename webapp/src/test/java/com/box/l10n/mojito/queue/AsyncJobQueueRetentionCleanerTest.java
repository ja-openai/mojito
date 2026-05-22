package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class AsyncJobQueueRetentionCleanerTest {

  private static final Instant CLEANUP_NOW = Instant.parse("2030-01-01T00:00:00Z");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void cleanupTerminalJobsDeletesOnlyBoundedTerminalStatusesAndRecordsMetrics() {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    completeJob(asyncJobStore, "assetlocalize", "{\"done\":1}");
    failJob(asyncJobStore, "assetlocalize", "{\"failed\":1}");
    asyncJobStore.enqueue("assetlocalize", "{\"queued\":1}", Instant.now());

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore,
            queueProperties("assetlocalize"),
            List.of(),
            meterRegistry,
            () -> CLEANUP_NOW);

    retentionCleaner.cleanupTerminalJobs();

    assertThat(asyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.DONE, 10)).isEmpty();
    assertThat(asyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.FAILED, 10)).isEmpty();
    assertThat(asyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.QUEUED, 10)).hasSize(1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.DONE, 1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 1);
  }

  @Test
  public void cleanupTerminalJobsRecordsFailureAndContinuesAcrossStatuses() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobs(
            eq("assetlocalize"), eq(AsyncJobStatus.DONE), any(Instant.class), eq(100)))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(asyncJobStore.deleteTerminalJobs(
            eq("assetlocalize"), eq(AsyncJobStatus.FAILED), any(Instant.class), eq(100)))
        .thenReturn(2);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore,
            queueProperties("assetlocalize"),
            List.of(),
            meterRegistry,
            () -> CLEANUP_NOW);

    retentionCleaner.cleanupTerminalJobs();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.retention.failed")
                .tag("queueName", "assetlocalize")
                .tag("status", "done")
                .counter()
                .count())
        .isEqualTo(1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 2);
    verify(asyncJobStore)
        .deleteTerminalJobs(
            eq("assetlocalize"), eq(AsyncJobStatus.FAILED), any(Instant.class), eq(100));
  }

  @Test
  public void cleanupTerminalJobsContinuesAfterOneQueueFails() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobs(
            eq("broken"), eq(AsyncJobStatus.DONE), any(Instant.class), eq(100)))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(asyncJobStore.deleteTerminalJobs(
            eq("assetlocalize"), eq(AsyncJobStatus.DONE), any(Instant.class), eq(100)))
        .thenReturn(3);
    when(asyncJobStore.deleteTerminalJobs(
            eq("assetlocalize"), eq(AsyncJobStatus.FAILED), any(Instant.class), eq(100)))
        .thenReturn(4);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore,
            queueProperties("broken", "assetlocalize"),
            List.of(),
            meterRegistry,
            () -> CLEANUP_NOW);

    retentionCleaner.cleanupTerminalJobs();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.retention.failed")
                .tag("queueName", "broken")
                .tag("status", "done")
                .counter()
                .count())
        .isEqualTo(1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.DONE, 3);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 4);
    verify(asyncJobStore)
        .deleteTerminalJobs(
            eq("assetlocalize"), eq(AsyncJobStatus.DONE), any(Instant.class), eq(100));
    verify(asyncJobStore)
        .deleteTerminalJobs(
            eq("assetlocalize"), eq(AsyncJobStatus.FAILED), any(Instant.class), eq(100));
  }

  @Test
  public void rejectsInvalidHandlerQueueNameBeforeCleanup() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AsyncJobQueueRetentionCleaner(
                    new InMemoryAsyncJobStore(),
                    new AsyncJobQueueProperties(),
                    List.of(handler(" ")),
                    meterRegistry,
                    () -> CLEANUP_NOW));

    assertThat(exception).hasMessageContaining("queueName must not be blank");
  }

  private void completeJob(InMemoryAsyncJobStore asyncJobStore, String queueName, String jobData) {
    AsyncJobId id = asyncJobStore.enqueue(queueName, jobData, Instant.now().minusSeconds(1));
    AsyncJobRecord job =
        asyncJobStore.claimNextJobs(queueName, 1, "worker-a", Duration.ofSeconds(30)).get(0);
    assertThat(job.id()).isEqualTo(id);
    assertThat(asyncJobStore.markDone(queueName, id, "worker-a", job.leaseToken(), null)).isTrue();
  }

  private void failJob(InMemoryAsyncJobStore asyncJobStore, String queueName, String jobData) {
    AsyncJobId id = asyncJobStore.enqueue(queueName, jobData, Instant.now().minusSeconds(1));
    AsyncJobRecord job =
        asyncJobStore.claimNextJobs(queueName, 1, "worker-a", Duration.ofSeconds(30)).get(0);
    assertThat(job.id()).isEqualTo(id);
    assertThat(
            asyncJobStore.markFailed(
                queueName, id, "worker-a", job.leaseToken(), null, "permanent failure"))
        .isTrue();
  }

  private AsyncJobQueueProperties queueProperties(String... queueNames) {
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    for (String queueName : queueNames) {
      asyncJobQueueProperties
          .getQueues()
          .put(queueName, new AsyncJobQueueProperties.QueueSettings());
    }
    return asyncJobQueueProperties;
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

  private void assertDeletedCounter(String queueName, AsyncJobStatus status, double expectedCount) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.retention.deleted")
                .tag("queueName", queueName)
                .tag("status", status.getDatabaseValue())
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }
}
