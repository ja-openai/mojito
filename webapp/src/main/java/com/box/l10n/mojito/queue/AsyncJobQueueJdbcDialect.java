package com.box.l10n.mojito.queue;

import java.util.Locale;

/** SQL fragments that differ by database for the async job queue JDBC store. */
enum AsyncJobQueueJdbcDialect {
  MYSQL(
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
      """,
      "SELECT CURRENT_TIMESTAMP"),

  POSTGRESQL(
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
      """,
      "SELECT CURRENT_TIMESTAMP"),

  HSQL(
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
      """,
      "VALUES CURRENT_TIMESTAMP");

  private final String claimNextJobsSql;
  private final String currentTimestampSql;

  AsyncJobQueueJdbcDialect(String claimNextJobsSql, String currentTimestampSql) {
    this.claimNextJobsSql = claimNextJobsSql;
    this.currentTimestampSql = currentTimestampSql;
  }

  String claimNextJobsSql() {
    return claimNextJobsSql;
  }

  String currentTimestampSql() {
    return currentTimestampSql;
  }

  static AsyncJobQueueJdbcDialect fromConfig(String dialect) {
    if (dialect == null || dialect.isBlank()) {
      return MYSQL;
    }

    return switch (dialect.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")) {
      case "mysql" -> MYSQL;
      case "postgres", "postgresql" -> POSTGRESQL;
      case "hsql", "hsqldb" -> HSQL;
      default ->
          throw new IllegalArgumentException(
              "Unsupported async job queue JDBC dialect: " + dialect);
    };
  }
}
