package com.box.l10n.mojito.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** SQL and temporal types that differ by database for the async job queue JDBC store. */
enum AsyncJobQueueJdbcDialect {
  // Limit ordered locking reads before merging states; an OR/filesort can lock the whole backlog.
  MYSQL(
      """
      SELECT id, status
      FROM (
        (SELECT id, status, available_at
         FROM async_job_queue
         FORCE INDEX (I__ASYNC_JOB_QUEUE__QNAME_STATUS_AVAILABLE_ID)
         WHERE %1$s
           AND id > 0
           AND status = :queuedStatus AND available_at <= :now
         ORDER BY available_at, id
         LIMIT :limit
         FOR UPDATE SKIP LOCKED)
        UNION ALL
        (SELECT id, status, available_at
         FROM async_job_queue
         FORCE INDEX (I__ASYNC_JOB_QUEUE__QNAME_STATUS_AVAILABLE_ID)
         WHERE %1$s
           AND id > 0
           AND status = :runningStatus AND lease_until <= :now
         ORDER BY available_at, id
         LIMIT :limit
         FOR UPDATE SKIP LOCKED)
      ) claim_candidates
      ORDER BY available_at, id
      LIMIT :limit
      """,
      "SELECT UTC_TIMESTAMP(6)"),

  POSTGRESQL(
      """
      SELECT id, status
      FROM async_job_queue
      WHERE %s
        AND id > 0
        AND (
          (status = :queuedStatus AND available_at <= :now)
          OR (status = :runningStatus AND lease_until <= :now)
        )
      ORDER BY available_at, id
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
      """,
      "SELECT clock_timestamp()"),

  HSQL(
      """
      SELECT id, status
      FROM async_job_queue
      WHERE %s
        AND id > 0
        AND (
          (status = :queuedStatus AND available_at <= :now)
          OR (status = :runningStatus AND lease_until <= :now)
        )
      ORDER BY available_at, id
      LIMIT :limit
      """,
      "VALUES CAST(CURRENT_TIMESTAMP AT TIME ZONE INTERVAL '0:00' HOUR TO MINUTE AS TIMESTAMP)");

  private final String claimNextJobsSql;
  private final String currentTimestampSql;

  AsyncJobQueueJdbcDialect(String claimNextJobsSql, String currentTimestampSql) {
    this.claimNextJobsSql = claimNextJobsSql;
    this.currentTimestampSql = currentTimestampSql;
  }

  String claimNextJobsSql() {
    return claimNextJobsSql.formatted(queueNamePredicateSql());
  }

  String queueNamePredicateSql() {
    String exact = exactIdentityPredicateSql("queue_name", "queueName");
    // Queue names are ASCII. Preserve the indexed equality as a collation-aware prefilter.
    return this == MYSQL ? "(queue_name = :queueName AND " + exact + ")" : exact;
  }

  String leaseOwnerPredicateSql() {
    return exactIdentityPredicateSql("worker_id", "workerId")
        + " AND "
        + exactIdentityPredicateSql("lease_token", "leaseToken");
  }

  private String exactIdentityPredicateSql(String column, String parameter) {
    String equality = column + " = :" + parameter;
    return switch (this) {
      case MYSQL ->
          // The column and connection can use different encodings for the same worker ID.
          // Owner queries already use the primary key; plain owner equality can coerce to latin1.
          ("CAST(CONVERT(%s USING utf8mb4) AS BINARY)"
                  + " = CAST(CONVERT(:%s USING utf8mb4) AS BINARY)")
              .formatted(column, parameter);
      case HSQL ->
          "(%s AND CHAR_LENGTH(%s) = CHAR_LENGTH(:%s))".formatted(equality, column, parameter);
      case POSTGRESQL -> equality;
    };
  }

  String currentTimestampSql() {
    return currentTimestampSql;
  }

  Object timestampParameter(Instant instant) {
    // Match each schema's temporal type without implicit JVM/driver/session-zone conversion.
    return this == POSTGRESQL
        ? instant.atOffset(ZoneOffset.UTC)
        : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  Instant readTimestamp(ResultSet resultSet, int column) throws SQLException {
    if (this == POSTGRESQL) {
      OffsetDateTime timestamp = resultSet.getObject(column, OffsetDateTime.class);
      return timestamp == null ? null : timestamp.toInstant();
    }
    LocalDateTime timestamp = resultSet.getObject(column, LocalDateTime.class);
    return timestamp == null ? null : timestamp.toInstant(ZoneOffset.UTC);
  }

  static AsyncJobQueueJdbcDialect fromConfig(String dialect) {
    if (dialect == null || dialect.isBlank()) {
      return MYSQL;
    }

    return switch (dialect.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")) {
      case "mysql" -> MYSQL;
      case "postgres", "postgresql" -> POSTGRESQL;
      case "hsql", "hsqldb" -> HSQL;
      default ->
          throw new IllegalArgumentException(
              "Unsupported async job queue JDBC dialect: " + dialect);
    };
  }
}
