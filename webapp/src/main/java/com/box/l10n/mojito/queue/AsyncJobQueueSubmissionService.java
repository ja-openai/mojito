package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Production-facing enqueue API that records submission telemetry and nudges local workers. */
@Service
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueSubmissionService {

  static Logger logger = LoggerFactory.getLogger(AsyncJobQueueSubmissionService.class);

  private final AsyncJobStore asyncJobStore;
  private final AsyncJobQueueCoordinator asyncJobQueueCoordinator;
  private final MeterRegistry meterRegistry;
  private final Clock clock;

  @Autowired
  public AsyncJobQueueSubmissionService(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry) {
    this(asyncJobStore, asyncJobQueueCoordinator, meterRegistry, Clock.systemUTC());
  }

  AsyncJobQueueSubmissionService(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.asyncJobQueueCoordinator = Objects.requireNonNull(asyncJobQueueCoordinator);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.clock = Objects.requireNonNull(clock);
  }

  public AsyncJobId enqueueNow(String queueName, String jobData) {
    return enqueue(queueName, jobData, clock.instant());
  }

  public AsyncJobId enqueue(String queueName, String jobData, Instant availableAt) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    try {
      Objects.requireNonNull(jobData);
      Instant validatedAvailableAt =
          AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);
      AsyncJobId asyncJobId =
          asyncJobStore.enqueue(validatedQueueName, jobData, validatedAvailableAt);
      incrementEnqueueCounter(validatedQueueName, "succeeded");
      if (!validatedAvailableAt.isAfter(clock.instant())) {
        triggerEnqueueWakeup(validatedQueueName, asyncJobId);
      }
      return asyncJobId;
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      incrementEnqueueCounter(validatedQueueName, "failed");
      logger.warn("Failed to enqueue async job for queue {}", validatedQueueName, exception);
      throw unchecked(exception);
    }
  }

  private void triggerEnqueueWakeup(String queueName, AsyncJobId asyncJobId) {
    try {
      asyncJobQueueCoordinator.triggerPollNow(queueName);
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      meterRegistry
          .counter("asyncJobQueue.enqueueWakeup.failed", "queueName", queueName)
          .increment();
      logger.warn(
          "Failed to trigger async job queue wakeup after enqueue for queue {}, job {}",
          queueName,
          asyncJobId.value(),
          exception);
    }
  }

  private void incrementEnqueueCounter(String queueName, String result) {
    meterRegistry
        .counter("asyncJobQueue.enqueue", "queueName", queueName, "result", result)
        .increment();
  }

  private boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError
        || "java.lang.ThreadDeath".equals(throwable.getClass().getName());
  }

  private RuntimeException unchecked(Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (throwable instanceof Error error) {
      throw error;
    }
    return new IllegalStateException(throwable);
  }
}
