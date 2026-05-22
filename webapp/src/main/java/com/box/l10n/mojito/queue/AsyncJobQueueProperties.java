package com.box.l10n.mojito.queue;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level configuration for async job queues and their per-queue runtime settings.
 *
 * <p>Example:
 *
 * <pre>
 * l10n.org.async-job-queue.enabled=true
 * l10n.org.async-job-queue.store=jdbc
 * l10n.org.async-job-queue.queues.assetlocalize.poll-interval-ms=250
 * l10n.org.async-job-queue.queues.assetlocalize.claim-batch-size=20
 * </pre>
 */
@Configuration
@ConfigurationProperties("l10n.org.async-job-queue")
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueProperties {

  /**
   * Active async job store backend implementation.
   *
   * <p>Defaults to {@code in-memory} so local or HSQL-backed runs can enable the feature without
   * requiring MySQL queue-table support. Production deployments should explicitly set {@code jdbc}.
   */
  private String store = "in-memory";

  private Map<String, QueueSettings> queues = new HashMap<>();

  public String getStore() {
    return store;
  }

  public void setStore(String store) {
    this.store = store;
  }

  public Map<String, QueueSettings> getQueues() {
    return queues;
  }

  public void setQueues(Map<String, QueueSettings> queues) {
    this.queues = queues;
  }

  /** Runtime settings for one logical async job queue. */
  public static class QueueSettings {
    private long pollIntervalMs = 250;
    private long maxPollIntervalMs = 5_000;
    private int claimBatchSize = 20;
    private int maxConcurrency = 10;
    private long leaseDurationMs = 120_000;
    private long heartbeatIntervalMs = 20_000;
    private int maxAttempts = 5;

    public long getPollIntervalMs() {
      return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
      this.pollIntervalMs = pollIntervalMs;
    }

    public long getMaxPollIntervalMs() {
      return maxPollIntervalMs;
    }

    public void setMaxPollIntervalMs(long maxPollIntervalMs) {
      this.maxPollIntervalMs = maxPollIntervalMs;
    }

    public int getClaimBatchSize() {
      return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
      this.claimBatchSize = claimBatchSize;
    }

    public int getMaxConcurrency() {
      return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
      this.maxConcurrency = maxConcurrency;
    }

    public long getLeaseDurationMs() {
      return leaseDurationMs;
    }

    public void setLeaseDurationMs(long leaseDurationMs) {
      this.leaseDurationMs = leaseDurationMs;
    }

    public long getHeartbeatIntervalMs() {
      return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
      this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }
  }
}
