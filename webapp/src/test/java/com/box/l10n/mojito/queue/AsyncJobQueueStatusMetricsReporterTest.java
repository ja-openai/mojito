package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class AsyncJobQueueStatusMetricsReporterTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void reportStatusCountsPublishesZerosAndUpdatesExistingGauges() {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    asyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    asyncJobStore.enqueue("assetlocalize", "{\"id\":2}", Instant.now().minusSeconds(1));
    AsyncJobRecord runningJob =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(10)).get(0);

    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore,
            queueProperties("assetlocalize"),
            List.of(handler("stats")),
            meterRegistry);

    reporter.reportStatusCounts();

    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 1);
    assertGaugeValue("assetlocalize", AsyncJobStatus.RUNNING, 1);
    assertGaugeValue("assetlocalize", AsyncJobStatus.DONE, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.FAILED, 0);
    assertGaugeValue("stats", AsyncJobStatus.QUEUED, 0);

    asyncJobStore.markDone(
        "assetlocalize", runningJob.id(), "worker-a", runningJob.leaseToken(), "{\"id\":2}");

    reporter.reportStatusCounts();

    assertGaugeValue("assetlocalize", AsyncJobStatus.RUNNING, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.DONE, 1);
  }

  @Test
  public void reportStatusCountsRecordsFailureCounterWithoutThrowing() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.countByStatus("assetlocalize"))
        .thenThrow(new IllegalStateException("database unavailable"));

    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    reporter.reportStatusCounts();

    assertThat(
            meterRegistry
                .get("asyncJobQueue.statusMetrics.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void reportStatusCountsHandlesNullConfiguredQueues() {
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    asyncJobQueueProperties.setQueues(null);
    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            new InMemoryAsyncJobStore(),
            asyncJobQueueProperties,
            List.of(handler("assetlocalize")),
            meterRegistry);

    reporter.reportStatusCounts();

    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 0);
  }

  @Test
  public void rejectsInvalidHandlerQueueNameBeforeReportingMetrics() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AsyncJobQueueStatusMetricsReporter(
                    new InMemoryAsyncJobStore(),
                    new AsyncJobQueueProperties(),
                    List.of(handler(" ")),
                    meterRegistry));

    assertThat(exception).hasMessageContaining("queueName must not be blank");
  }

  private AsyncJobQueueProperties queueProperties(String queueName) {
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    asyncJobQueueProperties.getQueues().put(queueName, new AsyncJobQueueProperties.QueueSettings());
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

  private void assertGaugeValue(String queueName, AsyncJobStatus status, long expectedValue) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.status")
                .tag("queueName", queueName)
                .tag("status", status.getDatabaseValue())
                .gauge()
                .value())
        .isEqualTo(expectedValue);
  }
}
