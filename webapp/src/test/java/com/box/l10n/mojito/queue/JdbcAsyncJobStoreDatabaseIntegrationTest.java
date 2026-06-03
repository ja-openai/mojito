package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.assertj.core.api.SoftAssertions;
import org.junit.Test;
import org.junit.runners.model.MultipleFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

public class JdbcAsyncJobStoreDatabaseIntegrationTest {

  private static final String ENABLE_PROPERTY = "mojito.asyncJobQueue.testcontainers";
  private static final String PERF_ENABLE_PROPERTY = "mojito.asyncJobQueue.perf";
  private static final Logger logger =
      LoggerFactory.getLogger(JdbcAsyncJobStoreDatabaseIntegrationTest.class);

  @Test
  public void migrationSqlFailureDoesNotRetryTheMigration() throws Exception {
    JdbcDatabaseContainer<?> container = mock(JdbcDatabaseContainer.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    SQLException failure = new SQLException("migration failed");
    when(container.createConnection("")).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenThrow(failure);

    assertThat(
            assertThrows(
                ScriptException.class,
                () -> runMigration(container, "db/migration/V109__Async_Job_Queue.sql")))
        .hasCause(failure);

    verify(container).createConnection("");
    verifyNoMoreInteractions(container);
    verify(statement).execute(anyString());
    verify(statement).close();
    verify(connection).close();
  }

  @Test
  public void migrationConnectionFailureIsNotRetriedOutsideContainerReadiness() throws Exception {
    JdbcDatabaseContainer<?> container = mock(JdbcDatabaseContainer.class);
    SQLException failure = new SQLException("connection readiness exhausted");
    when(container.createConnection("")).thenThrow(failure);

    assertSame(
        failure,
        assertThrows(
            SQLException.class,
            () -> runMigration(container, "db/migration/V109__Async_Job_Queue.sql")));

    verify(container).createConnection("");
    verifyNoMoreInteractions(container);
  }

  @Test
  public void postgresqlMigrationIsOutsideDefaultMysqlFlywayLocation() {
    assertThat(
            Files.exists(
                Path.of("src/main/resources/db/migration/postgresql/V109__Async_Job_Queue.sql")))
        .isFalse();
    assertThat(new ClassPathResource("db/postgresql/migration/V109__Async_Job_Queue.sql").exists())
        .isTrue();
  }

  @Test
  public void mysqlAndPostgresqlMigrationsStayStructurallyAligned() throws Exception {
    String mysqlMigration = resourceText("db/migration/V109__Async_Job_Queue.sql");
    String postgresqlMigration = resourceText("db/postgresql/migration/V109__Async_Job_Queue.sql");

    assertCoreQueueMigrationShape(mysqlMigration);
    assertCoreQueueMigrationShape(postgresqlMigration);

    assertThat(mysqlMigration)
        .contains("id BIGINT AUTO_INCREMENT PRIMARY KEY")
        .doesNotContain("BIGINT UNSIGNED")
        .contains("available_at DATETIME(6) NOT NULL")
        .contains("lease_until DATETIME(6) NULL")
        .contains("job_data LONGTEXT NOT NULL")
        .contains("created_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
        .contains(
            "updated_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE"
                + " CURRENT_TIMESTAMP(6)")
        .contains("CHECK (REGEXP_LIKE(queue_name, '^[A-Za-z0-9._-]+$', 'c'))")
        .doesNotContain("C__ASYNC_JOB_QUEUE__ID_POSITIVE");
    assertThat(postgresqlMigration)
        .contains("id BIGSERIAL PRIMARY KEY")
        .contains("available_at TIMESTAMPTZ(6) NOT NULL")
        .contains("lease_until TIMESTAMPTZ(6) NULL")
        .contains("job_data TEXT NOT NULL")
        .contains("created_date TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP")
        .contains("updated_date TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP")
        .contains("C__ASYNC_JOB_QUEUE__ID_POSITIVE")
        .contains("CHECK (id > 0)")
        .contains("CHECK (queue_name ~ '^[A-Za-z0-9._-]+$')")
        .doesNotContain("ON UPDATE");
  }

  @Test
  public void mysqlStoreContractRunsAgainstRealDatabase() throws Exception {
    assumeContainerTestsEnabled();
    try (MySQLContainer<?> container = mysqlContainer()) {
      runStoreContract(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void postgresqlStoreContractRunsAgainstRealDatabase() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgresContainer()) {
      runStoreContract(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void mysqlIdentityRemainsExactAcrossCaseAndPaddingCollations() throws Exception {
    assumeContainerTestsEnabled();
    try (MySQLContainer<?> container =
        mysqlContainer().withUrlParam("characterEncoding", "UTF-8")) {
      container.start();
      runMigration(container, "db/migration/V109__Async_Job_Queue.sql");
      DataSource dataSource = dataSource(container);
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      assertThat(jdbc.queryForObject("SELECT @@character_set_connection", String.class))
          .isEqualTo("utf8mb4");
      AsyncJobStore store = jdbcStore(dataSource, AsyncJobQueueJdbcDialect.MYSQL);
      // Fixture-only collation changes: the application migration remains byte-for-byte intact.
      for (String collation : List.of("utf8mb4_0900_ai_ci", "utf8mb4_general_ci", "utf8mb4_bin")) {
        jdbc.execute(
            "ALTER TABLE async_job_queue CONVERT TO CHARACTER SET utf8mb4 COLLATE " + collation);
        AsyncJobStoreIdentityContract.assertQueueIsolation(store);
        AsyncJobStoreIdentityContract.assertMaintenanceIsolation(store);
        AsyncJobStoreIdentityContract.assertExpiredQueueIsolation(store);
        AsyncJobStoreIdentityContract.assertLeaseOwnerIsolation(store);
      }
      jdbc.execute(
          "ALTER TABLE async_job_queue MODIFY worker_id VARCHAR(128) CHARACTER SET latin1 COLLATE latin1_swedish_ci NULL");
      AsyncJobStoreIdentityContract.assertLeaseOwnerIsolation(store);
    }
  }

  @Test
  public void mysqlClaimBatchKeepsPeerProgressAndBoundedRecordLocks() throws Exception {
    assumeContainerTestsEnabled();
    // Root is fixture-only: observing performance_schema locks needs more than schema DML grants.
    try (MySQLContainer<?> container =
        mysqlContainer().withUsername("root").withUrlParam("rewriteBatchedStatements", "true")) {
      container.start();
      runMigration(container, "db/migration/V109__Async_Job_Queue.sql");
      DataSource dataSource = dataSource(container);
      for (String fixture : List.of("queued", "running", "mixed", "foreign", "active")) {
        for (int batch : List.of(1, 3)) {
          assertMysqlClaimLockFootprint(dataSource, fixture, batch);
        }
      }
    }
  }

  private void assertMysqlClaimLockFootprint(DataSource dataSource, String fixture, int batch)
      throws Exception {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("TRUNCATE TABLE async_job_queue");
    seedMysqlClaimBacklog(dataSource, fixture);
    jdbc.execute("ANALYZE TABLE async_job_queue");
    List<Map<String, Object>> before =
        jdbc.queryForList("SELECT * FROM async_job_queue ORDER BY id");
    CountDownLatch selected = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AsyncJobStore firstStore =
        new JdbcAsyncJobStore(
            new NamedParameterJdbcTemplate(dataSource) {
              @Override
              public <T> List<T> query(
                  String sql, SqlParameterSource params, RowMapper<T> rowMapper) {
                List<T> result = super.query(sql, params, rowMapper);
                if (sql.equals(AsyncJobQueueJdbcDialect.MYSQL.claimNextJobsSql())) {
                  selected.countDown();
                  try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Claim candidate release timed out");
                    }
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                  }
                }
                return result;
              }
            },
            AsyncJobQueueJdbcDialect.MYSQL,
            new DataSourceTransactionManager(dataSource));
    AsyncJobStore peerStore = jdbcStore(dataSource, AsyncJobQueueJdbcDialect.MYSQL);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<List<AsyncJobRecord>> pending =
        executor.submit(
            () -> firstStore.claimNextJobs("claim-plan", batch, "first", Duration.ofSeconds(30)));
    try {
      assertThat(selected.await(10, TimeUnit.SECONDS)).as("candidate query completed").isTrue();
      Map<String, Long> locks = new LinkedHashMap<>();
      jdbc.query(
          """
          SELECT INDEX_NAME, COUNT(*) AS lock_count
          FROM performance_schema.data_locks
          WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = 'async_job_queue'
            AND LOCK_TYPE = 'RECORD'
          GROUP BY INDEX_NAME
          """,
          rs -> {
            locks.put(rs.getString("INDEX_NAME"), rs.getLong("lock_count"));
          });
      // This peer commits before the first claimant is allowed to release any candidate locks.
      List<AsyncJobRecord> peer =
          peerStore.claimNextJobs("claim-plan", batch, "peer", Duration.ofSeconds(30));
      release.countDown();
      List<AsyncJobRecord> first = pending.get(10, TimeUnit.SECONDS);
      SoftAssertions results = new SoftAssertions();
      results.assertThat(peer).as("peer progress: %s batch=%s", fixture, batch).hasSize(batch);
      results.assertThat(first).hasSize(batch);
      long oldestId = fixture.equals("foreign") || fixture.equals("active") ? 501 : 1;
      List<AsyncJobId> expectedFirst = new ArrayList<>();
      for (int i = 0; i < batch; i++) {
        expectedFirst.add(new AsyncJobId(Long.toString(oldestId + i)));
      }
      results
          .assertThat(first)
          .extracting(AsyncJobRecord::id)
          .containsExactlyElementsOf(expectedFirst);
      results
          .assertThat(locks.getOrDefault("PRIMARY", 0L))
          .as("retained primary locks: %s batch=%s", fixture, batch)
          .isBetween((long) batch, 2L * batch);
      results
          .assertThat(locks.values().stream().mapToLong(Long::longValue).sum())
          .as("retained index record locks: %s batch=%s", fixture, batch)
          .isLessThanOrEqualTo(4L * batch);
      Set<Long> claimed = ConcurrentHashMap.newKeySet();
      for (AsyncJobRecord record : first) {
        results.assertThat(record.workerId()).isEqualTo("first");
        results.assertThat(claimed.add(Long.parseLong(record.id().value()))).isTrue();
      }
      for (AsyncJobRecord record : peer) {
        results.assertThat(record.workerId()).isEqualTo("peer");
        results.assertThat(claimed.add(Long.parseLong(record.id().value()))).isTrue();
      }
      List<Map<String, Object>> after =
          jdbc.queryForList("SELECT * FROM async_job_queue ORDER BY id");
      results
          .assertThat(
              after.stream()
                  .filter(row -> !claimed.contains(((Number) row.get("id")).longValue()))
                  .toList())
          .as("unselected candidates and excluded rows stay unchanged")
          .containsExactlyElementsOf(
              before.stream()
                  .filter(row -> !claimed.contains(((Number) row.get("id")).longValue()))
                  .toList());
      results.assertAll();
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void seedMysqlClaimBacklog(DataSource dataSource, String fixture) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        var insert =
            connection.prepareStatement(
                "INSERT INTO async_job_queue(queue_name,status,available_at,job_data,worker_id,lease_token,lease_until,attempt_count) VALUES(?,?,?,'{}',?,?,?,?)")) {
      connection.setAutoCommit(false);
      for (int i = 0; i < 1500; i++) {
        boolean running = fixture.equals("running") || (!fixture.equals("queued") && i % 2 != 0);
        boolean active = fixture.equals("active") && i < 500;
        running |= active;
        insert.setString(1, fixture.equals("foreign") && i < 500 ? "Claim-Plan" : "claim-plan");
        insert.setString(2, running ? "running" : "queued");
        // Pairs tie across statuses, exercising the global id tie-break after branch merging.
        insert.setObject(3, LocalDateTime.of(2020, 1, 1, 0, 0).plusSeconds(i / 2));
        insert.setString(4, running ? "old-worker" : null);
        insert.setString(5, running ? "old-token" : null);
        insert.setObject(6, running ? LocalDateTime.of(active ? 9998 : 2020, 1, 1, 0, 0) : null);
        insert.setInt(7, running ? 1 : 0);
        insert.addBatch();
      }
      insert.executeBatch();
      connection.commit();
    }
  }

  @Test
  public void runtimeRenewsLeasesUnderRowLockAndClaimContentionAgainstRealDatabases()
      throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgresContainer()) {
      assertRuntimeRenewsContendedLeases(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
    try (MySQLContainer<?> container = mysqlContainer()) {
      assertRuntimeRenewsContendedLeases(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  private void assertRuntimeRenewsContendedLeases(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(container, migrationPath);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(10);
    AtomicReference<RenewalLockProbe> activeProbe = new AtomicReference<>();
    ConcurrentHashMap<AsyncJobId, AtomicInteger> renewals = new ConcurrentHashMap<>();
    AsyncJobStore store =
        new JdbcAsyncJobStore(
            new NamedParameterJdbcTemplate(jdbc) {
              @Override
              public <T> List<T> query(
                  String sql, SqlParameterSource params, RowMapper<T> rowMapper) {
                RenewalLockProbe probe = activeProbe.get();
                boolean observed =
                    probe != null && sql.contains("FOR UPDATE") && params.hasValue("leaseToken");
                if (observed) {
                  probe.started.countDown();
                }
                List<T> result = super.query(sql, params, rowMapper);
                if (observed) {
                  probe.finished.countDown();
                }
                return result;
              }
            },
            dialect,
            new DataSourceTransactionManager(dataSource)) {
          @Override
          public boolean heartbeat(
              String queueName, AsyncJobId id, String worker, String token, Duration duration) {
            boolean renewed = super.heartbeat(queueName, id, worker, token, duration);
            if (renewed) {
              renewals.computeIfAbsent(id, unused -> new AtomicInteger()).incrementAndGet();
            }
            return renewed;
          }
        };
    String queueName = "runtime-renewal";
    int runtimeCount = 4;
    int concurrency = 6;
    int jobCount = runtimeCount * concurrency;
    Duration lease = Duration.ofSeconds(10);
    List<AsyncJobId> ids = new ArrayList<>();
    for (int i = 0; i < jobCount; i++) {
      ids.add(store.enqueueNow(queueName, "{}"));
    }
    CountDownLatch handlersStarted = new CountDownLatch(jobCount);
    CountDownLatch finishHandlers = new CountDownLatch(1);
    Set<AsyncJobId> startedIds = ConcurrentHashMap.newKeySet();
    AtomicInteger duplicates = new AtomicInteger();
    List<AsyncJobQueueRuntime> runtimes = new ArrayList<>();
    List<ThreadPoolTaskExecutor> executors = new ArrayList<>();
    List<ThreadPoolTaskScheduler> heartbeatSchedulers = new ArrayList<>();
    List<ThreadPoolTaskScheduler> initializedSchedulers = new ArrayList<>();
    List<SimpleMeterRegistry> registries = new ArrayList<>();
    Throwable originalFailure = null;
    try {
      for (int i = 0; i < runtimeCount; i++) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registries.add(registry);
        AsyncJobQueueCoordinator factory =
            new AsyncJobQueueCoordinator(
                store,
                new AsyncJobQueueProperties(),
                List.of(),
                mock(TaskScheduler.class),
                registry);
        AsyncJobQueueProperties.QueueSettings settings =
            runtimeQueueSettings(concurrency, concurrency, lease);
        settings.setHeartbeatIntervalMs(250);
        settings.setMaxAttempts(1);
        settings.setShutdownAwaitTerminationMs(5000);
        ThreadPoolTaskExecutor executor = factory.queueExecutor(queueName, settings);
        executors.add(executor);
        ThreadPoolTaskScheduler heartbeat = factory.queueHeartbeatScheduler(queueName, settings);
        heartbeatSchedulers.add(heartbeat);
        heartbeat.initialize();
        initializedSchedulers.add(heartbeat);
        AsyncJobQueueRuntime runtime =
            new AsyncJobQueueRuntime(
                queueName,
                store,
                settings,
                new AsyncJobHandler() {
                  @Override
                  public String queueName() {
                    return queueName;
                  }

                  @Override
                  public AsyncJobHandlerResult process(AsyncJobRecord record) throws Exception {
                    if (!startedIds.add(record.id())) {
                      duplicates.incrementAndGet();
                    }
                    handlersStarted.countDown();
                    if (!finishHandlers.await(45, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("Renewal test did not release handlers");
                    }
                    return AsyncJobHandlerResult.done("{}");
                  }
                },
                mock(TaskScheduler.class),
                executor,
                registry,
                "renewal-worker-" + i,
                null,
                heartbeat);
        runtimes.add(runtime);
        runtime.pollOnce();
      }
      assertThat(handlersStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(startedIds).containsExactlyInAnyOrderElementsOf(ids);
      // Keep timestamps in the database; JDBC/JVM timezone conversion is not an expiry oracle.
      jdbc.execute(
          "CREATE TABLE renewal_snapshot AS SELECT id, lease_token, lease_until "
              + "FROM async_job_queue WHERE queue_name = 'runtime-renewal'");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM renewal_snapshot", Integer.class))
          .isEqualTo(jobCount);

      RenewalLockProbe probe = new RenewalLockProbe(runtimeCount * 5);
      try (Connection lock = dataSource.getConnection()) {
        lock.setAutoCommit(false);
        try (Statement statement = lock.createStatement()) {
          statement.setQueryTimeout(5);
          statement.executeQuery("SELECT id FROM async_job_queue ORDER BY id FOR UPDATE").close();
        }
        assertLeaseHeadroom(lock, dialect, jobCount, 6);
        activeProbe.set(probe);
        assertThat(probe.started.await(4, TimeUnit.SECONDS))
            .as("All production heartbeat threads must reach a locked lease row")
            .isTrue();
        assertThat(probe.finished.await(100, TimeUnit.MILLISECONDS))
            .as("A renewal query must actually wait on the database lock")
            .isFalse();
        assertLeaseHeadroom(lock, dialect, jobCount, 1);
        lock.commit();
        assertThat(probe.finished.await(4, TimeUnit.SECONDS)).isTrue();
      } finally {
        activeProbe.set(null);
      }

      int polls = 0;
      int renewedPastSnapshot = 0;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (System.nanoTime() < deadline && renewedPastSnapshot != jobCount) {
        assertThat(store.claimNextJobs(queueName, jobCount, "competing-worker", lease))
            .as("A competing worker must not reclaim any live long-running handler")
            .isEmpty();
        polls++;
        renewedPastSnapshot =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM async_job_queue q JOIN renewal_snapshot s ON q.id = s.id "
                    + "WHERE q.status = 'running' AND q.attempt_count = 1 "
                    + "AND q.lease_token = s.lease_token AND s.lease_until <= ("
                    + dialect.currentTimestampSql()
                    + ") AND q.lease_until > ("
                    + dialect.currentTimestampSql()
                    + ")",
                Integer.class);
        if (renewedPastSnapshot != jobCount) {
          Thread.sleep(25);
        }
      }
      assertThat(renewedPastSnapshot)
          .as("All old database deadlines must pass")
          .isEqualTo(jobCount);
      assertThat(store.claimNextJobs(queueName, jobCount, "post-deadline-contender", lease))
          .as("No handler may be reclaimed after every captured deadline has passed")
          .isEmpty();
      assertThat(renewals.keySet()).containsExactlyInAnyOrderElementsOf(ids);
      assertThat(renewals.values()).allSatisfy(count -> assertThat(count.get()).isGreaterThan(1));
      assertThat(counterCount(registries, "asyncJobQueue.heartbeat.failed", queueName)).isZero();
      assertThat(duplicates.get()).isZero();
      finishHandlers.countDown();
      waitForAllRuntimesIdle(runtimes);
      waitForAggregatedProcessingLatencyCount(registries, queueName, jobCount);
      assertThat(store.getByIds(ids))
          .hasSize(jobCount)
          .allSatisfy(
              record -> {
                assertThat(record.status()).isEqualTo(AsyncJobStatus.DONE);
                assertThat(record.attemptCount()).isEqualTo(1);
              });
      assertThat(counterCount(registries, "asyncJobQueue.completed", queueName))
          .isEqualTo(jobCount);
      assertThat(counterCount(registries, "asyncJobQueue.heartbeat.failed", queueName))
          .as("Confirmed completion must not manufacture a lease-loss signal")
          .isZero();
      logger.info(
          "Async job queue {} renewal contention: {} handlers, {} competing polls, {} successful renewals",
          dialect,
          jobCount,
          polls,
          renewals.values().stream().mapToInt(AtomicInteger::get).sum());
    } catch (Exception | AssertionError failure) {
      originalFailure = failure;
      throw failure;
    } finally {
      finishHandlers.countDown();
      try {
        stopRenewalRuntimes(
            runtimes, executors, heartbeatSchedulers, initializedSchedulers, registries);
      } catch (Exception | AssertionError failure) {
        if (originalFailure == null) {
          throw failure;
        }
        originalFailure.addSuppressed(failure);
      }
    }
  }

  private void assertLeaseHeadroom(
      Connection connection, AsyncJobQueueJdbcDialect dialect, int expectedRows, double seconds)
      throws Exception {
    String remaining =
        dialect == AsyncJobQueueJdbcDialect.MYSQL
            ? "TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), lease_until) / 1000000.0"
            : "EXTRACT(EPOCH FROM (lease_until - clock_timestamp()))";
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows =
          statement.executeQuery("SELECT COUNT(*), MIN(" + remaining + ") FROM async_job_queue")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(expectedRows);
        assertThat(rows.getDouble(2))
            .as("Every lease needs headroom around the artificial row-lock burst")
            .isGreaterThan(seconds);
      }
    }
  }

  private void stopRenewalRuntimes(
      List<AsyncJobQueueRuntime> runtimes,
      List<ThreadPoolTaskExecutor> executors,
      List<ThreadPoolTaskScheduler> schedulers,
      List<ThreadPoolTaskScheduler> initializedSchedulers,
      List<SimpleMeterRegistry> registries)
      throws Exception {
    List<AutoCloseable> cleanup = new ArrayList<>();
    runtimes.forEach(runtime -> cleanup.add(runtime::stop));
    // Include partially constructed runtimes, and attempt every cleanup even after a failure.
    executors.forEach(executor -> cleanup.add(executor::shutdown));
    schedulers.forEach(scheduler -> cleanup.add(scheduler::shutdown));
    executors.forEach(
        executor ->
            cleanup.add(
                () ->
                    assertThat(
                            executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS))
                        .isTrue()));
    initializedSchedulers.forEach(
        scheduler ->
            cleanup.add(
                () ->
                    assertThat(
                            scheduler
                                .getScheduledThreadPoolExecutor()
                                .awaitTermination(10, TimeUnit.SECONDS))
                        .isTrue()));
    registries.forEach(registry -> cleanup.add(registry::close));
    List<Throwable> failures = new ArrayList<>();
    for (AutoCloseable action : cleanup) {
      try {
        action.close();
      } catch (Exception | AssertionError failure) {
        failures.add(failure);
      }
    }
    MultipleFailureException.assertEmpty(failures);
  }

  private static class RenewalLockProbe {
    final CountDownLatch started;
    final CountDownLatch finished = new CountDownLatch(1);

    RenewalLockProbe(int expectedBlockedQueries) {
      started = new CountDownLatch(expectedBlockedQueries);
    }
  }

  @Test
  public void retentionPreservesConcurrentReplayAgainstRealDatabases() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgresContainer()) {
      assertRetentionPreservesConcurrentUpdate(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
    try (MySQLContainer<?> container = mysqlContainer()) {
      assertRetentionPreservesConcurrentUpdate(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void leaseTransitionsRejectExpiredLeasesAfterLockWaitAgainstRealDatabases()
      throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgresContainer()) {
      assertLeaseTransitionsRejectExpiryDuringLockWait(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
    try (MySQLContainer<?> container = mysqlContainer()) {
      assertLeaseTransitionsRejectExpiryDuringLockWait(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  private void assertRetentionPreservesConcurrentUpdate(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(container, migrationPath);
    AsyncJobStore store = jdbcStore(dataSource, dialect);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      for (boolean replay : List.of(true, false)) {
        AsyncJobId id = store.enqueueNow("retention-race", "{}");
        AsyncJobRecord claimed =
            store.claimNextJobs("retention-race", 1, "worker", Duration.ofSeconds(30)).get(0);
        assertThat(
                store.markFailed(
                    "retention-race", id, "worker", claimed.leaseToken(), null, "old failure"))
            .isTrue();
        new JdbcTemplate(dataSource)
            .update(
                "UPDATE async_job_queue SET updated_date = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                Long.parseLong(id.value()));
        CountDownLatch transitionStarted = new CountDownLatch(1);
        AsyncJobStore contendedStore =
            new JdbcAsyncJobStore(
                new LockWaitObservingJdbcTemplate(dataSource, transitionStarted),
                dialect,
                new DataSourceTransactionManager(dataSource));

        try (Connection connection = dataSource.getConnection()) {
          connection.setAutoCommit(false);
          try (var statement =
              connection.prepareStatement(
                  "UPDATE async_job_queue SET status = ?, updated_date = ? WHERE id = ?")) {
            statement.setString(1, replay ? "queued" : "failed");
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, Long.parseLong(id.value()));
            statement.executeUpdate();
          }
          Future<Integer> deleted =
              executor.submit(
                  () ->
                      contendedStore.deleteTerminalJobs(
                          "retention-race",
                          AsyncJobStatus.FAILED,
                          Instant.EPOCH.plusSeconds(1),
                          1));
          assertThat(transitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
          assertThrows(TimeoutException.class, () -> deleted.get(100, TimeUnit.MILLISECONDS));
          connection.commit();
          assertThat(deleted.get(5, TimeUnit.SECONDS))
              .as(
                  "%s retention must recheck %s after its lock wait",
                  dialect, replay ? "replayed status" : "updated cutoff")
              .isZero();
        }
        assertThat(store.getByIds(List.of(id))).hasSize(1);
        new JdbcTemplate(dataSource)
            .update("DELETE FROM async_job_queue WHERE id = ?", Long.parseLong(id.value()));
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void assertLeaseTransitionsRejectExpiryDuringLockWait(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(container, migrationPath);
    AsyncJobStore store = jdbcStore(dataSource, dialect);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      for (String transition : List.of("heartbeat", "done", "failed", "requeue", "requeueAfter")) {
        String queueName = "lock-" + transition;
        AsyncJobId id = store.enqueueNow(queueName, "{}");
        AsyncJobRecord claimed =
            store.claimNextJobs(queueName, 1, "worker", Duration.ofSeconds(2)).get(0);
        CountDownLatch transitionStarted = new CountDownLatch(1);
        AsyncJobStore contendedStore =
            new JdbcAsyncJobStore(
                new LockWaitObservingJdbcTemplate(dataSource, transitionStarted),
                dialect,
                new DataSourceTransactionManager(dataSource));
        try (Connection connection = dataSource.getConnection()) {
          connection.setAutoCommit(false);
          try (var statement =
              connection.prepareStatement(
                  "SELECT id FROM async_job_queue WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, Long.parseLong(id.value()));
            statement.executeQuery().close();
          }
          Future<Boolean> updated =
              executor.submit(
                  () ->
                      switch (transition) {
                        case "heartbeat" ->
                            contendedStore.heartbeat(
                                queueName,
                                id,
                                "worker",
                                claimed.leaseToken(),
                                Duration.ofSeconds(30));
                        case "done" ->
                            contendedStore.markDone(
                                queueName, id, "worker", claimed.leaseToken(), null);
                        case "failed" ->
                            contendedStore.markFailed(
                                queueName, id, "worker", claimed.leaseToken(), null, "failure");
                        case "requeue" ->
                            contendedStore.requeue(
                                queueName,
                                id,
                                "worker",
                                claimed.leaseToken(),
                                Instant.now(),
                                null,
                                "retry");
                        default ->
                            contendedStore.requeueAfter(
                                queueName,
                                id,
                                "worker",
                                claimed.leaseToken(),
                                Duration.ofSeconds(1),
                                null,
                                "retry");
                      });
          assertThat(transitionStarted.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(leaseIsLive(connection, id, dialect)).isTrue();
          assertThrows(TimeoutException.class, () -> updated.get(100, TimeUnit.MILLISECONDS));
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (leaseIsLive(connection, id, dialect) && System.nanoTime() < deadline) {
            Thread.sleep(25);
          }
          assertThat(leaseIsLive(connection, id, dialect)).isFalse();
          connection.commit();
          assertThat(updated.get(5, TimeUnit.SECONDS))
              .as(
                  "%s %s must reject a lease that expired while waiting for its row lock",
                  dialect, transition)
              .isFalse();
        }
        AsyncJobRecord unchanged = store.getByIds(List.of(id)).get(0);
        assertThat(unchanged.status()).isEqualTo(AsyncJobStatus.RUNNING);
        assertThat(unchanged.leaseToken()).isEqualTo(claimed.leaseToken());
        assertThat(unchanged.leaseUntil()).isEqualTo(claimed.leaseUntil());
      }
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private boolean leaseIsLive(
      Connection connection, AsyncJobId id, AsyncJobQueueJdbcDialect dialect) throws Exception {
    try (var statement =
        connection.prepareStatement(
            "SELECT lease_until > ("
                + dialect.currentTimestampSql()
                + ") FROM async_job_queue WHERE id = ?")) {
      statement.setLong(1, Long.parseLong(id.value()));
      try (var resultSet = statement.executeQuery()) {
        assertThat(resultSet.next()).isTrue();
        return resultSet.getBoolean(1);
      }
    }
  }

  private static class LockWaitObservingJdbcTemplate extends NamedParameterJdbcTemplate {
    private final CountDownLatch transitionStarted;

    LockWaitObservingJdbcTemplate(DataSource dataSource, CountDownLatch transitionStarted) {
      super(dataSource);
      this.transitionStarted = transitionStarted;
    }

    @Override
    public int update(String sql, SqlParameterSource params) {
      transitionStarted.countDown();
      return super.update(sql, params);
    }

    @Override
    public <T> List<T> query(String sql, SqlParameterSource params, RowMapper<T> rowMapper) {
      if (sql.contains("FOR UPDATE")) {
        transitionStarted.countDown();
      }
      return super.query(sql, params, rowMapper);
    }
  }

  @Test
  public void postgresqlWakeupProviderDeliversRealNotifications() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgresContainer()) {
      container.start();
      DataSource dataSource = dataSource(container);
      SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
      AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
      org.mockito.Mockito.when(coordinator.hasEnabledConsumers()).thenReturn(true);
      AsyncJobQueueProperties.WakeupSettings wakeupSettings =
          new AsyncJobQueueProperties.WakeupSettings();
      wakeupSettings.setPostgresListenTimeoutMs(100);
      wakeupSettings.setReconnectDelayMs(10);
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          new JdbcPostgresAsyncJobQueueWakeupListener(
              dataSource, wakeupSettings, coordinator, meterRegistry);
      JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
          new JdbcPostgresAsyncJobQueueWakeupNotifier(
              dataSource, wakeupSettings.getPostgresChannel(), meterRegistry);

      try {
        listener.start();
        waitForPostgresWakeupListenerConnected(meterRegistry);

        notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42"));

        org.mockito.Mockito.verify(coordinator, org.mockito.Mockito.timeout(5_000))
            .triggerPollNow("assetlocalize");
        assertThat(postgresWakeupNotifyCount(meterRegistry, "assetlocalize", "succeeded"))
            .isEqualTo(1);
        assertThat(postgresWakeupReceivedCount(meterRegistry, "assetlocalize", "triggered"))
            .isEqualTo(1);
      } finally {
        listener.stop();
        meterRegistry.close();
      }
    }
  }

  @Test
  public void runtimePerformanceSmokeRunsAgainstRealDatabases() throws Exception {
    assumeContainerTestsEnabled();
    assumePerformanceTestsEnabled();

    try (PostgreSQLContainer<?> container = postgresContainer()) {
      runRuntimePerformanceSmoke(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
    try (MySQLContainer<?> container = mysqlContainer()) {
      runRuntimePerformanceSmoke(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  private void assumeContainerTestsEnabled() {
    assumeTrue(
        "Set -D" + ENABLE_PROPERTY + "=true to run Docker-backed queue integration tests",
        Boolean.getBoolean(ENABLE_PROPERTY));
  }

  @Test
  public void mysqlFailureDiagnosticsRemainPersistable() throws Exception {
    assumeContainerTestsEnabled();
    try (MySQLContainer<?> container = mysqlContainer()) {
      assertFailureDiagnosticsRemainPersistable(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void postgresqlFailureDiagnosticsRemainPersistable() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgresContainer()) {
      assertFailureDiagnosticsRemainPersistable(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void mysqlPayloadWritesRejectNonPortableTextWithoutMutation() throws Exception {
    assumeContainerTestsEnabled();
    try (MySQLContainer<?> container = mysqlContainer()) {
      assertPayloadWritesRejectNonPortableText(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void postgresqlPayloadWritesRejectNonPortableTextWithoutMutation() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgresContainer()) {
      assertPayloadWritesRejectNonPortableText(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
  }

  private void assertPayloadWritesRejectNonPortableText(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(container, migrationPath);
    AsyncJobStore store = jdbcStore(dataSource, dialect);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    SoftAssertions rejectedWrites = new SoftAssertions();
    for (String payload : List.of("bad\0input", "bad\uD800input", "bad\uDC00input")) {
      rejectedWrites
          .assertThatThrownBy(() -> store.enqueueNow("portable-payload", payload))
          .isInstanceOf(IllegalArgumentException.class);
      rejectedWrites
          .assertThatThrownBy(() -> store.enqueue("portable-payload", payload, Instant.now()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    rejectedWrites
        .assertThat(
            jdbc.queryForList("SELECT job_data FROM async_job_queue ORDER BY id", String.class))
        .as("unsupported payload writes must not persist or silently replace characters")
        .isEmpty();
    rejectedWrites.assertAll();

    String portable = "\t\n\r\\u0000-\u00e9-e\u0301-\uD83D\uDE80";
    for (String payload : List.of("", portable, "x".repeat(999_998) + "\uD83D\uDE80")) {
      AsyncJobId id = store.enqueueNow("payload-roundtrip", payload);
      AsyncJobRecord claimed =
          store.claimNextJobs("payload-roundtrip", 1, "worker", Duration.ofMinutes(2)).getFirst();
      assertThat(claimed.jobData()).isEqualTo(payload);
      assertThat(store.markDone("payload-roundtrip", id, "worker", claimed.leaseToken(), null))
          .isTrue();
      assertThat(rawPayload(jdbc, id)).isEqualTo(payload);
    }

    AsyncJobId updating = store.enqueueNow("payload-update", "original");
    AsyncJobRecord running =
        store.claimNextJobs("payload-update", 1, "worker", Duration.ofMinutes(2)).getFirst();
    AsyncJobId replaying = store.enqueueNow("payload-replay", "original");
    AsyncJobRecord replayClaim =
        store.claimNextJobs("payload-replay", 1, "worker", Duration.ofMinutes(2)).getFirst();
    assertThat(
            store.markFailed(
                "payload-replay", replaying, "worker", replayClaim.leaseToken(), null, "failure"))
        .isTrue();
    AsyncJobRecord failed = store.getByIds(List.of(replaying)).getFirst();
    for (String payload : List.of("bad\0input", "bad\uD800input", "bad\uDC00input")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.markDone("payload-update", updating, "worker", running.leaseToken(), payload));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.markFailed(
                  "payload-update", updating, "worker", running.leaseToken(), payload, "failure"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.requeue(
                  "payload-update",
                  updating,
                  "worker",
                  running.leaseToken(),
                  Instant.now(),
                  payload,
                  null));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              store.requeueAfter(
                  "payload-update",
                  updating,
                  "worker",
                  running.leaseToken(),
                  Duration.ZERO,
                  payload,
                  null));
      assertThat(store.getByIds(List.of(updating)).getFirst()).isEqualTo(running);
      assertThrows(
          IllegalArgumentException.class,
          () -> store.requeueFailed("payload-replay", replaying, Instant.now(), payload));
      assertThrows(
          IllegalArgumentException.class,
          () -> store.requeueFailedNow("payload-replay", replaying, payload));
      assertThat(store.getByIds(List.of(replaying)).getFirst()).isEqualTo(failed);
    }
    assertThat(store.markDone("payload-update", updating, "worker", running.leaseToken(), portable))
        .isTrue();
    assertThat(store.requeueFailedNow("payload-replay", replaying, portable)).isTrue();
    assertThat(rawPayload(jdbc, updating)).isEqualTo(portable);
    assertThat(rawPayload(jdbc, replaying)).isEqualTo(portable);

    if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
      assertLegacyMysqlPayloadRemainsUsable(store, jdbc);
    }
  }

  private String rawPayload(JdbcTemplate jdbc, AsyncJobId id) {
    return jdbc.queryForObject(
        "SELECT job_data FROM async_job_queue WHERE id = ?",
        String.class,
        Long.parseLong(id.value()));
  }

  private void assertLegacyMysqlPayloadRemainsUsable(AsyncJobStore store, JdbcTemplate jdbc) {
    String legacy = "old\0input";
    String queue = "legacy-payload";
    AsyncJobId id = store.enqueueNow(queue, "original");
    jdbc.update(
        "UPDATE async_job_queue SET job_data = ? WHERE id = ?", legacy, Long.parseLong(id.value()));
    assertThat(store.getByIds(List.of(id)).getFirst().jobData()).isEqualTo(legacy);
    assertThat(store.findByStatus(queue, AsyncJobStatus.QUEUED, 10).getFirst().jobData())
        .isEqualTo(legacy);
    AsyncJobId healthy = store.enqueueNow(queue, "healthy");
    List<AsyncJobRecord> batch = store.claimNextJobs(queue, 2, "worker", Duration.ofMinutes(2));
    assertThat(batch).extracting(AsyncJobRecord::id).containsExactly(id, healthy);
    assertThat(store.markDone(queue, healthy, "worker", batch.get(1).leaseToken(), null)).isTrue();
    jdbc.update(
        "UPDATE async_job_queue SET lease_until = '2001-01-01 00:00:00' WHERE id = ?",
        Long.parseLong(id.value()));
    AsyncJobRecord reclaimed =
        store.claimNextJobs(queue, 1, "worker", Duration.ofMinutes(2)).getFirst();
    assertThat(reclaimed.leaseReclaimed()).isTrue();
    assertThat(reclaimed.jobData()).isEqualTo(legacy);
    assertThat(
            store.requeueAfter(
                queue, id, "worker", reclaimed.leaseToken(), Duration.ZERO, null, null))
        .isTrue();
    AsyncJobRecord retry =
        store.claimNextJobs(queue, 1, "worker", Duration.ofMinutes(2)).getFirst();
    assertThat(store.markFailed(queue, id, "worker", retry.leaseToken(), null, "failure")).isTrue();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      AsyncJobQueueInspectionService inspection =
          new AsyncJobQueueInspectionService(store, mock(AsyncJobQueueCoordinator.class), registry);
      assertThat(inspection.requeueFailedJob(queue, id.value(), null).jobData()).isEqualTo(legacy);
    } finally {
      registry.close();
    }
    AsyncJobRecord replay =
        store.claimNextJobs(queue, 1, "worker", Duration.ofMinutes(2)).getFirst();
    assertThat(store.markDone(queue, id, "worker", replay.leaseToken(), null)).isTrue();
    assertThat(store.getByIds(List.of(id)).getFirst().status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(rawPayload(jdbc, id)).isEqualTo(legacy);
  }

  private void assertFailureDiagnosticsRemainPersistable(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(container, migrationPath);
    AsyncJobStore store = jdbcStore(dataSource, dialect);
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("bad\0input", "bad\\u0000input");
    cases.put("bad-\uD800-x-\uDC00", "bad-\\ud800-x-\\udc00");
    cases.put("x".repeat(3_999) + "\uD83D\uDE80tail", "x".repeat(3_999));
    cases.put("x".repeat(3_998) + "\uD83D\uDE80tail", "x".repeat(3_998) + "\uD83D\uDE80");
    String queueName = "portable-diagnostics";
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    for (Map.Entry<String, String> entry : cases.entrySet()) {
      AsyncJobId id = store.enqueueNow(queueName, "{}");
      AsyncJobRecord first =
          store.claimNextJobs(queueName, 1, "worker", Duration.ofSeconds(30)).getFirst();
      assertThat(
              store.requeueAfter(
                  queueName, id, "worker", first.leaseToken(), Duration.ZERO, null, entry.getKey()))
          .isTrue();
      assertThat(store.getByIds(List.of(id)).getFirst().lastError()).isEqualTo(entry.getValue());
      assertThat(
              jdbc.queryForObject(
                  "SELECT last_error FROM async_job_queue WHERE id = ?",
                  String.class,
                  Long.parseLong(id.value())))
          .isEqualTo(entry.getValue());
      AsyncJobRecord second =
          store.claimNextJobs(queueName, 1, "worker", Duration.ofSeconds(30)).getFirst();
      assertThat(
              store.markFailed(queueName, id, "worker", second.leaseToken(), null, entry.getKey()))
          .isTrue();
      AsyncJobRecord failed = store.getByIds(List.of(id)).getFirst();
      assertThat(failed.status()).isEqualTo(AsyncJobStatus.FAILED);
      assertThat(failed.attemptCount()).isEqualTo(2);
      assertThat(failed.lastError()).isEqualTo(entry.getValue());
      assertThat(
              jdbc.queryForObject(
                  "SELECT last_error FROM async_job_queue WHERE id = ?",
                  String.class,
                  Long.parseLong(id.value())))
          .isEqualTo(entry.getValue());
    }
    assertRuntimeDiagnosticFailureRespectsAttemptBudget(store);
  }

  private void assertRuntimeDiagnosticFailureRespectsAttemptBudget(AsyncJobStore store)
      throws Exception {
    String queueName = "runtime-portable-diagnostics";
    String rawError = "bad\0input-\uD800-x-\uDC00-\uD83D\uDE80";
    String expectedError =
        "java.lang.IllegalStateException: bad\\u0000input-\\ud800-x-\\udc00-\uD83D\uDE80";
    AsyncJobId poison = store.enqueueNow(queueName, "poison");
    AsyncJobId healthy = store.enqueueNow(queueName, "healthy");
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<AsyncJobRecord> callbackRecord = new AtomicReference<>();
    ThreadPoolTaskExecutor executor = runtimeExecutor(1);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (AutoCloseable cleanup =
        () -> {
          try {
            executor.shutdown();
            assertThat(executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS))
                .isTrue();
          } finally {
            registry.close();
          }
        }) {
      AsyncJobQueueProperties.QueueSettings settings =
          runtimeQueueSettings(1, 1, Duration.ofSeconds(30));
      settings.setMaxAttempts(2);
      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              queueName,
              store,
              settings,
              new AsyncJobHandler() {
                @Override
                public String queueName() {
                  return queueName;
                }

                @Override
                public AsyncJobHandlerResult process(AsyncJobRecord record) {
                  if (record.id().equals(poison)) {
                    attempts.incrementAndGet();
                    throw new IllegalStateException(rawError);
                  }
                  return AsyncJobHandlerResult.done();
                }

                @Override
                public void onJobFailedPermanently(
                    AsyncJobRecord record, Throwable failure, String lastError) {
                  callbackRecord.set(record);
                }
              },
              mock(TaskScheduler.class),
              executor,
              registry,
              "runtime-worker");
      try {
        runtime.pollOnce();
        waitForRuntimeIdle(runtime);
        AsyncJobRecord retried = store.getByIds(List.of(poison)).getFirst();
        assertThat(retried.status()).isEqualTo(AsyncJobStatus.QUEUED);
        assertThat(retried.attemptCount()).isEqualTo(1);
        assertThat(retried.lastError()).isEqualTo(expectedError);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
            && (statusCount(store, queueName, AsyncJobStatus.DONE) != 1
                || statusCount(store, queueName, AsyncJobStatus.FAILED) != 1)) {
          runtime.pollOnce();
          waitForRuntimeIdle(runtime);
          Thread.sleep(2);
        }
        AsyncJobRecord failed = store.getByIds(List.of(poison)).getFirst();
        assertThat(failed.status()).isEqualTo(AsyncJobStatus.FAILED);
        assertThat(failed.attemptCount()).isEqualTo(2);
        assertThat(failed.lastError()).isEqualTo(expectedError);
        assertThat(callbackRecord.get()).isNotNull();
        assertThat(callbackRecord.get().lastError()).isEqualTo(expectedError);
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(store.getByIds(List.of(healthy)).getFirst().status())
            .isEqualTo(AsyncJobStatus.DONE);
        assertThat(store.claimNextJobs(queueName, 1, "observer", Duration.ofSeconds(30))).isEmpty();
      } finally {
        runtime.stop();
      }
    }
  }

  private void assumePerformanceTestsEnabled() {
    assumeTrue(
        "Set -D" + PERF_ENABLE_PROPERTY + "=true to run queue performance smoke tests",
        Boolean.getBoolean(PERF_ENABLE_PROPERTY));
  }

  private void runStoreContract(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(container, migrationPath);
    assertSchemaRejectsInvalidDirectWrites(dataSource);
    AsyncJobStore store = jdbcStore(dataSource, dialect);
    if (dialect == AsyncJobQueueJdbcDialect.MYSQL) {
      assertStoreIgnoresNonPositiveIdsIfSchemaAllows(dataSource, store);
    }

    AsyncJobId id =
        store.enqueue("assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord firstClaim =
        store.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofMillis(50)).get(0);
    assertThat(firstClaim.id()).isEqualTo(id);
    assertThat(firstClaim.attemptCount()).isEqualTo(1);
    assertThat(firstClaim.leaseReclaimed()).isFalse();

    assertThat(
            store.heartbeat("assetlocalize", id, "worker-a", "wrong-token", Duration.ofSeconds(5)))
        .isFalse();

    AsyncJobRecord reclaimed =
        claimEventually(
            store, "assetlocalize", "worker-b", Duration.ofSeconds(5), Duration.ofSeconds(2));
    assertThat(reclaimed.id()).isEqualTo(id);
    assertThat(reclaimed.attemptCount()).isEqualTo(2);
    assertThat(reclaimed.leaseReclaimed()).isTrue();
    assertThat(reclaimed.leaseToken()).isNotEqualTo(firstClaim.leaseToken());

    assertThat(
            store.markDone(
                "assetlocalize",
                id,
                "worker-a",
                firstClaim.leaseToken(),
                "{\"step\":\"stale-done\"}"))
        .isFalse();
    assertThat(
            store.requeue(
                "assetlocalize",
                id,
                "worker-a",
                firstClaim.leaseToken(),
                Instant.now().minusSeconds(1),
                "{\"step\":\"stale-requeue\"}",
                "stale requeue"))
        .isFalse();
    assertThat(
            store.markFailed(
                "assetlocalize", id, "worker-a", firstClaim.leaseToken(), null, "stale failure"))
        .isFalse();
    AsyncJobRecord stillLeasedToReclaimer = store.getByIds(List.of(id)).get(0);
    assertThat(stillLeasedToReclaimer.status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(stillLeasedToReclaimer.workerId()).isEqualTo("worker-b");
    assertThat(stillLeasedToReclaimer.leaseToken()).isEqualTo(reclaimed.leaseToken());
    assertThat(stillLeasedToReclaimer.attemptCount()).isEqualTo(2);

    assertThat(
            store.markFailed(
                "assetlocalize",
                id,
                "worker-b",
                reclaimed.leaseToken(),
                null,
                "operator-visible failure"))
        .isTrue();
    assertThat(store.findByStatus("assetlocalize", AsyncJobStatus.FAILED, 10))
        .extracting(AsyncJobRecord::id)
        .containsExactly(id);

    assertThat(
            store.requeueFailed(
                "assetlocalize",
                id,
                Instant.now().minusSeconds(1),
                "{\"step\":\"operator-fixed\"}"))
        .isTrue();
    AsyncJobRecord replayClaim =
        store.claimNextJobs("assetlocalize", 1, "worker-c", Duration.ofSeconds(5)).get(0);
    assertThat(replayClaim.attemptCount()).isEqualTo(1);
    assertThat(replayClaim.jobData()).isEqualTo("{\"step\":\"operator-fixed\"}");
    assertThat(replayClaim.lastError()).isEqualTo("operator-visible failure");

    assertThat(
            store.markDone(
                "assetlocalize", id, "worker-c", replayClaim.leaseToken(), "{\"step\":\"done\"}"))
        .isTrue();
    AsyncJobRecord done = store.getByIds(List.of(id)).get(0);
    assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(done.lastError()).isNull();
    assertThat(done.workerId()).isNull();
    assertThat(done.leaseToken()).isNull();
    assertThat(done.leaseUntil()).isNull();
    assertThat(
            store.deleteTerminalJobs(
                "assetlocalize", AsyncJobStatus.DONE, Instant.now().plusSeconds(1), 10))
        .isEqualTo(1);
    assertThat(store.getByIds(List.of(id))).isEmpty();

    runScheduledAvailabilityContract(store);
    runRelativeRetryAndImmediateReplayContract(store);
    runConcurrentClaimContract(store);
    runRuntimeDrainContract(store);
    runRuntimeLeaseReclaimFencingContract(store);
    runRuntimeContentionContract(store);
    AsyncJobQueueDrainIntegrationTest.assertProducerOnlyNodeCanDrain(store);
    AsyncJobStoreIdentityContract.assertQueueIsolation(store);
    AsyncJobStoreIdentityContract.assertMaintenanceIsolation(store);
    AsyncJobStoreIdentityContract.assertExpiredQueueIsolation(store);
    AsyncJobStoreIdentityContract.assertLeaseOwnerIsolation(store);
  }

  // Bound JDBC handshakes too: the container readiness deadline cannot interrupt a socket read.
  private MySQLContainer<?> mysqlContainer() {
    return new MySQLContainer<>("mysql:8.4")
        .withConnectTimeoutSeconds(10)
        .withUrlParam("connectTimeout", "5000")
        .withUrlParam("socketTimeout", "30000");
  }

  private PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>("postgres:16")
        .withConnectTimeoutSeconds(10)
        .withUrlParam("connectTimeout", "5")
        .withUrlParam("socketTimeout", "30");
  }

  private void runRuntimePerformanceSmoke(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(container, migrationPath);
    AsyncJobStore store = jdbcStore(dataSource, dialect);

    RuntimeContentionResult result =
        runRuntimeContention(
            store,
            "runtime-perf-" + dialect.name().toLowerCase(),
            1_000,
            4,
            4,
            32,
            Duration.ofSeconds(45),
            Duration.ZERO);

    logger.info(
        "Async job queue {} perf smoke drained {} jobs in {} ms at {} jobs/s with {} polls, {} poll"
            + " failures, claim failures by kind {}",
        dialect,
        result.jobCount(),
        result.elapsedMs(),
        result.jobsPerSecond(),
        result.pollCount(),
        result.pollFailureCount(),
        result.claimFailuresByKind());
    assertThat(result.jobsPerSecond()).isGreaterThan(10.0);
  }

  private DataSource dataSource(JdbcDatabaseContainer<?> container) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(container.getDriverClassName());
    dataSource.setUrl(container.getJdbcUrl());
    dataSource.setUsername(container.getUsername());
    dataSource.setPassword(container.getPassword());
    return dataSource;
  }

  private AsyncJobStore jdbcStore(DataSource dataSource, AsyncJobQueueJdbcDialect dialect) {
    return new JdbcAsyncJobStore(
        new NamedParameterJdbcTemplate(dataSource),
        dialect,
        new DataSourceTransactionManager(dataSource));
  }

  private void assertSchemaRejectsInvalidDirectWrites(DataSource dataSource) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    String oversizedPayload = "x".repeat(AsyncJobQueueValidation.JOB_DATA_MAX_LENGTH + 1);
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                """
                INSERT INTO async_job_queue (
                  queue_name,
                  status,
                  available_at,
                  job_data,
                  attempt_count
                ) VALUES (
                  'assetlocalize',
                  'queued',
                  CURRENT_TIMESTAMP,
                  ?,
                  0
                )
                """,
                oversizedPayload));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                """
                INSERT INTO async_job_queue (
                  queue_name,
                  status,
                  available_at,
                  job_data,
                  attempt_count
                ) VALUES (
                  'assetlocalize',
                  'queued',
                  CURRENT_TIMESTAMP,
                  '{}',
                  102
                )
                """));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                """
                INSERT INTO async_job_queue (
                  queue_name,
                  status,
                  available_at,
                  job_data,
                  attempt_count,
                  last_error
                ) VALUES (
                  'assetlocalize',
                  'failed',
                  CURRENT_TIMESTAMP,
                  '{}',
                  1,
                  NULL
                )
                """));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                """
                INSERT INTO async_job_queue (
                  queue_name,
                  status,
                  available_at,
                  job_data,
                  attempt_count,
                  last_error
                ) VALUES (
                  'assetlocalize',
                  'failed',
                  CURRENT_TIMESTAMP,
                  '{}',
                  1,
                  '   '
                )
                """));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbcTemplate.update(
                """
                INSERT INTO async_job_queue (
                  queue_name,
                  status,
                  available_at,
                  job_data,
                  attempt_count,
                  last_error
                ) VALUES (
                  'assetlocalize',
                  'done',
                  CURRENT_TIMESTAMP,
                  '{}',
                  1,
                  'stale failure'
                )
                """));
  }

  private void assertStoreIgnoresNonPositiveIdsIfSchemaAllows(
      DataSource dataSource, AsyncJobStore store) {
    String queueName = "corrupt-negative-id";
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.update(
        """
        INSERT INTO async_job_queue (
          id,
          queue_name,
          status,
          available_at,
          job_data,
          attempt_count
        ) VALUES (
          -1,
          ?,
          'queued',
          CURRENT_TIMESTAMP,
          '{}',
          0
        )
        """,
        queueName);

    assertThat(statusCount(store, queueName, AsyncJobStatus.QUEUED)).isZero();
    assertThat(store.readyStatus(queueName).count()).isZero();
    assertThat(store.findByStatus(queueName, AsyncJobStatus.QUEUED, 10)).isEmpty();
    assertThat(store.claimNextJobs(queueName, 1, "worker-a", Duration.ofSeconds(5))).isEmpty();
  }

  private void runScheduledAvailabilityContract(AsyncJobStore store) {
    String queueName = "scheduled-availability";
    AsyncJobId immediateId = store.enqueueNow(queueName, "{\"step\":\"immediate\"}");
    AsyncJobId futureId =
        store.enqueue(queueName, "{\"step\":\"future\"}", Instant.now().plusSeconds(30));

    List<AsyncJobRecord> claimed =
        store.claimNextJobs(queueName, 10, "worker-a", Duration.ofSeconds(5));
    assertThat(claimed).extracting(AsyncJobRecord::id).containsExactly(immediateId);
    AsyncJobRecord immediate = claimed.get(0);
    assertThat(immediate.jobData()).isEqualTo("{\"step\":\"immediate\"}");
    assertThat(immediate.attemptCount()).isEqualTo(1);

    assertThat(store.markDone(queueName, immediateId, "worker-a", immediate.leaseToken(), null))
        .isTrue();
    assertThat(store.claimNextJobs(queueName, 10, "worker-b", Duration.ofSeconds(5))).isEmpty();
    AsyncJobRecord future = store.getByIds(List.of(futureId)).get(0);
    assertThat(future.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(future.attemptCount()).isZero();
    assertThat(statusCount(store, queueName, AsyncJobStatus.QUEUED)).isEqualTo(1);
  }

  private void runRelativeRetryAndImmediateReplayContract(AsyncJobStore store) throws Exception {
    String queueName = "relative-retry";
    AsyncJobId id = store.enqueue(queueName, "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        store.claimNextJobs(queueName, 1, "worker-a", Duration.ofSeconds(5)).get(0);

    assertThat(
            store.requeueAfter(
                queueName,
                id,
                "worker-a",
                claimed.leaseToken(),
                Duration.ofMillis(500),
                "{\"step\":\"retry\"}",
                "retryable failure"))
        .isTrue();
    AsyncJobRecord requeued = store.getByIds(List.of(id)).get(0);
    assertThat(requeued.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(requeued.workerId()).isNull();
    assertThat(requeued.leaseToken()).isNull();
    assertThat(requeued.leaseUntil()).isNull();
    assertThat(requeued.lastError()).isEqualTo("retryable failure");
    assertThat(requeued.jobData()).isEqualTo("{\"step\":\"retry\"}");
    assertThat(store.claimNextJobs(queueName, 1, "worker-a", Duration.ofSeconds(5))).isEmpty();

    AsyncJobRecord retried =
        claimEventually(store, queueName, "worker-b", Duration.ofSeconds(5), Duration.ofSeconds(3));
    assertThat(retried.id()).isEqualTo(id);
    assertThat(retried.attemptCount()).isEqualTo(2);
    assertThat(retried.lastError()).isEqualTo("retryable failure");
    assertThat(
            store.markFailed(
                queueName,
                id,
                "worker-b",
                retried.leaseToken(),
                "{\"step\":\"failed\"}",
                "terminal failure"))
        .isTrue();

    assertThat(store.requeueFailedNow(queueName, id, "{\"step\":\"operator-fixed\"}")).isTrue();
    AsyncJobRecord replayed =
        store.claimNextJobs(queueName, 1, "worker-c", Duration.ofSeconds(5)).get(0);
    assertThat(replayed.id()).isEqualTo(id);
    assertThat(replayed.attemptCount()).isEqualTo(1);
    assertThat(replayed.lastError()).isEqualTo("terminal failure");
    assertThat(replayed.jobData()).isEqualTo("{\"step\":\"operator-fixed\"}");
    assertThat(
            store.markDone(queueName, id, "worker-c", replayed.leaseToken(), "{\"step\":\"done\"}"))
        .isTrue();
    assertThat(store.getByIds(List.of(id)).get(0).lastError()).isNull();
  }

  private void runConcurrentClaimContract(AsyncJobStore store) throws Exception {
    String queueName = "concurrent";
    int jobCount = 120;
    int workerCount = 8;
    int batchSize = 3;
    for (int i = 0; i < jobCount; i++) {
      store.enqueue(queueName, "{\"id\":" + i + "}", Instant.now().minusSeconds(1));
    }

    Set<AsyncJobId> claimedIds = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executorService = Executors.newFixedThreadPool(workerCount);
    List<Future<Integer>> futures = new ArrayList<>();
    try {
      for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
        String workerId = "worker-" + workerIndex;
        futures.add(
            executorService.submit(
                () -> {
                  start.await();
                  int processed = 0;
                  while (statusCount(store, queueName, AsyncJobStatus.DONE) < jobCount) {
                    List<AsyncJobRecord> claimed =
                        store.claimNextJobs(queueName, batchSize, workerId, Duration.ofSeconds(30));
                    if (claimed.isEmpty()) {
                      Thread.sleep(10);
                      continue;
                    }

                    for (AsyncJobRecord job : claimed) {
                      assertThat(claimedIds.add(job.id()))
                          .as("job should only be claimed once: %s", job.id())
                          .isTrue();
                      assertThat(
                              store.markDone(
                                  queueName,
                                  job.id(),
                                  workerId,
                                  job.leaseToken(),
                                  "{\"done\":" + job.id().value() + "}"))
                          .isTrue();
                      processed++;
                    }
                  }
                  return processed;
                }));
      }

      start.countDown();
      int processed = 0;
      for (Future<Integer> future : futures) {
        processed += future.get(30, TimeUnit.SECONDS);
      }

      assertThat(processed).isEqualTo(jobCount);
      assertThat(claimedIds).hasSize(jobCount);
      assertThat(statusCount(store, queueName, AsyncJobStatus.DONE)).isEqualTo(jobCount);
      assertThat(statusCount(store, queueName, AsyncJobStatus.QUEUED)).isZero();
      assertThat(statusCount(store, queueName, AsyncJobStatus.RUNNING)).isZero();
      assertThat(statusCount(store, queueName, AsyncJobStatus.FAILED)).isZero();
    } finally {
      executorService.shutdownNow();
      assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void runRuntimeDrainContract(AsyncJobStore store) throws Exception {
    String queueName = "runtime-drain";
    int jobCount = 80;
    for (int i = 0; i < jobCount; i++) {
      store.enqueue(queueName, "{\"id\":" + i + "}", Instant.now().minusSeconds(1));
    }

    Set<AsyncJobId> processedJobIds = ConcurrentHashMap.newKeySet();
    AtomicInteger duplicateExecutions = new AtomicInteger();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ThreadPoolTaskExecutor executor = runtimeExecutor(6);
    try {
      AsyncJobQueueProperties.QueueSettings queueSettings =
          runtimeQueueSettings(12, 6, Duration.ofSeconds(30));

      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              queueName,
              store,
              queueSettings,
              new AsyncJobHandler() {
                @Override
                public String queueName() {
                  return queueName;
                }

                @Override
                public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) {
                  if (!processedJobIds.add(asyncJobRecord.id())) {
                    duplicateExecutions.incrementAndGet();
                  }
                  return AsyncJobHandlerResult.done();
                }
              },
              mock(TaskScheduler.class),
              executor,
              meterRegistry,
              "runtime-worker");

      Instant deadline = Instant.now().plusSeconds(15);
      while (Instant.now().isBefore(deadline)
          && (statusCount(store, queueName, AsyncJobStatus.DONE) < jobCount
              || runtime.inFlightCount() > 0)) {
        runtime.pollOnce();
        Thread.sleep(2);
      }

      assertThat(statusCount(store, queueName, AsyncJobStatus.DONE)).isEqualTo(jobCount);
      assertThat(statusCount(store, queueName, AsyncJobStatus.QUEUED)).isZero();
      assertThat(statusCount(store, queueName, AsyncJobStatus.RUNNING)).isZero();
      assertThat(statusCount(store, queueName, AsyncJobStatus.FAILED)).isZero();
      assertThat(processedJobIds).hasSize(jobCount);
      assertThat(duplicateExecutions.get()).isZero();
      assertThat(
              meterRegistry
                  .get("asyncJobQueue.completed")
                  .tag("queueName", queueName)
                  .counter()
                  .count())
          .isEqualTo(jobCount);
      waitForProcessingLatencyCount(meterRegistry, queueName, jobCount);
    } finally {
      executor.shutdown();
      meterRegistry.close();
    }
  }

  private void runRuntimeLeaseReclaimFencingContract(AsyncJobStore store) throws Exception {
    String queueName = "runtime-fence";
    AsyncJobId id = store.enqueue(queueName, "{\"id\":1}", Instant.now().minusSeconds(1));

    CountDownLatch firstAttemptStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstAttempt = new CountDownLatch(1);
    AtomicInteger slowInvocations = new AtomicInteger();
    AtomicInteger reclaimInvocations = new AtomicInteger();
    AtomicBoolean reclaimedLeaseObserved = new AtomicBoolean();
    SimpleMeterRegistry slowMeterRegistry = new SimpleMeterRegistry();
    SimpleMeterRegistry reclaimMeterRegistry = new SimpleMeterRegistry();
    ThreadPoolTaskExecutor slowExecutor = runtimeExecutor(1);
    ThreadPoolTaskExecutor reclaimExecutor = runtimeExecutor(1);
    try {
      AsyncJobQueueRuntime slowRuntime =
          new AsyncJobQueueRuntime(
              queueName,
              store,
              runtimeQueueSettings(1, 1, Duration.ofMillis(75)),
              new AsyncJobHandler() {
                @Override
                public String queueName() {
                  return queueName;
                }

                @Override
                public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord)
                    throws Exception {
                  slowInvocations.incrementAndGet();
                  firstAttemptStarted.countDown();
                  releaseFirstAttempt.await(5, TimeUnit.SECONDS);
                  return AsyncJobHandlerResult.done("{\"stale\":true}");
                }
              },
              mock(TaskScheduler.class),
              slowExecutor,
              slowMeterRegistry,
              "runtime-slow");
      AsyncJobQueueRuntime reclaimRuntime =
          new AsyncJobQueueRuntime(
              queueName,
              store,
              runtimeQueueSettings(1, 1, Duration.ofSeconds(5)),
              new AsyncJobHandler() {
                @Override
                public String queueName() {
                  return queueName;
                }

                @Override
                public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) {
                  reclaimInvocations.incrementAndGet();
                  reclaimedLeaseObserved.set(asyncJobRecord.leaseReclaimed());
                  return AsyncJobHandlerResult.done("{\"reclaimed\":true}");
                }
              },
              mock(TaskScheduler.class),
              reclaimExecutor,
              reclaimMeterRegistry,
              "runtime-reclaimer");

      slowRuntime.pollOnce();

      assertThat(firstAttemptStarted.await(3, TimeUnit.SECONDS)).isTrue();
      AsyncJobRecord firstClaim = store.getByIds(List.of(id)).get(0);
      assertThat(firstClaim.status()).isEqualTo(AsyncJobStatus.RUNNING);
      assertThat(firstClaim.workerId()).isEqualTo("runtime-slow");
      assertThat(firstClaim.attemptCount()).isEqualTo(1);

      pollUntilDone(reclaimRuntime, store, queueName, 1);

      releaseFirstAttempt.countDown();
      waitForRuntimeIdle(slowRuntime);

      AsyncJobRecord completed = store.getByIds(List.of(id)).get(0);
      assertThat(completed.status()).isEqualTo(AsyncJobStatus.DONE);
      assertThat(completed.attemptCount()).isEqualTo(2);
      assertThat(completed.jobData()).isEqualTo("{\"reclaimed\":true}");
      assertThat(completed.workerId()).isNull();
      assertThat(completed.leaseToken()).isNull();
      assertThat(completed.leaseUntil()).isNull();
      assertThat(slowInvocations.get()).isEqualTo(1);
      assertThat(reclaimInvocations.get()).isEqualTo(1);
      assertThat(reclaimedLeaseObserved.get()).isTrue();
      assertThat(counterCount(slowMeterRegistry, "asyncJobQueue.completed", queueName)).isZero();
      assertThat(transitionFailureCount(slowMeterRegistry, queueName, "done")).isEqualTo(1);
      assertThat(
              counterCount(reclaimMeterRegistry, "asyncJobQueue.leaseExpiredReclaimed", queueName))
          .isEqualTo(1);
      assertThat(counterCount(reclaimMeterRegistry, "asyncJobQueue.completed", queueName))
          .isEqualTo(1);
    } finally {
      releaseFirstAttempt.countDown();
      slowExecutor.shutdown();
      reclaimExecutor.shutdown();
      slowMeterRegistry.close();
      reclaimMeterRegistry.close();
    }
  }

  private void runRuntimeContentionContract(AsyncJobStore store) throws Exception {
    RuntimeContentionResult result =
        runRuntimeContention(
            store,
            "runtime-contention",
            240,
            4,
            2,
            4,
            Duration.ofSeconds(20),
            Duration.ofMillis(1));
    assertThat(result.pollCount()).isGreaterThan(0);
  }

  private RuntimeContentionResult runRuntimeContention(
      AsyncJobStore store,
      String queueName,
      int jobCount,
      int runtimeCount,
      int concurrencyPerRuntime,
      int claimBatchSize,
      Duration timeout,
      Duration handlerDelay)
      throws Exception {
    for (int i = 0; i < jobCount; i++) {
      store.enqueue(queueName, "{\"id\":" + i + "}", Instant.now().minusSeconds(1));
    }

    long handlerDelayMs = Math.max(0, handlerDelay.toMillis());
    Set<AsyncJobId> processedJobIds = ConcurrentHashMap.newKeySet();
    AtomicInteger duplicateExecutions = new AtomicInteger();
    AtomicInteger pollFailures = new AtomicInteger();
    ConcurrentHashMap<String, AtomicInteger> processedByWorker = new ConcurrentHashMap<>();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pollExecutor = Executors.newFixedThreadPool(runtimeCount);
    List<ThreadPoolTaskExecutor> runtimeExecutors = new ArrayList<>();
    List<SimpleMeterRegistry> meterRegistries = new ArrayList<>();
    List<AsyncJobQueueRuntime> runtimes = new ArrayList<>();
    List<Future<Integer>> pollFutures = new ArrayList<>();
    try {
      for (int runtimeIndex = 0; runtimeIndex < runtimeCount; runtimeIndex++) {
        String workerId = "runtime-contention-" + runtimeIndex;
        ThreadPoolTaskExecutor runtimeExecutor = runtimeExecutor(concurrencyPerRuntime);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        runtimeExecutors.add(runtimeExecutor);
        meterRegistries.add(meterRegistry);
        runtimes.add(
            new AsyncJobQueueRuntime(
                queueName,
                store,
                runtimeQueueSettings(claimBatchSize, concurrencyPerRuntime, Duration.ofSeconds(30)),
                new AsyncJobHandler() {
                  @Override
                  public String queueName() {
                    return queueName;
                  }

                  @Override
                  public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord)
                      throws Exception {
                    if (!processedJobIds.add(asyncJobRecord.id())) {
                      duplicateExecutions.incrementAndGet();
                    }
                    processedByWorker
                        .computeIfAbsent(workerId, unused -> new AtomicInteger())
                        .incrementAndGet();
                    if (handlerDelayMs > 0) {
                      Thread.sleep(handlerDelayMs);
                    }
                    return AsyncJobHandlerResult.done("{\"worker\":\"" + workerId + "\"}");
                  }
                },
                mock(TaskScheduler.class),
                runtimeExecutor,
                meterRegistry,
                workerId));
      }

      Instant deadline = Instant.now().plus(timeout);
      for (AsyncJobQueueRuntime runtime : runtimes) {
        pollFutures.add(
            pollExecutor.submit(
                () -> {
                  start.await();
                  int polls = 0;
                  while (Instant.now().isBefore(deadline)
                      && (statusCount(store, queueName, AsyncJobStatus.DONE) < jobCount
                          || anyRuntimeInFlight(runtimes))) {
                    try {
                      runtime.pollOnce();
                    } catch (DataAccessException e) {
                      pollFailures.incrementAndGet();
                      Thread.sleep(5);
                    }
                    polls++;
                    Thread.sleep(1);
                  }
                  return polls;
                }));
      }

      long startedAt = System.nanoTime();
      start.countDown();
      int pollCount = 0;
      for (Future<Integer> pollFuture : pollFutures) {
        pollCount += pollFuture.get(timeout.plusSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
      }
      waitForAllRuntimesIdle(runtimes);
      waitForAggregatedProcessingLatencyCount(meterRegistries, queueName, jobCount);
      long elapsedMs = Math.max(1, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());

      assertThat(statusCount(store, queueName, AsyncJobStatus.DONE)).isEqualTo(jobCount);
      assertThat(statusCount(store, queueName, AsyncJobStatus.QUEUED)).isZero();
      assertThat(statusCount(store, queueName, AsyncJobStatus.RUNNING)).isZero();
      assertThat(statusCount(store, queueName, AsyncJobStatus.FAILED)).isZero();
      assertThat(processedJobIds).hasSize(jobCount);
      assertThat(duplicateExecutions.get()).isZero();
      assertThat(processedByWorker.values().stream().mapToInt(AtomicInteger::get).sum())
          .isEqualTo(jobCount);
      assertThat(counterCount(meterRegistries, "asyncJobQueue.completed", queueName))
          .isEqualTo(jobCount);
      Map<String, Double> claimFailuresByKind = claimFailureCounts(meterRegistries, queueName);
      assertThat(claimFailuresByKind.values().stream().mapToDouble(Double::doubleValue).sum())
          .isEqualTo(pollFailures.get());
      assertThat(claimFailuresByKind).doesNotContainKeys("dataAccess", "other");
      return new RuntimeContentionResult(
          jobCount, elapsedMs, pollCount, pollFailures.get(), claimFailuresByKind);
    } finally {
      pollExecutor.shutdownNow();
      assertThat(pollExecutor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      shutdownRuntimeExecutors(runtimeExecutors);
      meterRegistries.forEach(SimpleMeterRegistry::close);
    }
  }

  private void shutdownRuntimeExecutors(List<ThreadPoolTaskExecutor> runtimeExecutors)
      throws InterruptedException {
    for (ThreadPoolTaskExecutor runtimeExecutor : runtimeExecutors) {
      runtimeExecutor.shutdown();
      assertThat(runtimeExecutor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS))
          .isTrue();
    }
  }

  private record RuntimeContentionResult(
      int jobCount,
      long elapsedMs,
      int pollCount,
      int pollFailureCount,
      Map<String, Double> claimFailuresByKind) {
    double jobsPerSecond() {
      return jobCount * 1_000.0 / Math.max(1, elapsedMs);
    }
  }

  private void pollUntilDone(
      AsyncJobQueueRuntime runtime, AsyncJobStore store, String queueName, int expectedDone)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline)
        && (statusCount(store, queueName, AsyncJobStatus.DONE) < expectedDone
            || runtime.inFlightCount() > 0)) {
      runtime.pollOnce();
      Thread.sleep(10);
    }

    assertThat(statusCount(store, queueName, AsyncJobStatus.DONE)).isEqualTo(expectedDone);
    waitForRuntimeIdle(runtime);
  }

  private void waitForRuntimeIdle(AsyncJobQueueRuntime runtime) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline) && runtime.inFlightCount() > 0) {
      Thread.sleep(10);
    }
    assertThat(runtime.inFlightCount()).isZero();
  }

  private void waitForAllRuntimesIdle(List<AsyncJobQueueRuntime> runtimes)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline) && anyRuntimeInFlight(runtimes)) {
      Thread.sleep(10);
    }
    assertThat(anyRuntimeInFlight(runtimes)).isFalse();
  }

  private boolean anyRuntimeInFlight(List<AsyncJobQueueRuntime> runtimes) {
    return runtimes.stream().anyMatch(runtime -> runtime.inFlightCount() > 0);
  }

  private AsyncJobQueueProperties.QueueSettings runtimeQueueSettings(
      int claimBatchSize, int maxConcurrency, Duration leaseDuration) {
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setPollIntervalMs(1);
    queueSettings.setMaxPollIntervalMs(50);
    queueSettings.setClaimBatchSize(claimBatchSize);
    queueSettings.setMaxConcurrency(maxConcurrency);
    queueSettings.setLeaseDurationMs(leaseDuration.toMillis());
    queueSettings.setHeartbeatIntervalMs(0);
    queueSettings.setMaxAttempts(3);
    queueSettings.setPollJitterPercent(0);
    queueSettings.setRetryJitterPercent(0);
    return queueSettings;
  }

  private void waitForProcessingLatencyCount(
      SimpleMeterRegistry meterRegistry, String queueName, int expectedCount)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(3);
    while (Instant.now().isBefore(deadline)) {
      Timer timer =
          meterRegistry
              .find("asyncJobQueue.processing.latency")
              .tag("queueName", queueName)
              .timer();
      if (timer != null && timer.count() == expectedCount) {
        return;
      }
      Thread.sleep(10);
    }

    assertThat(
            meterRegistry
                .get("asyncJobQueue.processing.latency")
                .tag("queueName", queueName)
                .timer()
                .count())
        .isEqualTo(expectedCount);
  }

  private void waitForAggregatedProcessingLatencyCount(
      List<SimpleMeterRegistry> meterRegistries, String queueName, int expectedCount)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      if (processingLatencyCount(meterRegistries, queueName) == expectedCount) {
        return;
      }
      Thread.sleep(10);
    }

    assertThat(processingLatencyCount(meterRegistries, queueName)).isEqualTo(expectedCount);
  }

