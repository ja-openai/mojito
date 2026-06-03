package com.box.l10n.mojito.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.Authority;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.entity.security.user.UserLocale;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.orm.jpa.DefaultJpaDialect;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** JPA/JDBC enlistment and runtime fault contracts, not schema upgrades or durable admission. */
@RunWith(Parameterized.class)
public class AsyncJobQueueJpaTransactionIntegrationTest {

  private static final Map<AsyncJobQueueJdbcDialect, Fixture> FIXTURES =
      new EnumMap<>(AsyncJobQueueJdbcDialect.class);

  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> databases() {
    List<Object[]> databases = new ArrayList<>();
    databases.add(new Object[] {AsyncJobQueueJdbcDialect.HSQL});
    if (Boolean.getBoolean("mojito.asyncJobQueue.testcontainers")) {
      databases.add(new Object[] {AsyncJobQueueJdbcDialect.MYSQL});
      databases.add(new Object[] {AsyncJobQueueJdbcDialect.POSTGRESQL});
    }
    return databases;
  }

  @BeforeClass
  public static void openDatabases() throws Exception {
    try {
      for (Object[] parameter : databases()) {
        AsyncJobQueueJdbcDialect dialect = (AsyncJobQueueJdbcDialect) parameter[0];
        Fixture fixture = new Fixture(dialect);
        FIXTURES.put(dialect, fixture);
        fixture.open();
      }
    } catch (Exception | Error failure) {
      try {
        closeDatabases();
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  @AfterClass
  public static void closeDatabases() {
    RuntimeException cleanupFailure = null;
    try {
      for (Fixture fixture : FIXTURES.values()) {
        try {
          fixture.close();
        } catch (RuntimeException failure) {
          if (cleanupFailure == null) {
            cleanupFailure = failure;
          } else {
            cleanupFailure.addSuppressed(failure);
          }
        }
      }
    } finally {
      FIXTURES.clear();
    }
    if (cleanupFailure != null) {
      throw cleanupFailure;
    }
  }

  private final AsyncJobQueueJdbcDialect dialect;
  private Fixture fixture;

  public AsyncJobQueueJpaTransactionIntegrationTest(AsyncJobQueueJdbcDialect dialect) {
    this.dialect = dialect;
  }

  @Before
  public void resetFaultsAndQueue() {
    fixture = FIXTURES.get(dialect);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    fixture.dataSource.fault.set(CommitFault.NONE);
    fixture.jdbc.update("DELETE FROM async_job_queue");
    fixture.dataSource.commits.set(0);
    fixture.dataSource.rollbacks.set(0);
    fixture.dataSource.injectedRollbacks.set(0);
    fixture.dataSource.faults.set(0);
    fixture.dataSource.connections.set(0);
    fixture.inserts.set(0);
    fixture.afterInsert = connection -> {};
  }

  @Test
  public void jpaAndNamedJdbcSharePhysicalConnectionAndRollbackTheRealTask() {
    AtomicReference<Long> taskId = new AtomicReference<>();
    fixture
        .businessTransaction()
        .executeWithoutResult(
            status -> {
              EntityManager entityManager = fixture.entityManager();
              PollableTask task = fixture.persistTask();
              taskId.set(task.getId());
              fixture.assertSharedConnection();
              assertEquals(
                  1,
                  fixture.namedJdbc.update(
                      "UPDATE pollable_task SET message = :message WHERE id = :id",
                      Map.of("message", "written via JDBC", "id", task.getId())));
              entityManager.clear();
              assertEquals(
                  "written via JDBC",
                  entityManager.find(PollableTask.class, task.getId()).getMessage());
              status.setRollbackOnly();
            });

    assertNotNull(taskId.get());
    assertEquals(0, fixture.taskCount(taskId.get()));
    assertEquals(0, fixture.dataSource.commits.get());
    assertEquals(1, fixture.dataSource.rollbacks.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void publicEnqueueCommitsRequiresNewAndRestoresOuterJpaTransactionBeforeRollback() {
    AtomicReference<Long> taskId = new AtomicReference<>();
    AtomicReference<AsyncJobId> jobId = new AtomicReference<>();
    fixture
        .businessTransaction()
        .executeWithoutResult(
            status -> {
              EntityManager outer = fixture.entityManager();
              taskId.set(fixture.persistTask().getId());
              Connection outerConnection = fixture.assertSharedConnection();
              fixture.afterInsert =
                  innerConnection -> {
                    assertNotSame(outer, fixture.entityManager());
                    assertNotSame(outerConnection, innerConnection);
                  };
              jobId.set(fixture.store.enqueueNow("jpa-contract", "{}"));
              assertSame(outer, fixture.entityManager());
              assertSame(outerConnection, fixture.assertSharedConnection());
              status.setRollbackOnly();
            });

    assertEquals(0, fixture.taskCount(taskId.get()));
    assertEquals(1, fixture.queueCount());
    assertEquals(
        Long.valueOf(jobId.get().value()),
        fixture.jdbc.queryForObject("SELECT id FROM async_job_queue", Long.class));
    assertEquals(1, fixture.inserts.get());
    assertEquals(1, fixture.dataSource.commits.get());
    assertEquals(1, fixture.dataSource.rollbacks.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void failureAfterActualQueueInsertRollsBackThroughTheBoundJpaManager() {
    IllegalStateException failure = new IllegalStateException("after actual insert");
    fixture.afterInsert =
        connection -> {
          assertEquals(1, fixture.queueCount());
          throw failure;
        };

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class, () -> fixture.store.enqueueNow("jpa-contract", "{}")));
    assertEquals(1, fixture.inserts.get());
    assertEquals(0, fixture.queueCount());
    assertEquals(0, fixture.dataSource.commits.get());
    assertEquals(1, fixture.dataSource.rollbacks.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void lostCommitAcknowledgementPropagatesEvenThoughTheQueueRowCommitted() {
    fixture.dataSource.failNextCommit(CommitFault.AFTER_COMMIT);

    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> fixture.store.enqueueNow("jpa-contract", "{}"));

    assertInjectedCommitFailure(failure);
    assertEquals(1, fixture.inserts.get());
    assertEquals(1, fixture.dataSource.commits.get());
    assertEquals(1, fixture.queueCount());
    assertEquals(
        "queued", fixture.jdbc.queryForObject("SELECT status FROM async_job_queue", String.class));
    fixture.assertNoBoundResources();
  }

  @Test
  public void lostClaimCommitAcknowledgementRecoversWithANewFencedLease() throws Exception {
    assertUncertainClaim(CommitFault.AFTER_COMMIT, 2);
  }

  @Test
  public void rolledBackClaimConsumesNoAttemptAndRemainsImmediatelyClaimable() throws Exception {
    assertUncertainClaim(CommitFault.ROLLBACK_BEFORE_COMMIT, 1);
  }

  @Test
  public void lostClaimAcknowledgementCanExhaustBudgetWithoutExecutingBusinessWork()
      throws Exception {
    assertUncertainClaim(CommitFault.AFTER_COMMIT, 1);
  }

  private void assertUncertainClaim(CommitFault fault, int maxAttempts) throws Exception {
    String queue = "claim-commit-contract";
    AsyncJobId id = fixture.store.enqueueNow(queue, "{}");
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("claim-commit-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    ThreadPoolExecutor workerPool = executor.getThreadPoolExecutor();
    CountDownLatch handlerEntered = new CountDownLatch(1);
    CountDownLatch releaseHandler = new CountDownLatch(1);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    AsyncJobHandler handler = mock(AsyncJobHandler.class);
    try {
      when(handler.process(any()))
          .thenAnswer(
              invocation -> {
                fixture.assertNoBoundResources();
                handlerEntered.countDown();
                assertTrue(
                    "Release the gated replacement handler",
                    releaseHandler.await(15, TimeUnit.SECONDS));
                return AsyncJobHandlerResult.done("{\"processed\":true}");
              });
      AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
      settings.setMaxConcurrency(1);
      settings.setClaimBatchSize(1);
      settings.setMaxAttempts(maxAttempts);
      settings.setHeartbeatIntervalMs(0);
      settings.setLeaseDurationMs(60_000);
      AsyncJobQueueRuntime runtime =
          new AsyncJobQueueRuntime(
              queue,
              fixture.store,
              settings,
              handler,
              mock(TaskScheduler.class),
              executor,
              meters,
              "claim-worker");
      try {
        fixture.dataSource.failNextCommit(fault);
        RuntimeException failure = assertThrows(RuntimeException.class, runtime::pollOnce);
        assertInjectedCommitFailure(failure);
        fixture.assertNoBoundResources();
        assertEquals(0, runtime.inFlightCount());
        assertEquals(0, workerPool.getCompletedTaskCount());
        verifyNoInteractions(handler);
        assertEquals(
            1,
            meters.get("asyncJobQueue.claim.failed").tags("queueName", queue).counter().count(),
            0);
        assertTrue(
            meters.find("asyncJobQueue.claimed").tags("queueName", queue).counters().isEmpty());
        AsyncJobRecord uncertain = fixture.store.getByIds(List.of(id)).getFirst();
        boolean committed = fault == CommitFault.AFTER_COMMIT;
        assertEquals(
            committed ? AsyncJobStatus.RUNNING : AsyncJobStatus.QUEUED, uncertain.status());
        assertEquals(committed ? 1 : 0, uncertain.attemptCount());
        assertEquals("{}", uncertain.jobData());
        assertEquals(null, uncertain.lastError());
        if (committed) {
          assertNotNull(uncertain.leaseToken());
          assertEquals("claim-worker", uncertain.workerId());
          assertTrue(
              fixture.store.claimNextJobs(queue, 1, "peer", Duration.ofMinutes(1)).isEmpty());
          assertEquals(uncertain, fixture.store.getByIds(List.of(id)).getFirst());
          // This fixture explicitly advances the lease; wall-clock/process-kill tests are separate.
          assertEquals(
              1,
              fixture.jdbc.update(
                  "UPDATE async_job_queue SET lease_until = created_date WHERE id = ?",
                  Long.valueOf(id.value())));
        } else {
          assertEquals(null, uncertain.leaseToken());
          assertEquals(null, uncertain.workerId());
          assertEquals(null, uncertain.leaseUntil());
        }

        boolean exhausted = committed && maxAttempts == 1;
        runtime.pollOnce();
        if (!exhausted) {
          assertTrue(
              "Replacement handler starts after a successful claim",
              handlerEntered.await(15, TimeUnit.SECONDS));
          AsyncJobRecord replacement = fixture.store.getByIds(List.of(id)).getFirst();
          assertEquals(AsyncJobStatus.RUNNING, replacement.status());
          assertEquals(committed ? 2 : 1, replacement.attemptCount());
          if (committed) {
            assertEquals(uncertain.workerId(), replacement.workerId());
            assertFalse(uncertain.leaseToken().equals(replacement.leaseToken()));
            assertFalse(
                fixture.store.heartbeat(
                    queue,
                    id,
                    uncertain.workerId(),
                    uncertain.leaseToken(),
                    Duration.ofMinutes(1)));
            assertFalse(
                fixture.store.markDone(
                    queue, id, uncertain.workerId(), uncertain.leaseToken(), "stale"));
            assertFalse(
                fixture.store.requeueAfter(
                    queue,
                    id,
                    uncertain.workerId(),
                    uncertain.leaseToken(),
                    Duration.ZERO,
                    "stale",
                    "stale"));
            assertFalse(
                fixture.store.markFailed(
                    queue, id, uncertain.workerId(), uncertain.leaseToken(), "stale", "stale"));
            assertEquals(replacement, fixture.store.getByIds(List.of(id)).getFirst());
          }
        }
        releaseHandler.countDown();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (runtime.inFlightCount() != 0 && System.nanoTime() < deadline) {
          Thread.sleep(10);
        }
        assertEquals(0, runtime.inFlightCount());
        AsyncJobRecord terminal = fixture.store.getByIds(List.of(id)).getFirst();
        assertEquals(exhausted ? AsyncJobStatus.FAILED : AsyncJobStatus.DONE, terminal.status());
        assertEquals(committed ? 2 : 1, terminal.attemptCount());
        assertEquals(null, terminal.leaseToken());
        assertEquals(null, terminal.workerId());
        assertEquals(1, fixture.queueCount());
        runtime.pollOnce();
        if (exhausted) {
          assertEquals("{}", terminal.jobData());
          assertTrue(
              terminal.lastError().contains("Attempt budget exhausted before handler invocation"));
          verify(handler, never()).process(any());
          verify(handler, never()).onJobDone(any(), any());
          verify(handler, times(1)).onJobFailedPermanently(any(), any(), any());
          assertEquals(
              1,
              meters
                  .get("asyncJobQueue.attempt.exhausted")
                  .tags("queueName", queue)
                  .counter()
                  .count(),
              0);
        } else {
          assertEquals("{\"processed\":true}", terminal.jobData());
          assertEquals(null, terminal.lastError());
          verify(handler, times(1)).process(any());
          verify(handler, times(1)).onJobDone(any(), any());
          verify(handler, never()).onJobFailedPermanently(any(), any(), any());
        }
        assertTrue(
            meters
                .find("asyncJobQueue.handler.failed")
                .tags("queueName", queue)
                .counters()
                .isEmpty());
        assertEquals(
            1, meters.get("asyncJobQueue.claimed").tags("queueName", queue).counter().count(), 0);
        fixture.assertNoBoundResources();
      } finally {
        releaseHandler.countDown();
        runtime.stop();
      }
    } finally {
      releaseHandler.countDown();
      try {
        executor.shutdown();
        if (!workerPool.isTerminated()) {
          workerPool.shutdownNow();
        }
        assertTrue(workerPool.awaitTermination(10, TimeUnit.SECONDS));
      } finally {
        meters.close();
      }
    }
  }

  @Test
  public void failureBeforeCommitWithRealRollbackLeavesNoQueueRow() {
    fixture.dataSource.failNextCommit(CommitFault.ROLLBACK_BEFORE_COMMIT);

    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> fixture.store.enqueueNow("jpa-contract", "{}"));

    assertInjectedCommitFailure(failure);
    assertEquals(1, fixture.inserts.get());
    assertEquals(0, fixture.dataSource.commits.get());
    assertEquals(1, fixture.dataSource.injectedRollbacks.get());
    assertTrue(fixture.dataSource.rollbacks.get() >= 1);
    assertEquals(0, fixture.queueCount());
    fixture.assertNoBoundResources();
  }

  @Test
  public void matchingJdbcAndManagerCannotOverrideTheJpaFactoryDataSource() {
    CommitFaultDataSource other =
        new CommitFaultDataSource(
            new DriverManagerDataSource("jdbc:hsqldb:mem:unused_" + UUID.randomUUID(), "sa", ""));
    JpaTransactionManager manager = new JpaTransactionManager(fixture.factory);
    manager.afterPropertiesSet();
    manager.setDataSource(other);

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new JdbcAsyncJobStore(new NamedParameterJdbcTemplate(other), dialect, manager));

    assertTrue(failure.getMessage().contains("EntityManagerFactory"));
    assertEquals(0, fixture.dataSource.connections.get());
    assertEquals(0, other.connections.get());
    assertEquals(0, fixture.inserts.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void incompatibleJpaManagersAreRejectedBeforeOpeningAnyConnection() {
    CommitFaultDataSource other =
        new CommitFaultDataSource(
            new DriverManagerDataSource("jdbc:hsqldb:mem:unused_" + UUID.randomUUID(), "sa", ""));
    JpaTransactionManager mismatched = new JpaTransactionManager(fixture.factory);
    mismatched.afterPropertiesSet();
    // Initialization derives the datasource from factory metadata, so change it afterwards.
    mismatched.setDataSource(other);
    JpaTransactionManager unsupportedDialect = new JpaTransactionManager(fixture.factory);
    unsupportedDialect.afterPropertiesSet();
    unsupportedDialect.setJpaDialect(new DefaultJpaDialect());
    JpaTransactionManager missingMetadata =
        new JpaTransactionManager(fixture.factoryBean.getNativeEntityManagerFactory());
    missingMetadata.setJpaDialect(new HibernateJpaDialect());
    missingMetadata.setDataSource(fixture.dataSource);
    missingMetadata.afterPropertiesSet();

    for (JpaTransactionManager manager : List.of(mismatched, unsupportedDialect, missingMetadata)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new JdbcAsyncJobStore(fixture.namedJdbc, dialect, manager));
    }
    assertEquals(0, fixture.dataSource.connections.get());
    assertEquals(0, other.connections.get());
    assertEquals(0, fixture.inserts.get());
    fixture.assertNoBoundResources();
  }

  private void assertInjectedCommitFailure(Throwable failure) {
    assertEquals(1, fixture.dataSource.faults.get());
    assertEquals(CommitFault.NONE, fixture.dataSource.fault.get());
    while (failure != null && failure != fixture.dataSource.failure) {
      failure = failure.getCause();
    }
    assertSame(
        "The real JDBC commit failure must reach the caller", fixture.dataSource.failure, failure);
  }

  private static final class Fixture implements AutoCloseable {
    private final AsyncJobQueueJdbcDialect dialect;
    private final AtomicInteger inserts = new AtomicInteger();
    private JdbcDatabaseContainer<?> container;
    private CommitFaultDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean factoryBean;
    private EntityManagerFactory factory;
    private JpaTransactionManager transactionManager;
    private NamedParameterJdbcTemplate namedJdbc;
    private JdbcTemplate jdbc;
    private JdbcAsyncJobStore store;
    private Consumer<Connection> afterInsert = connection -> {};

    private Fixture(AsyncJobQueueJdbcDialect dialect) {
      this.dialect = dialect;
    }

    private void open() throws Exception {
      DriverManagerDataSource target = new DriverManagerDataSource();
      if (dialect == AsyncJobQueueJdbcDialect.HSQL) {
        target.setUrl("jdbc:hsqldb:mem:jpa_queue_" + UUID.randomUUID() + ";hsqldb.tx=mvcc");
        target.setUsername("sa");
        target.setPassword("");
      } else {
        container =
            dialect == AsyncJobQueueJdbcDialect.MYSQL
                ? new MySQLContainer<>("mysql:8.4")
                    .withConnectTimeoutSeconds(10)
                    .withUrlParam("connectTimeout", "5000")
                    .withUrlParam("socketTimeout", "30000")
                : new PostgreSQLContainer<>("postgres:16")
                    .withConnectTimeoutSeconds(10)
                    .withUrlParam("connectTimeout", "5")
                    .withUrlParam("socketTimeout", "30");
        container.start();
        target.setDriverClassName(container.getDriverClassName());
        target.setUrl(container.getJdbcUrl());
        target.setUsername(container.getUsername());
        target.setPassword(container.getPassword());
        String migration =
            dialect == AsyncJobQueueJdbcDialect.MYSQL
                ? "db/migration/V109__Async_Job_Queue.sql"
                : "db/postgresql/migration/V109__Async_Job_Queue.sql";
        try (Connection connection = container.createConnection("")) {
          ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
        }
      }
      dataSource = new CommitFaultDataSource(target);
      jdbc = new JdbcTemplate(dataSource);
      jdbc.setQueryTimeout(10);
      if (dialect == AsyncJobQueueJdbcDialect.HSQL) {
        jdbc.execute(
            """
            CREATE TABLE async_job_queue (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY(START WITH 1) PRIMARY KEY,
              queue_name VARCHAR(64) NOT NULL,
              status VARCHAR(16) NOT NULL,
              available_at TIMESTAMP(6) NOT NULL,
              lease_until TIMESTAMP(6),
              worker_id VARCHAR(128),
              lease_token VARCHAR(64),
              job_data LONGVARCHAR NOT NULL,
              attempt_count INTEGER DEFAULT 0 NOT NULL,
              last_error LONGVARCHAR,
              created_date TIMESTAMP(6) NOT NULL,
              updated_date TIMESTAMP(6) NOT NULL
            )
            """);
      }

      factoryBean = new LocalContainerEntityManagerFactoryBean();
      factoryBean.setDataSource(dataSource);
      factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factoryBean.setManagedTypes(
          PersistenceManagedTypes.of(
              PollableTask.class.getName(),
              User.class.getName(),
              Authority.class.getName(),
              UserLocale.class.getName(),
              Locale.class.getName()));
      // Create only the managed graph; disposable databases are removed on close, not upgraded.
      factoryBean.setJpaPropertyMap(
          Map.of(
              "hibernate.hbm2ddl.auto",
              "create-only",
              "hibernate.hbm2ddl.halt_on_error",
              "true",
              "hibernate.auto_quote_keyword",
              "true",
              "hibernate.physical_naming_strategy",
              "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
              "hibernate.connection.handling_mode",
              "DELAYED_ACQUISITION_AND_HOLD",
              "jakarta.persistence.validation.mode",
              "none"));
      factoryBean.afterPropertiesSet();
      factory = factoryBean.getObject();
      assertNotNull(factory);
      transactionManager = new JpaTransactionManager(factory);
      transactionManager.setDataSource(dataSource);
      transactionManager.afterPropertiesSet();
      namedJdbc =
          new NamedParameterJdbcTemplate(jdbc) {
            @Override
            public int update(
                String sql, SqlParameterSource parameters, KeyHolder keys, String[] keyColumns) {
              Connection connection = assertSharedConnection();
              int updated = super.update(sql, parameters, keys, keyColumns);
              assertEquals(1, updated);
              inserts.incrementAndGet();
              afterInsert.accept(connection);
              return updated;
            }
          };
      store = new JdbcAsyncJobStore(namedJdbc, dialect, transactionManager);
    }

    private TransactionTemplate businessTransaction() {
      TransactionTemplate template = new TransactionTemplate(transactionManager);
      template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
      template.setTimeout(10);
      return template;
    }

    private EntityManager entityManager() {
      EntityManagerHolder holder =
          (EntityManagerHolder) TransactionSynchronizationManager.getResource(factory);
      assertNotNull("The real JpaTransactionManager must bind an EntityManager", holder);
      return holder.getEntityManager();
    }

    private PollableTask persistTask() {
      User actor = new User();
      actor.setUsername("jpa-contract-" + UUID.randomUUID());
      entityManager().persist(actor);
      PollableTask task = new PollableTask();
      task.setName("jpa-transaction-contract");
      task.setCreatedByUser(actor);
      task.setCreatedDate(ZonedDateTime.now());
      task.setLastModifiedDate(task.getCreatedDate());
      entityManager().persist(task);
      entityManager().flush();
      return task;
    }

    private Connection assertSharedConnection() {
      Connection jpaConnection =
          entityManager()
              .unwrap(Session.class)
              .doReturningWork(DataSourceUtils::getTargetConnection);
      Connection bound = DataSourceUtils.getConnection(dataSource);
      try {
        assertSame(jpaConnection, DataSourceUtils.getTargetConnection(bound));
      } finally {
        DataSourceUtils.releaseConnection(bound, dataSource);
      }
      jdbc.execute(
          (ConnectionCallback<Void>)
              connection -> {
                assertSame(jpaConnection, DataSourceUtils.getTargetConnection(connection));
                assertFalse(connection.getAutoCommit());
                assertEquals(
                    Connection.TRANSACTION_READ_COMMITTED, connection.getTransactionIsolation());
                return null;
              });
      return jpaConnection;
    }

    private int queueCount() {
      return jdbc.queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class);
    }

    private int taskCount(long id) {
      return jdbc.queryForObject(
          "SELECT COUNT(*) FROM pollable_task WHERE id = ?", Integer.class, id);
    }

    private void assertNoBoundResources() {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(TransactionSynchronizationManager.hasResource(factory));
      assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
    }

    @Override
    public void close() {
      try {
        if (factory != null) {
          factoryBean.destroy();
        }
      } finally {
        if (container != null) {
          container.close();
        } else if (jdbc != null) {
          jdbc.execute("SHUTDOWN");
        }
      }
    }
  }

  private enum CommitFault {
    NONE,
    AFTER_COMMIT,
    ROLLBACK_BEFORE_COMMIT
  }

  private static final class CommitFaultDataSource extends DelegatingDataSource {
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicReference<CommitFault> fault = new AtomicReference<>(CommitFault.NONE);
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicInteger injectedRollbacks = new AtomicInteger();
    private final AtomicInteger faults = new AtomicInteger();
    private SQLException failure;

    private CommitFaultDataSource(DataSource target) {
      super(target);
    }

    private void failNextCommit(CommitFault mode) {
      failure = new SQLException("injected " + mode, "08006");
      fault.set(mode);
    }

    @Override
    public Connection getConnection() throws SQLException {
      connections.incrementAndGet();
      return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      connections.incrementAndGet();
      return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection target) {
      return (Connection)
          Proxy.newProxyInstance(
              ConnectionProxy.class.getClassLoader(),
              new Class<?>[] {ConnectionProxy.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("getTargetConnection")) {
                  return target;
                }
                if (method.getName().equals("commit")) {
                  CommitFault injected = fault.getAndSet(CommitFault.NONE);
                  if (injected == CommitFault.ROLLBACK_BEFORE_COMMIT) {
                    target.rollback();
                    rollbacks.incrementAndGet();
                    injectedRollbacks.incrementAndGet();
                  } else {
                    target.commit();
                    commits.incrementAndGet();
                  }
                  if (injected != CommitFault.NONE) {
                    faults.incrementAndGet();
                    throw failure;
                  }
                  return null;
                }
                if (method.getName().equals("rollback") && method.getParameterCount() == 0) {
                  target.rollback();
                  rollbacks.incrementAndGet();
                  return null;
                }
                try {
                  return method.invoke(target, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }
  }
}
