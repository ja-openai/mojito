package com.box.l10n.mojito.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * In-memory async job store implementation useful for unit tests and local runs.
 *
 * <p>This implementation is process-local and non-durable; all state is lost on JVM restart.
 */
public class InMemoryAsyncJobStore implements AsyncJobStore {

  private static final Comparator<StoredAsyncJob> CLAIM_ORDER =
      Comparator.comparing(StoredAsyncJob::availableAt).thenComparingLong(StoredAsyncJob::idLong);

  private final AtomicLong idGenerator = new AtomicLong();
  private final Map<AsyncJobId, StoredAsyncJob> jobsById = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> queueLocks = new ConcurrentHashMap<>();

  @Override
  public AsyncJobId enqueue(String queueName, String jobData, Instant availableAt) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    Objects.requireNonNull(jobData);
    Instant validatedAvailableAt =
        AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);

    return withQueueLock(
        queueName,
        () -> {
          long idLong = idGenerator.incrementAndGet();
          AsyncJobId id = new AsyncJobId(String.valueOf(idLong));
          Instant now = Instant.now();
          jobsById.put(
              id,
              new StoredAsyncJob(
                  idLong,
                  id,
                  queueName,
                  AsyncJobStatus.QUEUED,
                  validatedAvailableAt,
                  null,
                  null,
                  null,
                  jobData,
                  0,
                  null,
                  now,
                  now));
          return id;
        });
  }

  @Override
  public List<AsyncJobRecord> claimNextJobs(
      String queueName, int limit, String workerId, Duration leaseDuration) {
    if (limit <= 0) {
      return Collections.emptyList();
    }
    int boundedLimit = AsyncJobQueueValidation.validateStoreQueryLimit("limit", limit);

    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    Objects.requireNonNull(leaseDuration);
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be > 0");
    }

    return withQueueLock(
        queueName,
        () -> {
          Instant now = Instant.now();
          Instant leaseUntil =
              AsyncJobQueueValidation.plusDurationWithinDatabaseTimestampRange(
                  "leaseUntil", now, leaseDuration);

          List<StoredAsyncJob> claimable =
              jobsById.values().stream()
                  .filter(
                      job ->
                          job.queueName().equals(queueName)
                              && ((job.status() == AsyncJobStatus.QUEUED
                                      && !job.availableAt().isAfter(now))
                                  || (job.status() == AsyncJobStatus.RUNNING
                                      && job.leaseUntil() != null
                                      && !job.leaseUntil().isAfter(now))))
                  .sorted(CLAIM_ORDER)
                  .limit(boundedLimit)
                  .toList();

          if (claimable.isEmpty()) {
            return Collections.emptyList();
          }

          List<AsyncJobRecord> claimedJobs = new ArrayList<>(claimable.size());
          for (StoredAsyncJob job : claimable) {
            String leaseToken = UUID.randomUUID().toString();
            StoredAsyncJob updated =
                job.withClaim(AsyncJobStatus.RUNNING, workerId, leaseToken, leaseUntil, now);
            jobsById.put(job.id(), updated);
            claimedJobs.add(toAsyncJob(updated, job.status() == AsyncJobStatus.RUNNING));
          }
          return claimedJobs;
        });
  }

  @Override
  public boolean heartbeat(
      String queueName, AsyncJobId id, String workerId, String leaseToken, Duration leaseDuration) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    Objects.requireNonNull(leaseDuration);
    validateLeaseToken(leaseToken);
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be > 0");
    }

    return withQueueLock(
        queueName,
        () -> {
          StoredAsyncJob job = jobsById.get(id);
          if (!isCurrentLeaseOwner(job, queueName, workerId, leaseToken)) {
            return false;
          }
          Instant now = Instant.now();
          jobsById.put(
              job.id(),
              job.withLeaseUntil(
                  AsyncJobQueueValidation.plusDurationWithinDatabaseTimestampRange(
                      "leaseUntil", now, leaseDuration),
                  now));
          return true;
        });
  }

  @Override
  public boolean markDone(
      String queueName, AsyncJobId id, String workerId, String leaseToken, String jobData) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    validateLeaseToken(leaseToken);

    return withQueueLock(
        queueName,
        () -> {
          StoredAsyncJob job = jobsById.get(id);
          if (!isCurrentLeaseOwner(job, queueName, workerId, leaseToken)) {
            return false;
          }
          Instant now = Instant.now();
          String nextJobData = jobData == null ? job.jobData() : jobData;
          jobsById.put(job.id(), job.withCompletion(nextJobData, now));
          return true;
        });
  }

  @Override
  public boolean requeue(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Instant availableAt,
      String jobData,
      String lastError) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    Instant validatedAvailableAt =
        AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);
    validateLeaseToken(leaseToken);

    return withQueueLock(
        queueName,
        () -> {
          StoredAsyncJob job = jobsById.get(id);
          if (!isCurrentLeaseOwner(job, queueName, workerId, leaseToken)) {
            return false;
          }
          Instant now = Instant.now();
          String nextJobData = jobData == null ? job.jobData() : jobData;
          jobsById.put(
              job.id(),
              job.withRequeue(
                  validatedAvailableAt,
                  nextJobData,
                  AsyncJobQueueValidation.truncateLastError(lastError),
                  now));
          return true;
        });
  }

  @Override
  public boolean markFailed(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      String jobData,
      String lastError) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    validateLeaseToken(leaseToken);

    return withQueueLock(
        queueName,
        () -> {
          StoredAsyncJob job = jobsById.get(id);
          if (!isCurrentLeaseOwner(job, queueName, workerId, leaseToken)) {
            return false;
          }
          Instant now = Instant.now();
          String nextJobData = jobData == null ? job.jobData() : jobData;
          jobsById.put(
              job.id(),
              job.withFailure(
                  nextJobData, AsyncJobQueueValidation.truncateLastError(lastError), now));
          return true;
        });
  }

  @Override
  public List<AsyncJobStatusCount> countByStatus(String queueName) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    return withQueueLock(
        queueName,
        () ->
            jobsById.values().stream()
                .filter(job -> job.queueName().equals(queueName))
                .collect(Collectors.groupingBy(StoredAsyncJob::status, Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AsyncJobStatusCount(entry.getKey(), entry.getValue()))
                .toList());
  }

  @Override
  public AsyncJobReadyStatus readyStatus(String queueName) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    return withQueueLock(
        queueName,
        () -> {
          Instant now = Instant.now();
          List<StoredAsyncJob> readyJobs =
              jobsById.values().stream()
                  .filter(job -> job.queueName().equals(queueName))
                  .filter(job -> job.status() == AsyncJobStatus.QUEUED)
                  .filter(job -> !job.availableAt().isAfter(now))
                  .toList();
          Instant oldestAvailableAt =
              readyJobs.stream()
                  .map(StoredAsyncJob::availableAt)
                  .min(Comparator.naturalOrder())
                  .orElse(null);
          return new AsyncJobReadyStatus(readyJobs.size(), oldestAvailableAt, now);
        });
  }

  @Override
  public List<AsyncJobRecord> findByStatus(String queueName, AsyncJobStatus status, int limit) {
    if (limit <= 0) {
      return Collections.emptyList();
    }
    int boundedLimit = AsyncJobQueueValidation.validateStoreQueryLimit("limit", limit);

    AsyncJobQueueValidation.validateQueueName(queueName);
    Objects.requireNonNull(status);
    return withQueueLock(
        queueName,
        () ->
            jobsById.values().stream()
                .filter(job -> job.queueName().equals(queueName) && job.status() == status)
                .sorted(
                    Comparator.comparing(StoredAsyncJob::updatedDate)
                        .thenComparingLong(StoredAsyncJob::idLong)
                        .reversed())
                .limit(boundedLimit)
                .map(this::toAsyncJob)
                .toList());
  }

  @Override
  public boolean requeueFailed(
      String queueName, AsyncJobId id, Instant availableAt, String jobData) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    Instant validatedAvailableAt =
        AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);

    return withQueueLock(
        queueName,
        () -> {
          StoredAsyncJob job = jobsById.get(id);
          if (job == null
              || !job.queueName().equals(queueName)
              || job.status() != AsyncJobStatus.FAILED) {
            return false;
          }
          Instant now = Instant.now();
          String nextJobData = jobData == null ? job.jobData() : jobData;
          jobsById.put(job.id(), job.withFailedReplay(validatedAvailableAt, nextJobData, now));
          return true;
        });
  }

  @Override
  public int deleteTerminalJobs(
      String queueName, AsyncJobStatus status, Instant updatedBefore, int limit) {
    if (limit <= 0) {
      return 0;
    }
    int boundedLimit = AsyncJobQueueValidation.validateStoreQueryLimit("limit", limit);

    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobStatus terminalStatus = AsyncJobQueueValidation.validateTerminalStatus(status);
    Instant validatedUpdatedBefore =
        AsyncJobQueueValidation.validateDatabaseTimestamp("updatedBefore", updatedBefore);

    return withQueueLock(
        queueName,
        () -> {
          List<AsyncJobId> idsToDelete =
              jobsById.values().stream()
                  .filter(
                      job ->
                          job.queueName().equals(queueName)
                              && job.status() == terminalStatus
                              && job.updatedDate().isBefore(validatedUpdatedBefore))
                  .sorted(Comparator.comparing(StoredAsyncJob::updatedDate))
                  .limit(boundedLimit)
                  .map(StoredAsyncJob::id)
                  .toList();
          idsToDelete.forEach(jobsById::remove);
          return idsToDelete.size();
        });
  }

  @Override
  public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    AsyncJobQueueValidation.validateStoreQueryLimit("ids", ids.size());

    List<AsyncJobRecord> jobs = new ArrayList<>(ids.size());
    for (AsyncJobId id : ids) {
      StoredAsyncJob job = jobsById.get(id);
      if (job != null) {
        jobs.add(toAsyncJob(job));
      }
    }
    return jobs;
  }

  private <T> T withQueueLock(String queueName, java.util.function.Supplier<T> action) {
    ReentrantLock lock = queueLocks.computeIfAbsent(queueName, key -> new ReentrantLock());
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private boolean isCurrentLeaseOwner(
      StoredAsyncJob job, String queueName, String workerId, String leaseToken) {
    return job != null
        && job.queueName().equals(queueName)
        && job.status() == AsyncJobStatus.RUNNING
        && job.leaseUntil() != null
        && job.leaseUntil().isAfter(Instant.now())
        && workerId.equals(job.workerId())
        && leaseToken.equals(job.leaseToken());
  }

  private AsyncJobRecord toAsyncJob(StoredAsyncJob job) {
    return toAsyncJob(job, false);
  }

  private AsyncJobRecord toAsyncJob(StoredAsyncJob job, boolean leaseReclaimed) {
    return new AsyncJobRecord(
        job.id(),
        job.queueName(),
        job.status(),
        job.availableAt(),
        job.leaseUntil(),
        job.workerId(),
        job.leaseToken(),
        job.jobData(),
        job.attemptCount(),
        job.lastError(),
        job.createdDate(),
        job.updatedDate(),
        leaseReclaimed);
  }

  private void validateLeaseToken(String leaseToken) {
    Objects.requireNonNull(leaseToken);
    if (leaseToken.isBlank()) {
      throw new IllegalArgumentException("leaseToken must not be blank");
    }
  }

  private record StoredAsyncJob(
      long idLong,
      AsyncJobId id,
      String queueName,
      AsyncJobStatus status,
      Instant availableAt,
      Instant leaseUntil,
      String workerId,
      String leaseToken,
      String jobData,
      int attemptCount,
      String lastError,
      Instant createdDate,
      Instant updatedDate) {

    StoredAsyncJob withClaim(
        AsyncJobStatus nextStatus,
        String nextWorkerId,
        String nextLeaseToken,
        Instant nextLeaseUntil,
        Instant now) {
      return new StoredAsyncJob(
          idLong,
          id,
          queueName,
          nextStatus,
          availableAt,
          nextLeaseUntil,
          nextWorkerId,
          nextLeaseToken,
          jobData,
          attemptCount + 1,
          lastError,
          createdDate,
          now);
    }

    StoredAsyncJob withLeaseUntil(Instant nextLeaseUntil, Instant now) {
      return new StoredAsyncJob(
          idLong,
          id,
          queueName,
          status,
          availableAt,
          nextLeaseUntil,
          workerId,
          leaseToken,
          jobData,
          attemptCount,
          lastError,
          createdDate,
          now);
    }

    StoredAsyncJob withCompletion(String nextJobData, Instant now) {
      return new StoredAsyncJob(
          idLong,
          id,
          queueName,
          AsyncJobStatus.DONE,
          availableAt,
          null,
          null,
          null,
          nextJobData,
          attemptCount,
          null,
          createdDate,
          now);
    }

    StoredAsyncJob withRequeue(
        Instant nextAvailableAt, String nextJobData, String nextLastError, Instant now) {
      return new StoredAsyncJob(
          idLong,
          id,
          queueName,
          AsyncJobStatus.QUEUED,
          nextAvailableAt,
          null,
          null,
          null,
          nextJobData,
          attemptCount,
          nextLastError,
          createdDate,
          now);
    }

    StoredAsyncJob withFailure(String nextJobData, String nextLastError, Instant now) {
      return new StoredAsyncJob(
          idLong,
          id,
          queueName,
          AsyncJobStatus.FAILED,
          availableAt,
          null,
          null,
          null,
          nextJobData,
          attemptCount,
          nextLastError,
          createdDate,
          now);
    }

    StoredAsyncJob withFailedReplay(Instant nextAvailableAt, String nextJobData, Instant now) {
      return new StoredAsyncJob(
          idLong,
          id,
          queueName,
          AsyncJobStatus.QUEUED,
          nextAvailableAt,
          null,
          null,
          null,
          nextJobData,
          0,
          lastError,
          createdDate,
          now);
    }
  }
}
