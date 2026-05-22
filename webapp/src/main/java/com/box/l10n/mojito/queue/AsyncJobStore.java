package com.box.l10n.mojito.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

  /** Fetches jobs by id; missing ids are omitted from the returned list. */
  List<AsyncJobRecord> getByIds(List<AsyncJobId> ids);
}
