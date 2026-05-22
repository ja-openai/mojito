package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Adaptive poll loop + local executor runtime for one queue on one pod. */
class AsyncJobQueueRuntime {

  static Logger logger = LoggerFactory.getLogger(AsyncJobQueueRuntime.class);

  private static final int MAX_ERROR_LENGTH = 4_000;

  private final String queueName;
  private final AsyncJobStore asyncJobStore;
  private final AsyncJobQueueProperties.QueueSettings queueSettings;
  private final AsyncJobHandler asyncJobHandler;
  private final TaskScheduler taskScheduler;
  private final ThreadPoolTaskExecutor executor;
  private final MeterRegistry meterRegistry;
  private final String workerId;
  private final LongUnaryOperator pollDelayJitter;

  private final Object scheduleLock = new Object();
  private final AtomicInteger inFlightCount = new AtomicInteger();
  private final AtomicBoolean pollInProgress = new AtomicBoolean();
  private final AtomicBoolean wakeRequested = new AtomicBoolean();

  private volatile ScheduledFuture<?> nextPollFuture;
  private volatile boolean started;
  private volatile long currentPollDelayMs;
  private long scheduledPollSequence;

  AsyncJobQueueRuntime(
      String queueName,
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties.QueueSettings queueSettings,
      AsyncJobHandler asyncJobHandler,
      TaskScheduler taskScheduler,
      ThreadPoolTaskExecutor executor,
      MeterRegistry meterRegistry,
      String workerId) {
    this(
        queueName,
        asyncJobStore,
        queueSettings,
        asyncJobHandler,
        taskScheduler,
        executor,
        meterRegistry,
        workerId,
        null);
  }

  AsyncJobQueueRuntime(
      String queueName,
      AsyncJobStore asyncJobStore,
      AsyncJobQueueProperties.QueueSettings queueSettings,
      AsyncJobHandler asyncJobHandler,
      TaskScheduler taskScheduler,
      ThreadPoolTaskExecutor executor,
      MeterRegistry meterRegistry,
      String workerId,
      LongUnaryOperator pollDelayJitter) {
    this.queueName = Objects.requireNonNull(queueName);
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.queueSettings = Objects.requireNonNull(queueSettings);
    this.asyncJobHandler = Objects.requireNonNull(asyncJobHandler);
    this.taskScheduler = Objects.requireNonNull(taskScheduler);
    this.executor = Objects.requireNonNull(executor);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.workerId = Objects.requireNonNull(workerId);
    this.pollDelayJitter =
        pollDelayJitter != null ? pollDelayJitter : this::applyRandomPollDelayJitter;
    validateQueueSettings(queueSettings);
    this.currentPollDelayMs = basePollDelayMs();
    meterRegistry.gauge(
        "asyncJobQueue.inflight",
        Tags.of("queueName", queueName),
        this,
        AsyncJobQueueRuntime::inFlightCount);
    meterRegistry.gauge(
        "asyncJobQueue.executor.active",
        Tags.of("queueName", queueName),
        executor,
        ThreadPoolTaskExecutor::getActiveCount);
    meterRegistry.gauge(
        "asyncJobQueue.executor.queued",
        Tags.of("queueName", queueName),
        executor,
        AsyncJobQueueRuntime::executorQueueSize);
  }

  void start() {
    synchronized (scheduleLock) {
      if (started) {
        return;
      }
      started = true;
      try {
        scheduleNextPoll(0);
      } catch (RuntimeException e) {
        started = false;
        logger.warn("Failed to schedule initial poll for queue {}", queueName, e);
        recordPollScheduleFailure();
        throw e;
      }
    }
  }

  void stop() {
    synchronized (scheduleLock) {
      started = false;
      if (nextPollFuture != null) {
        nextPollFuture.cancel(false);
        nextPollFuture = null;
      }
    }
    executor.shutdown();
  }

  void triggerPollNow() {
    synchronized (scheduleLock) {
      if (!started) {
        return;
      }
      if (pollInProgress.get()) {
        wakeRequested.set(true);
        return;
      }
      wakeRequested.set(false);
      ScheduledFuture<?> previousPollFuture = nextPollFuture;
      try {
        scheduleNextPoll(0);
        if (previousPollFuture != null) {
          previousPollFuture.cancel(false);
        }
      } catch (RuntimeException e) {
        logger.warn("Failed to trigger immediate poll for queue {}", queueName, e);
        meterRegistry.counter("asyncJobQueue.trigger.failed", "queueName", queueName).increment();
      }
    }
  }

