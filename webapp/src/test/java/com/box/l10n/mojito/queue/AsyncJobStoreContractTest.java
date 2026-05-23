package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@RunWith(Parameterized.class)
public class AsyncJobStoreContractTest {

  private final StoreHarness storeHarness;
  private final AsyncJobStore asyncJobStore;

  @Parameterized.Parameters(name = "{0}")
  public static Iterable<Object[]> stores() {
    return List.of(
        new Object[] {"in-memory", (StoreHarnessFactory) InMemoryStoreHarness::new},
        new Object[] {"jdbc-hsql", (StoreHarnessFactory) HsqlJdbcStoreHarness::new});
  }

  public AsyncJobStoreContractTest(String storeName, StoreHarnessFactory storeHarnessFactory) {
    this.storeHarness = storeHarnessFactory.create();
    this.asyncJobStore = storeHarness.store();
  }

  @After
  public void tearDown() throws Exception {
    storeHarness.close();
  }

  @Test
  public void enqueueNowCreatesImmediatelyClaimableQueuedJob() {
    AsyncJobId id = asyncJobStore.enqueueNow("assetlocalize", "{\"step\":\"new\"}");

    AsyncJobRecord claimed =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5)).get(0);

    assertThat(claimed.id()).isEqualTo(id);
    assertThat(claimed.queueName()).isEqualTo("assetlocalize");
    assertThat(claimed.status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(claimed.jobData()).isEqualTo("{\"step\":\"new\"}");
    assertThat(claimed.attemptCount()).isEqualTo(1);
  }

  @Test
  public void reclaimedLeaseFencesStaleOwnerTransitions() throws Exception {
    AsyncJobId id =
        asyncJobStore.enqueue("assetlocalize", "{\"v\":1}", Instant.now().minusSeconds(1));
    AsyncJobRecord firstClaim =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofMillis(50)).get(0);

    AsyncJobRecord reclaimed =
        claimEventually("assetlocalize", "worker-b", Duration.ofSeconds(5), Duration.ofSeconds(2));

    assertThat(reclaimed.id()).isEqualTo(id);
    assertThat(reclaimed.workerId()).isEqualTo("worker-b");
    assertThat(reclaimed.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
    assertThat(reclaimed.attemptCount()).isEqualTo(2);
    assertThat(reclaimed.leaseReclaimed()).isTrue();

    assertThat(
            asyncJobStore.heartbeat(
                "assetlocalize", id, "worker-a", firstClaim.leaseToken(), Duration.ofSeconds(5)))
        .isFalse();
    assertThat(
            asyncJobStore.markDone(
                "assetlocalize", id, "worker-a", firstClaim.leaseToken(), "{\"v\":\"stale\"}"))
        .isFalse();
    assertThat(
            asyncJobStore.requeue(
                "assetlocalize",
                id,
                "worker-a",
                firstClaim.leaseToken(),
                Instant.now().minusSeconds(1),
                "{\"v\":\"stale\"}",
                "stale requeue"))
        .isFalse();
    assertThat(
            asyncJobStore.markFailed(
                "assetlocalize", id, "worker-a", firstClaim.leaseToken(), null, "stale failure"))
        .isFalse();

    AsyncJobRecord stillOwnedByReclaimer = asyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(stillOwnedByReclaimer.status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(stillOwnedByReclaimer.workerId()).isEqualTo("worker-b");
    assertThat(stillOwnedByReclaimer.leaseToken()).isEqualTo(reclaimed.leaseToken());

    assertThat(
            asyncJobStore.markDone(
                "assetlocalize", id, "worker-b", reclaimed.leaseToken(), "{\"v\":\"done\"}"))
        .isTrue();
  }

  @Test
  public void expiredLeaseStatusTracksLeaseRecoveryBoundary() throws Exception {
    AsyncJobId id =
        asyncJobStore.enqueue("assetlocalize", "{\"v\":1}", Instant.now().minusSeconds(1));
    AsyncJobRecord firstClaim =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofMillis(50)).get(0);

    assertThat(asyncJobStore.expiredLeaseStatus("assetlocalize").count()).isEqualTo(0);

    AsyncJobExpiredLeaseStatus expiredLeaseStatus =
        expiredLeaseStatusEventually("assetlocalize", Duration.ofSeconds(2));
    assertThat(expiredLeaseStatus.count()).isEqualTo(1);
    assertThat(expiredLeaseStatus.oldestLeaseUntil())
        .isBeforeOrEqualTo(expiredLeaseStatus.observedAt());

    AsyncJobRecord reclaimed =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-b", Duration.ofSeconds(5)).get(0);

    assertThat(reclaimed.id()).isEqualTo(id);
    assertThat(reclaimed.leaseReclaimed()).isTrue();
    assertThat(reclaimed.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
    assertThat(asyncJobStore.expiredLeaseStatus("assetlocalize").count()).isEqualTo(0);
  }

  @Test
  public void requeuePersistsErrorAndCompletionClearsIt() throws Exception {
    AsyncJobId id =
        asyncJobStore.enqueue("assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5)).get(0);

    assertThat(
            asyncJobStore.requeue(
                "assetlocalize",
                id,
                "worker-a",
                claimed.leaseToken(),
                Instant.now().plusMillis(50),
                "{\"step\":\"retry\"}",
                "temporary failure"))
        .isTrue();
    assertThat(asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5)))
        .isEmpty();

    AsyncJobRecord retried =
        claimEventually("assetlocalize", "worker-a", Duration.ofSeconds(5), Duration.ofSeconds(2));
    assertThat(retried.id()).isEqualTo(id);
    assertThat(retried.jobData()).isEqualTo("{\"step\":\"retry\"}");
    assertThat(retried.lastError()).isEqualTo("temporary failure");
    assertThat(retried.attemptCount()).isEqualTo(2);

    assertThat(
            asyncJobStore.markDone(
                "assetlocalize", id, "worker-a", retried.leaseToken(), "{\"step\":\"done\"}"))
        .isTrue();

    AsyncJobRecord done = asyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(done.lastError()).isNull();
    assertThat(done.workerId()).isNull();
    assertThat(done.leaseToken()).isNull();
    assertThat(done.leaseUntil()).isNull();
  }

  @Test
  public void failedReplayResetsAttemptsAndKeepsLastErrorUntilCompletion() {
    AsyncJobId id =
        asyncJobStore.enqueue("assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5)).get(0);

    assertThat(
            asyncJobStore.markFailed(
                "assetlocalize",
                id,
                "worker-a",
                claimed.leaseToken(),
                "{\"step\":\"failed\"}",
                "operator-visible failure"))
        .isTrue();
    assertThat(asyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.FAILED, 10))
        .extracting(AsyncJobRecord::id)
        .containsExactly(id);

    assertThat(
            asyncJobStore.requeueFailed(
                "assetlocalize",
                id,
                Instant.now().minusSeconds(1),
                "{\"step\":\"operator-fixed\"}"))
        .isTrue();

    AsyncJobRecord replayClaim =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-b", Duration.ofSeconds(5)).get(0);
    assertThat(replayClaim.attemptCount()).isEqualTo(1);
    assertThat(replayClaim.jobData()).isEqualTo("{\"step\":\"operator-fixed\"}");
    assertThat(replayClaim.lastError()).isEqualTo("operator-visible failure");

    assertThat(
            asyncJobStore.markDone(
                "assetlocalize", id, "worker-b", replayClaim.leaseToken(), "{\"step\":\"done\"}"))
        .isTrue();
    assertThat(asyncJobStore.getByIds(List.of(id)).get(0).lastError()).isNull();
  }

  @Test
  public void failedReplayIsScopedToQueueName() {
    AsyncJobId id =
        asyncJobStore.enqueue("assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5)).get(0);

    assertThat(
            asyncJobStore.markFailed(
                "assetlocalize",
                id,
                "worker-a",
                claimed.leaseToken(),
                "{\"step\":\"failed\"}",
                "operator-visible failure"))
        .isTrue();

    assertThat(
            asyncJobStore.requeueFailed(
                "otherqueue", id, Instant.now().minusSeconds(1), "{\"step\":\"wrong-queue\"}"))
        .isFalse();

    AsyncJobRecord stillFailed = asyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(stillFailed.queueName()).isEqualTo("assetlocalize");
    assertThat(stillFailed.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(stillFailed.jobData()).isEqualTo("{\"step\":\"failed\"}");
    assertThat(stillFailed.lastError()).isEqualTo("operator-visible failure");
  }

  @Test
  public void terminalFailureRequiresPersistedError() {
    AsyncJobId id =
        asyncJobStore.enqueue("assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5)).get(0);

    assertThrows(
        NullPointerException.class,
        () ->
            asyncJobStore.markFailed(
                "assetlocalize", id, "worker-a", claimed.leaseToken(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            asyncJobStore.markFailed(
                "assetlocalize", id, "worker-a", claimed.leaseToken(), null, "   "));

    AsyncJobRecord stillRunning = asyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(stillRunning.status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(stillRunning.workerId()).isEqualTo("worker-a");
    assertThat(stillRunning.leaseToken()).isEqualTo(claimed.leaseToken());

    assertThat(
            asyncJobStore.markFailed(
                "assetlocalize", id, "worker-a", claimed.leaseToken(), null, "visible failure"))
        .isTrue();
    assertThat(asyncJobStore.getByIds(List.of(id)).get(0).lastError()).isEqualTo("visible failure");
  }

  private AsyncJobRecord claimEventually(
      String queueName, String workerId, Duration leaseDuration, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    List<AsyncJobRecord> claimed = List.of();
    while (Instant.now().isBefore(deadline)) {
      claimed = asyncJobStore.claimNextJobs(queueName, 1, workerId, leaseDuration);
      if (!claimed.isEmpty()) {
        return claimed.get(0);
      }
      Thread.sleep(10);
    }
    assertThat(claimed).as("claim should succeed before timeout").isNotEmpty();
    return claimed.get(0);
  }

  private AsyncJobExpiredLeaseStatus expiredLeaseStatusEventually(
      String queueName, Duration timeout) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    AsyncJobExpiredLeaseStatus expiredLeaseStatus = asyncJobStore.expiredLeaseStatus(queueName);
    while (Instant.now().isBefore(deadline)) {
      expiredLeaseStatus = asyncJobStore.expiredLeaseStatus(queueName);
      if (expiredLeaseStatus.count() > 0) {
        return expiredLeaseStatus;
      }
      Thread.sleep(10);
    }
    assertThat(expiredLeaseStatus.count()).as("expired lease should appear before timeout").isOne();
    return expiredLeaseStatus;
  }

  private interface StoreHarnessFactory {
    StoreHarness create();
  }

  private interface StoreHarness extends AutoCloseable {
    AsyncJobStore store();

    @Override
    default void close() throws Exception {}
  }

  private static class InMemoryStoreHarness implements StoreHarness {
    private final AsyncJobStore store = new InMemoryAsyncJobStore();

    @Override
    public AsyncJobStore store() {
      return store;
    }
  }

  private static class HsqlJdbcStoreHarness implements StoreHarness {
    private final JdbcTemplate jdbcTemplate;
    private final AsyncJobStore store;

    HsqlJdbcStoreHarness() {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.hsqldb.jdbc.JDBCDriver");
      dataSource.setUrl(
          "jdbc:hsqldb:mem:async_job_store_contract_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      dataSource.setUsername("sa");
      dataSource.setPassword("");

      jdbcTemplate = new JdbcTemplate(dataSource);
      createSchema(jdbcTemplate);
      store =
          new JdbcAsyncJobStore(
              new NamedParameterJdbcTemplate(dataSource), AsyncJobQueueJdbcDialect.HSQL);
    }

    @Override
    public AsyncJobStore store() {
      return store;
    }

    @Override
    public void close() {
      jdbcTemplate.execute("DROP TABLE async_job_queue IF EXISTS");
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
      jdbcTemplate.execute(
          """
          CREATE TABLE async_job_queue (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY(START WITH 1) PRIMARY KEY,
            queue_name VARCHAR(64) NOT NULL,
            status VARCHAR(16) NOT NULL,
            available_at TIMESTAMP(6) NOT NULL,
            lease_until TIMESTAMP(6) NULL,
            worker_id VARCHAR(128) NULL,
            lease_token VARCHAR(64) NULL,
            job_data LONGVARCHAR NOT NULL,
            attempt_count INTEGER DEFAULT 0 NOT NULL,
            last_error LONGVARCHAR NULL,
            created_date TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP NOT NULL,
            updated_date TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT C_ASYNC_JOB_QUEUE_ID_POSITIVE
              CHECK (id > 0),
            CONSTRAINT C_ASYNC_JOB_QUEUE_STATUS
              CHECK (status IN ('queued', 'running', 'done', 'failed')),
            CONSTRAINT C_ASYNC_JOB_QUEUE_ATTEMPT_NONNEGATIVE
              CHECK (attempt_count >= 0),
            CONSTRAINT C_ASYNC_JOB_QUEUE_LAST_ERROR_LENGTH
              CHECK (last_error IS NULL OR CHAR_LENGTH(last_error) <= 4000),
            CONSTRAINT C_ASYNC_JOB_QUEUE_FAILED_LAST_ERROR
              CHECK (status <> 'failed' OR (last_error IS NOT NULL AND TRIM(last_error) <> '')),
            CONSTRAINT C_ASYNC_JOB_QUEUE_RUNNING_LEASE_OWNER
              CHECK (
                (status = 'running' AND lease_until IS NOT NULL AND worker_id IS NOT NULL AND lease_token IS NOT NULL)
                OR (status <> 'running' AND lease_until IS NULL AND worker_id IS NULL AND lease_token IS NULL)
              ),
            CONSTRAINT C_ASYNC_JOB_QUEUE_LEASE_OWNER_NONBLANK
              CHECK (
                status <> 'running'
                OR (TRIM(worker_id) <> '' AND TRIM(lease_token) <> '')
              )
          )
          """);
      jdbcTemplate.execute(
          """
          CREATE INDEX I_ASYNC_JOB_QUEUE_QNAME_STATUS_AVAILABLE_ID
            ON async_job_queue (queue_name, status, available_at, id)
          """);
      jdbcTemplate.execute(
          """
          CREATE INDEX I_ASYNC_JOB_QUEUE_QNAME_STATUS_LEASE_ID
            ON async_job_queue (queue_name, status, lease_until, id)
          """);
    }
  }
}
