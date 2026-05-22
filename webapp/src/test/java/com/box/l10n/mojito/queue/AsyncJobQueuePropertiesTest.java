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
      "l10n.org.async-job-queue.queues.assetlocalize.poll-interval-ms=300",
      "l10n.org.async-job-queue.queues.assetlocalize.max-poll-interval-ms=4000",
      "l10n.org.async-job-queue.queues.assetlocalize.claim-batch-size=32",
      "l10n.org.async-job-queue.queues.assetlocalize.max-concurrency=12",
      "l10n.org.async-job-queue.queues.assetlocalize.max-attempts=9",
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

    Map<String, AsyncJobQueueProperties.QueueSettings> queues = asyncJobQueueProperties.getQueues();

    assertThat(queues).containsOnlyKeys("assetlocalize", "stats");

    AsyncJobQueueProperties.QueueSettings assetlocalize = queues.get("assetlocalize");
    assertThat(assetlocalize.getPollIntervalMs()).isEqualTo(300);
    assertThat(assetlocalize.getMaxPollIntervalMs()).isEqualTo(4000);
    assertThat(assetlocalize.getClaimBatchSize()).isEqualTo(32);
    assertThat(assetlocalize.getMaxConcurrency()).isEqualTo(12);
    assertThat(assetlocalize.getMaxAttempts()).isEqualTo(9);
    assertThat(assetlocalize.getLeaseDurationMs()).isEqualTo(180000);
    assertThat(assetlocalize.getHeartbeatIntervalMs()).isEqualTo(15000);

    AsyncJobQueueProperties.QueueSettings stats = queues.get("stats");
    assertThat(stats.getPollIntervalMs()).isEqualTo(1000);
    assertThat(stats.getMaxPollIntervalMs()).isEqualTo(5000);
    assertThat(stats.getMaxConcurrency()).isEqualTo(2);
    assertThat(stats.getMaxAttempts()).isEqualTo(5);
    assertThat(stats.getClaimBatchSize()).isEqualTo(20);
    assertThat(stats.getLeaseDurationMs()).isEqualTo(120000);
    assertThat(stats.getHeartbeatIntervalMs()).isEqualTo(20000);
  }
}
