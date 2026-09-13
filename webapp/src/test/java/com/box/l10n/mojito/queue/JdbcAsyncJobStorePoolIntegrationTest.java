package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Disposable V113 schema only. Pool starvation and forced session loss must preserve fencing;
 * blocked reauthentication is not a network blackhole or a multi-host partition test.
 */
@RunWith(Parameterized.class)
public class JdbcAsyncJobStorePoolIntegrationTest {

  private static final int WAIT_SECONDS = 10;
  private static final String WINNER_PAYLOAD = "{\"owner\":\"winner\"}";
  private static final String STALE_PAYLOAD = "{\"owner\":\"stale\"}";
  private static final String WORKER_USER = "queue_disconnect_worker";
  private static final String WORKER_PASSWORD = "disposable-worker-password";
  private final AsyncJobQueueJdbcDialect dialect;

  @Parameterized.Parameters(name = "{0}")
  public static List<AsyncJobQueueJdbcDialect> databases() {
    return List.of(AsyncJobQueueJdbcDialect.MYSQL, AsyncJobQueueJdbcDialect.POSTGRESQL);
  }

  public JdbcAsyncJobStorePoolIntegrationTest(AsyncJobQueueJdbcDialect dialect) {
    this.dialect = dialect;
  }

  @Test
  public void poolOutageCannotLetAStaleHandlerOverwriteTheReclaimedWinner() throws Exception {
    assertOutageRecovery(false);
  }

  @Test
  public void disconnectedWorkerRecoversItsPoolWithoutOverwritingAnActiveReplacementLease()
      throws Exception {
    assertOutageRecovery(true);
  }