  private long processingLatencyCount(List<SimpleMeterRegistry> meterRegistries, String queueName) {
    return meterRegistries.stream()
        .mapToLong(
            meterRegistry -> {
              Timer timer =
                  meterRegistry
                      .find("asyncJobQueue.processing.latency")
                      .tag("queueName", queueName)
                      .timer();
              return timer == null ? 0 : timer.count();
            })
        .sum();
  }

  private double counterCount(
      SimpleMeterRegistry meterRegistry, String meterName, String queueName) {
    Counter counter = meterRegistry.find(meterName).tag("queueName", queueName).counter();
    return counter == null ? 0 : counter.count();
  }

  private double counterCount(
      List<SimpleMeterRegistry> meterRegistries, String meterName, String queueName) {
    return meterRegistries.stream()
        .mapToDouble(meterRegistry -> counterCount(meterRegistry, meterName, queueName))
        .sum();
  }

  private Map<String, Double> claimFailureCounts(
      List<SimpleMeterRegistry> meterRegistries, String queueName) {
    Map<String, Double> countsByKind = new LinkedHashMap<>();
    for (String failureKind :
        List.of("deadlock", "serialization", "lock", "timeout", "dataAccess", "other")) {
      double count =
          meterRegistries.stream()
              .mapToDouble(
                  meterRegistry -> {
                    Counter counter =
                        meterRegistry
                            .find("asyncJobQueue.claim.failed")
                            .tag("queueName", queueName)
                            .tag("failure", failureKind)
                            .counter();
                    return counter == null ? 0 : counter.count();
                  })
              .sum();
      if (count > 0) {
        countsByKind.put(failureKind, count);
      }
    }
    return countsByKind;
  }