  PollCycleResult pollOnce() {
    int freeCapacity = freeCapacity();
    if (freeCapacity <= 0) {
      meterRegistry
          .counter("asyncJobQueue.poll.skippedSaturated", "queueName", queueName)
          .increment();
      return PollCycleResult.saturated(basePollDelayMs());
    }

    int claimLimit = Math.min(queueSettings.getClaimBatchSize(), freeCapacity);
    long claimStartNanos = System.nanoTime();
    List<AsyncJobRecord> claimedJobs;
    try {
      claimedJobs =
          asyncJobStore.claimNextJobs(
              queueName,
              claimLimit,
              workerId,
              java.time.Duration.ofMillis(queueSettings.getLeaseDurationMs()));
    } finally {
      meterRegistry
          .timer("asyncJobQueue.claim.latency", "queueName", queueName)
          .record(System.nanoTime() - claimStartNanos, TimeUnit.NANOSECONDS);
    }

    if (claimedJobs.isEmpty()) {
      currentPollDelayMs = nextEmptyPollDelayMs();
      meterRegistry.counter("asyncJobQueue.poll.empty", "queueName", queueName).increment();
      return PollCycleResult.empty(currentPollDelayMs);
    }

    currentPollDelayMs = basePollDelayMs();
    meterRegistry
        .counter("asyncJobQueue.claimed", "queueName", queueName)
        .increment(claimedJobs.size());
    recordLeaseExpiredReclaims(claimedJobs);
    recordQueueWaitLatency(claimedJobs);

    for (AsyncJobRecord claimedJob : claimedJobs) {
      submitClaimedJob(claimedJob);
    }

    boolean continueImmediately = freeCapacity() > 0 && claimedJobs.size() == claimLimit;
    return PollCycleResult.claimed(
        claimedJobs.size(), continueImmediately ? 0 : basePollDelayMs(), continueImmediately);
  }

  int inFlightCount() {
    return inFlightCount.get();
  }

  private static int executorQueueSize(ThreadPoolTaskExecutor executor) {
    ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
    return threadPoolExecutor.getQueue().size();
  }

  private void runScheduledPoll(long pollSequence) {
    synchronized (scheduleLock) {
      if (!started) {
        return;
      }
      if (pollSequence != scheduledPollSequence) {
        return;
      }
      nextPollFuture = null;
      if (!pollInProgress.compareAndSet(false, true)) {
        return;
      }
    }

    PollCycleResult pollCycleResult;
    try {
      pollCycleResult = pollOnce();
    } catch (RuntimeException e) {
      logger.error("Async queue poll failed for queue {}", queueName, e);
      meterRegistry.counter("asyncJobQueue.poll.failed", "queueName", queueName).increment();
      pollCycleResult = PollCycleResult.failed(basePollDelayMs());
    }

    synchronized (scheduleLock) {
      pollInProgress.set(false);
      if (wakeRequested.getAndSet(false)) {
        pollCycleResult = PollCycleResult.wakeup();
      }
      if (started) {
        try {
          scheduleNextPoll(pollCycleResult.nextDelayMs());
        } catch (RuntimeException e) {
          logger.warn("Failed to schedule next poll for queue {}", queueName, e);
          recordPollScheduleFailure();
        }
      }
    }
  }

  private void scheduleNextPoll(long delayMs) {
    long boundedDelayMs = Math.max(0, delayMs);
    if (boundedDelayMs > 0) {
      boundedDelayMs = Math.max(0, pollDelayJitter.applyAsLong(boundedDelayMs));
    }
    long pollSequence = scheduledPollSequence + 1;
    ScheduledFuture<?> scheduledFuture =
        taskScheduler.schedule(
            () -> runScheduledPoll(pollSequence),
            Date.from(Instant.now().plusMillis(boundedDelayMs)));
    if (scheduledFuture == null) {
      throw new IllegalStateException("TaskScheduler returned null ScheduledFuture");
    }
    scheduledPollSequence = pollSequence;
    nextPollFuture = scheduledFuture;
  }

  private void recordPollScheduleFailure() {
    meterRegistry.counter("asyncJobQueue.poll.schedule.failed", "queueName", queueName).increment();
  }

