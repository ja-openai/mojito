package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

public class JdbcAsyncJobStoreDatabaseIntegrationTest {

  private static final String ENABLE_PROPERTY = "mojito.asyncJobQueue.testcontainers";

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
          "db/migration/postgresql/V92__Async_Job_Queue.sql");
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
    JdbcAsyncJobStore store =
        new JdbcAsyncJobStore(new NamedParameterJdbcTemplate(dataSource), dialect);

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
  }

  private DataSource dataSource(JdbcDatabaseContainer<?> container) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(container.getDriverClassName());
    dataSource.setUrl(container.getJdbcUrl());
    dataSource.setUsername(container.getUsername());
    dataSource.setPassword(container.getPassword());
    return dataSource;
  }

  private AsyncJobRecord claimEventually(
      JdbcAsyncJobStore store,
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
}
