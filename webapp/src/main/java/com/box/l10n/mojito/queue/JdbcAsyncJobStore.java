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
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Low-level JDBC operations for async job state.
 *
 * <p>State transitions are kept in explicit short transactions to avoid long-lived DB transactions
 * around async job execution.
 */
public class JdbcAsyncJobStore implements AsyncJobStore {

  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

  public JdbcAsyncJobStore(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
    this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public AsyncJobId enqueue(String queueName, String jobData, Instant availableAt) {
    Objects.requireNonNull(queueName);
    Objects.requireNonNull(jobData);
    Objects.requireNonNull(availableAt);

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

    Instant now = Instant.now();
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
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public List<AsyncJobRecord> claimNextJobs(
      String queueName, int limit, String workerId, Duration leaseDuration) {
    if (limit <= 0) {
      return Collections.emptyList();
    }

    Objects.requireNonNull(queueName);
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(leaseDuration);
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be > 0");
    }

    Instant now = Instant.now();
    Instant leaseUntil = now.plus(leaseDuration);

    String selectIdsSql =
        """
        SELECT id
        FROM async_job_queue
        WHERE queue_name = :queueName
          AND (
            (status = :queuedStatus AND available_at <= :now)
            OR (status = :runningStatus AND lease_until <= :now)
          )
        ORDER BY available_at, id
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """;

    MapSqlParameterSource selectParams =
        new MapSqlParameterSource()
            .addValue("queueName", queueName)
            .addValue("queuedStatus", AsyncJobStatus.QUEUED.getDatabaseValue())
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("now", Timestamp.from(now))
            .addValue("limit", limit);

    List<Long> ids =
        namedParameterJdbcTemplate.query(
            selectIdsSql, selectParams, (rs, rowNum) -> rs.getLong("id"));
    if (ids.isEmpty()) {
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
          attempt_count = attempt_count + 1,
          updated_date = :updatedDate
        WHERE id = :id
          AND queue_name = :queueName
        """;

    Map<AsyncJobId, String> leaseTokenById = new LinkedHashMap<>();
    for (Long id : ids) {
      AsyncJobId asyncJobId = new AsyncJobId(String.valueOf(id));
      String leaseToken = UUID.randomUUID().toString();

      MapSqlParameterSource claimParams =
          new MapSqlParameterSource()
              .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
              .addValue("workerId", workerId)
              .addValue("leaseToken", leaseToken)
              .addValue("leaseUntil", Timestamp.from(leaseUntil))
              .addValue("updatedDate", Timestamp.from(now))
              .addValue("id", id)
              .addValue("queueName", queueName);

      int updated = namedParameterJdbcTemplate.update(claimSql, claimParams);
      if (updated == 1) {
        leaseTokenById.put(asyncJobId, leaseToken);
      }
    }

    if (leaseTokenById.isEmpty()) {
      return Collections.emptyList();
    }

    Map<AsyncJobId, AsyncJobRecord> claimedJobsById =
        getByIds(new ArrayList<>(leaseTokenById.keySet())).stream()
            .collect(Collectors.toMap(AsyncJobRecord::id, job -> job));

    List<AsyncJobRecord> claimedJobs = new ArrayList<>();
    for (Map.Entry<AsyncJobId, String> claimEntry : leaseTokenById.entrySet()) {
      AsyncJobRecord job = claimedJobsById.get(claimEntry.getKey());
      if (job != null) {
        claimedJobs.add(job);
      }
    }
    return claimedJobs;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public boolean heartbeat(
      String queueName, AsyncJobId id, String workerId, String leaseToken, Duration leaseDuration) {
    Objects.requireNonNull(queueName);
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(leaseDuration);
    validateLeaseToken(leaseToken);
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be > 0");
    }

    long parsedId = parseId(id);
    Instant now = Instant.now();
    Instant leaseUntil = now.plus(leaseDuration);
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

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public boolean markDone(
      String queueName, AsyncJobId id, String workerId, String leaseToken, String jobData) {
    Objects.requireNonNull(queueName);
    Objects.requireNonNull(workerId);
    validateLeaseToken(leaseToken);
    long parsedId = parseId(id);
    Instant now = Instant.now();

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
            .addValue("jobData", jobData)
            .addValue("updatedDate", Timestamp.from(now))
            .addValue("now", Timestamp.from(now))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("workerId", workerId)
            .addValue("leaseToken", leaseToken);
    return namedParameterJdbcTemplate.update(sql, params) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public boolean requeue(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      Instant availableAt,
      String jobData,
      String lastError) {
    Objects.requireNonNull(queueName);
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(availableAt);
    validateLeaseToken(leaseToken);
    long parsedId = parseId(id);
    Instant now = Instant.now();

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
            .addValue("availableAt", Timestamp.from(availableAt))
            .addValue("jobData", jobData)
            .addValue("lastError", lastError)
            .addValue("updatedDate", Timestamp.from(now))
            .addValue("now", Timestamp.from(now))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("workerId", workerId)
            .addValue("leaseToken", leaseToken);
    return namedParameterJdbcTemplate.update(sql, params) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public boolean markFailed(
      String queueName,
      AsyncJobId id,
      String workerId,
      String leaseToken,
      String jobData,
      String lastError) {
    Objects.requireNonNull(queueName);
    Objects.requireNonNull(workerId);
    validateLeaseToken(leaseToken);
    long parsedId = parseId(id);
    Instant now = Instant.now();

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
            .addValue("jobData", jobData)
            .addValue("lastError", lastError)
            .addValue("updatedDate", Timestamp.from(now))
            .addValue("now", Timestamp.from(now))
            .addValue("id", parsedId)
            .addValue("queueName", queueName)
            .addValue("runningStatus", AsyncJobStatus.RUNNING.getDatabaseValue())
            .addValue("workerId", workerId)
            .addValue("leaseToken", leaseToken);
    return namedParameterJdbcTemplate.update(sql, params) == 1;
  }

  @Transactional(readOnly = true)
  @Override
  public List<AsyncJobStatusCount> countByStatus(String queueName) {
    Objects.requireNonNull(queueName);
    String sql =
        """
        SELECT status, COUNT(*) AS count
        FROM async_job_queue
        WHERE queue_name = :queueName
        GROUP BY status
        """;
    return namedParameterJdbcTemplate.query(
        sql,
        new MapSqlParameterSource().addValue("queueName", queueName),
        (rs, rowNum) ->
            new AsyncJobStatusCount(
                AsyncJobStatus.fromDatabaseValue(rs.getString("status")), rs.getLong("count")));
  }

  @Transactional(readOnly = true)
  @Override
  public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }

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
            toInstant(resultSet, "updated_date"));
  }

  private Instant toInstant(ResultSet resultSet, String columnName) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(columnName);
    return timestamp == null ? null : timestamp.toInstant();
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

  private void validateLeaseToken(String leaseToken) {
    Objects.requireNonNull(leaseToken);
    if (leaseToken.isBlank()) {
      throw new IllegalArgumentException("leaseToken must not be blank");
    }
  }
}
