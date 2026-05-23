package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

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
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

public class JdbcAsyncJobStoreDatabaseIntegrationTest {

  private static final String ENABLE_PROPERTY = "mojito.asyncJobQueue.testcontainers";

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
        .contains("created_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")
        .contains(
            "updated_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)")
        .doesNotContain("C__ASYNC_JOB_QUEUE__ID_POSITIVE")
        .doesNotContain("CHECK (id > 0)");
    assertThat(postgresqlMigration)
        .contains("id BIGSERIAL PRIMARY KEY")
        .contains("available_at TIMESTAMPTZ(6) NOT NULL")
        .contains("lease_until TIMESTAMPTZ(6) NULL")
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

  private void assumeContainerTestsEnabled() {
    assumeTrue(
        "Set -D" + ENABLE_PROPERTY + "=true to run Docker-backed queue integration tests",
        Boolean.getBoolean(ENABLE_PROPERTY));
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
