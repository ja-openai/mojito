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
  }

  void start() {
    synchronized (scheduleLock) {
      if (started) {
        return;
      }
      started = true;
      scheduleNextPoll(0);
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
      if (nextPollFuture != null) {
        nextPollFuture.cancel(false);
      }
      scheduleNextPoll(0);
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
        scheduleNextPoll(pollCycleResult.nextDelayMs());
      }
    }
  }

  private void scheduleNextPoll(long delayMs) {
    long boundedDelayMs = Math.max(0, delayMs);
    if (boundedDelayMs > 0) {
      boundedDelayMs = Math.max(0, pollDelayJitter.applyAsLong(boundedDelayMs));
    }
    long pollSequence = ++scheduledPollSequence;
    nextPollFuture =
        taskScheduler.schedule(
            () -> runScheduledPoll(pollSequence),
            Date.from(Instant.now().plusMillis(boundedDelayMs)));
  }

  private void submitClaimedJob(AsyncJobRecord asyncJobRecord) {
    inFlightCount.incrementAndGet();
    try {
      executor.execute(() -> processClaimedJob(asyncJobRecord));
    } catch (RejectedExecutionException e) {
      inFlightCount.decrementAndGet();
      logger.warn(
          "Queue executor rejected claimed job {} for queue {}", asyncJobRecord.id(), queueName, e);
      boolean requeued =
          asyncJobStore.requeueAfter(
              queueName,
              asyncJobRecord.id(),
              asyncJobRecord.workerId(),
              asyncJobRecord.leaseToken(),
              Duration.ZERO,
              null,
              errorMessage(e));
      if (!requeued) {
        logger.warn(
            "Failed to requeue rejected job {} for queue {}", asyncJobRecord.id(), queueName);
        recordTransitionFailure("executorRejectedRequeue");
      }
    }
  }

  private void processClaimedJob(AsyncJobRecord asyncJobRecord) {
    long processingStartNanos = System.nanoTime();
    ScheduledFuture<?> heartbeatFuture = scheduleHeartbeat(asyncJobRecord);
    try {
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

  private void applyHandlerResult(
      AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult) {
    switch (asyncJobHandlerResult.action()) {
      case DONE -> {
        boolean markedDone =
            asyncJobStore.markDone(
                queueName,
                asyncJobRecord.id(),
                asyncJobRecord.workerId(),
                asyncJobRecord.leaseToken(),
                asyncJobHandlerResult.jobData());
        if (!markedDone) {
          logger.warn(
              "Failed to mark async job {} done for queue {}", asyncJobRecord.id(), queueName);
          recordTransitionFailure("done");
        } else {
          meterRegistry.counter("asyncJobQueue.completed", "queueName", queueName).increment();
        }
      }
      case REQUEUE -> {
        boolean requeued;
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
      boolean markedFailed =
          asyncJobStore.markFailed(
              queueName,
              asyncJobRecord.id(),
              asyncJobRecord.workerId(),
              asyncJobRecord.leaseToken(),
              null,
              errorMessage);
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
    boolean requeued =
        asyncJobStore.requeueAfter(
            queueName,
            asyncJobRecord.id(),
            asyncJobRecord.workerId(),
            asyncJobRecord.leaseToken(),
            Duration.ofMillis(Math.max(0, delayMs)),
            jobData,
            lastError);
    if (!requeued) {
      logger.warn(
          "Failed to requeue async job {} after handler failure for queue {}",
          asyncJobRecord.id(),
          queueName);
      recordTransitionFailure("retry");
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

    return taskScheduler.scheduleAtFixedRate(
        () -> heartbeat(asyncJobRecord),
        Date.from(Instant.now().plusMillis(queueSettings.getHeartbeatIntervalMs())),
        queueSettings.getHeartbeatIntervalMs());
  }

  private void heartbeat(AsyncJobRecord asyncJobRecord) {
    boolean renewed =
        asyncJobStore.heartbeat(
            queueName,
            asyncJobRecord.id(),
            asyncJobRecord.workerId(),
            asyncJobRecord.leaseToken(),
            java.time.Duration.ofMillis(queueSettings.getLeaseDurationMs()));
    if (!renewed) {
      logger.warn("Failed to renew heartbeat for queue {}, job {}", queueName, asyncJobRecord.id());
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

  private long nextEmptyPollDelayMs() {
    long base = basePollDelayMs();
    long max = Math.max(base, queueSettings.getMaxPollIntervalMs());
    long current = Math.max(base, currentPollDelayMs);
    return Math.min(max, current >= max ? max : current * 2);
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
