package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AsyncJobQueueJdbcDialectTest {

  @Test
  public void fromConfigDefaultsToMysql() {
    assertThat(AsyncJobQueueJdbcDialect.fromConfig(null)).isEqualTo(AsyncJobQueueJdbcDialect.MYSQL);
    assertThat(AsyncJobQueueJdbcDialect.fromConfig("")).isEqualTo(AsyncJobQueueJdbcDialect.MYSQL);
  }

  @Test
  public void fromConfigSupportsStableAliases() {
    assertThat(AsyncJobQueueJdbcDialect.fromConfig("mysql"))
        .isEqualTo(AsyncJobQueueJdbcDialect.MYSQL);
    assertThat(AsyncJobQueueJdbcDialect.fromConfig("postgres"))
        .isEqualTo(AsyncJobQueueJdbcDialect.POSTGRESQL);
    assertThat(AsyncJobQueueJdbcDialect.fromConfig("postgresql"))
        .isEqualTo(AsyncJobQueueJdbcDialect.POSTGRESQL);
    assertThat(AsyncJobQueueJdbcDialect.fromConfig(" postgresql "))
        .isEqualTo(AsyncJobQueueJdbcDialect.POSTGRESQL);
    assertThat(AsyncJobQueueJdbcDialect.fromConfig("hsql-db"))
        .isEqualTo(AsyncJobQueueJdbcDialect.HSQL);
  }

  @Test
  public void mysqlAndPostgresqlUseSkipLockedClaimSql() {
    assertThat(AsyncJobQueueJdbcDialect.MYSQL.claimNextJobsSql())
        .contains("SELECT id, status")
        .contains("AND id > 0")
        .contains("LIMIT :limit")
        .contains("FOR UPDATE SKIP LOCKED");
    assertThat(AsyncJobQueueJdbcDialect.POSTGRESQL.claimNextJobsSql())
        .contains("SELECT id, status")
        .contains("AND id > 0")
        .contains("LIMIT :limit")
        .contains("FOR UPDATE SKIP LOCKED");
  }

  @Test
  public void mysqlIdentitiesKeepIndexedEqualityAndCompareUnpaddedUtf8Bytes() {
    AsyncJobQueueJdbcDialect dialect = AsyncJobQueueJdbcDialect.MYSQL;
    assertThat(dialect.queueNamePredicateSql())
        .isEqualTo(
            "(queue_name = :queueName AND CAST(CONVERT(queue_name USING utf8mb4) AS BINARY)"
                + " = CAST(CONVERT(:queueName USING utf8mb4) AS BINARY))");
    assertThat(dialect.claimNextJobsSql()).contains("WHERE " + dialect.queueNamePredicateSql());
    assertThat(dialect.leaseOwnerPredicateSql())
        .isEqualTo(
            "CAST(CONVERT(worker_id USING utf8mb4) AS BINARY)"
                + " = CAST(CONVERT(:workerId USING utf8mb4) AS BINARY) AND "
                + "CAST(CONVERT(lease_token USING utf8mb4) AS BINARY)"
                + " = CAST(CONVERT(:leaseToken USING utf8mb4) AS BINARY)");
  }

  @Test
  public void mysqlBoundsBothOrderedLockingReadsBeforeMergingCandidates() {
    String sql = AsyncJobQueueJdbcDialect.MYSQL.claimNextJobsSql();
    assertThat(sql).containsOnlyOnce("UNION ALL").endsWith("LIMIT :limit\n");
    assertThat(sql.split("FOR UPDATE SKIP LOCKED", -1)).hasSize(3);
    assertThat(sql.split("LIMIT :limit", -1)).hasSize(4);
    assertThat(sql.split("FORCE INDEX ", -1)).hasSize(3);
    assertThat(sql)
        .contains("FORCE INDEX (I__ASYNC_JOB_QUEUE__QNAME_STATUS_AVAILABLE_ID)")
        .contains("status = :queuedStatus AND available_at <= :now")
        .contains("status = :runningStatus AND lease_until <= :now")
        .doesNotContain(" OR ");
  }

  @Test
  public void postgresqlIdentityPredicatesRemainUnchanged() {
    assertThat(AsyncJobQueueJdbcDialect.POSTGRESQL.queueNamePredicateSql())
        .isEqualTo("queue_name = :queueName");
    assertThat(AsyncJobQueueJdbcDialect.POSTGRESQL.leaseOwnerPredicateSql())
        .isEqualTo("worker_id = :workerId AND lease_token = :leaseToken");
  }

  @Test
  public void hsqlIdentityPredicatesRejectImplicitSpacePadding() {
    assertThat(AsyncJobQueueJdbcDialect.HSQL.queueNamePredicateSql())
        .isEqualTo(
            "(queue_name = :queueName AND CHAR_LENGTH(queue_name) = CHAR_LENGTH(:queueName))");
    assertThat(AsyncJobQueueJdbcDialect.HSQL.leaseOwnerPredicateSql())
        .isEqualTo(
            "(worker_id = :workerId AND CHAR_LENGTH(worker_id) = CHAR_LENGTH(:workerId))"
                + " AND (lease_token = :leaseToken AND CHAR_LENGTH(lease_token) = CHAR_LENGTH(:leaseToken))");
  }

  @Test
  public void mysqlAndPostgresqlUseCurrentStatementOrWallClockTime() {
    assertThat(AsyncJobQueueJdbcDialect.MYSQL.currentTimestampSql())
        .isEqualTo("SELECT UTC_TIMESTAMP(6)");
    assertThat(AsyncJobQueueJdbcDialect.POSTGRESQL.currentTimestampSql())
        .isEqualTo("SELECT clock_timestamp()");
  }

  @Test
  public void hsqlDialectDropsSkipLockedForEmbeddedTests() {
    assertThat(AsyncJobQueueJdbcDialect.HSQL.claimNextJobsSql())
        .contains("SELECT id, status")
        .contains("AND id > 0")
        .contains("LIMIT :limit")
        .doesNotContain("FOR UPDATE SKIP LOCKED");
    assertThat(AsyncJobQueueJdbcDialect.HSQL.currentTimestampSql())
        .isEqualTo(
            "VALUES CAST(CURRENT_TIMESTAMP AT TIME ZONE INTERVAL '0:00' HOUR TO MINUTE AS TIMESTAMP)");
  }

  @Test
  public void unsupportedDialectFailsFast() {
    assertThrows(
        IllegalArgumentException.class, () -> AsyncJobQueueJdbcDialect.fromConfig("sqlite"));
  }
}
