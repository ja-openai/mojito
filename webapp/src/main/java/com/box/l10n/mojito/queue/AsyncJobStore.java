package com.box.l10n.mojito.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Storage contract for async job state transitions within a named queue.
 *
 * <p>Async job orchestration depends on this interface instead of a specific backend. Current
 * implementations include JDBC-backed persistence and an in-memory implementation for tests and
 * local runs.
 */
public interface AsyncJobStore {

  /**
   * Enqueues a job as claimable work at or after {@code availableAt}.
   *
   * @return persistent async job id
   */
  AsyncJobId enqueue(String queueName, String jobData, Instant availableAt);

  /**
   * Enqueues a job that should be claimable immediately.
   *
   * <p>Durable implementations should anchor "now" on database time, not JVM time, so immediate
   * availability stays consistent with claim ordering under pod clock skew.
   *
   * @return persistent async job id
   */
  default AsyncJobId enqueueNow(String queueName, String jobData) {
    return enqueue(queueName, jobData, Instant.now());
  }

  /**
   * Claims up to {@code limit} jobs for a worker and assigns a new lease token + lease window.
   *
   * <p>Claim policy is backend-specific but generally includes:
   *
   * <ul>
   *   <li>queued jobs with {@code availableAt <= now}
   *   <li>running jobs whose lease already expired
   * </ul>
   */
  List<AsyncJobRecord> claimNextJobs(
      String queueName, int limit, String workerId, Duration leaseDuration);

  /**
   * Renews the lease for a currently running job.
   *
   * @return true if lease was renewed, false if the lease expired or caller no longer owns it
   */
  boolean heartbeat(
      String queueName, AsyncJobId id, String workerId, String leaseToken, Duration leaseDuration);

  /**
   * Marks a running job as done and optionally updates job data atomically.
   *
   * @param jobData optional replacement payload; null keeps existing value
   * @return true if transition succeeded, false if the lease expired or caller no longer owns it
   */
  boolean markDone(
      String queueName, AsyncJobId id, String workerId, String leaseToken, String jobData);

  /**
   * Requeue a running job for a future claim.
   *
   * @param jobData optional replacement payload; null keeps the existing value
   * @param lastError optional latest processing error; null clears the previous error
   * @return true if transition succeeded, false if the lease expired or caller no longer owns it
   */
  boolean requeue(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Instant availableAt,
      String jobData,
      String lastError);

  /**
   * Requeue a running job after a relative delay.
   *
   * <p>Durable implementations should anchor this delay on database time, not JVM time, so retry
   * scheduling stays consistent with lease fencing under pod clock skew.
   *
   * @param jobData optional replacement payload; null keeps the existing value
   * @param lastError optional latest processing error; null clears the previous error
   * @return true if transition succeeded, false if the lease expired or caller no longer owns it
   */
  default boolean requeueAfter(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Duration delay,
      String jobData,
      String lastError) {
    Objects.requireNonNull(delay);
    Duration boundedDelay = delay.isNegative() ? Duration.ZERO : delay;
    return requeue(
        queueName,
        id,
        workerId,
        leaseToken,
        AsyncJobQueueValidation.plusDuration("availableAt", Instant.now(), boundedDelay),
        jobData,
        lastError);
  }

  /**
   * Marks a running job as terminally failed.
   *
   * @param jobData optional replacement payload; null keeps existing value
   * @param lastError failure summary to persist for inspection
   * @return true if transition succeeded, false if the lease expired or caller no longer owns it
   */
  boolean markFailed(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      String jobData,
      String lastError);

  /** Returns per-status counters for a queue at query time. */
  List<AsyncJobStatusCount> countByStatus(String queueName);

  /**
   * Returns ready queued work for a queue at query time.
   *
   * <p>"Ready" means status is queued and availability is not in the future. Delayed retries remain
   * visible in status counts but do not contribute to ready backlog or ready-age gauges.
   */
  AsyncJobReadyStatus readyStatus(String queueName);

  /**
   * Returns running jobs whose lease already expired for a queue at query time.
   *
   * <p>Expired leases are claimable by the queue runtime, but they are reported separately from
   * ready queued work so operators can distinguish new backlog from crashed/stalled worker
   * recovery.
   */
  AsyncJobExpiredLeaseStatus expiredLeaseStatus(String queueName);

  /**
   * Lists recent jobs for a queue/status, primarily for operator inspection.
   *
   * <p>This is not used for claiming work; claim ordering remains backend-specific.
   */
  List<AsyncJobRecord> findByStatus(String queueName, AsyncJobStatus status, int limit);

  /**
   * Moves a terminal failed job back to queued state for operator-driven replay.
   *
   * <p>Replay resets the attempt budget and preserves the last error until the job succeeds or
   * fails again, so operators can still see why it was replayed.
   *
   * @param jobData optional replacement payload; null keeps existing value
   * @return true if a failed job was requeued, false if the job was not failed or did not exist
   */
  boolean requeueFailed(String queueName, AsyncJobId id, Instant availableAt, String jobData);

  /**
   * Moves a terminal failed job back to queued state for immediate operator-driven replay.
   *
   * <p>Durable implementations should anchor "now" on database time, not JVM time, so replay
   * availability stays consistent with claim ordering under pod clock skew.
   *
   * @param jobData optional replacement payload; null keeps existing value
   * @return true if a failed job was requeued, false if the job was not failed or did not exist
   */
  default boolean requeueFailedNow(String queueName, AsyncJobId id, String jobData) {
    return requeueFailed(queueName, id, Instant.now(), jobData);
  }

  /**
   * Deletes a bounded batch of terminal jobs older than {@code updatedBefore}.
   *
   * <p>Only {@link AsyncJobStatus#DONE} and {@link AsyncJobStatus#FAILED} are accepted. Operators
   * can use this as a retention primitive without risking queued/running work.
   *
   * @return number of rows deleted
   */
  int deleteTerminalJobs(String queueName, AsyncJobStatus status, Instant updatedBefore, int limit);

  /**
   * Fetches jobs by id; missing ids are omitted from the returned list.
   *
   * <p>Implementations should reject excessive id batches instead of issuing unbounded lookups.
   */
  List<AsyncJobRecord> getByIds(List<AsyncJobId> ids);
}
