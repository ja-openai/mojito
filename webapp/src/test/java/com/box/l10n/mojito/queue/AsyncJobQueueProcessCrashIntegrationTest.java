package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Forced worker-JVM death against disposable schemas, not database failure or network soak. */
@RunWith(Parameterized.class)
public class AsyncJobQueueProcessCrashIntegrationTest {

  private static final String QUEUE = "process-crash";
  private static final String INPUT = "{\"input\":true}";
  private static final String OUTPUT = "{\"completed\":true}";
  private final AsyncJobQueueJdbcDialect dialect;

  @Parameterized.Parameters(name = "{0}")
  public static List<AsyncJobQueueJdbcDialect> databases() {
    return List.of(AsyncJobQueueJdbcDialect.MYSQL, AsyncJobQueueJdbcDialect.POSTGRESQL);
  }

  public AsyncJobQueueProcessCrashIntegrationTest(AsyncJobQueueJdbcDialect dialect) {
    this.dialect = dialect;
  }

  @Test
  public void killedHandlerRecoversAfterNaturalExpiryWithANewFencedAttempt() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (Fixture fixture = new Fixture(dialect)) {
      AsyncJobId id = fixture.store.enqueueNow(QUEUE, INPUT);
      try (Worker worker = fixture.startWorker("running")) {
        worker.awaitProbe(fixture, "process");
        AsyncJobRecord initial = fixture.row(id);
        assertThat(initial.status()).isEqualTo(AsyncJobStatus.RUNNING);
        assertThat(initial.attemptCount()).isEqualTo(1);
        await(
            "child heartbeat extends the observed lease",
            () -> {
              assertThat(worker.process.isAlive()).as(worker.log.toString()).isTrue();
              return fixture.row(id).leaseUntil().isAfter(initial.leaseUntil());
            });
        worker.kill();
        fixture.awaitTerminatedWriter(id);
        AsyncJobRecord stranded = fixture.row(id);
        assertThat(stranded.leaseToken()).isEqualTo(initial.leaseToken());
        assertThat(stranded.jobData()).isEqualTo(INPUT);
        assertThat(Duration.between(fixture.now(), stranded.leaseUntil()))
            .isGreaterThan(Duration.ofSeconds(2));
        assertThat(fixture.store.claimNextJobs(QUEUE, 1, "early-peer", Duration.ofSeconds(30)))
            .isEmpty();
        assertThat(fixture.row(id)).isEqualTo(stranded);
        assertThat(fixture.probes("process")).isEqualTo(1);
        assertThat(fixture.probes("callback")).isZero();

        // Observe database time; never UPDATE the lease or substitute a synthetic queue clock.
        await(
            "database reaches the killed worker's last lease",
            () -> !fixture.now().isBefore(stranded.leaseUntil()));
        assertThat(fixture.row(id)).isEqualTo(stranded);
        assertThat(fixture.store.expiredLeaseStatus(QUEUE).count()).isEqualTo(1);
        try (Replacement replacement = new Replacement(fixture, stranded.workerId())) {
          replacement.runtime.pollOnce();
          AsyncJobRecord reclaimed = replacement.handler.started.get(10, TimeUnit.SECONDS);
          assertThat(reclaimed.id()).isEqualTo(id);
          assertThat(reclaimed.workerId()).isEqualTo(stranded.workerId());
          assertThat(reclaimed.attemptCount()).isEqualTo(2);
          assertThat(reclaimed.leaseReclaimed()).isTrue();
          assertThat(reclaimed.leaseToken()).isNotEqualTo(stranded.leaseToken());
          assertThat(reclaimed.updatedDate()).isAfterOrEqualTo(stranded.leaseUntil());
          AsyncJobRecord active = fixture.row(id);
          assertThat(active.status()).isEqualTo(AsyncJobStatus.RUNNING);
          String oldToken = stranded.leaseToken();
          String workerId = stranded.workerId();
          assertThat(fixture.store.heartbeat(QUEUE, id, workerId, oldToken, Duration.ofSeconds(30)))
              .isFalse();
          assertThat(fixture.store.markDone(QUEUE, id, workerId, oldToken, "stale")).isFalse();
          assertThat(fixture.store.markFailed(QUEUE, id, workerId, oldToken, "stale", "stale"))
              .isFalse();
          assertThat(
                  fixture.store.requeueAfter(
                      QUEUE, id, workerId, oldToken, Duration.ZERO, "stale", "stale"))
              .isFalse();
          assertThat(fixture.row(id)).isEqualTo(active);
          replacement.handler.release.countDown();
          await(
              "replacement completes and releases capacity",
              () -> replacement.runtime.inFlightCount() == 0);
          AsyncJobRecord done = fixture.row(id);
          assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
          assertThat(done.attemptCount()).isEqualTo(2);
          assertThat(done.jobData()).isEqualTo(OUTPUT);
          assertThat(done.workerId()).isNull();
          assertThat(done.leaseToken()).isNull();
          assertThat(done.leaseUntil()).isNull();
          assertThat(done.lastError()).isNull();
          // Both pre-crash and retried business effects persist: this is not exactly-once work.
          assertThat(fixture.probes("process")).isEqualTo(2);
          assertThat(fixture.probes("callback")).isEqualTo(1);
          replacement.runtime.pollOnce();
          assertThat(fixture.row(id)).isEqualTo(done);
          assertThat(fixture.probes("process")).isEqualTo(2);
        }
      }
    }
  }

  @Test
  public void killedAfterDoneCommitLeavesTerminalRowWithoutReplayingItsCallback() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (Fixture fixture = new Fixture(dialect)) {
      AsyncJobId id = fixture.store.enqueueNow(QUEUE, INPUT);
      try (Worker worker = fixture.startWorker("done")) {
        worker.awaitProbe(fixture, "done-committed");
        worker.kill();
        fixture.awaitTerminatedWriter(id);
        AsyncJobRecord done = fixture.row(id);
        assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
        assertThat(done.attemptCount()).isEqualTo(1);
        assertThat(done.jobData()).isEqualTo(OUTPUT);
        assertThat(done.leaseToken()).isNull();
        assertThat(fixture.probes("process")).isEqualTo(1);
        assertThat(fixture.probes("callback")).isZero();
        try (Replacement replacement = new Replacement(fixture, "replacement")) {
          replacement.runtime.pollOnce();
          replacement.runtime.pollOnce();
          assertThat(replacement.meters.get("asyncJobQueue.poll.empty").counter().count())
              .isEqualTo(2);
          assertThat(replacement.runtime.inFlightCount()).isZero();
          assertThat(replacement.handler.started.isDone()).isFalse();
          assertThat(fixture.row(id)).isEqualTo(done);
          assertThat(fixture.probes("process")).isEqualTo(1);
          assertThat(fixture.probes("callback")).isZero();
        }
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final AsyncJobQueueJdbcDialect dialect;
    private final JdbcDatabaseContainer<?> database;
    private final JdbcTemplate jdbc;
    private final JdbcAsyncJobStore store;

    Fixture(AsyncJobQueueJdbcDialect dialect) throws Exception {
      this.dialect = dialect;
      database =
          dialect == AsyncJobQueueJdbcDialect.MYSQL
              ? new MySQLContainer<>("mysql:8.4")
                  .withConnectTimeoutSeconds(10)
                  .withUrlParam("connectTimeout", "5000")
                  .withUrlParam("socketTimeout", "30000")
              : new PostgreSQLContainer<>("postgres:16")
                  .withConnectTimeoutSeconds(10)
                  .withUrlParam("connectTimeout", "5")
                  .withUrlParam("socketTimeout", "30");
      try {
        database.start();
        String migration =
            dialect == AsyncJobQueueJdbcDialect.MYSQL
                ? "db/migration/V109__Async_Job_Queue.sql"
                : "db/postgresql/migration/V109__Async_Job_Queue.sql";
        try (Connection connection = database.createConnection("")) {
          ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
        }
        DriverManagerDataSource source =
            new DriverManagerDataSource(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
        jdbc = jdbc(source);
        store = store(jdbc, dialect);
        jdbc.execute(
            "CREATE TABLE queue_crash_probe (phase VARCHAR(32) NOT NULL, "
                + "attempt INTEGER NOT NULL, PRIMARY KEY (phase, attempt))");
      } catch (Exception | Error failure) {
        database.close();
        throw failure;
      }
    }

    AsyncJobRecord row(AsyncJobId id) {
      return store.getByIds(List.of(id)).get(0);
    }

    void awaitTerminatedWriter(AsyncJobId id) {
      // JVM exit does not wait for an already-sent COMMIT; drain its row lock before sampling.
      long numericId = Long.parseLong(id.value());
      TransactionTemplate transaction =
          new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
      transaction.setTimeout(10);
      transaction.executeWithoutResult(
          status ->
              assertThat(
                      jdbc.queryForObject(
                          "SELECT id FROM async_job_queue WHERE id = ? FOR UPDATE",
                          Long.class,
                          numericId))
                  .isEqualTo(numericId));
    }

    int probes(String phase) {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM queue_crash_probe WHERE phase = ?", Integer.class, phase);
    }

    Instant now() {
      return jdbc.queryForObject(
          dialect.currentTimestampSql(), (rs, row) -> dialect.readTimestamp(rs, 1));
    }

    Worker startWorker(String mode) throws Exception {
      Path log = Files.createTempFile("queue-crash-" + dialect + "-" + mode + "-", ".log");
      ProcessBuilder builder =
          new ProcessBuilder(
              Path.of(System.getProperty("java.home"), "bin", "java").toString(),
              "-cp",
              System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
              CrashWorker.class.getName(),
              dialect.name(),
              mode);
      builder.environment().put("QUEUE_CRASH_URL", database.getJdbcUrl());
      builder.environment().put("QUEUE_CRASH_USER", database.getUsername());
      builder.environment().put("QUEUE_CRASH_PASSWORD", database.getPassword());
      builder.redirectErrorStream(true).redirectOutput(log.toFile());
      return new Worker(builder.start(), log);
    }

    @Override
    public void close() {
      database.close();
    }
  }

  private record Worker(Process process, Path log) implements AutoCloseable {
    void awaitProbe(Fixture fixture, String phase) throws Exception {
      await(
          "child reaches " + phase + "; child output: " + log,
          () -> {
            assertThat(process.isAlive()).as("child output: %s", log).isTrue();
            return fixture.probes(phase) == 1;
          });
    }

    void kill() throws Exception {
      assertThat(process.isAlive()).isTrue();
      process.destroyForcibly();
      assertTrue(
          "child exits after forced termination: " + log, process.waitFor(10, TimeUnit.SECONDS));
      assertThat(process.exitValue()).isNotZero();
      System.out.println(
          "QUEUE CRASH pid=" + process.pid() + " exit=" + process.exitValue() + " log=" + log);
    }

    @Override
    public void close() throws Exception {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue("child cleanup: " + log, process.waitFor(10, TimeUnit.SECONDS));
      }
    }
  }

  private static final class Replacement implements AutoCloseable {
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ProbeHandler handler;
    private final AsyncJobQueueRuntime runtime;

    Replacement(Fixture fixture, String worker) {
      handler = new ProbeHandler(fixture.jdbc, true);
      scheduler.initialize();
      executor.setCorePoolSize(1);
      executor.setMaxPoolSize(1);
      executor.setQueueCapacity(1);
      executor.initialize();
      AsyncJobQueueProperties.QueueSettings settings = settings();
      settings.setHeartbeatIntervalMs(0);
      settings.setLeaseDurationMs(30_000);
      runtime =
          new AsyncJobQueueRuntime(
              QUEUE, fixture.store, settings, handler, scheduler, executor, meters, worker);
    }

    @Override
    public void close() throws Exception {
      handler.release.countDown();
      try {
        runtime.stop();
      } finally {
        scheduler.shutdown();
        meters.close();
        assertTrue(
            "replacement executor exits",
            executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(
            "replacement scheduler exits",
            scheduler.getScheduledThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  private static final class ProbeHandler implements AsyncJobHandler {
    private final JdbcTemplate jdbc;
    private final boolean gated;
    private final CountDownLatch release = new CountDownLatch(1);
    private final CompletableFuture<AsyncJobRecord> started = new CompletableFuture<>();

    ProbeHandler(JdbcTemplate jdbc, boolean gated) {
      this.jdbc = jdbc;
      this.gated = gated;
    }

    @Override
    public String queueName() {
      return QUEUE;
    }

    @Override
    public AsyncJobHandlerResult process(AsyncJobRecord job) throws Exception {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      probe(jdbc, "process", job.attemptCount());
      started.complete(job);
      if (gated) {
        assertTrue("handler released or child killed", release.await(90, TimeUnit.SECONDS));
      }
      return AsyncJobHandlerResult.done(OUTPUT);
    }

    @Override
    public void onJobDone(AsyncJobRecord job, AsyncJobHandlerResult result) {
      probe(jdbc, "callback", job.attemptCount());
    }
  }

  /** Launched by the test as a separate JVM; no application bootstrap or graceful shutdown hook. */
  public static final class CrashWorker {
    public static void main(String[] args) throws Exception {
      AsyncJobQueueJdbcDialect dialect = AsyncJobQueueJdbcDialect.valueOf(args[0]);
      boolean running = args[1].equals("running");
      DriverManagerDataSource source =
          new DriverManagerDataSource(
              System.getenv("QUEUE_CRASH_URL"),
              System.getenv("QUEUE_CRASH_USER"),
              System.getenv("QUEUE_CRASH_PASSWORD"));
      JdbcTemplate jdbc = jdbc(source);
      JdbcAsyncJobStore store =
          new JdbcAsyncJobStore(
              new NamedParameterJdbcTemplate(jdbc),
              dialect,
              new DataSourceTransactionManager(source)) {
            @Override
            public boolean markDone(
                String queue, AsyncJobId id, String worker, String token, String data) {
              boolean committed = super.markDone(queue, id, worker, token, data);
              if (committed && !running) {
                probe(jdbc, "done-committed", 1);
                try {
                  if (!new CountDownLatch(1).await(90, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Parent did not kill worker after commit");
                  }
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(failure);
                }
              }
              return committed;
            }
          };
      ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
      scheduler.initialize();
      SimpleMeterRegistry meters = new SimpleMeterRegistry();
      AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
      properties.getQueues().put(QUEUE, settings());
      AsyncJobQueueCoordinator coordinator =
          new AsyncJobQueueCoordinator(
              store, properties, List.of(new ProbeHandler(jdbc, running)), scheduler, meters);
      try {
        coordinator.start();
        new CountDownLatch(1).await(90, TimeUnit.SECONDS);
        throw new IllegalStateException("Parent did not kill worker");
      } finally {
        coordinator.stop();
        scheduler.shutdown();
        meters.close();
      }
    }
  }

  private static AsyncJobQueueProperties.QueueSettings settings() {
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setMaxAttempts(3);
    settings.setPollIntervalMs(25);
    settings.setMaxPollIntervalMs(100);
    settings.setPollJitterPercent(0);
    settings.setHeartbeatIntervalMs(250);
    settings.setLeaseDurationMs(8000);
    return settings;
  }

  private static JdbcTemplate jdbc(DriverManagerDataSource source) {
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  private static JdbcAsyncJobStore store(JdbcTemplate jdbc, AsyncJobQueueJdbcDialect dialect) {
    return new JdbcAsyncJobStore(
        new NamedParameterJdbcTemplate(jdbc),
        dialect,
        new DataSourceTransactionManager(jdbc.getDataSource()));
  }

  private static void probe(JdbcTemplate jdbc, String phase, int attempt) {
    assertThat(
            jdbc.update(
                "INSERT INTO queue_crash_probe (phase, attempt) VALUES (?, ?)", phase, attempt))
        .isEqualTo(1);
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