  private void assertOutageRecovery(boolean disconnect) throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (JdbcDatabaseContainer<?> database = database()) {
      database.start();
      String migration =
          dialect == AsyncJobQueueJdbcDialect.MYSQL
              ? "db/migration/V113__Async_Job_Queue.sql"
              : "db/postgresql/migration/V113__Async_Job_Queue.sql";
      try (Connection connection = database.createConnection("")) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
      }
      DataSource independent =
          new DriverManagerDataSource(
              database.getJdbcUrl(),
              disconnect && dialect == AsyncJobQueueJdbcDialect.MYSQL
                  ? "root"
                  : database.getUsername(),
              database.getPassword());
      JdbcTemplate oracle = jdbc(independent);
      if (disconnect) {
        createWorkerAccount(oracle, database.getDatabaseName());
      }
      JdbcAsyncJobStore peer =
          new JdbcAsyncJobStore(
              new NamedParameterJdbcTemplate(oracle),
              dialect,
              new DataSourceTransactionManager(independent));
      SimpleMeterRegistry meters = new SimpleMeterRegistry();
      try (AutoCloseable metersCleanup = meters::close;
          HikariDataSource pool = pool(database, disconnect)) {
        ThreadPoolTaskScheduler pollScheduler = new ThreadPoolTaskScheduler();
        pollScheduler.setPoolSize(1);
        pollScheduler.setAwaitTerminationMillis(5000);
        pollScheduler.setThreadNamePrefix("pool-test-poll-");
        try (AutoCloseable schedulerCleanup = pollScheduler::shutdown) {
          pollScheduler.initialize();
          GatedHandler handler = new GatedHandler("pool-" + UUID.randomUUID());
          ObservedStore store = new ObservedStore(pool, dialect);
          AsyncJobQueueCoordinator coordinator =
              new AsyncJobQueueCoordinator(
                  store, properties(handler.queueName()), List.of(handler), pollScheduler, meters);
          // The coordinator constructs the production executor and owned heartbeat scheduler.
          try (AutoCloseable coordinatorCleanup = coordinator::stop) {
            try {
              assertPoolOutage(pool, peer, oracle, store, coordinator, handler, meters, disconnect);
            } finally {
              handler.release.countDown();
            }
          }
        }
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
      }
    }
  }

  private void assertPoolOutage(
      HikariDataSource pool,
      JdbcAsyncJobStore peer,
      JdbcTemplate oracle,
      ObservedStore store,
      AsyncJobQueueCoordinator coordinator,
      GatedHandler handler,
      SimpleMeterRegistry meters,
      boolean disconnect)
      throws Exception {
    String queue = handler.queueName();
    AsyncJobId id = peer.enqueueNow(queue, "{}");
    coordinator.start();
    AsyncJobRecord original = handler.started.get(WAIT_SECONDS, TimeUnit.SECONDS);
    assertThat(original.id()).isEqualTo(id);
    assertThat(original.attemptCount()).isEqualTo(1);
    assertThat(meters.get("asyncJobQueue.inflight").gauge().value()).isEqualTo(1);

    AsyncJobRecord winningRow;
    boolean loginBlocked = false;
    // Borrow every shared connection only after business work has actually started.
    try (Connection held = pool.getConnection()) {
      assertThat(held.isClosed()).isFalse();
      assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
      assertThat(pool.getHikariPoolMXBean().getIdleConnections()).isZero();
      if (disconnect) {
        long sessionId;
        try (var statement = held.createStatement();
            var result =
                statement.executeQuery(
                    dialect == AsyncJobQueueJdbcDialect.MYSQL
                        ? "SELECT CONNECTION_ID()"
                        : "SELECT pg_backend_pid()")) {
          assertThat(result.next()).isTrue();
          sessionId = result.getLong(1);
        }
        setWorkerLogin(oracle, false);
        loginBlocked = true;
        if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
          oracle.execute("KILL CONNECTION " + sessionId);
        } else {
          assertThat(
                  oracle.queryForObject(
                      "SELECT pg_terminate_backend(?)", Boolean.class, Math.toIntExact(sessionId)))
              .isTrue();
        }
        awaitCondition(
            "the worker's real JDBC connection is terminated",
            () -> {
              try {
                return !held.isValid(1);
              } catch (SQLException disconnected) {
                return true;
              }
            });
        // Return the dead connection: the outage must persist without holding the pool permit.
        try {
          held.close();
        } catch (SQLException disconnected) {
          // PostgreSQL can report connection loss while Hikari clears warnings on close.
          assertThat(disconnected.getSQLState()).startsWith("08");
        }
        assertThat(held.isClosed()).isTrue();
        awaitCondition(
            "the dead worker connection no longer holds a pool permit",
            () -> pool.getHikariPoolMXBean().getActiveConnections() == 0);
        DriverManagerDataSource blockedWorker =
            new DriverManagerDataSource(pool.getJdbcUrl(), WORKER_USER, WORKER_PASSWORD);
        assertThatThrownBy(
                () -> {
                  try (Connection unexpected = blockedWorker.getConnection()) {
                    // Closing a wrongly accepted connection also keeps a failing fixture bounded.
                  }
                })
            .isInstanceOf(SQLException.class);
      }
      // Ignore renewals that began while the fixture was still setting up the outage.
      store.observeFailures = true;
      TimeoutObservation timeout = store.timeout.get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertThat(pool.getConnectionTimeout()).isEqualTo(250);
      if (disconnect) {
        Throwable cause = timeout.failure();
        while (cause != null && !(cause instanceof SQLException)) {
          cause = cause.getCause();
        }
        assertThat(cause)
            .as("renewal failed through the actual JDBC/pool path")
            .isInstanceOf(SQLException.class);
        awaitCondition(
            "the disconnected worker has no borrowed pool connections",
            () -> pool.getHikariPoolMXBean().getActiveConnections() == 0);
      } else {
        assertThat(timeout.failure())
            .isInstanceOf(CannotCreateTransactionException.class)
            .hasCauseInstanceOf(SQLTransientConnectionException.class);
        assertThat(timeout.elapsedMs()).isGreaterThanOrEqualTo(pool.getConnectionTimeout());
      }

      AsyncJobRecord stranded = peer.getByIds(List.of(id)).get(0);
      assertThat(stranded.status()).isEqualTo(AsyncJobStatus.RUNNING);
      assertThat(stranded.leaseToken()).isEqualTo(original.leaseToken());
      Instant leaseUntil = stranded.leaseUntil();
      awaitCondition(
          "database clock reaches the stranded lease expiry",
          () -> !databaseInstant(oracle).isBefore(leaseUntil));
      assertThat(peer.expiredLeaseStatus(queue).count()).isEqualTo(1);
      assertThat(peer.getByIds(List.of(id)).get(0).leaseUntil()).isEqualTo(leaseUntil);
      List<AsyncJobRecord> reclaimed =
          peer.claimNextJobs(queue, 1, original.workerId(), Duration.ofSeconds(30));
      assertThat(reclaimed).hasSize(1);
      AsyncJobRecord winner = reclaimed.get(0);
      assertThat(winner.id()).isEqualTo(id);
      assertThat(winner.attemptCount()).isEqualTo(2);
      assertThat(winner.leaseReclaimed()).isTrue();
      assertThat(winner.workerId()).isEqualTo(original.workerId());
      assertThat(winner.leaseToken()).isNotBlank().isNotEqualTo(original.leaseToken());
      winningRow = peer.getByIds(List.of(id)).get(0);
      assertThat(winningRow.status()).isEqualTo(AsyncJobStatus.RUNNING);
      assertThat(handler.release.getCount()).isEqualTo(1);
      assertThat(handler.doneCallbacks.get()).isZero();
      System.out.println(
          "POOL "
              + dialect
              + " disconnect="
              + disconnect
              + " timeoutMs="
              + timeout.elapsedMs()
              + " leaseUntil="
              + leaseUntil
              + " reclaimedAt="
              + winner.updatedDate()
              + " winnerAttempt="
              + winner.attemptCount());
    } finally {
      if (loginBlocked) {
        setWorkerLogin(oracle, true);
      }
    }

    // Pool recovery allows a real renewal attempt, which must reject the old token.
    assertThat(store.rejectedHeartbeat.get(WAIT_SECONDS, TimeUnit.SECONDS)).isFalse();
    assertThat(handler.release.getCount()).isEqualTo(1);
    assertThat(store.completion.isDone()).isFalse();
    assertThat(peer.getByIds(List.of(id)).get(0)).isEqualTo(winningRow);
    handler.release.countDown();
    assertThat(store.completion.get(WAIT_SECONDS, TimeUnit.SECONDS)).isFalse();
    awaitCondition(
        "runtime releases its in-flight and executor capacity",
        () ->
            meters.get("asyncJobQueue.inflight").gauge().value() == 0
                && meters.get("asyncJobQueue.executor.active").gauge().value() == 0
                && meters.get("asyncJobQueue.executor.queued").gauge().value() == 0
                && meters.get("asyncJobQueue.processing.latency").timer().count() == 1);
    assertThat(handler.invocations.get()).isEqualTo(1);
    assertThat(handler.doneCallbacks.get()).isZero();
    assertThat(peer.getByIds(List.of(id)).get(0)).isEqualTo(winningRow);
    assertThat(
            peer.markDone(
                queue, id, winningRow.workerId(), winningRow.leaseToken(), WINNER_PAYLOAD))
        .isTrue();
    AsyncJobRecord completedWinner = peer.getByIds(List.of(id)).get(0);
    assertThat(completedWinner.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(completedWinner.jobData()).isEqualTo(WINNER_PAYLOAD);
    assertThat(
            meters
                .get("asyncJobQueue.transition.failed")
                .tag("transition", "done")
                .counter()
                .count())
        .isEqualTo(1);
    System.out.println(
        "POOL "
            + dialect
            + " staleHeartbeat=false staleCompletion=false callbacks=0"
            + " inflight=0 winnerPreserved=true");
  }

  private void createWorkerAccount(JdbcTemplate admin, String databaseName) {
    if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
      assertThat(databaseName).matches("[A-Za-z0-9_]+");
      admin.execute(
          "CREATE USER '" + WORKER_USER + "'@'%' IDENTIFIED BY '" + WORKER_PASSWORD + "'");
      admin.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON `"
              + databaseName
              + "`.async_job_queue TO '"
              + WORKER_USER
              + "'@'%'");
    } else {
      admin.execute("CREATE ROLE " + WORKER_USER + " LOGIN PASSWORD '" + WORKER_PASSWORD + "'");
      admin.execute("GRANT USAGE ON SCHEMA public TO " + WORKER_USER);
      admin.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON async_job_queue TO " + WORKER_USER);
      admin.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + WORKER_USER);
    }
  }

  private void setWorkerLogin(JdbcTemplate admin, boolean enabled) {
    admin.execute(
        dialect == AsyncJobQueueJdbcDialect.MYSQL
            ? "ALTER USER '" + WORKER_USER + "'@'%' ACCOUNT " + (enabled ? "UNLOCK" : "LOCK")
            : "ALTER ROLE " + WORKER_USER + (enabled ? " LOGIN" : " NOLOGIN"));
  }

  private JdbcDatabaseContainer<?> database() {
    if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
      return new MySQLContainer<>("mysql:8.4")
          .withConnectTimeoutSeconds(10)
          .withUrlParam("connectTimeout", "5000")
          .withUrlParam("socketTimeout", "30000");
    }
    return new PostgreSQLContainer<>("postgres:16")
        .withConnectTimeoutSeconds(10)
        .withUrlParam("connectTimeout", "5")
        .withUrlParam("socketTimeout", "30");
  }

  private static HikariDataSource pool(JdbcDatabaseContainer<?> database, boolean disconnect) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("queue-starvation");
    config.setJdbcUrl(database.getJdbcUrl());
    config.setUsername(disconnect ? WORKER_USER : database.getUsername());
    config.setPassword(disconnect ? WORKER_PASSWORD : database.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setConnectionTimeout(250);
    config.setValidationTimeout(250);
    config.setInitializationFailTimeout(5000);
    config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    return new HikariDataSource(config);
  }

  private static AsyncJobQueueProperties properties(String queue) {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStore("jdbc");
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setPollIntervalMs(25);
    settings.setMaxPollIntervalMs(100);
    settings.setPollJitterPercent(0);
    settings.setHeartbeatIntervalMs(100);
    settings.setLeaseDurationMs(1500);
    settings.setShutdownAwaitTerminationMs(5000);
    properties.getQueues().put(queue, settings);
    return properties;
  }

  private static JdbcTemplate jdbc(DataSource source) {
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  private Instant databaseInstant(JdbcTemplate jdbc) {
    String sql =
        dialect == AsyncJobQueueJdbcDialect.MYSQL
            ? "SELECT TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6))"
            : "SELECT (EXTRACT(EPOCH FROM clock_timestamp()) * 1000000)::bigint";
    long micros = jdbc.queryForObject(sql, Long.class);
    return Instant.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
  }

  private static void awaitCondition(String description, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    CountDownLatch pollInterval = new CountDownLatch(1);
    while (!condition.getAsBoolean()) {
      long remaining = deadline - System.nanoTime();
      assertTrue(description, remaining > 0);
      pollInterval.await(
          Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS);
    }
  }

  private static final class GatedHandler implements AsyncJobHandler {
    private final String queue;
    private final CompletableFuture<AsyncJobRecord> started = new CompletableFuture<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger doneCallbacks = new AtomicInteger();

    private GatedHandler(String queue) {
      this.queue = queue;
    }

    @Override
    public String queueName() {
      return queue;
    }

    @Override
    public AsyncJobHandlerResult process(AsyncJobRecord job) throws Exception {
      invocations.incrementAndGet();
      started.complete(job);
      assertTrue("test releases the held handler", release.await(30, TimeUnit.SECONDS));
      return AsyncJobHandlerResult.done(STALE_PAYLOAD);
    }

    @Override
    public void onJobDone(AsyncJobRecord job, AsyncJobHandlerResult result) {
      doneCallbacks.incrementAndGet();
    }
  }

  private record TimeoutObservation(RuntimeException failure, long elapsedMs) {}

  private static final class ObservedStore extends JdbcAsyncJobStore {
    private final CompletableFuture<TimeoutObservation> timeout = new CompletableFuture<>();
    private final CompletableFuture<Boolean> rejectedHeartbeat = new CompletableFuture<>();
    private final CompletableFuture<Boolean> completion = new CompletableFuture<>();
    private volatile boolean observeFailures;

    private ObservedStore(DataSource source, AsyncJobQueueJdbcDialect dialect) {
      super(
          new NamedParameterJdbcTemplate(jdbc(source)),
          dialect,
          new DataSourceTransactionManager(source));
    }

    @Override
    public boolean heartbeat(
        String queue, AsyncJobId id, String worker, String token, Duration duration) {
      boolean observeFailure = observeFailures;
      long started = System.nanoTime();
      try {
        boolean renewed = super.heartbeat(queue, id, worker, token, duration);
        if (!renewed) {
          rejectedHeartbeat.complete(false);
        }
        return renewed;
      } catch (RuntimeException failure) {
        if (observeFailure) {
          timeout.complete(
              new TimeoutObservation(
                  failure, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        }
        throw failure;
      }
    }

    @Override
    public boolean markDone(String queue, AsyncJobId id, String worker, String token, String data) {
      boolean persisted = super.markDone(queue, id, worker, token, data);
      completion.complete(persisted);
      return persisted;
    }
  }
}
