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
    assertThat(AsyncJobQueueJdbcDialect.fromConfig("hsql-db"))
        .isEqualTo(AsyncJobQueueJdbcDialect.HSQL);
  }

  @Test
  public void mysqlAndPostgresqlUseSkipLockedClaimSql() {
    assertThat(AsyncJobQueueJdbcDialect.MYSQL.claimNextJobsSql())
        .contains("LIMIT :limit")
        .contains("FOR UPDATE SKIP LOCKED");
    assertThat(AsyncJobQueueJdbcDialect.POSTGRESQL.claimNextJobsSql())
        .contains("LIMIT :limit")
        .contains("FOR UPDATE SKIP LOCKED");
  }

  @Test
  public void hsqlDialectDropsSkipLockedForEmbeddedTests() {
    assertThat(AsyncJobQueueJdbcDialect.HSQL.claimNextJobsSql())
        .contains("LIMIT :limit")
        .doesNotContain("FOR UPDATE SKIP LOCKED");
    assertThat(AsyncJobQueueJdbcDialect.HSQL.currentTimestampSql())
        .isEqualTo("VALUES CURRENT_TIMESTAMP");
  }

  @Test
  public void unsupportedDialectFailsFast() {
    assertThrows(
        IllegalArgumentException.class, () -> AsyncJobQueueJdbcDialect.fromConfig("sqlite"));
  }
}
