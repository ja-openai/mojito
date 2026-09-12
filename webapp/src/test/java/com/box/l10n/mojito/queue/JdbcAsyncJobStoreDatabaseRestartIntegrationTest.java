package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Disposable database crash/restart, not a network partition or business-side fencing proof. */
@RunWith(Parameterized.class)
public class JdbcAsyncJobStoreDatabaseRestartIntegrationTest {

  private static final Duration LEASE = Duration.ofSeconds(2);
  private final AsyncJobQueueJdbcDialect dialect;

  @Parameterized.Parameters(name = "{0}")
  public static List<AsyncJobQueueJdbcDialect> databases() {
    return List.of(AsyncJobQueueJdbcDialect.MYSQL, AsyncJobQueueJdbcDialect.POSTGRESQL);
  }

  public JdbcAsyncJobStoreDatabaseRestartIntegrationTest(AsyncJobQueueJdbcDialect dialect) {
    this.dialect = dialect;
  }

  @Test(timeout = 120_000)
  public void databaseRestartPreservesCommittedRowsAndFencesExpiredOwners() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (JdbcDatabaseContainer<?> database = database()) {
      database.start();
      try (Connection connection = database.createConnection("")) {
        ScriptUtils.executeSqlScript(
            connection,
            new ClassPathResource(
                dialect == AsyncJobQueueJdbcDialect.MYSQL
                    ? "db/migration/V109__Async_Job_Queue.sql"
                    : "db/postgresql/migration/V109__Async_Job_Queue.sql"));
      }
      try (HikariDataSource pool = pool(database)) {
        JdbcTemplate jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout(2);
        JdbcAsyncJobStore store =
            new JdbcAsyncJobStore(
                new NamedParameterJdbcTemplate(jdbc),
                dialect,
                new DataSourceTransactionManager(pool));
        assertDurabilitySettings(jdbc);
        Instant beforeRestart =
            dialect == AsyncJobQueueJdbcDialect.POSTGRESQL ? postmasterStartedAt(jdbc) : null;
        AsyncJobId queuedId = store.enqueueNow("restart-queued", "original input");
        AsyncJobId doneId = store.enqueueNow("restart-done", "done input");
        AsyncJobRecord doneClaim =
            store.claimNextJobs("restart-done", 1, "worker", Duration.ofSeconds(30)).getFirst();
        assertThat(
                store.markDone("restart-done", doneId, "worker", doneClaim.leaseToken(), "winner"))
            .isTrue();
        AsyncJobRecord queued = row(store, queuedId);
        AsyncJobRecord done = row(store, doneId);
        AsyncJobId runningId = store.enqueueNow("restart-running", "running input");
        AsyncJobRecord claim =
            store.claimNextJobs("restart-running", 1, "worker", LEASE).getFirst();
        AsyncJobRecord running = row(store, runningId);

        // A separate, uncommitted writer must disappear; already acknowledged queue writes must
        // not.
        try (Connection uncommitted = database.createConnection("")) {
          uncommitted.setAutoCommit(false);
          try (var statement =
              uncommitted.prepareStatement(
                  "UPDATE async_job_queue SET job_data = ? WHERE id = ?")) {
            statement.setQueryTimeout(2);
            statement.setString(1, "uncommitted input");
            statement.setLong(2, Long.parseLong(queuedId.value()));
            assertThat(statement.executeUpdate()).isEqualTo(1);
          }
          assertThat(row(store, queuedId)).isEqualTo(queued);
          // Keep the same container, data directory and published port; stop() would remove them.
          database
              .getDockerClient()
              .killContainerCmd(database.getContainerId())
              .withSignal("KILL")
              .exec();
          await("the database container stops", () -> !database.isRunning());
          assertThat(uncommitted.isValid(1)).isFalse();
          assertThatThrownBy(
                  () -> {
                    try (Connection unexpected = database.createConnection("")) {
                      // Also close a wrongly accepted connection if the outage assertion fails.
                    }
                  })
              .isInstanceOf(SQLException.class);
          assertThatThrownBy(
                  () ->
                      store.heartbeat(
                          "restart-running", runningId, "worker", claim.leaseToken(), LEASE))
              .isInstanceOf(RuntimeException.class)
              .hasCauseInstanceOf(SQLException.class);
        }

        database.getDockerClient().startContainerCmd(database.getContainerId()).exec();
        await(
            "the existing worker pool reconnects after database restart",
            () -> recovered(jdbc, beforeRestart));
        assertThat(database.isRunning()).isTrue();
        assertDurabilitySettings(jdbc);
        assertThat(row(store, queuedId)).isEqualTo(queued);
        assertThat(row(store, doneId)).isEqualTo(done);
        assertThat(row(store, runningId)).isEqualTo(running);
        await(
            "database clock reaches the original lease expiry",
            () -> store.expiredLeaseStatus("restart-running").count() == 1);
        assertStaleOwnerRejected(store, claim);
        assertThat(row(store, runningId)).isEqualTo(running);

        AsyncJobRecord replacement =
            store.claimNextJobs("restart-running", 1, "worker", Duration.ofSeconds(30)).getFirst();
        assertThat(replacement.id()).isEqualTo(runningId);
        assertThat(replacement.workerId()).isEqualTo(claim.workerId());
        assertThat(replacement.leaseToken()).isNotBlank().isNotEqualTo(claim.leaseToken());
        assertThat(replacement.attemptCount()).isEqualTo(2);
        assertThat(replacement.leaseReclaimed()).isTrue();
        AsyncJobRecord activeReplacement = row(store, runningId);
        assertStaleOwnerRejected(store, claim);
        assertThat(row(store, runningId)).isEqualTo(activeReplacement);
        assertThat(
                store.markDone(
                    "restart-running",
                    runningId,
                    "worker",
                    replacement.leaseToken(),
                    "recovered output"))
            .isTrue();
        assertThat(row(store, runningId).status()).isEqualTo(AsyncJobStatus.DONE);
        assertThat(row(store, runningId).jobData()).isEqualTo("recovered output");

        AsyncJobRecord queuedClaim =
            store.claimNextJobs("restart-queued", 1, "worker", Duration.ofSeconds(30)).getFirst();
        assertThat(queuedClaim.id()).isEqualTo(queuedId);
        assertThat(queuedClaim.attemptCount()).isEqualTo(1);
        assertThat(queuedClaim.jobData()).isEqualTo("original input");
        assertThat(
                store.markDone(
                    "restart-queued",
                    queuedId,
                    "worker",
                    queuedClaim.leaseToken(),
                    "queued output"))
            .isTrue();
        assertThat(store.claimNextJobs("restart-done", 1, "worker", LEASE)).isEmpty();
        assertThat(row(store, doneId)).isEqualTo(done);
        AsyncJobId newId = store.enqueueNow("restart-new", "after restart");
        assertThat(Long.parseLong(newId.value())).isGreaterThan(Long.parseLong(runningId.value()));
        assertThat(row(store, newId).jobData()).isEqualTo("after restart");
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
      }
    }
  }

  private static void assertStaleOwnerRejected(JdbcAsyncJobStore store, AsyncJobRecord claim) {
    String queue = claim.queueName();
    AsyncJobId id = claim.id();
    String worker = claim.workerId();
    String token = claim.leaseToken();
    assertThat(store.heartbeat(queue, id, worker, token, LEASE)).isFalse();
    assertThat(store.markDone(queue, id, worker, token, "stale output")).isFalse();
    assertThat(store.markFailed(queue, id, worker, token, "stale error", "stale input")).isFalse();
    assertThat(
            store.requeueAfter(
                queue, id, worker, token, Duration.ZERO, "stale error", "stale input"))
        .isFalse();
  }

  private static AsyncJobRecord row(JdbcAsyncJobStore store, AsyncJobId id) {
    return store.getByIds(List.of(id)).getFirst();
  }

  private JdbcDatabaseContainer<?> database() {
    if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
      return new MySQLContainer<>("mysql:8.4")
          .withCommand(
              "mysqld",
              "--innodb-flush-log-at-trx-commit=1",
              "--sync-binlog=1",
              "--innodb-doublewrite=ON")
          .withConnectTimeoutSeconds(10)
          .withUrlParam("connectTimeout", "1000")
          .withUrlParam("socketTimeout", "2000");
    }
    return new PostgreSQLContainer<>("postgres:16")
        .withCommand(
            "postgres",
            "-c",
            "fsync=on",
            "-c",
            "synchronous_commit=on",
            "-c",
            "full_page_writes=on")
        .withConnectTimeoutSeconds(10)
        .withUrlParam("connectTimeout", "1")
        .withUrlParam("socketTimeout", "2");
  }

  private static HikariDataSource pool(JdbcDatabaseContainer<?> database) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("queue-database-restart");
    config.setJdbcUrl(database.getJdbcUrl());
    config.setUsername(database.getUsername());
    config.setPassword(database.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(250);
    config.setValidationTimeout(250);
    config.setInitializationFailTimeout(5000);
    return new HikariDataSource(config);
  }

  private void assertDurabilitySettings(JdbcTemplate jdbc) {
    if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
      assertThat(jdbc.queryForObject("SELECT @@innodb_flush_log_at_trx_commit", Integer.class))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT @@sync_binlog", Integer.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT @@innodb_doublewrite", String.class)).isEqualTo("ON");
      return;
    }
    for (String setting : List.of("fsync", "synchronous_commit", "full_page_writes")) {
      assertThat(jdbc.queryForObject("SHOW " + setting, String.class)).isEqualTo("on");
    }
  }

  private static Instant postmasterStartedAt(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "SELECT pg_postmaster_start_time()",
        (rs, row) -> AsyncJobQueueJdbcDialect.POSTGRESQL.readTimestamp(rs, 1));
  }

  private static boolean recovered(JdbcTemplate jdbc, Instant previousStart) {
    try {
      return previousStart == null
          ? jdbc.queryForObject("SELECT 1", Integer.class) == 1
          : postmasterStartedAt(jdbc).isAfter(previousStart);
    } catch (org.springframework.dao.DataAccessException unavailable) {
      return false;
    }
  }

  private static void await(String description, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    CountDownLatch interval = new CountDownLatch(1);
    while (!condition.getAsBoolean()) {
      long remaining = deadline - System.nanoTime();
      assertTrue(description, remaining > 0);
      interval.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
    }
  }
}
