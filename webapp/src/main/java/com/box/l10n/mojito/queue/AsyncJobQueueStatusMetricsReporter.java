package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically samples durable queue depth by status into gauges. */
@Profile("!disablescheduling")
@Component
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueStatusMetricsReporter {

  static Logger logger = LoggerFactory.getLogger(AsyncJobQueueStatusMetricsReporter.class);

  private final AsyncJobStore asyncJobStore;
  private final AsyncJobQueueProperties asyncJobQueueProperties;
  private final List<AsyncJobHandler> asyncJobHandlers;
  private final MeterRegistry meterRegistry;
  private final Map<StatusMetricKey, AtomicLong> statusGauges = new ConcurrentHashMap<>();

  public AsyncJobQueueStatusMetricsReporter(
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

  @Scheduled(fixedDelayString = "${l10n.org.async-job-queue.status-metrics-interval-ms:10000}")
  public void reportStatusCounts() {
    for (String queueName : queueNames()) {
      try {
        reportStatusCounts(queueName);
      } catch (Throwable e) {
        if (isJvmFatal(e)) {
          throw (Error) e;
        }
        logger.warn("Failed to report async job queue status metrics for {}", queueName, e);
        meterRegistry
            .counter("asyncJobQueue.statusMetrics.failed", "queueName", queueName)
            .increment();
      }
    }
  }

  private void reportStatusCounts(String queueName) {
    Map<AsyncJobStatus, Long> countsByStatus = zeroCountsByStatus();
    for (AsyncJobStatusCount statusCount : asyncJobStore.countByStatus(queueName)) {
      countsByStatus.put(statusCount.status(), statusCount.count());
    }

    countsByStatus.forEach(
        (status, count) ->
            statusGauge(queueName, status).set(count == null ? 0L : Math.max(0L, count)));
  }

  private Set<String> queueNames() {
    Set<String> queueNames = new LinkedHashSet<>(asyncJobQueueProperties.getQueues().keySet());
    asyncJobHandlers.stream()
        .map(AsyncJobHandler::queueName)
        .map(AsyncJobQueueValidation::validateQueueName)
        .forEach(queueNames::add);
    return queueNames;
  }

  private Map<AsyncJobStatus, Long> zeroCountsByStatus() {
    Map<AsyncJobStatus, Long> countsByStatus = new EnumMap<>(AsyncJobStatus.class);
    for (AsyncJobStatus status : AsyncJobStatus.values()) {
      countsByStatus.put(status, 0L);
    }
    return countsByStatus;
  }

  private AtomicLong statusGauge(String queueName, AsyncJobStatus status) {
    StatusMetricKey key = new StatusMetricKey(queueName, status);
    return statusGauges.computeIfAbsent(
        key,
        ignored ->
            meterRegistry.gauge(
                "asyncJobQueue.status",
                Tags.of("queueName", queueName, "status", status.getDatabaseValue()),
                new AtomicLong()));
  }

  private boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError
        || "java.lang.ThreadDeath".equals(throwable.getClass().getName());
  }

  private record StatusMetricKey(String queueName, AsyncJobStatus status) {}
}
