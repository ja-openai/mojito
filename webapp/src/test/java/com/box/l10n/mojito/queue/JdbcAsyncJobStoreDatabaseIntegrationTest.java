package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

public class JdbcAsyncJobStoreDatabaseIntegrationTest {

  private static final String ENABLE_PROPERTY = "mojito.asyncJobQueue.testcontainers";
  private static final String PERF_ENABLE_PROPERTY = "mojito.asyncJobQueue.perf";
  private static final Logger logger =
      LoggerFactory.getLogger(JdbcAsyncJobStoreDatabaseIntegrationTest.class);

  @Test
  public void postgresqlMigrationIsOutsideDefaultMysqlFlywayLocation() {
    assertThat(
            Files.exists(
                Path.of("src/main/resources/db/migration/postgresql/V92__Async_Job_Queue.sql")))
        .isFalse();
    assertThat(new ClassPathResource("db/postgresql/migration/V92__Async_Job_Queue.sql").exists())
        .isTrue();
  }

  @Test
  public void mysqlAndPostgresqlMigrationsStayStructurallyAligned() throws Exception {
    String mysqlMigration = resourceText("db/migration/V92__Async_Job_Queue.sql");
    String postgresqlMigration = resourceText("db/postgresql/migration/V92__Async_Job_Queue.sql");

    assertCoreQueueMigrationShape(mysqlMigration);
    assertCoreQueueMigrationShape(postgresqlMigration);

    assertThat(mysqlMigration)
        .contains("id BIGINT AUTO_INCREMENT PRIMARY KEY")
        .contains("available_at DATETIME(6) NOT NULL")
        .contains("lease_until DATETIME(6) NULL")
        .contains("job_data LONGTEXT NOT NULL")
        .contains("created_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
        .contains(
            "updated_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)")
        .doesNotContain("C__ASYNC_JOB_QUEUE__ID_POSITIVE")
        .doesNotContain("CHECK (id > 0)");
    assertThat(postgresqlMigration)
        .contains("id BIGSERIAL PRIMARY KEY")
        .contains("available_at TIMESTAMPTZ(6) NOT NULL")
        .contains("lease_until TIMESTAMPTZ(6) NULL")
        .contains("job_data TEXT NOT NULL")
        .contains("created_date TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP")
        .contains("updated_date TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP")
        .contains("C__ASYNC_JOB_QUEUE__ID_POSITIVE")
        .contains("CHECK (id > 0)")
        .doesNotContain("ON UPDATE");
  }

  @Test
  public void mysqlStoreContractRunsAgainstRealDatabase() throws Exception {
    assumeContainerTestsEnabled();
    try (MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")) {
      runStoreContract(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V92__Async_Job_Queue.sql");
    }
  }

  @Test
  public void postgresqlStoreContractRunsAgainstRealDatabase() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16")) {
      runStoreContract(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V92__Async_Job_Queue.sql");
    }
  }

  @Test
  public void runtimePerformanceSmokeRunsAgainstRealDatabases() throws Exception {
    assumeContainerTestsEnabled();
    assumePerformanceTestsEnabled();

    try (PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16")) {
      runRuntimePerformanceSmoke(
          container,
          AsyncJobQueueJdbcDialect.POSTGRESQL,
          "db/postgresql/migration/V92__Async_Job_Queue.sql");
    }
    try (MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")) {
      runRuntimePerformanceSmoke(
          container, AsyncJobQueueJdbcDialect.MYSQL, "db/migration/V92__Async_Job_Queue.sql");
    }
  }

  private void assumeContainerTestsEnabled() {
    assumeTrue(
        "Set -D" + ENABLE_PROPERTY + "=true to run Docker-backed queue integration tests",
        Boolean.getBoolean(ENABLE_PROPERTY));
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
    runMigration(dataSource, migrationPath);
    AsyncJobStore store =
        transactionalStore(
            dataSource, new JdbcAsyncJobStore(new NamedParameterJdbcTemplate(dataSource), dialect));

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

    runRelativeRetryAndImmediateReplayContract(store);
    runConcurrentClaimContract(store);
    runRuntimeDrainContract(store);
    runRuntimeLeaseReclaimFencingContract(store);
    runRuntimeContentionContract(store);
  }

  private void runRuntimePerformanceSmoke(
      JdbcDatabaseContainer<?> container, AsyncJobQueueJdbcDialect dialect, String migrationPath)
      throws Exception {
    container.start();
    DataSource dataSource = dataSource(container);
    runMigration(dataSource, migrationPath);
    AsyncJobStore store =
        transactionalStore(
            dataSource, new JdbcAsyncJobStore(new NamedParameterJdbcTemplate(dataSource), dialect));

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
        "Async job queue {} perf smoke drained {} jobs in {} ms at {} jobs/s with {} polls and {} poll failures",
        dialect,
        result.jobCount(),
        result.elapsedMs(),
        result.jobsPerSecond(),
        result.pollCount(),
        result.pollFailureCount());
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

  private AsyncJobStore transactionalStore(DataSource dataSource, AsyncJobStore delegate) {
    // The production Spring bean applies @Transactional through a proxy. This test constructs the
    // store directly, so wrap each store call to keep SELECT ... FOR UPDATE and its update fenced.
    return new TransactionalAsyncJobStore(
        delegate, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
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
      return new RuntimeContentionResult(jobCount, elapsedMs, pollCount, pollFailures.get());
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
      int jobCount, long elapsedMs, int pollCount, int pollFailureCount) {
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

  private ThreadPoolTaskExecutor runtimeExecutor(int concurrency) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setQueueCapacity(0);
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

  private void runMigration(DataSource dataSource, String migrationPath) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(
          connection, new EncodedResource(new ClassPathResource(migrationPath)));
    }
  }

  private static class TransactionalAsyncJobStore implements AsyncJobStore {

    private final AsyncJobStore delegate;
    private final TransactionTemplate transactionTemplate;

    TransactionalAsyncJobStore(AsyncJobStore delegate, TransactionTemplate transactionTemplate) {
      this.delegate = delegate;
      this.transactionTemplate = transactionTemplate;
    }

    @Override
    public AsyncJobId enqueue(String queueName, String jobData, Instant availableAt) {
      return transactionTemplate.execute(
          status -> delegate.enqueue(queueName, jobData, availableAt));
    }

    @Override
    public List<AsyncJobRecord> claimNextJobs(
        String queueName, int limit, String workerId, Duration leaseDuration) {
      return transactionTemplate.execute(
          status -> delegate.claimNextJobs(queueName, limit, workerId, leaseDuration));
    }

    @Override
    public boolean heartbeat(
        String queueName,
        AsyncJobId id,
        String workerId,
        String leaseToken,
        Duration leaseDuration) {
      return Boolean.TRUE.equals(
          transactionTemplate.execute(
              status -> delegate.heartbeat(queueName, id, workerId, leaseToken, leaseDuration)));
    }

    @Override
    public boolean markDone(
        String queueName, AsyncJobId id, String workerId, String leaseToken, String jobData) {
      return Boolean.TRUE.equals(
          transactionTemplate.execute(
              status -> delegate.markDone(queueName, id, workerId, leaseToken, jobData)));
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
      return Boolean.TRUE.equals(
          transactionTemplate.execute(
              status ->
                  delegate.requeue(
                      queueName, id, workerId, leaseToken, availableAt, jobData, lastError)));
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
      return Boolean.TRUE.equals(
          transactionTemplate.execute(
              status ->
                  delegate.requeueAfter(
                      queueName, id, workerId, leaseToken, delay, jobData, lastError)));
    }

    @Override
    public boolean markFailed(
        String queueName,
        AsyncJobId id,
        String workerId,
        String leaseToken,
        String jobData,
        String lastError) {
      return Boolean.TRUE.equals(
          transactionTemplate.execute(
              status ->
                  delegate.markFailed(queueName, id, workerId, leaseToken, jobData, lastError)));
    }

    @Override
    public List<AsyncJobStatusCount> countByStatus(String queueName) {
      return transactionTemplate.execute(status -> delegate.countByStatus(queueName));
    }

    @Override
    public AsyncJobReadyStatus readyStatus(String queueName) {
      return transactionTemplate.execute(status -> delegate.readyStatus(queueName));
    }

    @Override
    public AsyncJobExpiredLeaseStatus expiredLeaseStatus(String queueName) {
      return transactionTemplate.execute(status -> delegate.expiredLeaseStatus(queueName));
    }

    @Override
    public List<AsyncJobRecord> findByStatus(String queueName, AsyncJobStatus status, int limit) {
      return transactionTemplate.execute(
          transactionStatus -> delegate.findByStatus(queueName, status, limit));
    }

    @Override
    public boolean requeueFailed(
        String queueName, AsyncJobId id, Instant availableAt, String jobData) {
      return Boolean.TRUE.equals(
          transactionTemplate.execute(
              status -> delegate.requeueFailed(queueName, id, availableAt, jobData)));
    }

    @Override
    public boolean requeueFailedNow(String queueName, AsyncJobId id, String jobData) {
      return Boolean.TRUE.equals(
          transactionTemplate.execute(status -> delegate.requeueFailedNow(queueName, id, jobData)));
    }

    @Override
    public int deleteTerminalJobs(
        String queueName, AsyncJobStatus status, Instant updatedBefore, int limit) {
      Integer deleted =
          transactionTemplate.execute(
              transactionStatus ->
                  delegate.deleteTerminalJobs(queueName, status, updatedBefore, limit));
      return deleted == null ? 0 : deleted;
    }

    @Override
    public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
      return transactionTemplate.execute(status -> delegate.getByIds(ids));
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
        .contains("C__ASYNC_JOB_QUEUE__STATUS")
        .contains("CHECK (status IN ('queued', 'running', 'done', 'failed'))")
        .contains("C__ASYNC_JOB_QUEUE__ATTEMPT_NONNEGATIVE")
        .contains("CHECK (attempt_count >= 0)")
        .contains("C__ASYNC_JOB_QUEUE__RUNNING_LEASE_OWNER")
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
