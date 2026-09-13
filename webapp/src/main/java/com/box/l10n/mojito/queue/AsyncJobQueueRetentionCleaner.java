package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

  @Autowired
  public AsyncJobQueueRetentionCleaner(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties asyncJobQueueProperties,
      List<AsyncJobHandler> asyncJobHandlers,
      MeterRegistry meterRegistry) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.asyncJobQueueProperties =
        AsyncJobQueueValidation.validateProperties(Objects.requireNonNull(asyncJobQueueProperties));
    this.asyncJobHandlers = Objects.requireNonNull(asyncJobHandlers);
    this.asyncJobHandlers.stream()
        .map(AsyncJobHandler::queueName)
        .forEach(AsyncJobQueueValidation::validateQueueName);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
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
    int deletedCount;
    try {
      deletedCount =
          asyncJobStore.deleteTerminalJobsOlderThan(
              queueName, status, Duration.ofMillis(retentionMs), batchSize);
    } catch (Throwable failure) {
      Error fatal = AsyncJobQueueFatalErrors.findJvmFatal(failure);
      if (fatal != null) {
        throw fatal;
      }
      recordDiagnostic(
          () ->
              logger.warn(
                  "Failed to delete async job queue {} rows for queue {} older than {} ms in store time",
                  status.getDatabaseValue(),
                  queueName,
                  retentionMs,
                  failure));
      recordDiagnostic(
          () ->
              meterRegistry
                  .counter(
                      "asyncJobQueue.retention.failed",
                      "queueName",
                      queueName,
                      "status",
                      status.getDatabaseValue())
                  .increment());
      return;
    }
    // Diagnostics run after the store returns; they cannot reclassify an acknowledged delete.
    if (deletedCount > 0) {
      recordDiagnostic(
          () ->
              logger.info(
                  "Deleted {} async job queue {} rows for queue {} older than {} ms in store time",
                  deletedCount,
                  status.getDatabaseValue(),
                  queueName,
                  retentionMs));
      recordDiagnostic(
          () ->
              meterRegistry
                  .counter(
                      "asyncJobQueue.retention.deleted",
                      "queueName",
                      queueName,
                      "status",
                      status.getDatabaseValue())
                  .increment(deletedCount));
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

  private void recordDiagnostic(Runnable recording) {
    try {
      recording.run();
    } catch (Throwable failure) {
      Error fatal = AsyncJobQueueFatalErrors.findJvmFatal(failure);
      if (fatal != null) {
        throw fatal;
      }
      try {
        logger.warn("Failed to record async job retention diagnostic", failure);
      } catch (Throwable loggingFailure) {
        Error loggingFatal = AsyncJobQueueFatalErrors.findJvmFatal(loggingFailure);
        if (loggingFatal != null) {
          throw loggingFatal;
        }
        // Do not recurse or prevent later queues/statuses from receiving their bounded pass.
      }
    }
  }
}
