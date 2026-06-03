package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

public class AsyncJobQueueJdbcTransactionConfigurationTest {

  @Test
  public void singleConnectionIsRejectedThroughSupportedTransactionAwareWrappersWithoutBorrowing() {
    SingleConnectionDataSource shared = mock(SingleConnectionDataSource.class);
    DataSource proxy = new TransactionAwareDataSourceProxy(shared);
    for (DataSource jdbcDataSource :
        List.of(shared, proxy, new TransactionAwareDataSourceProxy(proxy))) {
      for (PlatformTransactionManager manager :
          List.of(
              new DataSourceTransactionManager(shared),
              new DataSourceTransactionManager(proxy),
              new JdbcTransactionManager(shared))) {
        assertThatThrownBy(
                () ->
                    new JdbcAsyncJobStore(
                        new NamedParameterJdbcTemplate(jdbcDataSource),
                        AsyncJobQueueJdbcDialect.HSQL,
                        manager))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("independent connections");
      }
    }
    verifyNoInteractions(shared);
  }

  @Test
  public void singleConnectionCannotCommitTheSuspendedCallerTransaction() throws Exception {
    ExplicitTransactionTestConfig config = new ExplicitTransactionTestConfig();
    DataSource independent = config.dataSource();
    JdbcTemplate observer = new JdbcTemplate(independent);
    try (SingleConnectionDataSource shared =
        new SingleConnectionDataSource(independent.getConnection(), true)) {
      config.createAsyncJobQueueSchema(independent);
      observer.execute("CREATE TABLE caller_work (id INTEGER PRIMARY KEY)");
      DataSourceTransactionManager manager = new DataSourceTransactionManager(shared);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                new JdbcTemplate(shared).update("INSERT INTO caller_work VALUES (1)");
                failure.set(
                    catchThrowable(
                        () ->
                            new JdbcAsyncJobStore(
                                    new NamedParameterJdbcTemplate(shared),
                                    AsyncJobQueueJdbcDialect.HSQL,
                                    manager)
                                .enqueueNow("single-connection", "{}")));
                status.setRollbackOnly();
              });

