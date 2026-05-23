package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
  private final AsyncJobQueueWakeupNotifier asyncJobQueueWakeupNotifier;
  private final MeterRegistry meterRegistry;
  private final Clock clock;

  @Autowired
  public AsyncJobQueueSubmissionService(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry,
      ObjectProvider<AsyncJobQueueWakeupNotifier> asyncJobQueueWakeupNotifier) {
    this(
        asyncJobStore,
        asyncJobQueueCoordinator,
        asyncJobQueueWakeupNotifier.getIfAvailable(() -> AsyncJobQueueWakeupNotifier.NO_OP),
        meterRegistry,
        Clock.systemUTC());
  }

  AsyncJobQueueSubmissionService(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry) {
    this(
        asyncJobStore,
        asyncJobQueueCoordinator,
        AsyncJobQueueWakeupNotifier.NO_OP,
        meterRegistry,
        Clock.systemUTC());
  }

  AsyncJobQueueSubmissionService(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      MeterRegistry meterRegistry,
      Clock clock) {
    this(
        asyncJobStore,
        asyncJobQueueCoordinator,
        AsyncJobQueueWakeupNotifier.NO_OP,
        meterRegistry,
        clock);
  }

  AsyncJobQueueSubmissionService(
      AsyncJobStore asyncJobStore,
      AsyncJobQueueCoordinator asyncJobQueueCoordinator,
      AsyncJobQueueWakeupNotifier asyncJobQueueWakeupNotifier,
      MeterRegistry meterRegistry,
      Clock clock) {
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.asyncJobQueueCoordinator = Objects.requireNonNull(asyncJobQueueCoordinator);
    this.asyncJobQueueWakeupNotifier = Objects.requireNonNull(asyncJobQueueWakeupNotifier);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.clock = Objects.requireNonNull(clock);
  }

  public AsyncJobId enqueueNow(String queueName, String jobData) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    String validatedJobData = validateJobDataForEnqueue(validatedQueueName, jobData);
    try {
      AsyncJobId asyncJobId = asyncJobStore.enqueueNow(validatedQueueName, validatedJobData);
      incrementEnqueueCounter(validatedQueueName, "succeeded");
      triggerEnqueueWakeup(validatedQueueName, asyncJobId);
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

  public AsyncJobId enqueue(String queueName, String jobData, Instant availableAt) {
    String validatedQueueName = AsyncJobQueueValidation.validateQueueName(queueName);
    String validatedJobData = validateJobDataForEnqueue(validatedQueueName, jobData);
    Instant validatedAvailableAt = validateAvailableAtForEnqueue(validatedQueueName, availableAt);
    try {
      boolean shouldTriggerWakeup = !validatedAvailableAt.isAfter(clock.instant());
      AsyncJobId asyncJobId =
          asyncJobStore.enqueue(validatedQueueName, validatedJobData, validatedAvailableAt);
      incrementEnqueueCounter(validatedQueueName, "succeeded");
      if (shouldTriggerWakeup) {
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

  private String validateJobDataForEnqueue(String queueName, String jobData) {
    try {
      return AsyncJobQueueValidation.validateJobData(jobData);
    } catch (RuntimeException exception) {
      incrementEnqueueCounter(queueName, "invalidPayload");
      throw exception;
    }
  }

  private Instant validateAvailableAtForEnqueue(String queueName, Instant availableAt) {
    try {
      return AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);
    } catch (RuntimeException exception) {
      incrementEnqueueCounter(queueName, "invalidAvailableAt");
      throw exception;
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
    try {
      asyncJobQueueWakeupNotifier.notifyJobAvailable(queueName, asyncJobId);
    } catch (Throwable exception) {
      if (isJvmFatal(exception)) {
        throw (Error) exception;
      }
      meterRegistry
          .counter("asyncJobQueue.enqueueWakeup.notify.failed", "queueName", queueName)
          .increment();
      logger.warn(
          "Failed to publish async job queue wakeup after enqueue for queue {}, job {}",
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
