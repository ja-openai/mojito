package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Adaptive poll loop + local executor runtime for one queue on one pod. */
class AsyncJobQueueRuntime {

  static Logger logger = LoggerFactory.getLogger(AsyncJobQueueRuntime.class);

  private final String queueName;
  private final AsyncJobStore asyncJobStore;
  private final AsyncJobQueueProperties.QueueSettings queueSettings;
  private final AsyncJobHandler asyncJobHandler;
  private final TaskScheduler taskScheduler;
  private final ThreadPoolTaskExecutor executor;
  private final MeterRegistry meterRegistry;
  private final String workerId;
  private final LongUnaryOperator pollDelayJitter;
  private final List<Meter> runtimeGauges;

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
    this.queueName = AsyncJobQueueValidation.validateQueueName(queueName);
    this.asyncJobStore = Objects.requireNonNull(asyncJobStore);
    this.queueSettings = Objects.requireNonNull(queueSettings);
    this.asyncJobHandler = Objects.requireNonNull(asyncJobHandler);
    this.taskScheduler = Objects.requireNonNull(taskScheduler);
    this.executor = Objects.requireNonNull(executor);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.workerId = AsyncJobQueueValidation.validateWorkerId(workerId);
    this.pollDelayJitter =
        pollDelayJitter != null ? pollDelayJitter : this::applyRandomPollDelayJitter;
    AsyncJobQueueValidation.validateQueueSettings(queueSettings);
    this.currentPollDelayMs = basePollDelayMs();
    this.runtimeGauges = registerRuntimeGauges();
  }

  void start() {
    synchronized (scheduleLock) {
      if (started) {
        return;
      }
      started = true;
      try {
        scheduleNextPoll(0);
      } catch (Throwable e) {
        started = false;
        logger.warn("Failed to schedule initial poll for queue {}", queueName, e);
        recordPollScheduleFailure();
        throw unchecked(e);
      }
    }
  }

  void stop() {
    synchronized (scheduleLock) {
      started = false;
      if (nextPollFuture != null) {
        try {
          nextPollFuture.cancel(false);
        } catch (Throwable e) {
          if (isJvmFatal(e)) {
            throw (Error) e;
          }
          logger.warn("Failed to cancel scheduled async queue poll for {}", queueName, e);
          meterRegistry
              .counter("asyncJobQueue.poll.cancel.failed", "queueName", queueName)
              .increment();
        } finally {
          nextPollFuture = null;
        }
      }
    }
    awaitActivePollBeforeExecutorShutdown();
    try {
      executor.shutdown();
    } finally {
      removeRuntimeGauges();
    }
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
      } catch (Throwable e) {
        if (isJvmFatal(e)) {
          throw (Error) e;
        }
        logger.warn("Failed to trigger immediate poll for queue {}", queueName, e);
        meterRegistry.counter("asyncJobQueue.trigger.failed", "queueName", queueName).increment();
        return;
      }
      if (previousPollFuture != null) {
        cancelSupersededPollFuture(previousPollFuture);
      }
    }
  }

  private void cancelSupersededPollFuture(ScheduledFuture<?> previousPollFuture) {
    try {
      previousPollFuture.cancel(false);
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      logger.warn("Failed to cancel superseded async queue poll for {}", queueName, e);
      meterRegistry.counter("asyncJobQueue.poll.cancel.failed", "queueName", queueName).increment();
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
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      recordClaimFailure(e);
      throw unchecked(e);
    } finally {
      meterRegistry
          .timer("asyncJobQueue.claim.latency", "queueName", queueName)
          .record(System.nanoTime() - claimStartNanos, TimeUnit.NANOSECONDS);
    }
    validateClaimedJobs(claimedJobs, claimLimit);

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

  private void validateClaimedJobs(List<AsyncJobRecord> claimedJobs, int claimLimit) {
    if (claimedJobs == null) {
      throw invalidClaim("nullList", "AsyncJobStore returned a null claimed job list");
    }
    if (claimedJobs.size() > claimLimit) {
      throw invalidClaim(
          "tooManyRecords",
          "AsyncJobStore returned "
              + claimedJobs.size()
              + " claimed jobs while polling queue "
              + queueName
              + " with claim limit "
              + claimLimit);
    }
    Set<AsyncJobId> claimedJobIds = new HashSet<>();
    for (AsyncJobRecord claimedJob : claimedJobs) {
      validateClaimedJob(claimedJob);
      if (!claimedJobIds.add(claimedJob.id())) {
        throw invalidClaim(
            "duplicateRecord",
            "AsyncJobStore returned duplicate claimed job "
                + claimedJob.id().value()
                + " while polling queue "
                + queueName);
      }
    }
  }

  private void validateClaimedJob(AsyncJobRecord claimedJob) {
    if (claimedJob == null) {
      throw invalidClaim("nullRecord", "AsyncJobStore returned a null claimed job record");
    }
    if (!queueName.equals(claimedJob.queueName())) {
      throw invalidClaim(
          "wrongQueueName",
          "AsyncJobStore returned job "
              + claimedJob.id().value()
              + " for queue "
              + claimedJob.queueName()
              + " while polling queue "
              + queueName);
    }
    if (claimedJob.status() != AsyncJobStatus.RUNNING) {
      throw invalidClaim(
          "wrongStatus",
          "AsyncJobStore returned job "
              + claimedJob.id().value()
              + " with status "
              + claimedJob.status().getDatabaseValue()
              + " while polling queue "
              + queueName);
    }
    if (claimedJob.attemptCount() <= 0) {
      throw invalidClaim(
          "invalidAttemptCount",
          "AsyncJobStore returned claimed job "
              + claimedJob.id().value()
              + " with non-positive attempt count "
              + claimedJob.attemptCount()
              + " while polling queue "
              + queueName);
    }
    if (!workerId.equals(claimedJob.workerId())) {
      throw invalidClaim(
          "wrongWorkerId",
          "AsyncJobStore returned job "
              + claimedJob.id().value()
              + " for worker "
              + claimedJob.workerId()
              + " while polling as "
              + workerId);
    }
  }

  private IllegalStateException invalidClaim(String reason, String message) {
    meterRegistry
        .counter("asyncJobQueue.claim.invalid", "queueName", queueName, "reason", reason)
        .increment();
    return new IllegalStateException(message);
  }

  int inFlightCount() {
    return inFlightCount.get();
  }

  private static int executorQueueSize(ThreadPoolTaskExecutor executor) {
    ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
    return threadPoolExecutor.getQueue().size();
  }

  private List<Meter> registerRuntimeGauges() {
    Tags queueTags = Tags.of("queueName", queueName);
    return List.<Meter>of(
        Gauge.builder("asyncJobQueue.inflight", this, AsyncJobQueueRuntime::inFlightCount)
            .tags(queueTags)
            .register(meterRegistry),
        Gauge.builder(
                "asyncJobQueue.executor.active", executor, ThreadPoolTaskExecutor::getActiveCount)
            .tags(queueTags)
            .register(meterRegistry),
        Gauge.builder(
                "asyncJobQueue.executor.queued", executor, AsyncJobQueueRuntime::executorQueueSize)
            .tags(queueTags)
            .register(meterRegistry),
        Gauge.builder("asyncJobQueue.poll.started", this, AsyncJobQueueRuntime::startedGauge)
            .tags(queueTags)
            .register(meterRegistry),
        Gauge.builder("asyncJobQueue.poll.scheduled", this, AsyncJobQueueRuntime::scheduledGauge)
            .tags(queueTags)
            .register(meterRegistry),
        Gauge.builder("asyncJobQueue.poll.active", this, AsyncJobQueueRuntime::activePollGauge)
            .tags(queueTags)
            .register(meterRegistry));
  }

  private int startedGauge() {
    return started ? 1 : 0;
  }

  private int scheduledGauge() {
    return nextPollFuture != null ? 1 : 0;
  }

  private int activePollGauge() {
    return pollInProgress.get() ? 1 : 0;
  }

  private void removeRuntimeGauges() {
    runtimeGauges.forEach(meterRegistry::remove);
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
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        synchronized (scheduleLock) {
          pollInProgress.set(false);
          scheduleLock.notifyAll();
        }
        throw (Error) e;
      }
      logPollFailure(e);
      recordPollFailure(e);
      pollCycleResult = PollCycleResult.failed(basePollDelayMs());
    }

    synchronized (scheduleLock) {
      pollInProgress.set(false);
      scheduleLock.notifyAll();
      if (wakeRequested.getAndSet(false)) {
        pollCycleResult = PollCycleResult.wakeup();
      }
      if (started) {
        scheduleNextPollAfterCycle(pollCycleResult.nextDelayMs());
      }
    }
  }

  private void scheduleNextPollAfterCycle(long delayMs) {
    try {
      scheduleNextPoll(delayMs);
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      logger.warn("Failed to schedule next poll for queue {}", queueName, e);
      recordPollScheduleFailure();
      scheduleRecoveryPollAfterScheduleFailure();
    }
  }

  private void scheduleRecoveryPollAfterScheduleFailure() {
    try {
      scheduleNextPoll(basePollDelayMs());
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      logger.warn("Failed to schedule recovery poll for queue {}", queueName, e);
      recordPollScheduleFailure();
      recordPollUnscheduled();
    }
  }

  private void awaitActivePollBeforeExecutorShutdown() {
    long shutdownAwaitTerminationMs = queueSettings.getShutdownAwaitTerminationMs();
    if (shutdownAwaitTerminationMs <= 0) {
      return;
    }

    long deadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownAwaitTerminationMs);
    boolean timedOut = false;
    boolean interrupted = false;
    synchronized (scheduleLock) {
      while (pollInProgress.get()) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
          timedOut = true;
          break;
        }
        try {
          scheduleLock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        } catch (InterruptedException e) {
          interrupted = true;
          break;
        }
      }
    }

    if (timedOut) {
      logger.warn(
          "Timed out waiting for active async queue poll to stop before executor shutdown for {}",
          queueName);
      meterRegistry.counter("asyncJobQueue.stop.pollTimeout", "queueName", queueName).increment();
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
      logger.warn(
          "Interrupted while waiting for active async queue poll to stop before executor shutdown for {}",
          queueName);
      meterRegistry
          .counter("asyncJobQueue.stop.pollInterrupted", "queueName", queueName)
          .increment();
    }
  }

  private void scheduleNextPoll(long delayMs) {
    long boundedDelayMs =
        scheduledPollDelayMs(delayMs, pollDelayJitter, queueSettings.getMaxPollIntervalMs());
    long pollSequence = scheduledPollSequence + 1;
    long previousPollSequence = scheduledPollSequence;
    scheduledPollSequence = pollSequence;
    ScheduledFuture<?> scheduledFuture;
    try {
      scheduledFuture =
          taskScheduler.schedule(
              () -> runScheduledPoll(pollSequence), startDateAfterMillis(boundedDelayMs));
      if (scheduledFuture == null) {
        throw new IllegalStateException("TaskScheduler returned null ScheduledFuture");
      }
    } catch (Throwable e) {
      if (scheduledPollSequence == pollSequence) {
        scheduledPollSequence = previousPollSequence;
      }
      throw unchecked(e);
    }
    if (scheduledPollSequence == pollSequence) {
      nextPollFuture = scheduledFuture;
    }
  }

  private void recordPollScheduleFailure() {
    meterRegistry.counter("asyncJobQueue.poll.schedule.failed", "queueName", queueName).increment();
  }

  private void recordPollUnscheduled() {
    meterRegistry.counter("asyncJobQueue.poll.unscheduled", "queueName", queueName).increment();
  }

  private void submitClaimedJob(AsyncJobRecord asyncJobRecord) {
    inFlightCount.incrementAndGet();
    try {
      executor.execute(() -> processClaimedJob(asyncJobRecord));
    } catch (Throwable e) {
      inFlightCount.decrementAndGet();
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      handleExecutorSubmitFailure(asyncJobRecord, e);
    }
  }

  private void handleExecutorSubmitFailure(AsyncJobRecord asyncJobRecord, Throwable e) {
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
      } catch (Throwable transitionException) {
        if (isJvmFatal(transitionException)) {
          throw (Error) transitionException;
        }
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
        notifyJobFailedPermanently(
            failedCallbackRecord(asyncJobRecord, null, errorMessage), e, errorMessage);
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
    } catch (Throwable transitionException) {
      if (isJvmFatal(transitionException)) {
        throw (Error) transitionException;
      }
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
    } else {
      meterRegistry.counter("asyncJobQueue.retried", "queueName", queueName).increment();
    }
  }

  private void processClaimedJob(AsyncJobRecord asyncJobRecord) {
    long processingStartNanos = System.nanoTime();
    ScheduledFuture<?> heartbeatFuture = null;
    try {
      if (asyncJobRecord.attemptCount() > queueSettings.getMaxAttempts()) {
        handleClaimedAttemptBudgetExhausted(asyncJobRecord);
        return;
      }
      try {
        heartbeatFuture = scheduleHeartbeat(asyncJobRecord);
      } catch (Throwable e) {
        if (isJvmFatal(e)) {
          throw (Error) e;
        }
        handleHeartbeatScheduleFailure(asyncJobRecord, e);
        return;
      }
      AsyncJobHandlerResult asyncJobHandlerResult =
          Objects.requireNonNull(
              asyncJobHandler.process(asyncJobRecord), "AsyncJobHandlerResult must not be null");
      applyHandlerResult(asyncJobRecord, asyncJobHandlerResult);
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      handleProcessingFailure(asyncJobRecord, e);
      if (hasCause(e, InterruptedException.class)) {
        Thread.currentThread().interrupt();
      }
    } finally {
      Throwable fatalCleanupFailure = null;
      if (heartbeatFuture != null) {
        try {
          heartbeatFuture.cancel(false);
        } catch (Throwable e) {
          if (isJvmFatal(e)) {
            fatalCleanupFailure = e;
          } else {
            logger.warn(
                "Failed to cancel async queue heartbeat for queue {}, job {}",
                queueName,
                asyncJobRecord.id(),
                e);
            meterRegistry
                .counter("asyncJobQueue.heartbeat.cancel.failed", "queueName", queueName)
                .increment();
          }
        }
      }
      inFlightCount.decrementAndGet();
      meterRegistry
          .timer("asyncJobQueue.processing.latency", "queueName", queueName)
          .record(System.nanoTime() - processingStartNanos, TimeUnit.NANOSECONDS);
      triggerPollNow();
      if (fatalCleanupFailure != null) {
        throw (Error) fatalCleanupFailure;
      }
    }
  }

  private void handleClaimedAttemptBudgetExhausted(AsyncJobRecord asyncJobRecord) {
    String errorMessage =
        "Attempt budget exhausted before handler invocation: attempt "
            + asyncJobRecord.attemptCount()
            + " exceeded maxAttempts "
            + queueSettings.getMaxAttempts();
    logger.error(
        "Async job attempt budget exhausted for queue {}, job {}; marking failed after {} attempts",
        queueName,
        asyncJobRecord.id(),
        asyncJobRecord.attemptCount());
    meterRegistry.counter("asyncJobQueue.attempt.exhausted", "queueName", queueName).increment();

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
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      logger.warn(
          "Failed to mark attempt-budget-exhausted job {} failed for queue {}",
          asyncJobRecord.id(),
          queueName,
          e);
      recordTransitionFailure("attemptBudgetExhausted");
      return;
    }
    if (!markedFailed) {
      logger.warn(
          "Failed to mark attempt-budget-exhausted job {} failed for queue {}",
          asyncJobRecord.id(),
          queueName);
      recordTransitionFailure("attemptBudgetExhausted");
    } else {
      meterRegistry.counter("asyncJobQueue.failed", "queueName", queueName).increment();
      notifyJobFailedPermanently(
          failedCallbackRecord(asyncJobRecord, null, errorMessage),
          new IllegalStateException(errorMessage),
          errorMessage);
    }
  }

  private void handleHeartbeatScheduleFailure(AsyncJobRecord asyncJobRecord, Throwable e) {
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
      } catch (Throwable transitionException) {
        if (isJvmFatal(transitionException)) {
          throw (Error) transitionException;
        }
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
        notifyJobFailedPermanently(
            failedCallbackRecord(asyncJobRecord, null, errorMessage), e, errorMessage);
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
        } catch (Throwable e) {
          if (isJvmFatal(e)) {
            throw (Error) e;
          }
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
          notifyJobDone(
              doneCallbackRecord(asyncJobRecord, asyncJobHandlerResult), asyncJobHandlerResult);
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
                    Duration.ofMillis(defaultHandlerRequeueDelayMs()),
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
        } catch (Throwable e) {
          if (isJvmFatal(e)) {
            throw (Error) e;
          }
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
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
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
      notifyJobFailedPermanently(
          failedCallbackRecord(asyncJobRecord, asyncJobHandlerResult.jobData(), errorMessage),
          new IllegalStateException(errorMessage),
          errorMessage);
    }
  }

  private AsyncJobRecord failedCallbackRecord(
      AsyncJobRecord asyncJobRecord, String jobData, String lastError) {
    return new AsyncJobRecord(
        asyncJobRecord.id(),
        asyncJobRecord.queueName(),
        AsyncJobStatus.FAILED,
        asyncJobRecord.availableAt(),
        null,
        null,
        null,
        jobData != null ? jobData : asyncJobRecord.jobData(),
        asyncJobRecord.attemptCount(),
        lastError,
        asyncJobRecord.createdDate(),
        java.time.Instant.now(),
        asyncJobRecord.leaseReclaimed());
  }

  private AsyncJobRecord doneCallbackRecord(
      AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult) {
    return new AsyncJobRecord(
        asyncJobRecord.id(),
        asyncJobRecord.queueName(),
        AsyncJobStatus.DONE,
        asyncJobRecord.availableAt(),
        null,
        null,
        null,
        asyncJobHandlerResult.jobData() != null
            ? asyncJobHandlerResult.jobData()
            : asyncJobRecord.jobData(),
        asyncJobRecord.attemptCount(),
        null,
        asyncJobRecord.createdDate(),
        java.time.Instant.now(),
        asyncJobRecord.leaseReclaimed());
  }

  private boolean isJvmFatal(Throwable throwable) {
    return AsyncJobQueueFatalErrors.isJvmFatal(throwable);
  }

  private RuntimeException unchecked(Throwable throwable) {
    if (isJvmFatal(throwable)) {
      throw (Error) throwable;
    }
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (throwable instanceof Error error) {
      throw error;
    }
    return new IllegalStateException(throwable);
  }

  private void handleProcessingFailure(AsyncJobRecord asyncJobRecord, Throwable e) {
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
      } catch (Throwable transitionException) {
        if (isJvmFatal(transitionException)) {
          throw (Error) transitionException;
        }
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
        notifyJobFailedPermanently(
            failedCallbackRecord(asyncJobRecord, null, errorMessage), e, errorMessage);
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
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
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

  private void notifyJobDone(
      AsyncJobRecord asyncJobRecord, AsyncJobHandlerResult asyncJobHandlerResult) {
    try {
      asyncJobHandler.onJobDone(asyncJobRecord, asyncJobHandlerResult);
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      logger.warn(
          "Async job handler done callback failed for queue {}, job {}",
          queueName,
          asyncJobRecord.id(),
          e);
      recordHandlerCompletionCallbackFailure("done");
    }
  }

  private void notifyJobFailedPermanently(
      AsyncJobRecord asyncJobRecord, Throwable failure, String lastError) {
    try {
      asyncJobHandler.onJobFailedPermanently(asyncJobRecord, failure, lastError);
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      logger.warn(
          "Async job handler permanent-failure callback failed for queue {}, job {}",
          queueName,
          asyncJobRecord.id(),
          e);
      recordHandlerCompletionCallbackFailure("failed");
    }
  }

  long retryDelayMs(int attemptCount) {
    long delayMs = basePollDelayMs();
    long maxRetryDelayMs = maxRetryDelayMs();
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

  private long defaultHandlerRequeueDelayMs() {
    return applyRetryDelayJitter(basePollDelayMs());
  }

  private long applyRetryDelayJitter(long delayMs) {
    return boundedJitteredDelayMs(
        delayMs,
        applyRandomDelayJitter(delayMs, queueSettings.getRetryJitterPercent()),
        maxRetryDelayMs());
  }

  private long maxRetryDelayMs() {
    return Math.max(basePollDelayMs(), queueSettings.getMaxRetryDelayMs());
  }

  private void recordTransitionFailure(String transition) {
    meterRegistry
        .counter(
            "asyncJobQueue.transition.failed", "queueName", queueName, "transition", transition)
        .increment();
  }

  private void recordHandlerCompletionCallbackFailure(String callback) {
    meterRegistry
        .counter(
            "asyncJobQueue.handler.completion.failed", "queueName", queueName, "callback", callback)
        .increment();
  }

  private void recordClaimFailure(Throwable e) {
    meterRegistry
        .counter(
            "asyncJobQueue.claim.failed", "queueName", queueName, "failure", claimFailureKind(e))
        .increment();
  }

  private void logPollFailure(Throwable e) {
    String failureKind = claimFailureKind(e);
    if (isTransientClaimFailure(failureKind)) {
      logger.warn("Transient async queue poll failed for queue {}: {}", queueName, failureKind, e);
    } else {
      logger.error("Async queue poll failed for queue {}", queueName, e);
    }
  }

  private void recordPollFailure(Throwable e) {
    String failureKind = claimFailureKind(e);
    meterRegistry.counter("asyncJobQueue.poll.failed", "queueName", queueName).increment();
    meterRegistry
        .counter(
            "asyncJobQueue.poll.failed.byFailure", "queueName", queueName, "failure", failureKind)
        .increment();
  }

  static String claimFailureKind(Throwable throwable) {
    String sqlFailureKind = sqlFailureKind(throwable);
    if (sqlFailureKind != null) {
      return sqlFailureKind;
    }
    if (hasCause(throwable, DeadlockLoserDataAccessException.class)) {
      return "deadlock";
    }
    if (hasMessageContaining(throwable, "deadlock")) {
      return "deadlock";
    }
    if (hasCause(
        throwable, CannotAcquireLockException.class, PessimisticLockingFailureException.class)) {
      return "lock";
    }
    if (hasCause(throwable, QueryTimeoutException.class)) {
      return "timeout";
    }
    if (hasCause(throwable, DataAccessException.class)) {
      return "dataAccess";
    }
    return "other";
  }

  private static String sqlFailureKind(Throwable throwable) {
    Set<Throwable> visitedThrowables = throwableIdentitySet();
    Deque<Throwable> pendingThrowables = pendingThrowables(throwable);
    while (!pendingThrowables.isEmpty()) {
      Throwable current = pendingThrowables.removeFirst();
      if (!visitedThrowables.add(current)) {
        continue;
      }
      if (current instanceof SQLException sqlException) {
        String failureKind = sqlFailureKind(sqlException);
        if (failureKind != null) {
          return failureKind;
        }
      }
      appendRelatedThrowables(pendingThrowables, current);
    }
    return null;
  }

  private static String sqlFailureKind(SQLException sqlException) {
    int errorCode = sqlException.getErrorCode();
    if (errorCode == 1213) {
      return "deadlock";
    }
    if (errorCode == 1205) {
      return "lock";
    }
    String sqlState = sqlException.getSQLState();
    if ("40001".equals(sqlState)) {
      return "serialization";
    }
    if ("40P01".equals(sqlState)) {
      return "deadlock";
    }
    if ("55P03".equals(sqlState)) {
      return "lock";
    }
    if ("57014".equals(sqlState)) {
      return "timeout";
    }
    return null;
  }

  private static boolean isTransientClaimFailure(String failureKind) {
    return "deadlock".equals(failureKind)
        || "serialization".equals(failureKind)
        || "lock".equals(failureKind)
        || "timeout".equals(failureKind);
  }

  @SafeVarargs
  private static boolean hasCause(
      Throwable throwable, Class<? extends Throwable>... exceptionTypes) {
    Set<Throwable> visitedThrowables = throwableIdentitySet();
    Deque<Throwable> pendingThrowables = pendingThrowables(throwable);
    while (!pendingThrowables.isEmpty()) {
      Throwable current = pendingThrowables.removeFirst();
      if (!visitedThrowables.add(current)) {
        continue;
      }
      for (Class<? extends Throwable> exceptionType : exceptionTypes) {
        if (exceptionType.isInstance(current)) {
          return true;
        }
      }
      appendRelatedThrowables(pendingThrowables, current);
    }
    return false;
  }

  private static boolean hasMessageContaining(Throwable throwable, String needle) {
    Set<Throwable> visitedThrowables = throwableIdentitySet();
    Deque<Throwable> pendingThrowables = pendingThrowables(throwable);
    while (!pendingThrowables.isEmpty()) {
      Throwable current = pendingThrowables.removeFirst();
      if (!visitedThrowables.add(current)) {
        continue;
      }
      String message = current.getMessage();
      if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
        return true;
      }
      appendRelatedThrowables(pendingThrowables, current);
    }
    return false;
  }

  private static Deque<Throwable> pendingThrowables(Throwable throwable) {
    Deque<Throwable> pendingThrowables = new ArrayDeque<>();
    if (throwable != null) {
      pendingThrowables.add(throwable);
    }
    return pendingThrowables;
  }

  private static void appendRelatedThrowables(
      Deque<Throwable> pendingThrowables, Throwable throwable) {
    Throwable cause = throwable.getCause();
    if (cause != null) {
      pendingThrowables.add(cause);
    }
    for (Throwable suppressed : throwable.getSuppressed()) {
      if (suppressed != null) {
        pendingThrowables.add(suppressed);
      }
    }
    if (throwable instanceof SQLException sqlException) {
      SQLException nextException = sqlException.getNextException();
      if (nextException != null) {
        pendingThrowables.add(nextException);
      }
    }
  }

  private static Set<Throwable> throwableIdentitySet() {
    return Collections.newSetFromMap(new IdentityHashMap<>());
  }

  private String errorMessage(Throwable e) {
    StringBuilder message = new StringBuilder();
    Set<Throwable> visitedThrowables = throwableIdentitySet();
    Deque<Throwable> pendingThrowables = pendingThrowables(e);
    while (!pendingThrowables.isEmpty()) {
      Throwable current = pendingThrowables.removeFirst();
      if (!visitedThrowables.add(current)) {
        continue;
      }
      if (!message.isEmpty()) {
        message.append("; caused by ");
      }
      appendThrowableSummary(message, current);
      if (message.length() >= AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH) {
        break;
      }
      appendRelatedThrowables(pendingThrowables, current);
    }
    return AsyncJobQueueValidation.truncateLastError(message.toString());
  }

  private void appendThrowableSummary(StringBuilder message, Throwable throwable) {
    message.append(throwable.getClass().getName());
    if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
      message.append(": ").append(throwable.getMessage());
    }
    if (throwable instanceof SQLException sqlException) {
      appendSqlExceptionSummary(message, sqlException);
    }
  }

  private void appendSqlExceptionSummary(StringBuilder message, SQLException sqlException) {
    String sqlState = sqlException.getSQLState();
    int errorCode = sqlException.getErrorCode();
    if ((sqlState == null || sqlState.isBlank()) && errorCode == 0) {
      return;
    }
    message.append(" [");
    boolean hasSqlState = sqlState != null && !sqlState.isBlank();
    if (hasSqlState) {
      message.append("SQLState=").append(sqlState);
    }
    if (errorCode != 0) {
      if (hasSqlState) {
        message.append(", ");
      }
      message.append("errorCode=").append(errorCode);
    }
    message.append("]");
  }

  private ScheduledFuture<?> scheduleHeartbeat(AsyncJobRecord asyncJobRecord) {
    if (queueSettings.getHeartbeatIntervalMs() <= 0) {
      return null;
    }

    ScheduledFuture<?> scheduledFuture =
        taskScheduler.scheduleAtFixedRate(
            () -> heartbeat(asyncJobRecord),
            startDateAfterMillis(queueSettings.getHeartbeatIntervalMs()),
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
    } catch (Throwable e) {
      if (isJvmFatal(e)) {
        throw (Error) e;
      }
      logger.warn(
          "Failed to renew heartbeat for queue {}, job {}", queueName, asyncJobRecord.id(), e);
      meterRegistry.counter("asyncJobQueue.heartbeat.failed", "queueName", queueName).increment();
    }
  }

  private void recordQueueWaitLatency(List<AsyncJobRecord> claimedJobs) {
    for (AsyncJobRecord claimedJob : claimedJobs) {
      if (claimedJob.leaseReclaimed()) {
        continue;
      }
      if (claimedJob.availableAt() == null || claimedJob.updatedDate() == null) {
        continue;
      }
      java.time.Duration queueWaitLatency =
          java.time.Duration.between(claimedJob.availableAt(), claimedJob.updatedDate());
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
    return applyRandomDelayJitter(delayMs, queueSettings.getPollJitterPercent());
  }

  private long applyRandomDelayJitter(long delayMs, int jitterPercent) {
    if (jitterPercent <= 0 || delayMs <= 0) {
      return delayMs;
    }

    long jitterRangeMs = jitterRangeMs(delayMs, jitterPercent);
    long jitter = ThreadLocalRandom.current().nextLong(-jitterRangeMs, jitterRangeMs + 1);
    return positiveJitteredDelayMs(delayMs, addJitter(delayMs, jitter));
  }

  static long positiveJitteredDelayMs(long originalDelayMs, long jitteredDelayMs) {
    if (originalDelayMs <= 0) {
      return Math.max(0, jitteredDelayMs);
    }
    return Math.max(1, jitteredDelayMs);
  }

  static long boundedJitteredDelayMs(long originalDelayMs, long jitteredDelayMs, long maxDelayMs) {
    long positiveDelayMs = positiveJitteredDelayMs(originalDelayMs, jitteredDelayMs);
    if (originalDelayMs <= 0) {
      return positiveDelayMs;
    }
    return Math.min(positiveDelayMs, Math.max(1, maxDelayMs));
  }

  static long scheduledPollDelayMs(long delayMs, LongUnaryOperator pollDelayJitter) {
    return scheduledPollDelayMs(delayMs, pollDelayJitter, Long.MAX_VALUE);
  }

  static long scheduledPollDelayMs(
      long delayMs, LongUnaryOperator pollDelayJitter, long maxDelayMs) {
    Objects.requireNonNull(pollDelayJitter);
    long boundedDelayMs = Math.max(0, delayMs);
    if (boundedDelayMs <= 0) {
      return 0;
    }
    return boundedJitteredDelayMs(
        boundedDelayMs, pollDelayJitter.applyAsLong(boundedDelayMs), maxDelayMs);
  }

  static long jitterRangeMs(long delayMs, int jitterPercent) {
    if (jitterPercent <= 0 || delayMs <= 0) {
      return 0;
    }
    long wholeHundreds = delayMs / 100;
    long remainingHundredths = delayMs % 100;
    long jitterRangeMs = wholeHundreds * jitterPercent + remainingHundredths * jitterPercent / 100;
    return Math.max(1, Math.min(Long.MAX_VALUE - 1, jitterRangeMs));
  }

  static long addJitter(long delayMs, long jitterMs) {
    if (jitterMs > 0 && delayMs > Long.MAX_VALUE - jitterMs) {
      return Long.MAX_VALUE;
    }
    if (jitterMs < 0) {
      long reduction = jitterMs == Long.MIN_VALUE ? Long.MAX_VALUE : -jitterMs;
      if (delayMs <= reduction) {
        return 0;
      }
    }
    return delayMs + jitterMs;
  }

  static Date startDateAfterMillis(long delayMs) {
    long boundedDelayMs = Math.max(0, delayMs);
    long nowMs = System.currentTimeMillis();
    if (boundedDelayMs > Long.MAX_VALUE - nowMs) {
      return new Date(Long.MAX_VALUE);
    }
    return new Date(nowMs + boundedDelayMs);
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