      assertThat(observer.queryForObject("SELECT COUNT(*) FROM caller_work", Integer.class))
          .isZero();
      assertThat(observer.queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class))
          .isZero();
      assertThat(failure.get())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("independent connections");
    } finally {
      observer.execute("SHUTDOWN");
    }
  }

  @Test
  public void mismatchedManagerCannotLeaveAnAutocommittedQueueRowAfterFailure() {
    ExplicitTransactionTestConfig config = new ExplicitTransactionTestConfig();
    DataSource queueDataSource = config.dataSource();
    DataSource unrelatedDataSource = config.dataSource();
    try {
      config.createAsyncJobQueueSchema(queueDataSource);
      Throwable failure =
          catchThrowable(
              () ->
                  new JdbcAsyncJobStore(
                          new DroppingGeneratedIdTemplate(queueDataSource),
                          AsyncJobQueueJdbcDialect.HSQL,
                          new DataSourceTransactionManager(unrelatedDataSource))
                      .enqueueNow("assetlocalize", "{}"));

      assertThat(
              new JdbcTemplate(queueDataSource)
                  .queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class))
          .isZero();
      assertThat(failure)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("same DataSource");
    } finally {
      new JdbcTemplate(queueDataSource).execute("SHUTDOWN");
      new JdbcTemplate(unrelatedDataSource).execute("SHUTDOWN");
    }
  }

  @Test
  public void transactionAwareProxyStillRollsBackTheInsertOnItsTargetDataSource() {
    ExplicitTransactionTestConfig config = new ExplicitTransactionTestConfig();
    DataSource dataSource = config.dataSource();
    try {
      config.createAsyncJobQueueSchema(dataSource);
      DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);
      JdbcAsyncJobStore store =
          new JdbcAsyncJobStore(
              new DroppingGeneratedIdTemplate(proxy),
              AsyncJobQueueJdbcDialect.HSQL,
              new DataSourceTransactionManager(proxy));

      assertThatThrownBy(() -> store.enqueueNow("assetlocalize", "{}"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("generated id");
      assertThat(
              new JdbcTemplate(dataSource)
                  .queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class))
          .isZero();
    } finally {
      new JdbcTemplate(dataSource).execute("SHUTDOWN");
    }
  }

  @Test
  public void unsupportedTransactionManagerFailsBeforeQueueAccess() {
    DataSource dataSource = mock(DataSource.class);
    assertThatThrownBy(
            () ->
                new JdbcAsyncJobStore(
                    new NamedParameterJdbcTemplate(dataSource),
                    AsyncJobQueueJdbcDialect.HSQL,
                    mock(PlatformTransactionManager.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("transaction manager");
    verifyNoInteractions(dataSource);
  }

  @Test
  public void nestedManagerTransactionAwareProxyCannotHideADifferentResourceKey() {
    DataSource dataSource = mock(DataSource.class);
    DataSource nested =
        new TransactionAwareDataSourceProxy(new TransactionAwareDataSourceProxy(dataSource));
    assertThatThrownBy(
            () ->
                new JdbcAsyncJobStore(
                    new NamedParameterJdbcTemplate(dataSource),
                    AsyncJobQueueJdbcDialect.HSQL,
                    new DataSourceTransactionManager(nested)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nested transaction-aware");
    verifyNoInteractions(dataSource);
  }

  @Test
  public void lazyDataSourceResourceIdentityMustBeSharedNotJustItsTarget() {
    DataSource target = mock(DataSource.class);
    DataSource shared = new LazyConnectionDataSourceProxy(target);
    DataSource otherWrapper = new LazyConnectionDataSourceProxy(target);
    DataSourceTransactionManager manager = new DataSourceTransactionManager(shared);
    assertThat(
            new JdbcAsyncJobStore(
                new NamedParameterJdbcTemplate(shared), AsyncJobQueueJdbcDialect.HSQL, manager))
        .isNotNull();
    assertThatThrownBy(
            () ->
                new JdbcAsyncJobStore(
                    new NamedParameterJdbcTemplate(otherWrapper),
                    AsyncJobQueueJdbcDialect.HSQL,
                    manager))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same DataSource");
    verifyNoInteractions(target);
  }

  @Test
  public void supportedJdbcManagersNeedNoStartupConnection() {
    DataSource dataSource = mock(DataSource.class);
    for (PlatformTransactionManager manager :
        List.of(
            new DataSourceTransactionManager(dataSource),
            new JdbcTransactionManager(dataSource),
            new CountingDataSourceTransactionManager(dataSource))) {
      assertThat(
              new JdbcAsyncJobStore(
                  new NamedParameterJdbcTemplate(dataSource),
                  AsyncJobQueueJdbcDialect.HSQL,
                  manager))
          .isNotNull();
    }
    verifyNoInteractions(dataSource);
  }

  @Test
  public void sameDatabaseUrlDoesNotMakeSeparateDataSourcesOneTransactionResource() {
    DriverManagerDataSource first =
        (DriverManagerDataSource) new ExplicitTransactionTestConfig().dataSource();
    DriverManagerDataSource second = new DriverManagerDataSource(first.getUrl(), "sa", "");
    assertThatThrownBy(
            () ->
                new JdbcAsyncJobStore(
                    new NamedParameterJdbcTemplate(first),
                    AsyncJobQueueJdbcDialect.HSQL,
                    new DataSourceTransactionManager(second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same DataSource");
  }

  @Test(timeout = 5000)
  public void cyclicJdbcTransactionAwareProxyIsRejectedWithoutLooping() {
    TransactionAwareDataSourceProxy cycle = new TransactionAwareDataSourceProxy();
    cycle.setTargetDataSource(cycle);
    DataSource target = mock(DataSource.class);
    assertThatThrownBy(
            () ->
                new JdbcAsyncJobStore(
                    new NamedParameterJdbcTemplate(cycle),
                    AsyncJobQueueJdbcDialect.HSQL,
                    new DataSourceTransactionManager(target)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cyclic");
    verifyNoInteractions(target);
  }

  @Test
  public void productionBeanDoesNotUseTheNullManagerSqlTestSeam() {
    assertThatThrownBy(
            () ->
                new AsyncJobQueueConfiguration()
                    .jdbcAsyncJobStore(
                        new NamedParameterJdbcTemplate(mock(DataSource.class)),
                        new AsyncJobQueueProperties(),
                        null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("transactionManager is required");
  }

  private static class DroppingGeneratedIdTemplate extends NamedParameterJdbcTemplate {
    DroppingGeneratedIdTemplate(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    public int update(
        String sql, SqlParameterSource params, KeyHolder keyHolder, String[] keyColumnNames) {
      int updated = super.update(sql, params, keyHolder, keyColumnNames);
      keyHolder.getKeyList().clear();
      return updated;
    }
  }

  @Test
  public void jdbcStoreBeanStartsExplicitTransactionsWithoutTransactionAdvice() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of(
              "l10n.org.async-job-queue.enabled=true",
              "l10n.org.async-job-queue.store=jdbc",
              "l10n.org.async-job-queue.jdbc-dialect=hsql")
          .applyTo(context);
      context.register(AsyncJobQueueConfiguration.class, ExplicitTransactionTestConfig.class);
      context.refresh();

      AsyncJobStore asyncJobStore = context.getBean(AsyncJobStore.class);
      CountingDataSourceTransactionManager transactionManager =
          context.getBean(CountingDataSourceTransactionManager.class);

      AsyncJobId asyncJobId = asyncJobStore.enqueue("assetlocalize", "{}", Instant.EPOCH);
      List<AsyncJobRecord> claimedJobs =
          asyncJobStore.claimNextJobs("assetlocalize", 1, "worker", Duration.ofSeconds(30));
      assertThat(claimedJobs).hasSize(1);
      AsyncJobRecord claimedJob = claimedJobs.get(0);

      assertThat(claimedJob.id()).isEqualTo(asyncJobId);
      assertThat(
              asyncJobStore.markDone(
                  "assetlocalize", asyncJobId, "worker", claimedJob.leaseToken(), "{}"))
          .isTrue();

      assertThat(transactionManager.beginCount.get()).isGreaterThanOrEqualTo(3);
      assertThat(transactionManager.commitCount.get()).isGreaterThanOrEqualTo(3);
      assertThat(transactionManager.rollbackCount.get()).isZero();
      assertThat(transactionManager.requiresNewBeginCount.get())
          .isEqualTo(transactionManager.beginCount.get());
      assertThat(transactionManager.readCommittedBeginCount.get())
          .isEqualTo(transactionManager.beginCount.get());
    }
  }

  @Configuration
  @EnableConfigurationProperties(AsyncJobQueueProperties.class)
  static class ExplicitTransactionTestConfig {

    @Bean
    DataSource dataSource() {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setUrl(
          "jdbc:hsqldb:mem:async_job_queue_tx_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      dataSource.setUsername("sa");
      dataSource.setPassword("");
      return dataSource;
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
      return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    CountingDataSourceTransactionManager transactionManager(DataSource dataSource) {
      return new CountingDataSourceTransactionManager(dataSource);
    }

    @Bean
    Object createAsyncJobQueueSchema(DataSource dataSource) {
      JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
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
            CONSTRAINT C_ASYNC_JOB_QUEUE_ATTEMPT_RANGE
              CHECK (attempt_count BETWEEN 0 AND 101),
            CONSTRAINT C_ASYNC_JOB_QUEUE_JOB_DATA_LENGTH
              CHECK (CHAR_LENGTH(job_data) <= 1000000),
            CONSTRAINT C_ASYNC_JOB_QUEUE_LAST_ERROR_LENGTH
              CHECK (last_error IS NULL OR CHAR_LENGTH(last_error) <= 4000),
            CONSTRAINT C_ASYNC_JOB_QUEUE_FAILED_LAST_ERROR
              CHECK (status <> 'failed' OR (last_error IS NOT NULL AND TRIM(last_error) <> '')),
            CONSTRAINT C_ASYNC_JOB_QUEUE_DONE_LAST_ERROR
              CHECK (status <> 'done' OR last_error IS NULL),
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
          CREATE INDEX I_ASYNC_JOB_QUEUE_QNAME_STATUS_UPDATED_ID
            ON async_job_queue (queue_name, status, updated_date, id)
          """);
      return new Object();
    }
  }

  static class CountingDataSourceTransactionManager extends DataSourceTransactionManager {

    private final AtomicInteger beginCount = new AtomicInteger();
    private final AtomicInteger commitCount = new AtomicInteger();
    private final AtomicInteger rollbackCount = new AtomicInteger();
    private final AtomicInteger requiresNewBeginCount = new AtomicInteger();
    private final AtomicInteger readCommittedBeginCount = new AtomicInteger();

    CountingDataSourceTransactionManager(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      beginCount.incrementAndGet();
      if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
        requiresNewBeginCount.incrementAndGet();
      }
      if (definition.getIsolationLevel() == JdbcAsyncJobStore.TRANSACTION_ISOLATION_LEVEL) {
        readCommittedBeginCount.incrementAndGet();
      }
      super.doBegin(transaction, definition);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commitCount.incrementAndGet();
      super.doCommit(status);
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbackCount.incrementAndGet();
      super.doRollback(status);
    }
  }
}