  private void submitClaimedJob(AsyncJobRecord asyncJobRecord) {
    inFlightCount.incrementAndGet();
    try {
      executor.execute(() -> processClaimedJob(asyncJobRecord));
    } catch (RuntimeException e) {
      inFlightCount.decrementAndGet();
      handleExecutorSubmitFailure(asyncJobRecord, e);
    }
  }

  private void handleExecutorSubmitFailure(AsyncJobRecord asyncJobRecord, RuntimeException e) {
    boolean rejected = e instanceof RejectedExecutionException;
    if (rejected) {
      meterRegistry.counter("asyncJobQueue.executor.rejected", "queueName", queueName).increment();
    } else {
      meterRegistry
          .counter("asyncJobQueue.executor.submit.failed", "queueName", queueName)
          .increment();
    }
    String errorMessage = errorMessage(e);
    String failureDescription = rejected ? "rejected" : "failed to accept";
    String failedTransition = rejected ? "executorRejectedFailed" : "executorSubmitFailed";
    String requeueTransition = rejected ? "executorRejectedRequeue" : "executorSubmitRequeue";

    if (asyncJobRecord.attemptCount() >= queueSettings.getMaxAttempts()) {
      logger.error(
          "Queue executor {} claimed job {} for queue {}; marking failed after {} attempts",
          failureDescription,
          asyncJobRecord.id(),
          queueName,
          asyncJobRecord.attemptCount(),
          e);
      boolean markedFailed;
      try {
        markedFailed =
            asyncJobStore.markFailed(
                queueName,
                asyncJobRecord.id(),
                asyncJobRecord.workerId(),
                asyncJobRecord.leaseToken(),
                null,
                errorMessage);
      } catch (RuntimeException transitionException) {
        logger.warn(
            "Failed to mark executor-submit-failed job {} failed for queue {}",
            asyncJobRecord.id(),
            queueName,
            transitionException);
        recordTransitionFailure(failedTransition);
        return;
      }
      if (!markedFailed) {
        logger.warn(
            "Failed to mark executor-submit-failed job {} failed for queue {}",
            asyncJobRecord.id(),
            queueName);
        recordTransitionFailure(failedTransition);
      } else {
        meterRegistry.counter("asyncJobQueue.failed", "queueName", queueName).increment();
      }
      return;
    }

    logger.warn(
        "Queue executor {} claimed job {} for queue {}",
        failureDescription,
        asyncJobRecord.id(),
        queueName,
        e);
    boolean requeued;
    try {
      requeued =
          asyncJobStore.requeueAfter(
              queueName,
              asyncJobRecord.id(),
              asyncJobRecord.workerId(),
              asyncJobRecord.leaseToken(),
              Duration.ofMillis(retryDelayMs(asyncJobRecord.attemptCount())),
              null,
              errorMessage);
    } catch (RuntimeException transitionException) {
      logger.warn(
          "Failed to requeue executor-submit-failed job {} for queue {}",
          asyncJobRecord.id(),
          queueName,
          transitionException);
      recordTransitionFailure(requeueTransition);
      return;
    }
    if (!requeued) {
      logger.warn(
          "Failed to requeue executor-submit-failed job {} for queue {}",
          asyncJobRecord.id(),
          queueName);
      recordTransitionFailure(requeueTransition);
    }
  }

  private void processClaimedJob(AsyncJobRecord asyncJobRecord) {
    long processingStartNanos = System.nanoTime();
    ScheduledFuture<?> heartbeatFuture = null;
    try {
      try {
        heartbeatFuture = scheduleHeartbeat(asyncJobRecord);
      } catch (RuntimeException e) {
        handleHeartbeatScheduleFailure(asyncJobRecord, e);
        return;
      }
      AsyncJobHandlerResult asyncJobHandlerResult = asyncJobHandler.process(asyncJobRecord);
      if (asyncJobHandlerResult == null) {
        asyncJobHandlerResult = AsyncJobHandlerResult.done();
      }
      applyHandlerResult(asyncJobRecord, asyncJobHandlerResult);
    } catch (Exception e) {
      handleProcessingFailure(asyncJobRecord, e);
    } finally {
      if (heartbeatFuture != null) {
        heartbeatFuture.cancel(false);
      }
      inFlightCount.decrementAndGet();
      meterRegistry
          .timer("asyncJobQueue.processing.latency", "queueName", queueName)
          .record(System.nanoTime() - processingStartNanos, TimeUnit.NANOSECONDS);
      triggerPollNow();
    }
  }

