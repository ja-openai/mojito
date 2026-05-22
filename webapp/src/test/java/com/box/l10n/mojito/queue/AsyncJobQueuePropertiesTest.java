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
      "l10n.org.async-job-queue.status-metrics-interval-ms=12000",
      "l10n.org.async-job-queue.queues.assetlocalize.poll-interval-ms=300",
      "l10n.org.async-job-queue.queues.assetlocalize.max-poll-interval-ms=4000",
      "l10n.org.async-job-queue.queues.assetlocalize.claim-batch-size=32",
      "l10n.org.async-job-queue.queues.assetlocalize.max-concurrency=12",
      "l10n.org.async-job-queue.queues.assetlocalize.max-attempts=9",
      "l10n.org.async-job-queue.queues.assetlocalize.poll-jitter-percent=25",
      "l10n.org.async-job-queue.queues.assetlocalize.max-retry-delay-ms=90000",
      "l10n.org.async-job-queue.queues.assetlocalize.retry-jitter-percent=30",
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
    assertThat(asyncJobQueueProperties.getStatusMetricsIntervalMs()).isEqualTo(12000);

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
    assertThat(stats.getClaimBatchSize()).isEqualTo(20);
    assertThat(stats.getLeaseDurationMs()).isEqualTo(120000);
    assertThat(stats.getHeartbeatIntervalMs()).isEqualTo(20000);
  }
}
