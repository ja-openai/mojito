package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes bounded batches of terminal async jobs according to the configured retention policy. */
@Profile("!disablescheduling")
@Component
@ConditionalOnProperty(
    name = {"l10n.org.async-job-queue.enabled", "l10n.org.async-job-queue.retention.enabled"},
    havingValue = "true")
public class AsyncJobQueueRetentionCleaner {

  static Logger logger = LoggerFactory.getLogger(AsyncJobQueueRetentionCleaner.class);

  private final AsyncJobStore asyncJobStore;
  private final AsyncJobQueueProperties asyncJobQueueProperties;
  private final List<AsyncJobHandler> asyncJobHandlers;
  private final MeterRegistry meterRegistry;
  private final Supplier<Instant> nowSupplier;

  @Autowired
  public AsyncJobQueueRetentionCleaner(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties asyncJobQueueProperties,
      List<AsyncJobHandler> asyncJobHandlers,
      MeterRegistry meterRegistry) {
    this(asyncJobStore, asyncJobQueueProperties, asyncJobHandlers, meterRegistry, Instant::now);
  }

  AsyncJobQueueRetentionCleaner(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties asyncJobQueueProperties,
      List<AsyncJobHandler> asyncJobHandlers,
      MeterRegistry meterRegistry,
      Supplier<Instant> nowSupplier) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.asyncJobQueueProperties =
        AsyncJobQueueValidation.validateProperties(Objects.requireNonNull(asyncJobQueueProperties));
    this.asyncJobHandlers = Objects.requireNonNull(asyncJobHandlers);
    this.asyncJobHandlers.stream()
        .map(AsyncJobHandler::queueName)
        .forEach(AsyncJobQueueValidation::validateQueueName);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.nowSupplier = Objects.requireNonNull(nowSupplier);
  }

  @Scheduled(fixedDelayString = "${l10n.org.async-job-queue.retention.interval-ms:3600000}")
  public void cleanupTerminalJobs() {
    AsyncJobQueueProperties.RetentionSettings retentionSettings =
        AsyncJobQueueValidation.validateRetentionSettings(asyncJobQueueProperties.getRetention());
    for (String queueName : queueNames()) {
      cleanupTerminalJobs(
          queueName,
          AsyncJobStatus.DONE,
          retentionSettings.getDoneRetentionMs(),
          retentionSettings.getBatchSize());
      cleanupTerminalJobs(
          queueName,
          AsyncJobStatus.FAILED,
          retentionSettings.getFailedRetentionMs(),
          retentionSettings.getBatchSize());
    }
  }

  private void cleanupTerminalJobs(
      String queueName, AsyncJobStatus status, long retentionMs, int batchSize) {
    Instant updatedBefore = nowSupplier.get().minus(Duration.ofMillis(retentionMs));
    try {
      int deletedCount =
          asyncJobStore.deleteTerminalJobs(queueName, status, updatedBefore, batchSize);
      if (deletedCount > 0) {
        logger.info(
            "Deleted {} async job queue {} rows for queue {} older than {}",
            deletedCount,
            status.getDatabaseValue(),
            queueName,
            updatedBefore);
        meterRegistry
            .counter(
                "asyncJobQueue.retention.deleted",
                "queueName",
                queueName,
                "status",
                status.getDatabaseValue())
            .increment(deletedCount);
      }
    } catch (RuntimeException e) {
      logger.warn(
          "Failed to delete async job queue {} rows for queue {} older than {}",
          status.getDatabaseValue(),
          queueName,
          updatedBefore,
          e);
      meterRegistry
          .counter(
              "asyncJobQueue.retention.failed",
              "queueName",
              queueName,
              "status",
              status.getDatabaseValue())
          .increment();
    }
  }

  private Set<String> queueNames() {
    Set<String> queueNames = new LinkedHashSet<>(asyncJobQueueProperties.getQueues().keySet());
    asyncJobHandlers.stream()
        .map(AsyncJobHandler::queueName)
        .map(AsyncJobQueueValidation::validateQueueName)
        .forEach(queueNames::add);
    return queueNames;
  }
}
