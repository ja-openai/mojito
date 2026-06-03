package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(
    classes = {AsyncJobQueueProperties.class, AsyncJobQueuePropertiesTest.class},
    properties = {
      "l10n.org.async-job-queue.enabled=true",
      "l10n.org.async-job-queue.store=jdbc",
      "l10n.org.async-job-queue.jdbc-dialect=postgresql",
      "l10n.org.async-job-queue.wakeup.mode=postgres-listen-notify",
      "l10n.org.async-job-queue.wakeup.postgres-channel=mojito_async_queue",
      "l10n.org.async-job-queue.wakeup.postgres-listen-timeout-ms=3000",
      "l10n.org.async-job-queue.wakeup.trigger-jitter-ms=125",
      "l10n.org.async-job-queue.wakeup.reconnect-delay-ms=7000",
      "l10n.org.async-job-queue.wakeup.reconnect-jitter-percent=15",
      "l10n.org.async-job-queue.status-metrics-interval-ms=12000",
      "l10n.org.async-job-queue.retention.enabled=true",
      "l10n.org.async-job-queue.retention.interval-ms=600000",
      "l10n.org.async-job-queue.retention.done-retention-ms=86400000",
      "l10n.org.async-job-queue.retention.failed-retention-ms=604800000",
      "l10n.org.async-job-queue.retention.batch-size=50",
      "l10n.org.async-job-queue.queues.assetlocalize.poll-interval-ms=300",
      "l10n.org.async-job-queue.queues.assetlocalize.max-poll-interval-ms=4000",
      "l10n.org.async-job-queue.queues.assetlocalize.claim-batch-size=32",
      "l10n.org.async-job-queue.queues.assetlocalize.max-concurrency=12",
      "l10n.org.async-job-queue.queues.assetlocalize.max-attempts=9",
      "l10n.org.async-job-queue.queues.assetlocalize.poll-jitter-percent=25",
      "l10n.org.async-job-queue.queues.assetlocalize.max-retry-delay-ms=90000",
      "l10n.org.async-job-queue.queues.assetlocalize.retry-jitter-percent=30",
      "l10n.org.async-job-queue.queues.assetlocalize.shutdown-await-termination-ms=45000",
      "l10n.org.async-job-queue.queues.assetlocalize.lease-duration-ms=180000",
      "l10n.org.async-job-queue.queues.assetlocalize.heartbeat-interval-ms=15000",
      "l10n.org.async-job-queue.queues.stats.poll-interval-ms=1000",
      "l10n.org.async-job-queue.queues.stats.max-concurrency=2"
    })
@EnableConfigurationProperties(AsyncJobQueueProperties.class)
public class AsyncJobQueuePropertiesTest {

  @Autowired AsyncJobQueueProperties asyncJobQueueProperties;

  @Test
  public void testQueueConfigMapBinding() {
    assertThat(asyncJobQueueProperties.getStore()).isEqualTo("jdbc");
    assertThat(asyncJobQueueProperties.getJdbcDialect()).isEqualTo("postgresql");
    assertThat(asyncJobQueueProperties.getWakeup().getMode()).isEqualTo("postgres-listen-notify");
    assertThat(asyncJobQueueProperties.getWakeup().getPostgresChannel())
        .isEqualTo("mojito_async_queue");
    assertThat(asyncJobQueueProperties.getWakeup().getPostgresListenTimeoutMs()).isEqualTo(3000);
    assertThat(asyncJobQueueProperties.getWakeup().getTriggerJitterMs()).isEqualTo(125);
    assertThat(asyncJobQueueProperties.getWakeup().getReconnectDelayMs()).isEqualTo(7000);
    assertThat(asyncJobQueueProperties.getWakeup().getReconnectJitterPercent()).isEqualTo(15);
    assertThat(asyncJobQueueProperties.getStatusMetricsIntervalMs()).isEqualTo(12000);
    assertThat(asyncJobQueueProperties.getRetention().isEnabled()).isTrue();
    assertThat(asyncJobQueueProperties.getRetention().getIntervalMs()).isEqualTo(600000);
    assertThat(asyncJobQueueProperties.getRetention().getDoneRetentionMs()).isEqualTo(86400000);
    assertThat(asyncJobQueueProperties.getRetention().getFailedRetentionMs()).isEqualTo(604800000);
    assertThat(asyncJobQueueProperties.getRetention().getBatchSize()).isEqualTo(50);

    Map<String, AsyncJobQueueProperties.QueueSettings> queues = asyncJobQueueProperties.getQueues();

    assertThat(queues).containsOnlyKeys("assetlocalize", "stats");

    AsyncJobQueueProperties.QueueSettings assetlocalize = queues.get("assetlocalize");
    assertThat(assetlocalize.getPollIntervalMs()).isEqualTo(300);
    assertThat(assetlocalize.getMaxPollIntervalMs()).isEqualTo(4000);
    assertThat(assetlocalize.getClaimBatchSize()).isEqualTo(32);
    assertThat(assetlocalize.getMaxConcurrency()).isEqualTo(12);
    assertThat(assetlocalize.getMaxAttempts()).isEqualTo(9);
    assertThat(assetlocalize.getPollJitterPercent()).isEqualTo(25);
    assertThat(assetlocalize.getMaxRetryDelayMs()).isEqualTo(90000);
    assertThat(assetlocalize.getRetryJitterPercent()).isEqualTo(30);
    assertThat(assetlocalize.getShutdownAwaitTerminationMs()).isEqualTo(45000);
    assertThat(assetlocalize.getLeaseDurationMs()).isEqualTo(180000);
    assertThat(assetlocalize.getHeartbeatIntervalMs()).isEqualTo(15000);

    AsyncJobQueueProperties.QueueSettings stats = queues.get("stats");
    assertThat(stats.getPollIntervalMs()).isEqualTo(1000);
    assertThat(stats.getMaxPollIntervalMs()).isEqualTo(5000);
    assertThat(stats.getMaxConcurrency()).isEqualTo(2);
    assertThat(stats.getMaxAttempts()).isEqualTo(5);
    assertThat(stats.getPollJitterPercent()).isEqualTo(10);
    assertThat(stats.getMaxRetryDelayMs()).isEqualTo(60000);
    assertThat(stats.getRetryJitterPercent()).isEqualTo(20);
    assertThat(stats.getShutdownAwaitTerminationMs()).isEqualTo(30000);
    assertThat(stats.getClaimBatchSize()).isEqualTo(20);
    assertThat(stats.getLeaseDurationMs()).isEqualTo(120000);
    assertThat(stats.getHeartbeatIntervalMs()).isEqualTo(20000);
  }

  @Test
  public void setQueuesHandlesNull() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();

    properties.setQueues(null);

    assertThat(properties.getQueues()).isEmpty();
  }

  @Test
  public void setRetentionHandlesNull() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();

    properties.setRetention(null);

    assertThat(properties.getRetention()).isNotNull();
    assertThat(properties.getRetention().isEnabled()).isFalse();
  }

  @Test
  public void setWakeupHandlesNull() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();

    properties.setWakeup(null);

    assertThat(properties.getWakeup()).isNotNull();
    assertThat(properties.getWakeup().getMode()).isEqualTo("polling");
  }
}