  private void handleHeartbeatScheduleFailure(AsyncJobRecord asyncJobRecord, RuntimeException e) {
    meterRegistry
        .counter("asyncJobQueue.heartbeat.schedule.failed", "queueName", queueName)
        .increment();
    String errorMessage = errorMessage(e);

    if (asyncJobRecord.attemptCount() >= queueSettings.getMaxAttempts()) {
      logger.error(
          "Failed to schedule heartbeat for queue {}, job {}; marking failed after {} attempts",
          queueName,
          asyncJobRecord.id(),
          asyncJobRecord.attemptCount(),
          e);
      boolean markedFailed;
      try {
        markedFailed =
            asyncJobStore.markFailed(
                queueName,
                asyncJobRecord.id(),
                asyncJobRecord.workerId(),
                asyncJobRecord.leaseToken(),
                null,
                errorMessage);
      } catch (RuntimeException transitionException) {
        logger.warn(
            "Failed to mark heartbeat-schedule-failed job {} failed for queue {}",
            asyncJobRecord.id(),
            queueName,
            transitionException);
        recordTransitionFailure("heartbeatScheduleFailed");
        return;
      }
      if (!markedFailed) {
        logger.warn(
            "Failed to mark heartbeat-schedule-failed job {} failed for queue {}",
            asyncJobRecord.id(),
            queueName);
        recordTransitionFailure("heartbeatScheduleFailed");
      } else {
        meterRegistry.counter("asyncJobQueue.failed", "queueName", queueName).increment();
      }
      return;
    }

    logger.warn(
        "Failed to schedule heartbeat for queue {}, job {}; requeueing attempt {}/{}",
        queueName,
        asyncJobRecord.id(),
        asyncJobRecord.attemptCount(),
        queueSettings.getMaxAttempts(),
        e);
    if (requeueWithDelay(
        asyncJobRecord,
        retryDelayMs(asyncJobRecord.attemptCount()),
        null,
        errorMessage,
        "heartbeatScheduleRequeue")) {
      meterRegistry.counter("asyncJobQueue.retried", "queueName", queueName).increment();
    }
  }

  private void applyHandlerResult(
      AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult) {
    switch (asyncJobHandlerResult.action()) {
      case DONE -> {
        boolean markedDone;
        try {
          markedDone =
              asyncJobStore.markDone(
                  queueName,
                  asyncJobRecord.id(),
                  asyncJobRecord.workerId(),
                  asyncJobRecord.leaseToken(),
                  asyncJobHandlerResult.jobData());
        } catch (RuntimeException e) {
          logger.warn(
              "Failed to mark async job {} done for queue {}", asyncJobRecord.id(), queueName, e);
          recordTransitionFailure("done");
          return;
        }
        if (!markedDone) {
          logger.warn(
              "Failed to mark async job {} done for queue {}", asyncJobRecord.id(), queueName);
          recordTransitionFailure("done");
        } else {
          meterRegistry.counter("asyncJobQueue.completed", "queueName", queueName).increment();
        }
      }
      case REQUEUE -> {
        if (asyncJobRecord.attemptCount() >= queueSettings.getMaxAttempts()) {
          handleHandlerRequeueAttemptBudgetExhausted(asyncJobRecord, asyncJobHandlerResult);
          return;
        }

        boolean requeued;
        try {
          if (asyncJobHandlerResult.availableAt() == null) {
            requeued =
                asyncJobStore.requeueAfter(
                    queueName,
                    asyncJobRecord.id(),
                    asyncJobRecord.workerId(),
                    asyncJobRecord.leaseToken(),
                    Duration.ofMillis(basePollDelayMs()),
                    asyncJobHandlerResult.jobData(),
                    null);
          } else {
            requeued =
                asyncJobStore.requeue(
                    queueName,
                    asyncJobRecord.id(),
                    asyncJobRecord.workerId(),
                    asyncJobRecord.leaseToken(),
                    asyncJobHandlerResult.availableAt(),
                    asyncJobHandlerResult.jobData(),
                    null);
          }
        } catch (RuntimeException e) {
          logger.warn(
              "Failed to requeue async job {} for queue {}", asyncJobRecord.id(), queueName, e);
          recordTransitionFailure("requeue");
          return;
        }
        if (!requeued) {
          logger.warn(
              "Failed to requeue async job {} for queue {}", asyncJobRecord.id(), queueName);
          recordTransitionFailure("requeue");
        } else {
          meterRegistry.counter("asyncJobQueue.requeued", "queueName", queueName).increment();
        }
      }
    }
  }

