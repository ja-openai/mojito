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
 * l10n.org.async-job-queue.jdbc-dialect=mysql
 * l10n.org.async-job-queue.wakeup.mode=polling
 * l10n.org.async-job-queue.status-metrics-interval-ms=10000
 * l10n.org.async-job-queue.queues.assetlocalize.poll-interval-ms=250
 * l10n.org.async-job-queue.queues.assetlocalize.poll-jitter-percent=10
 * l10n.org.async-job-queue.queues.assetlocalize.max-retry-delay-ms=60000
 * l10n.org.async-job-queue.queues.assetlocalize.retry-jitter-percent=20
 * l10n.org.async-job-queue.queues.assetlocalize.shutdown-await-termination-ms=30000
 * l10n.org.async-job-queue.queues.assetlocalize.claim-batch-size=20
 * l10n.org.async-job-queue.retention.enabled=false
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

  private String jdbcDialect = "mysql";

  private long statusMetricsIntervalMs = 10_000;

  private WakeupSettings wakeup = new WakeupSettings();

  private RetentionSettings retention = new RetentionSettings();

  private Map<String, QueueSettings> queues = new HashMap<>();

  public String getStore() {
    return store;
  }

  public void setStore(String store) {
    this.store = store;
  }

  public String getJdbcDialect() {
    return jdbcDialect;
  }

  public void setJdbcDialect(String jdbcDialect) {
    this.jdbcDialect = jdbcDialect;
  }

  public long getStatusMetricsIntervalMs() {
    return statusMetricsIntervalMs;
  }

  public void setStatusMetricsIntervalMs(long statusMetricsIntervalMs) {
    this.statusMetricsIntervalMs = statusMetricsIntervalMs;
  }

  public WakeupSettings getWakeup() {
    if (wakeup == null) {
      wakeup = new WakeupSettings();
    }
    return wakeup;
  }

  public void setWakeup(WakeupSettings wakeup) {
    this.wakeup = wakeup == null ? new WakeupSettings() : wakeup;
  }

  public RetentionSettings getRetention() {
    if (retention == null) {
      retention = new RetentionSettings();
    }
    return retention;
  }

  public void setRetention(RetentionSettings retention) {
    this.retention = retention == null ? new RetentionSettings() : retention;
  }

  public Map<String, QueueSettings> getQueues() {
    if (queues == null) {
      queues = new HashMap<>();
    }
    return queues;
  }

  public void setQueues(Map<String, QueueSettings> queues) {
    this.queues = queues == null ? new HashMap<>() : queues;
  }

  /** Best-effort cross-process wakeup hints; workers still poll for durability. */
  public static class WakeupSettings {
    private String mode = AsyncJobQueueValidation.WAKEUP_MODE_POLLING;
    private String postgresChannel = "mojito_async_job_queue";
    private long postgresListenTimeoutMs = 5_000;
    private long triggerJitterMs = 50;
    private long reconnectDelayMs = 5_000;
    private int reconnectJitterPercent = 20;

    public String getMode() {
      return mode;
    }

    public void setMode(String mode) {
      this.mode = mode;
    }

    public String getPostgresChannel() {
      return postgresChannel;
    }

    public void setPostgresChannel(String postgresChannel) {
      this.postgresChannel = postgresChannel;
    }

    public long getPostgresListenTimeoutMs() {
      return postgresListenTimeoutMs;
    }

    public void setPostgresListenTimeoutMs(long postgresListenTimeoutMs) {
      this.postgresListenTimeoutMs = postgresListenTimeoutMs;
    }

    public long getTriggerJitterMs() {
      return triggerJitterMs;
    }

    public void setTriggerJitterMs(long triggerJitterMs) {
      this.triggerJitterMs = triggerJitterMs;
    }

    public long getReconnectDelayMs() {
      return reconnectDelayMs;
    }

    public void setReconnectDelayMs(long reconnectDelayMs) {
      this.reconnectDelayMs = reconnectDelayMs;
    }

    public int getReconnectJitterPercent() {
      return reconnectJitterPercent;
    }

    public void setReconnectJitterPercent(int reconnectJitterPercent) {
      this.reconnectJitterPercent = reconnectJitterPercent;
    }
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
    private int pollJitterPercent = 10;
    private long maxRetryDelayMs = 60_000;
    private int retryJitterPercent = 20;
    private long shutdownAwaitTerminationMs = 30_000;

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

    public int getPollJitterPercent() {
      return pollJitterPercent;
    }

    public void setPollJitterPercent(int pollJitterPercent) {
      this.pollJitterPercent = pollJitterPercent;
    }

    public long getMaxRetryDelayMs() {
      return maxRetryDelayMs;
    }

    public void setMaxRetryDelayMs(long maxRetryDelayMs) {
      this.maxRetryDelayMs = maxRetryDelayMs;
    }

    public int getRetryJitterPercent() {
      return retryJitterPercent;
    }

    public void setRetryJitterPercent(int retryJitterPercent) {
      this.retryJitterPercent = retryJitterPercent;
    }

    public long getShutdownAwaitTerminationMs() {
      return shutdownAwaitTerminationMs;
    }

    public void setShutdownAwaitTerminationMs(long shutdownAwaitTerminationMs) {
      this.shutdownAwaitTerminationMs = shutdownAwaitTerminationMs;
    }
  }

  /** Scheduled retention policy for terminal async jobs. */
  public static class RetentionSettings {
    private boolean enabled = false;
    private long intervalMs = 3_600_000;
    private long doneRetentionMs = 604_800_000;
    private long failedRetentionMs = 2_592_000_000L;
    private int batchSize = 100;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public long getIntervalMs() {
      return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
      this.intervalMs = intervalMs;
    }

    public long getDoneRetentionMs() {
      return doneRetentionMs;
    }

    public void setDoneRetentionMs(long doneRetentionMs) {
      this.doneRetentionMs = doneRetentionMs;
    }

    public long getFailedRetentionMs() {
      return failedRetentionMs;
    }

    public void setFailedRetentionMs(long failedRetentionMs) {
      this.failedRetentionMs = failedRetentionMs;
    }

    public int getBatchSize() {
      return batchSize;
    }

    public void setBatchSize(int batchSize) {
      this.batchSize = batchSize;
    }
  }
}
