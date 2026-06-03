package com.box.l10n.mojito.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Low-level JDBC operations for async job state.
 *
 * <p>State transitions are kept in explicit short transactions to avoid long-lived DB transactions
 * around async job execution.
 */
public class JdbcAsyncJobStore implements AsyncJobStore {

  static final String DEFAULT_CLAIM_NEXT_JOBS_SQL =
      AsyncJobQueueJdbcDialect.MYSQL.claimNextJobsSql();

  static final String DEFAULT_CURRENT_TIMESTAMP_SQL =
      AsyncJobQueueJdbcDialect.MYSQL.currentTimestampSql();

  static final int TRANSACTION_ISOLATION_LEVEL = TransactionDefinition.ISOLATION_READ_COMMITTED;

  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final String claimNextJobsSql;
  private final String currentTimestampSql;
  private final TransactionTemplate transactionTemplate;

  public JdbcAsyncJobStore(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
    this(namedParameterJdbcTemplate, AsyncJobQueueJdbcDialect.MYSQL);
  }

  JdbcAsyncJobStore(
      NamedParameterJdbcTemplate namedParameterJdbcTemplate,
      AsyncJobQueueJdbcDialect asyncJobQueueJdbcDialect) {
    this(namedParameterJdbcTemplate, asyncJobQueueJdbcDialect, null);
  }

  JdbcAsyncJobStore(
      NamedParameterJdbcTemplate namedParameterJdbcTemplate,
      AsyncJobQueueJdbcDialect asyncJobQueueJdbcDialect,
      PlatformTransactionManager transactionManager) {
    this(
        namedParameterJdbcTemplate,
        asyncJobQueueJdbcDialect.claimNextJobsSql(),
        asyncJobQueueJdbcDialect.currentTimestampSql(),
        transactionManager);
  }

  JdbcAsyncJobStore(
      NamedParameterJdbcTemplate namedParameterJdbcTemplate, String claimNextJobsSql) {
    this(namedParameterJdbcTemplate, claimNextJobsSql, DEFAULT_CURRENT_TIMESTAMP_SQL);
  }

  JdbcAsyncJobStore(
      NamedParameterJdbcTemplate namedParameterJdbcTemplate,
      String claimNextJobsSql,
      String currentTimestampSql) {
    this(
        namedParameterJdbcTemplate,
        claimNextJobsSql,
        currentTimestampSql,
        (TransactionTemplate) null);
  }

  JdbcAsyncJobStore(
      NamedParameterJdbcTemplate namedParameterJdbcTemplate,
      String claimNextJobsSql,
      String currentTimestampSql,
      PlatformTransactionManager transactionManager) {
    this(
        namedParameterJdbcTemplate,
        claimNextJobsSql,
        currentTimestampSql,
        createRequiresNewTransactionTemplate(transactionManager));
  }

  JdbcAsyncJobStore(
      NamedParameterJdbcTemplate namedParameterJdbcTemplate,
      String claimNextJobsSql,
      String currentTimestampSql,
      TransactionTemplate transactionTemplate) {
    this.namedParameterJdbcTemplate = Objects.requireNonNull(namedParameterJdbcTemplate);
    this.claimNextJobsSql = Objects.requireNonNull(claimNextJobsSql);
    this.currentTimestampSql = Objects.requireNonNull(currentTimestampSql);
    this.transactionTemplate = transactionTemplate;
  }

  private static TransactionTemplate createRequiresNewTransactionTemplate(
      PlatformTransactionManager transactionManager) {
    if (transactionManager == null) {
      return null;
    }
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactionTemplate.setIsolationLevel(TRANSACTION_ISOLATION_LEVEL);
    return transactionTemplate;
  }

  private <T> T inTransaction(Supplier<T> callback) {
    Objects.requireNonNull(callback);
    if (transactionTemplate == null) {
      return callback.get();
    }
    return transactionTemplate.execute(status -> callback.get());
  }

  @Override
  public AsyncJobId enqueue(String queueName, String jobData, Instant availableAt) {
    return inTransaction(() -> enqueueInTransaction(queueName, jobData, availableAt));
  }