  private void handleHandlerRequeueAttemptBudgetExhausted(
      AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult) {
    meterRegistry.counter("asyncJobQueue.requeue.exhausted", "queueName", queueName).increment();
    String errorMessage = "Handler requested requeue after attempt budget exhausted";

    logger.error(
        "Async job handler requested requeue for queue {}, job {}; marking failed after {} attempts",
        queueName,
        asyncJobRecord.id(),
        asyncJobRecord.attemptCount());
    boolean markedFailed;
    try {
      markedFailed =
          asyncJobStore.markFailed(
              queueName,
              asyncJobRecord.id(),
              asyncJobRecord.workerId(),
              asyncJobRecord.leaseToken(),
              asyncJobHandlerResult.jobData(),
              errorMessage);
    } catch (RuntimeException e) {
      logger.warn(
          "Failed to mark requeue-exhausted async job {} failed for queue {}",
          asyncJobRecord.id(),
          queueName,
          e);
      recordTransitionFailure("requeueExhausted");
      return;
    }
    if (!markedFailed) {
      logger.warn(
          "Failed to mark requeue-exhausted async job {} failed for queue {}",
          asyncJobRecord.id(),
          queueName);
      recordTransitionFailure("requeueExhausted");
    } else {
      meterRegistry.counter("asyncJobQueue.failed", "queueName", queueName).increment();
    }
  }

  private void handleProcessingFailure(AsyncJobRecord asyncJobRecord, Exception e) {
    meterRegistry.counter("asyncJobQueue.handler.failed", "queueName", queueName).increment();
    String errorMessage = errorMessage(e);

    if (asyncJobRecord.attemptCount() >= queueSettings.getMaxAttempts()) {
      logger.error(
          "Async job handler failed for queue {}, job {}; marking failed after {} attempts",
          queueName,
          asyncJobRecord.id(),
          asyncJobRecord.attemptCount(),
          e);
      boolean markedFailed;
      try {
        markedFailed =
            asyncJobStore.markFailed(
                queueName,
                asyncJobRecord.id(),
                asyncJobRecord.workerId(),
                asyncJobRecord.leaseToken(),
                null,
                errorMessage);
      } catch (RuntimeException transitionException) {
        logger.warn(
            "Failed to mark async job {} failed for queue {}",
            asyncJobRecord.id(),
            queueName,
            transitionException);
        recordTransitionFailure("failed");
        return;
      }
      if (!markedFailed) {
        logger.warn(
            "Failed to mark async job {} failed for queue {}", asyncJobRecord.id(), queueName);
        recordTransitionFailure("failed");
      } else {
        meterRegistry.counter("asyncJobQueue.failed", "queueName", queueName).increment();
      }
      return;
    }

    logger.error(
        "Async job handler failed for queue {}, job {}; requeueing attempt {}/{}",
        queueName,
        asyncJobRecord.id(),
        asyncJobRecord.attemptCount(),
        queueSettings.getMaxAttempts(),
        e);
    if (requeueWithDelay(
        asyncJobRecord, retryDelayMs(asyncJobRecord.attemptCount()), null, errorMessage)) {
      meterRegistry.counter("asyncJobQueue.retried", "queueName", queueName).increment();
    }
  }

  private boolean requeueWithDelay(
      AsyncJobRecord asyncJobRecord, long delayMs, String jobData, String lastError) {
    return requeueWithDelay(asyncJobRecord, delayMs, jobData, lastError, "retry");
  }