  private double transitionFailureCount(
      SimpleMeterRegistry meterRegistry, String queueName, String transition) {
    Counter counter =
        meterRegistry
            .find("asyncJobQueue.transition.failed")
            .tag("queueName", queueName)
            .tag("transition", transition)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private void waitForPostgresWakeupListenerConnected(SimpleMeterRegistry meterRegistry)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      if (postgresWakeupListenCount(meterRegistry, "connected") > 0) {
        return;
      }
      Thread.sleep(10);
    }

    assertThat(postgresWakeupListenCount(meterRegistry, "connected")).isGreaterThan(0);
  }

  private double postgresWakeupListenCount(SimpleMeterRegistry meterRegistry, String result) {
    Counter counter =
        meterRegistry
            .find("asyncJobQueue.wakeup.listen")
            .tag("provider", "postgres")
            .tag("result", result)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private double postgresWakeupNotifyCount(
      SimpleMeterRegistry meterRegistry, String queueName, String result) {
    Counter counter =
        meterRegistry
            .find("asyncJobQueue.wakeup.notify")
            .tag("queueName", queueName)
            .tag("provider", "postgres")
            .tag("result", result)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private double postgresWakeupReceivedCount(
      SimpleMeterRegistry meterRegistry, String queueName, String result) {
    Counter counter =
        meterRegistry
            .find("asyncJobQueue.wakeup.received")
            .tag("queueName", queueName)
            .tag("provider", "postgres")
            .tag("result", result)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private ThreadPoolTaskExecutor runtimeExecutor(int concurrency) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setQueueCapacity(concurrency);
    executor.initialize();
    return executor;
  }

  private long statusCount(AsyncJobStore store, String queueName, AsyncJobStatus status) {
    return store.countByStatus(queueName).stream()
        .filter(count -> count.status() == status)
        .mapToLong(AsyncJobStatusCount::count)
        .findFirst()
        .orElse(0);
  }

  private AsyncJobRecord claimEventually(
      AsyncJobStore store,
      String queueName,
      String workerId,
      Duration leaseDuration,
      Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    List<AsyncJobRecord> claimed = List.of();
    while (Instant.now().isBefore(deadline)) {
      claimed = store.claimNextJobs(queueName, 1, workerId, leaseDuration);
      if (!claimed.isEmpty()) {
        return claimed.get(0);
      }
      Thread.sleep(25);
    }

    assertThat(claimed).as("claim should succeed before timeout").isNotEmpty();
    return claimed.get(0);
  }

  private void runMigration(JdbcDatabaseContainer<?> container, String migrationPath)
      throws Exception {
    // PostgreSQL's ready logs can precede host JDBC readiness. Retry only opening the
    // initial connection; schema SQL and all subsequent queue operations must fail normally.
    try (Connection connection = container.createConnection("")) {
      ScriptUtils.executeSqlScript(
          connection, new EncodedResource(new ClassPathResource(migrationPath)));
    }
  }

  private void assertCoreQueueMigrationShape(String migration) {
    assertThat(migration)
        .contains("CREATE TABLE async_job_queue")
        .contains("queue_name VARCHAR(64) NOT NULL")
        .contains("status VARCHAR(16) NOT NULL")
        .contains("worker_id VARCHAR(128) NULL")
        .contains("lease_token VARCHAR(64) NULL")
        .contains("attempt_count")
        .contains("NOT NULL DEFAULT 0")
        .contains("last_error TEXT NULL")
        .contains("C__ASYNC_JOB_QUEUE__QUEUE_NAME")
        .contains("C__ASYNC_JOB_QUEUE__STATUS")
        .contains("CHECK (status IN ('queued', 'running', 'done', 'failed'))")
        .contains("C__ASYNC_JOB_QUEUE__ATTEMPT_RANGE")
        .contains("CHECK (attempt_count BETWEEN 0 AND 101)")
        .contains("C__ASYNC_JOB_QUEUE__JOB_DATA_LENGTH")
        .contains("CHECK (CHAR_LENGTH(job_data) <= 1000000)")
        .contains("C__ASYNC_JOB_QUEUE__LAST_ERROR_LENGTH")
        .contains("CHECK (last_error IS NULL OR CHAR_LENGTH(last_error) <= 4000)")
        .contains("C__ASYNC_JOB_QUEUE__FAILED_LAST_ERROR")
        .contains(
            "CHECK (status <> 'failed' OR (last_error IS NOT NULL AND TRIM(last_error) <> ''))")
        .contains("C__ASYNC_JOB_QUEUE__DONE_LAST_ERROR")
        .contains("CHECK (status <> 'done' OR last_error IS NULL)")
        .contains("C__ASYNC_JOB_QUEUE__RUNNING_LEASE_OWNER")
        .contains("C__ASYNC_JOB_QUEUE__LEASE_OWNER_NONBLANK")
        .contains("OR (TRIM(worker_id) <> '' AND TRIM(lease_token) <> '')")
        .contains("I__ASYNC_JOB_QUEUE__QNAME_STATUS_AVAILABLE_ID")
        .contains("ON async_job_queue (queue_name, status, available_at, id)")
        .contains("I__ASYNC_JOB_QUEUE__QNAME_STATUS_LEASE_ID")
        .contains("ON async_job_queue (queue_name, status, lease_until, id)")
        .contains("I__ASYNC_JOB_QUEUE__QNAME_STATUS_UPDATED_ID")
        .contains("ON async_job_queue (queue_name, status, updated_date, id)");
  }

  private String resourceText(String path) throws Exception {
    try (InputStream inputStream = new ClassPathResource(path).getInputStream()) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