  private AsyncJobId enqueueInTransaction(String queueName, String jobData, Instant availableAt) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    String validatedJobData = AsyncJobQueueValidation.validateJobData(jobData);
    Instant validatedAvailableAt =
        AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);

    return enqueueAtDatabaseTime(queueName, validatedJobData, validatedAvailableAt, databaseNow());
  }

  @Override
  public AsyncJobId enqueueNow(String queueName, String jobData) {
    return inTransaction(() -> enqueueNowInTransaction(queueName, jobData));
  }

  private AsyncJobId enqueueNowInTransaction(String queueName, String jobData) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    String validatedJobData = AsyncJobQueueValidation.validateJobData(jobData);
    Instant now = databaseNow();
    return enqueueAtDatabaseTime(queueName, validatedJobData, now, now);
  }

  private AsyncJobId enqueueAtDatabaseTime(
      String queueName, String jobData, Instant availableAt, Instant now) {
    String sql =
        """
        INSERT INTO async_job_queue (
          queue_name,
          status,
          available_at,
          job_data,
          created_date,
          updated_date
        ) VALUES (
          :queueName,
          :status,
          :availableAt,
          :jobData,
          :createdDate,
          :updatedDate
        )
        """;

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("queueName", queueName)
            .addValue("status", AsyncJobStatus.QUEUED.getDatabaseValue())
            .addValue("availableAt", Timestamp.from(availableAt))
            .addValue("jobData", jobData)
            .addValue("createdDate", Timestamp.from(now))
            .addValue("updatedDate", Timestamp.from(now));

    KeyHolder keyHolder = new GeneratedKeyHolder();
    namedParameterJdbcTemplate.update(sql, params, keyHolder, new String[] {"id"});
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Async job insert did not return a generated id");
    }
    return new AsyncJobId(String.valueOf(key.longValue()));
  }

  /**
   * Claims async jobs atomically using row locks.
   *
   * <p>Claim policy:
   *
   * <ul>
   *   <li>new work: queued and available_at <= now
   *   <li>reclaimed work: running and lease_until <= now
   * </ul>
   */
  @Override
  public List<AsyncJobRecord> claimNextJobs(
      String queueName, int limit, String workerId, Duration leaseDuration) {
    return inTransaction(
        () -> claimNextJobsInTransaction(queueName, limit, workerId, leaseDuration));
  }

  private List<AsyncJobRecord> claimNextJobsInTransaction(
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

    Instant now = databaseNow();
    Instant leaseUntil =
        AsyncJobQueueValidation.plusDurationWithinDatabaseTimestampRange(
            "leaseUntil", now, leaseDuration);

    MapSqlParameterSource selectParams =
        new MapSqlParameterSource()
            .addValue("queueName", queueName)
            .addValue("queuedStatus", AsyncJobStatus.QUEUED.getDatabaseValue())
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("now", Timestamp.from(now))
            .addValue("limit", boundedLimit);

    List<ClaimCandidate> claimCandidates =
        namedParameterJdbcTemplate.query(
            claimNextJobsSql,
            selectParams,
            (rs, rowNum) ->
                new ClaimCandidate(
                    rs.getLong("id"), AsyncJobStatus.fromDatabaseValue(rs.getString("status"))));
    if (claimCandidates.isEmpty()) {
      return Collections.emptyList();
    }

    String claimSql =
        """
        UPDATE async_job_queue
        SET
          status = :runningStatus,
          worker_id = :workerId,
          lease_token = :leaseToken,
          lease_until = :leaseUntil,
          attempt_count = CASE
            WHEN attempt_count < :storedAttemptCountMax THEN attempt_count + 1
            ELSE attempt_count
          END,
          updated_date = :updatedDate
        WHERE id = :id
          AND queue_name = :queueName
          AND status = :selectedStatus
          AND (
            (status = :queuedStatus AND available_at <= :now)
            OR (status = :runningStatus AND lease_until <= :now)
          )
        """;

    Map<AsyncJobId, String> leaseTokenById = new LinkedHashMap<>();
    Map<AsyncJobId, Boolean> leaseReclaimedById = new LinkedHashMap<>();
    for (ClaimCandidate claimCandidate : claimCandidates) {
      AsyncJobId asyncJobId = new AsyncJobId(String.valueOf(claimCandidate.id()));
      String leaseToken = UUID.randomUUID().toString();

      MapSqlParameterSource claimParams =
          new MapSqlParameterSource()
              .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
              .addValue("queuedStatus", AsyncJobStatus.QUEUED.getDatabaseValue())
              .addValue("selectedStatus", claimCandidate.status().getDatabaseValue())
              .addValue("workerId", workerId)
              .addValue("leaseToken", leaseToken)
              .addValue("leaseUntil", Timestamp.from(leaseUntil))
              .addValue("storedAttemptCountMax", AsyncJobQueueValidation.STORED_ATTEMPT_COUNT_MAX)
              .addValue("updatedDate", Timestamp.from(now))
              .addValue("now", Timestamp.from(now))
              .addValue("id", claimCandidate.id())
              .addValue("queueName", queueName);

      int updated = namedParameterJdbcTemplate.update(claimSql, claimParams);
      if (updated == 1) {
        leaseTokenById.put(asyncJobId, leaseToken);
        leaseReclaimedById.put(asyncJobId, claimCandidate.status() == AsyncJobStatus.RUNNING);
      }
    }

    if (leaseTokenById.isEmpty()) {
      return Collections.emptyList();
    }

    Map<AsyncJobId, AsyncJobRecord> claimedJobsById =
        getByIdsNoTransaction(new ArrayList<>(leaseTokenById.keySet())).stream()
            .collect(Collectors.toMap(AsyncJobRecord::id, job -> job));

    List<AsyncJobRecord> claimedJobs = new ArrayList<>();
    for (Map.Entry<AsyncJobId, String> claimEntry : leaseTokenById.entrySet()) {
      AsyncJobRecord job = claimedJobsById.get(claimEntry.getKey());
      if (job == null) {
        throw new IllegalStateException(
            "Async job claim readback missing claimed job "
                + claimEntry.getKey().value()
                + " for queue "
                + queueName);
      }
      validateClaimReadback(queueName, workerId, claimEntry.getValue(), job);
      claimedJobs.add(
          job.withLeaseReclaimed(Boolean.TRUE.equals(leaseReclaimedById.get(job.id()))));
    }
    return claimedJobs;
  }

  private void validateClaimReadback(
      String queueName, String workerId, String leaseToken, AsyncJobRecord job) {
    if (!queueName.equals(job.queueName())
        || job.status() != AsyncJobStatus.RUNNING
        || !workerId.equals(job.workerId())
        || !leaseToken.equals(job.leaseToken())
        || job.attemptCount() <= 0) {
      throw new IllegalStateException(
          "Async job claim readback returned inconsistent claimed job "
              + job.id().value()
              + " for queue "
              + queueName);
    }
  }

  @Override
  public boolean heartbeat(
      String queueName, AsyncJobId id, String workerId, String leaseToken, Duration leaseDuration) {
    return inTransaction(
        () -> heartbeatInTransaction(queueName, id, workerId, leaseToken, leaseDuration));
  }

  private boolean heartbeatInTransaction(
      String queueName, AsyncJobId id, String workerId, String leaseToken, Duration leaseDuration) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    Objects.requireNonNull(leaseDuration);
    validateLeaseToken(leaseToken);
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be > 0");
    }

    long parsedId = parseId(id);
    Instant now = databaseNow();
    Instant leaseUntil =
        AsyncJobQueueValidation.plusDurationWithinDatabaseTimestampRange(
            "leaseUntil", now, leaseDuration);
    String sql =
        """
        UPDATE async_job_queue
        SET
          lease_until = :leaseUntil,
          updated_date = :updatedDate
        WHERE id = :id
          AND queue_name = :queueName
          AND status = :runningStatus
          AND lease_until > :now
          AND worker_id = :workerId
          AND lease_token = :leaseToken
        """;

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("leaseUntil", Timestamp.from(leaseUntil))
            .addValue("updatedDate", Timestamp.from(now))
            .addValue("now", Timestamp.from(now))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("workerId", workerId)
            .addValue("leaseToken", leaseToken);
    return namedParameterJdbcTemplate.update(sql, params) == 1;
  }

  @Override
  public boolean markDone(
      String queueName, AsyncJobId id, String workerId, String leaseToken, String jobData) {
    return inTransaction(() -> markDoneInTransaction(queueName, id, workerId, leaseToken, jobData));
  }

  private boolean markDoneInTransaction(
      String queueName, AsyncJobId id, String workerId, String leaseToken, String jobData) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    validateLeaseToken(leaseToken);
    long parsedId = parseId(id);
    String validatedJobData = AsyncJobQueueValidation.validateOptionalJobData(jobData);
    Instant now = databaseNow();

    String sql =
        """
        UPDATE async_job_queue
        SET
          status = :doneStatus,
          lease_until = NULL,
          worker_id = NULL,
          lease_token = NULL,
          job_data = COALESCE(:jobData, job_data),
          last_error = NULL,
          updated_date = :updatedDate
        WHERE id = :id
          AND queue_name = :queueName
          AND status = :runningStatus
          AND lease_until > :now
          AND worker_id = :workerId
          AND lease_token = :leaseToken
        """;

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("doneStatus", AsyncJobStatus.DONE.getDatabaseValue())
            .addValue("jobData", validatedJobData)
            .addValue("updatedDate", Timestamp.from(now))
            .addValue("now", Timestamp.from(now))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("workerId", workerId)
            .addValue("leaseToken", leaseToken);
    return namedParameterJdbcTemplate.update(sql, params) == 1;
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
    return inTransaction(
        () ->
            requeueInTransaction(
                queueName, id, workerId, leaseToken, availableAt, jobData, lastError));
  }

  private boolean requeueInTransaction(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Instant availableAt,
      String jobData,
      String lastError) {
    validateRequeueInput(queueName, id, workerId, leaseToken, availableAt);
    return requeueAtDatabaseTime(
        queueName, id, workerId, leaseToken, availableAt, jobData, lastError, databaseNow());
  }

  @Override
  public boolean requeueAfter(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Duration delay,
      String jobData,
      String lastError) {
    return inTransaction(
        () ->
            requeueAfterInTransaction(
                queueName, id, workerId, leaseToken, delay, jobData, lastError));
  }

  private boolean requeueAfterInTransaction(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Duration delay,
      String jobData,
      String lastError) {
    validateRequeueOwnership(queueName, id, workerId, leaseToken);
    Objects.requireNonNull(delay);
    Instant now = databaseNow();
    Duration boundedDelay = delay.isNegative() ? Duration.ZERO : delay;
    return requeueAtDatabaseTime(
        queueName,
        id,
        workerId,
        leaseToken,
        AsyncJobQueueValidation.plusDurationWithinDatabaseTimestampRange(
            "availableAt", now, boundedDelay),
        jobData,
        lastError,
        now);
  }

  private boolean requeueAtDatabaseTime(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Instant availableAt,
      String jobData,
      String lastError,
      Instant now) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    Instant validatedAvailableAt =
        AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);
    Objects.requireNonNull(now);
    validateLeaseToken(leaseToken);
    long parsedId = parseId(id);
    String validatedJobData = AsyncJobQueueValidation.validateOptionalJobData(jobData);

    String sql =
        """
        UPDATE async_job_queue
        SET
          status = :queuedStatus,
          available_at = :availableAt,
          lease_until = NULL,
          worker_id = NULL,
          lease_token = NULL,
          job_data = COALESCE(:jobData, job_data),
          last_error = :lastError,
          updated_date = :updatedDate
        WHERE id = :id
          AND queue_name = :queueName
          AND status = :runningStatus
          AND lease_until > :now
          AND worker_id = :workerId
          AND lease_token = :leaseToken
        """;

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("queuedStatus", AsyncJobStatus.QUEUED.getDatabaseValue())
            .addValue("availableAt", Timestamp.from(validatedAvailableAt))
            .addValue("jobData", validatedJobData)
            .addValue("lastError", AsyncJobQueueValidation.truncateLastError(lastError))
            .addValue("updatedDate", Timestamp.from(now))
            .addValue("now", Timestamp.from(now))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("workerId", workerId)
            .addValue("leaseToken", leaseToken);
    return namedParameterJdbcTemplate.update(sql, params) == 1;
  }

  @Override
  public boolean markFailed(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      String jobData,
      String lastError) {
    return inTransaction(
        () -> markFailedInTransaction(queueName, id, workerId, leaseToken, jobData, lastError));
  }

  private boolean markFailedInTransaction(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      String jobData,
      String lastError) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    validateLeaseToken(leaseToken);
    String validatedLastError = AsyncJobQueueValidation.validateFailureLastError(lastError);
    long parsedId = parseId(id);
    String validatedJobData = AsyncJobQueueValidation.validateOptionalJobData(jobData);
    Instant now = databaseNow();

    String sql =
        """
        UPDATE async_job_queue
        SET
          status = :failedStatus,
          lease_until = NULL,
          worker_id = NULL,
          lease_token = NULL,
          job_data = COALESCE(:jobData, job_data),
          last_error = :lastError,
          updated_date = :updatedDate
        WHERE id = :id
          AND queue_name = :queueName
          AND status = :runningStatus
          AND lease_until > :now
          AND worker_id = :workerId
          AND lease_token = :leaseToken
        """;

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("failedStatus", AsyncJobStatus.FAILED.getDatabaseValue())
            .addValue("jobData", validatedJobData)
            .addValue("lastError", validatedLastError)
            .addValue("updatedDate", Timestamp.from(now))
            .addValue("now", Timestamp.from(now))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("workerId", workerId)
            .addValue("leaseToken", leaseToken);
    return namedParameterJdbcTemplate.update(sql, params) == 1;
  }

  @Override
  public List<AsyncJobStatusCount> countByStatus(String queueName) {
    return inTransaction(() -> countByStatusInTransaction(queueName));
  }

  private List<AsyncJobStatusCount> countByStatusInTransaction(String queueName) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    String sql =
        """
        SELECT status, COUNT(*) AS count
        FROM async_job_queue
        WHERE queue_name = :queueName
          AND id > 0
        GROUP BY status
        """;
    return namedParameterJdbcTemplate.query(
        sql,
        new MapSqlParameterSource().addValue("queueName", queueName),
        (rs, rowNum) ->
            new AsyncJobStatusCount(
                AsyncJobStatus.fromDatabaseValue(rs.getString("status")), rs.getLong("count")));
  }

  @Override
  public AsyncJobReadyStatus readyStatus(String queueName) {
    return inTransaction(() -> readyStatusInTransaction(queueName));
  }

  private AsyncJobReadyStatus readyStatusInTransaction(String queueName) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    Instant now = databaseNow();
    String sql =
        """
        SELECT COUNT(*) AS count, MIN(available_at) AS oldest_available_at
        FROM async_job_queue
        WHERE queue_name = :queueName
          AND id > 0
          AND status = :queuedStatus
          AND available_at <= :now
        """;
    return namedParameterJdbcTemplate.queryForObject(
        sql,
        new MapSqlParameterSource()
            .addValue("queueName", queueName)
            .addValue("queuedStatus", AsyncJobStatus.QUEUED.getDatabaseValue())
            .addValue("now", Timestamp.from(now)),
        (resultSet, rowNum) ->
            new AsyncJobReadyStatus(
                resultSet.getLong("count"), toInstant(resultSet, "oldest_available_at"), now));
  }

  @Override
  public AsyncJobExpiredLeaseStatus expiredLeaseStatus(String queueName) {
    return inTransaction(() -> expiredLeaseStatusInTransaction(queueName));
  }

  private AsyncJobExpiredLeaseStatus expiredLeaseStatusInTransaction(String queueName) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    Instant now = databaseNow();
    String sql =
        """
        SELECT COUNT(*) AS count, MIN(lease_until) AS oldest_lease_until
        FROM async_job_queue
        WHERE queue_name = :queueName
          AND id > 0
          AND status = :runningStatus
          AND lease_until <= :now
        """;
    return namedParameterJdbcTemplate.queryForObject(
        sql,
        new MapSqlParameterSource()
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("now", Timestamp.from(now)),
        (resultSet, rowNum) ->
            new AsyncJobExpiredLeaseStatus(
                resultSet.getLong("count"), toInstant(resultSet, "oldest_lease_until"), now));
  }

  @Override
  public List<AsyncJobRecord> findByStatus(String queueName, AsyncJobStatus status, int limit) {
    return inTransaction(() -> findByStatusInTransaction(queueName, status, limit));
  }

  private List<AsyncJobRecord> findByStatusInTransaction(
      String queueName, AsyncJobStatus status, int limit) {
    if (limit <= 0) {
      return Collections.emptyList();
    }
    int boundedLimit = AsyncJobQueueValidation.validateStoreQueryLimit("limit", limit);

    AsyncJobQueueValidation.validateQueueName(queueName);
    Objects.requireNonNull(status);
    String sql =
        """
        SELECT
          id,
          queue_name,
          status,
          available_at,
          lease_until,
          worker_id,
          lease_token,
          job_data,
          attempt_count,
          last_error,
          created_date,
          updated_date
        FROM async_job_queue
        WHERE queue_name = :queueName
          AND id > 0
          AND status = :status
        ORDER BY updated_date DESC, id DESC
        LIMIT :limit
        """;
    return namedParameterJdbcTemplate.query(
        sql,
        new MapSqlParameterSource()
            .addValue("queueName", queueName)
            .addValue("status", status.getDatabaseValue())
            .addValue("limit", boundedLimit),
        asyncJobRowMapper());
  }

  @Override
  public boolean requeueFailed(
      String queueName, AsyncJobId id, Instant availableAt, String jobData) {
    return inTransaction(() -> requeueFailedInTransaction(queueName, id, availableAt, jobData));
  }

  private boolean requeueFailedInTransaction(
      String queueName, AsyncJobId id, Instant availableAt, String jobData) {
    validateFailedReplayInput(queueName, id);
    Instant validatedAvailableAt =
        AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);
    return requeueFailedAtDatabaseTime(queueName, id, validatedAvailableAt, jobData, databaseNow());
  }

  @Override
  public boolean requeueFailedNow(String queueName, AsyncJobId id, String jobData) {
    return inTransaction(() -> requeueFailedNowInTransaction(queueName, id, jobData));
  }

  private boolean requeueFailedNowInTransaction(String queueName, AsyncJobId id, String jobData) {
    validateFailedReplayInput(queueName, id);
    Instant now = databaseNow();
    return requeueFailedAtDatabaseTime(queueName, id, now, jobData, now);
  }

  private boolean requeueFailedAtDatabaseTime(
      String queueName, AsyncJobId id, Instant availableAt, String jobData, Instant updatedDate) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    long parsedId = parseId(id);
    String validatedJobData = AsyncJobQueueValidation.validateOptionalJobData(jobData);

    String sql =
        """
        UPDATE async_job_queue
        SET
          status = :queuedStatus,
          available_at = :availableAt,
          lease_until = NULL,
          worker_id = NULL,
          lease_token = NULL,
          job_data = COALESCE(:jobData, job_data),
          attempt_count = 0,
          updated_date = :updatedDate
        WHERE id = :id
          AND queue_name = :queueName
          AND status = :failedStatus
        """;
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("queuedStatus", AsyncJobStatus.QUEUED.getDatabaseValue())
            .addValue("availableAt", Timestamp.from(availableAt))
            .addValue("jobData", validatedJobData)
            .addValue("updatedDate", Timestamp.from(updatedDate))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("failedStatus", AsyncJobStatus.FAILED.getDatabaseValue());
    return namedParameterJdbcTemplate.update(sql, params) == 1;
  }

  @Override
  public int deleteTerminalJobs(
      String queueName, AsyncJobStatus status, Instant updatedBefore, int limit) {
    return inTransaction(
        () -> deleteTerminalJobsInTransaction(queueName, status, updatedBefore, limit));
  }

  private int deleteTerminalJobsInTransaction(
      String queueName, AsyncJobStatus status, Instant updatedBefore, int limit) {
    if (limit <= 0) {
      return 0;
    }
    int boundedLimit = AsyncJobQueueValidation.validateStoreQueryLimit("limit", limit);

    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobStatus terminalStatus = AsyncJobQueueValidation.validateTerminalStatus(status);
    Instant validatedUpdatedBefore =
        AsyncJobQueueValidation.validateDatabaseTimestamp("updatedBefore", updatedBefore);

    String sql =
        """
        DELETE FROM async_job_queue
        WHERE id IN (
          SELECT id
          FROM (
            SELECT id
            FROM async_job_queue
            WHERE queue_name = :queueName
              AND status = :status
              AND updated_date < :updatedBefore
            ORDER BY updated_date ASC, id ASC
            LIMIT :limit
          ) purge_candidates
        )
        """;

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("queueName", queueName)
            .addValue("status", terminalStatus.getDatabaseValue())
            .addValue("updatedBefore", Timestamp.from(validatedUpdatedBefore))
            .addValue("limit", boundedLimit);
    return namedParameterJdbcTemplate.update(sql, params);
  }

  @Override
  public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
    return inTransaction(() -> getByIdsNoTransaction(ids));
  }

  private List<AsyncJobRecord> getByIdsNoTransaction(List<AsyncJobId> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    AsyncJobQueueValidation.validateStoreQueryLimit("ids", ids.size());

    List<Long> parsedIds = parseIds(ids);
    if (parsedIds.isEmpty()) {
      return Collections.emptyList();
    }

    String sql =
        """
        SELECT
          id,
          queue_name,
          status,
          available_at,
          lease_until,
          worker_id,
          lease_token,
          job_data,
          attempt_count,
          last_error,
          created_date,
          updated_date
        FROM async_job_queue
        WHERE id IN (:ids)
        """;
    return namedParameterJdbcTemplate.query(
        sql, new MapSqlParameterSource().addValue("ids", parsedIds), asyncJobRowMapper());
  }

  private RowMapper<AsyncJobRecord> asyncJobRowMapper() {
    return (resultSet, rowNum) ->
        new AsyncJobRecord(
            new AsyncJobId(String.valueOf(resultSet.getLong("id"))),
            resultSet.getString("queue_name"),
            AsyncJobStatus.fromDatabaseValue(resultSet.getString("status")),
            toInstant(resultSet, "available_at"),
            toInstant(resultSet, "lease_until"),
            resultSet.getString("worker_id"),
            resultSet.getString("lease_token"),
            resultSet.getString("job_data"),
            resultSet.getInt("attempt_count"),
            resultSet.getString("last_error"),
            toInstant(resultSet, "created_date"),
            toInstant(resultSet, "updated_date"),
            false);
  }

  private Instant toInstant(ResultSet resultSet, String columnName) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(columnName);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private Instant databaseNow() {
    Timestamp timestamp =
        namedParameterJdbcTemplate.queryForObject(
            currentTimestampSql, new MapSqlParameterSource(), Timestamp.class);
    if (timestamp == null) {
      throw new IllegalStateException("Database did not return CURRENT_TIMESTAMP");
    }
    return timestamp.toInstant();
  }

  private List<Long> parseIds(List<AsyncJobId> ids) {
    List<Long> parsed = new ArrayList<>(ids.size());
    for (AsyncJobId id : ids) {
      parsed.add(parseId(id));
    }
    return parsed;
  }

  private long parseId(AsyncJobId id) {
    Objects.requireNonNull(id);
    try {
      return Long.parseLong(id.value());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Async job id must be a numeric string: " + id.value(), e);
    }
  }

  private void validateRequeueInput(
      String queueName, AsyncJobId id, String workerId, String leaseToken, Instant availableAt) {
    validateRequeueOwnership(queueName, id, workerId, leaseToken);
    AsyncJobQueueValidation.validateDatabaseTimestamp("availableAt", availableAt);
  }

  private void validateRequeueOwnership(
      String queueName, AsyncJobId id, String workerId, String leaseToken) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    AsyncJobQueueValidation.validateWorkerId(workerId);
    validateLeaseToken(leaseToken);
    parseId(id);
  }

  private void validateFailedReplayInput(String queueName, AsyncJobId id) {
    AsyncJobQueueValidation.validateQueueName(queueName);
    parseId(id);
  }

  private void validateLeaseToken(String leaseToken) {
    Objects.requireNonNull(leaseToken);
    if (leaseToken.isBlank()) {
      throw new IllegalArgumentException("leaseToken must not be blank");
    }
  }

  private record ClaimCandidate(long id, AsyncJobStatus status) {}
}