  private boolean requeueWithDelay(
      AsyncJobRecord asyncJobRecord,
      long delayMs,
      String jobData,
      String lastError,
      String transitionFailureName) {
    boolean requeued;
    try {
      requeued =
          asyncJobStore.requeueAfter(
              queueName,
              asyncJobRecord.id(),
              asyncJobRecord.workerId(),
              asyncJobRecord.leaseToken(),
              Duration.ofMillis(Math.max(0, delayMs)),
              jobData,
              lastError);
    } catch (RuntimeException e) {
      logger.warn("Failed to requeue async job {} for queue {}", asyncJobRecord.id(), queueName, e);
      recordTransitionFailure(transitionFailureName);
      return false;
    }
    if (!requeued) {
      logger.warn("Failed to requeue async job {} for queue {}", asyncJobRecord.id(), queueName);
      recordTransitionFailure(transitionFailureName);
    }
    return requeued;
  }

  long retryDelayMs(int attemptCount) {
    long delayMs = basePollDelayMs();
    long maxRetryDelayMs = Math.max(delayMs, queueSettings.getMaxRetryDelayMs());
    int boundedAttemptCount = Math.max(1, attemptCount);
    for (int i = 1; i < boundedAttemptCount && delayMs < maxRetryDelayMs; i++) {
      if (delayMs > Long.MAX_VALUE / 2) {
        delayMs = maxRetryDelayMs;
      } else {
        delayMs = Math.min(maxRetryDelayMs, delayMs * 2);
      }
    }
    return applyRetryDelayJitter(delayMs);
  }

  private long applyRetryDelayJitter(long delayMs) {
    int jitterPercent = queueSettings.getRetryJitterPercent();
    if (jitterPercent <= 0) {
      return delayMs;
    }

    long jitterRangeMs = Math.max(1, delayMs * jitterPercent / 100);
    long jitter = ThreadLocalRandom.current().nextLong(-jitterRangeMs, jitterRangeMs + 1);
    return Math.max(0, delayMs + jitter);
  }

  private void recordTransitionFailure(String transition) {
    meterRegistry
        .counter(
            "asyncJobQueue.transition.failed", "queueName", queueName, "transition", transition)
        .increment();
  }

  private String errorMessage(Exception e) {
    String message = e.getClass().getName();
    if (e.getMessage() != null && !e.getMessage().isBlank()) {
      message += ": " + e.getMessage();
    }
    return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
  }

  private ScheduledFuture<?> scheduleHeartbeat(AsyncJobRecord asyncJobRecord) {
    if (queueSettings.getHeartbeatIntervalMs() <= 0) {
      return null;
    }

    ScheduledFuture<?> scheduledFuture =
        taskScheduler.scheduleAtFixedRate(
            () -> heartbeat(asyncJobRecord),
            Date.from(Instant.now().plusMillis(queueSettings.getHeartbeatIntervalMs())),
            queueSettings.getHeartbeatIntervalMs());
    if (scheduledFuture == null) {
      throw new IllegalStateException("TaskScheduler returned null heartbeat ScheduledFuture");
    }
    return scheduledFuture;
  }

  private void heartbeat(AsyncJobRecord asyncJobRecord) {
    try {
      boolean renewed =
          asyncJobStore.heartbeat(
              queueName,
              asyncJobRecord.id(),
              asyncJobRecord.workerId(),
              asyncJobRecord.leaseToken(),
              java.time.Duration.ofMillis(queueSettings.getLeaseDurationMs()));
      if (!renewed) {
        logger.warn(
            "Failed to renew heartbeat for queue {}, job {}", queueName, asyncJobRecord.id());
        meterRegistry.counter("asyncJobQueue.heartbeat.failed", "queueName", queueName).increment();
      }
    } catch (RuntimeException e) {
      logger.warn(
          "Failed to renew heartbeat for queue {}, job {}", queueName, asyncJobRecord.id(), e);
      meterRegistry.counter("asyncJobQueue.heartbeat.failed", "queueName", queueName).increment();
    }
  }

  private void recordQueueWaitLatency(List<AsyncJobRecord> claimedJobs) {
    for (AsyncJobRecord claimedJob : claimedJobs) {
      if (claimedJob.createdDate() == null || claimedJob.updatedDate() == null) {
        continue;
      }
      java.time.Duration queueWaitLatency =
          java.time.Duration.between(claimedJob.createdDate(), claimedJob.updatedDate());
      if (!queueWaitLatency.isNegative()) {
        meterRegistry
            .timer("asyncJobQueue.queueWait.latency", "queueName", queueName)
            .record(queueWaitLatency);
      }
    }
  }

  private void recordLeaseExpiredReclaims(List<AsyncJobRecord> claimedJobs) {
    long reclaimedCount = claimedJobs.stream().filter(AsyncJobRecord::leaseReclaimed).count();
    if (reclaimedCount > 0) {
      meterRegistry
          .counter("asyncJobQueue.leaseExpiredReclaimed", "queueName", queueName)
          .increment(reclaimedCount);
    }
  }

  private long nextEmptyPollDelayMs() {
    long base = basePollDelayMs();
    long max = Math.max(base, queueSettings.getMaxPollIntervalMs());
    long current = Math.max(base, currentPollDelayMs);
    if (current >= max || current > Long.MAX_VALUE / 2) {
      return max;
    }
    return Math.min(max, current * 2);
  }

  private int freeCapacity() {
    return Math.max(0, queueSettings.getMaxConcurrency() - inFlightCount.get());
  }

  private long basePollDelayMs() {
    return Math.max(1, queueSettings.getPollIntervalMs());
  }

  private long applyRandomPollDelayJitter(long delayMs) {
    int jitterPercent = queueSettings.getPollJitterPercent();
    if (jitterPercent <= 0) {
      return delayMs;
    }

    long jitterRangeMs = Math.max(1, delayMs * jitterPercent / 100);
    long jitter = ThreadLocalRandom.current().nextLong(-jitterRangeMs, jitterRangeMs + 1);
    return Math.max(0, delayMs + jitter);
  }

  private void validateQueueSettings(AsyncJobQueueProperties.QueueSettings queueSettings) {
    if (queueSettings.getPollIntervalMs() <= 0) {
      throw new IllegalArgumentException("pollIntervalMs must be > 0");
    }
    if (queueSettings.getMaxPollIntervalMs() <= 0) {
      throw new IllegalArgumentException("maxPollIntervalMs must be > 0");
    }
    if (queueSettings.getClaimBatchSize() <= 0) {
      throw new IllegalArgumentException("claimBatchSize must be > 0");
    }
    if (queueSettings.getMaxConcurrency() <= 0) {
      throw new IllegalArgumentException("maxConcurrency must be > 0");
    }
    if (queueSettings.getLeaseDurationMs() <= 0) {
      throw new IllegalArgumentException("leaseDurationMs must be > 0");
    }
    if (queueSettings.getMaxPollIntervalMs() < queueSettings.getPollIntervalMs()) {
      throw new IllegalArgumentException("maxPollIntervalMs must be >= pollIntervalMs");
    }
    if (queueSettings.getHeartbeatIntervalMs() >= queueSettings.getLeaseDurationMs()
        && queueSettings.getHeartbeatIntervalMs() > 0) {
      throw new IllegalArgumentException("heartbeatIntervalMs must be < leaseDurationMs");
    }
    if (queueSettings.getMaxAttempts() <= 0) {
      throw new IllegalArgumentException("maxAttempts must be > 0");
    }
    if (queueSettings.getPollJitterPercent() < 0 || queueSettings.getPollJitterPercent() > 100) {
      throw new IllegalArgumentException("pollJitterPercent must be between 0 and 100");
    }
    if (queueSettings.getMaxRetryDelayMs() <= 0) {
      throw new IllegalArgumentException("maxRetryDelayMs must be > 0");
    }
    if (queueSettings.getRetryJitterPercent() < 0 || queueSettings.getRetryJitterPercent() > 100) {
      throw new IllegalArgumentException("retryJitterPercent must be between 0 and 100");
    }
  }

  record PollCycleResult(
      int claimedCount, boolean skippedSaturated, boolean continueImmediately, long nextDelayMs) {

    static PollCycleResult empty(long nextDelayMs) {
      return new PollCycleResult(0, false, false, nextDelayMs);
    }

    static PollCycleResult saturated(long nextDelayMs) {
      return new PollCycleResult(0, true, false, nextDelayMs);
    }

    static PollCycleResult claimed(
        int claimedCount, long nextDelayMs, boolean continueImmediately) {
      return new PollCycleResult(claimedCount, false, continueImmediately, nextDelayMs);
    }

    static PollCycleResult failed(long nextDelayMs) {
      return new PollCycleResult(0, false, false, nextDelayMs);
    }

    static PollCycleResult wakeup() {
      return new PollCycleResult(0, false, true, 0);
    }
  }
}
