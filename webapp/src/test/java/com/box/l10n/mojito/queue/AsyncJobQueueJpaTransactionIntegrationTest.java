package com.box.l10n.mojito.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.queue.CommitFaultDataSource.CommitFault;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobOutputStorage;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutInput;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hibernate.Session;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.orm.jpa.DefaultJpaDialect;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** JPA/JDBC enlistment, runtime faults and durable legacy fanout contracts; not schema upgrades. */
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
    fixture.enqueueSqlCalls.set(0);
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
  public void currentTransactionEnqueueCommitsTaskAndQueueOnTheCallerConnection() {
    assertEnlistedTaskAndQueueOutcome(false, CommitFault.NONE);
  }

  @Test
  public void currentTransactionEnqueueRollsBackTaskAndQueueDespiteReturningAProvisionalId() {
    assertEnlistedTaskAndQueueOutcome(true, CommitFault.NONE);
  }

  @Test
  public void currentTransactionLostCommitAcknowledgementLeavesBothTaskAndQueueCommitted() {
    assertEnlistedTaskAndQueueOutcome(false, CommitFault.AFTER_COMMIT);
  }

  @Test
  public void currentTransactionRollbackBeforeCommitFailureLeavesNeitherTaskNorQueue() {
    assertEnlistedTaskAndQueueOutcome(false, CommitFault.ROLLBACK_BEFORE_COMMIT);
  }

  @Test
  public void consumerCannotRunEnlistedJobBeforeTaskAndQueueCommit() throws Exception {
    assertConsumerWaitsForEnlistedOutcome(false, CommitFault.NONE);
  }

  @Test
  public void consumerNeverRunsEnlistedJobRolledBackWithItsTask() throws Exception {
    assertConsumerWaitsForEnlistedOutcome(true, CommitFault.NONE);
  }

  @Test
  public void consumerRunsCommittedEnlistedJobDespiteLostProducerAcknowledgement()
      throws Exception {
    assertConsumerWaitsForEnlistedOutcome(false, CommitFault.AFTER_COMMIT);
  }

  @Test
  public void consumerCannotRunEnlistedJobAfterProducerCommitFailureWithRollback()
      throws Exception {
    assertConsumerWaitsForEnlistedOutcome(false, CommitFault.ROLLBACK_BEFORE_COMMIT);
  }

  @Test
  public void consumerCanFinishWhileCommittedProducerStillAwaitsItsCommitResult() throws Exception {
    assertConsumerWaitsForEnlistedOutcome(false, CommitFault.AFTER_COMMIT, true);
  }

  @Test
  public void consumerCannotRunRolledBackWorkWhileProducerStillAwaitsItsCommitResult()
      throws Exception {
    assertConsumerWaitsForEnlistedOutcome(false, CommitFault.ROLLBACK_BEFORE_COMMIT, true);
  }

  private void assertConsumerWaitsForEnlistedOutcome(boolean rollback, CommitFault fault)
      throws Exception {
    assertConsumerWaitsForEnlistedOutcome(rollback, fault, false);
  }

  private void assertConsumerWaitsForEnlistedOutcome(
      boolean rollback, CommitFault fault, boolean holdCommitResult) throws Exception {
    boolean committed = !rollback && fault != CommitFault.ROLLBACK_BEFORE_COMMIT;
    String queue = "enlisted-consumer-contract";
    AtomicReference<Long> taskId = new AtomicReference<>();
    AtomicReference<AsyncJobId> jobId = new AtomicReference<>();
    AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    AtomicInteger resolutionCallbacks = new AtomicInteger();
    CountDownLatch inserted = new CountDownLatch(1);
    CountDownLatch releaseProducer = new CountDownLatch(1);
    CountDownLatch databaseResolved = new CountDownLatch(1);
    CountDownLatch releaseCommitResult = new CountDownLatch(1);
    ExecutorService producer = Executors.newSingleThreadExecutor();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("enlisted-consumer-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationMillis(5_000);
    executor.initialize();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    AsyncJobHandler handler = mock(AsyncJobHandler.class);
    AsyncJobQueueRuntime runtime = null;
    try {
      when(handler.process(any()))
          .thenAnswer(
              invocation -> {
                try {
                  fixture.assertNoBoundResources();
                  AsyncJobRecord job = invocation.getArgument(0);
                  assertEquals(jobId.get(), job.id());
                  assertEquals("{\"pollableTaskId\":" + taskId.get() + "}", job.jobData());
                  fixture
                      .businessTransaction()
                      .executeWithoutResult(
                          status -> {
                            fixture.assertSharedConnection();
                            PollableTask task =
                                fixture.entityManager().find(PollableTask.class, taskId.get());
                            assertNotNull("The worker must see the committed task", task);
                            assertNull(task.getFinishedDate());
                            task.setMessage("worker business committed");
                          });
                  fixture.assertNoBoundResources();
                  return AsyncJobHandlerResult.done();
                } catch (Throwable failure) {
                  // Runtime catches handler failures; preserve assertions for the test thread.
                  handlerFailure.set(failure);
                  throw failure;
                }
              });
      AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
      settings.setMaxConcurrency(1);
      settings.setClaimBatchSize(1);
      settings.setMaxAttempts(1);
      settings.setHeartbeatIntervalMs(0);
      settings.setLeaseDurationMs(60_000);
      runtime =
          new AsyncJobQueueRuntime(
              queue,
              fixture.store,
              settings,
              handler,
              mock(TaskScheduler.class),
              executor,
              meters,
              "enlisted-worker");
      Future<?> outcome =
          producer.submit(
              () -> {
                try {
                  fixture
                      .businessTransaction()
                      .executeWithoutResult(
                          status -> {
                            taskId.set(fixture.persistTask().getId());
                            jobId.set(
                                fixture.store.enqueueNowInCurrentTransaction(
                                    queue, "{\"pollableTaskId\":" + taskId.get() + "}"));
                            fixture.assertSharedConnection();
                            inserted.countDown();
                            try {
                              assertTrue(
                                  "Release the open producer transaction",
                                  releaseProducer.await(15, TimeUnit.SECONDS));
                            } catch (InterruptedException failure) {
                              Thread.currentThread().interrupt();
                              throw new AssertionError(
                                  "Producer interrupted before commit", failure);
                            }
                            if (rollback) {
                              status.setRollbackOnly();
                            }
                            // Arm only after the pre-commit consumer poll has completed.
                            if (fault != CommitFault.NONE) {
                              fixture.dataSource.failNextCommit(
                                  fault,
                                  () -> {
                                    resolutionCallbacks.incrementAndGet();
                                    if (holdCommitResult) {
                                      databaseResolved.countDown();
                                      try {
                                        assertTrue(
                                            "Release the withheld commit result",
                                            releaseCommitResult.await(15, TimeUnit.SECONDS));
                                      } catch (InterruptedException failure) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(
                                            "Producer interrupted after database resolution",
                                            failure);
                                      }
                                    }
                                  });
                            }
                          });
                } finally {
                  fixture.assertNoBoundResources();
                }
              });

      assertTrue("Producer inserts task and queue row", inserted.await(15, TimeUnit.SECONDS));
      assertFalse(outcome.isDone());
      fixture.assertIndependentTaskAndQueueCounts(taskId.get(), jobId.get(), 0);
      assertEquals(0, runtime.pollOnce().claimedCount());
      fixture.assertNoBoundResources();
      assertEquals(0, runtime.inFlightCount());
      assertEquals(0, executor.getThreadPoolExecutor().getTaskCount());
      verifyNoInteractions(handler);
      assertFalse("Producer remains gated during the poll", outcome.isDone());

      releaseProducer.countDown();
      if (holdCommitResult) {
        assertTrue(
            "Database resolves before the caller receives its result",
            databaseResolved.await(15, TimeUnit.SECONDS));
        assertFalse("Commit result is still withheld", outcome.isDone());
      } else {
        assertProducerOutcome(outcome, fault);
      }
      fixture.assertIndependentTaskAndQueueCounts(taskId.get(), jobId.get(), committed ? 1 : 0);
      assertEquals(committed ? 1 : 0, runtime.pollOnce().claimedCount());
      long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      while (runtime.inFlightCount() != 0 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(0, runtime.inFlightCount());
      assertNull("Handler assertions must reach the test thread", handlerFailure.get());
      assertEquals(committed ? 1 : 0, fixture.queueCount());
      if (!committed) {
        assertTrue(fixture.store.getByIds(List.of(jobId.get())).isEmpty());
        verifyNoInteractions(handler);
      } else {
        AsyncJobRecord terminal = fixture.store.getByIds(List.of(jobId.get())).getFirst();
        assertEquals(AsyncJobStatus.DONE, terminal.status());
        assertEquals(1, terminal.attemptCount());
        assertNull(terminal.lastError());
        assertNull(terminal.workerId());
        assertNull(terminal.leaseToken());
        assertEquals(
            "worker business committed",
            fixture.jdbc.queryForObject(
                "SELECT message FROM pollable_task WHERE id = ?", String.class, taskId.get()));
        assertEquals(0, runtime.pollOnce().claimedCount());
        verify(handler, times(1)).process(any());
        verify(handler, times(1)).onJobDone(any(), any());
        verify(handler, never()).onJobFailedPermanently(any(), any(), any());
      }
      if (holdCommitResult) {
        assertFalse("Consumer completed its poll before producer return", outcome.isDone());
        List<AsyncJobRecord> beforeAcknowledgement = fixture.store.getByIds(List.of(jobId.get()));
        releaseCommitResult.countDown();
        assertProducerOutcome(outcome, fault);
        assertEquals(beforeAcknowledgement, fixture.store.getByIds(List.of(jobId.get())));
        fixture.assertIndependentTaskAndQueueCounts(taskId.get(), jobId.get(), committed ? 1 : 0);
        if (committed) {
          assertEquals(
              "worker business committed",
              fixture.jdbc.queryForObject(
                  "SELECT message FROM pollable_task WHERE id = ?", String.class, taskId.get()));
        }
      }
      assertEquals(fault == CommitFault.NONE ? 0 : 1, resolutionCallbacks.get());
      fixture.assertNoBoundResources();
    } finally {
      releaseProducer.countDown();
      releaseCommitResult.countDown();
      producer.shutdownNow();
      try {
        assertTrue("Producer thread terminates", producer.awaitTermination(15, TimeUnit.SECONDS));
      } finally {
        try {
          if (runtime != null) {
            runtime.stop();
          }
        } finally {
          executor.shutdown();
          executor.getThreadPoolExecutor().shutdownNow();
          try {
            assertTrue(executor.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS));
          } finally {
            meters.close();
          }
        }
      }
    }
  }

  private void assertProducerOutcome(Future<?> outcome, CommitFault fault) throws Exception {
    if (fault == CommitFault.NONE) {
      outcome.get(15, TimeUnit.SECONDS);
    } else {
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> outcome.get(15, TimeUnit.SECONDS));
      assertInjectedCommitFailure(failure.getCause());
    }
  }

  private void assertEnlistedTaskAndQueueOutcome(boolean rollbackOnly, CommitFault fault) {
    AtomicReference<Long> taskId = new AtomicReference<>();
    // Test-only capture, not a published result or a durable retry identity.
    AtomicReference<AsyncJobId> provisionalId = new AtomicReference<>();
    Runnable transaction =
        () ->
            fixture
                .businessTransaction()
                .executeWithoutResult(
                    status -> {
                      EntityManager caller = fixture.entityManager();
                      taskId.set(fixture.persistTask().getId());
                      Connection callerConnection = fixture.assertSharedConnection();
                      fixture.afterInsert =
                          connection -> {
                            assertSame(caller, fixture.entityManager());
                            assertSame(callerConnection, connection);
                            assertEquals(1, fixture.taskCount(taskId.get()));
                            assertEquals(1, fixture.queueCount());
                          };

                      provisionalId.set(
                          fixture.store.enqueueNowInCurrentTransaction(
                              "jpa-enlisted-contract",
                              "{\"pollableTaskId\":" + taskId.get() + "}"));

                      assertSame(caller, fixture.entityManager());
                      assertSame(callerConnection, fixture.assertSharedConnection());
                      assertEquals(1, fixture.dataSource.connections.get());
                      assertEquals(0, fixture.dataSource.commits.get());
                      assertEquals(0, fixture.dataSource.rollbacks.get());
                      fixture.assertIndependentTaskAndQueueCounts(
                          taskId.get(), provisionalId.get(), 0);
                      if (rollbackOnly) {
                        status.setRollbackOnly();
                      }
                      if (fault != CommitFault.NONE) {
                        fixture.dataSource.failNextCommit(fault);
                      }
                    });

    if (fault == CommitFault.NONE) {
      transaction.run();
      assertEquals(0, fixture.dataSource.faults.get());
      assertEquals(rollbackOnly ? 1 : 0, fixture.dataSource.rollbacks.get());
    } else {
      assertInjectedCommitFailure(assertThrows(RuntimeException.class, transaction::run));
    }

    fixture.assertNoBoundResources();
    assertNotNull(taskId.get());
    assertNotNull(provisionalId.get());
    assertEquals(1, fixture.dataSource.connections.get());
    assertEquals(2, fixture.enqueueSqlCalls.get());
    assertEquals(1, fixture.inserts.get());
    boolean committed = !rollbackOnly && fault != CommitFault.ROLLBACK_BEFORE_COMMIT;
    assertEquals(committed ? 1 : 0, fixture.dataSource.commits.get());
    assertEquals(
        fault == CommitFault.ROLLBACK_BEFORE_COMMIT ? 1 : 0,
        fixture.dataSource.injectedRollbacks.get());
    assertEquals(committed ? 1 : 0, fixture.taskCount(taskId.get()));
    assertEquals(committed ? 1 : 0, fixture.queueCount());
    fixture.assertIndependentTaskAndQueueCounts(
        taskId.get(), provisionalId.get(), committed ? 1 : 0);
    if (committed) {
      assertEquals(
          Long.valueOf(provisionalId.get().value()),
          fixture.jdbc.queryForObject("SELECT id FROM async_job_queue", Long.class));
      assertEquals(
          "{\"pollableTaskId\":" + taskId.get() + "}",
          fixture.jdbc.queryForObject("SELECT job_data FROM async_job_queue", String.class));
      assertEquals(
          Integer.valueOf(1),
          fixture.jdbc.queryForObject(
              """
              SELECT COUNT(*) FROM async_job_queue
              WHERE queue_name = 'jpa-enlisted-contract' AND status = 'queued'
                AND attempt_count = 0 AND lease_until IS NULL AND lease_token IS NULL
                AND worker_id IS NULL AND last_error IS NULL
                AND available_at = created_date AND updated_date = created_date
              """,
              Integer.class));
    }
    fixture.assertNoBoundResources();
  }

  @Test
  public void currentTransactionFailureAfterActualInsertRollsBackBothTaskAndQueue() {
    AtomicReference<Long> taskId = new AtomicReference<>();
    AtomicReference<AsyncJobId> provisionalId = new AtomicReference<>();
    IllegalStateException failure = new IllegalStateException("after enlisted insert");

    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                fixture
                    .businessTransaction()
                    .executeWithoutResult(
                        status -> {
                          EntityManager caller = fixture.entityManager();
                          taskId.set(fixture.persistTask().getId());
                          Connection callerConnection = fixture.assertSharedConnection();
                          fixture.afterInsert =
                              connection -> {
                                assertSame(caller, fixture.entityManager());
                                assertSame(callerConnection, connection);
                                assertEquals(1, fixture.taskCount(taskId.get()));
                                assertEquals(1, fixture.queueCount());
                                throw failure;
                              };
                          provisionalId.set(
                              fixture.store.enqueueNowInCurrentTransaction(
                                  "jpa-enlisted-contract",
                                  "{\"pollableTaskId\":" + taskId.get() + "}"));
                        })));

    fixture.assertNoBoundResources();
    assertNotNull(taskId.get());
    assertNull(provisionalId.get());
    assertEquals(1, fixture.dataSource.connections.get());
    assertEquals(2, fixture.enqueueSqlCalls.get());
    assertEquals(1, fixture.inserts.get());
    assertEquals(0, fixture.dataSource.commits.get());
    assertEquals(1, fixture.dataSource.rollbacks.get());
    assertEquals(0, fixture.taskCount(taskId.get()));
    assertEquals(0, fixture.queueCount());
    fixture.assertNoBoundResources();
  }

  @Test
  public void currentTransactionEnqueueRejectsNoTransactionBeforeSql() {
    assertCurrentTransactionEnqueueRejectedBeforeSql(fixture.store);
    assertEquals(0, fixture.dataSource.connections.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void currentTransactionEnqueueRejectsSynchronizationWithoutActualTransactionBeforeSql() {
    TransactionTemplate transaction = fixture.businessTransaction();
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
    transaction.executeWithoutResult(
        status -> {
          assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
          assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
          assertCurrentTransactionEnqueueRejectedBeforeSql(fixture.store);
        });
    assertEquals(0, fixture.dataSource.connections.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void currentTransactionEnqueueRejectsUnconfiguredManagerEvenInsideValidTransaction() {
    JdbcAsyncJobStore unconfigured = new JdbcAsyncJobStore(fixture.namedJdbc, dialect);
    fixture
        .businessTransaction()
        .executeWithoutResult(
            status -> {
              fixture.assertSharedConnection();
              assertCurrentTransactionEnqueueRejectedBeforeSql(unconfigured);
              status.setRollbackOnly();
            });
    assertEquals(1, fixture.dataSource.connections.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void currentTransactionEnqueueRejectsReadOnlyTransactionBeforeSql() {
    TransactionTemplate transaction = fixture.businessTransaction();
    transaction.setReadOnly(true);
    transaction.executeWithoutResult(
        status -> {
          assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
          assertCurrentTransactionEnqueueRejectedBeforeSql(fixture.store);
          status.setRollbackOnly();
        });
    assertEquals(1, fixture.dataSource.connections.get());
    fixture.assertNoBoundResources();
  }

  @Test
  public void currentTransactionEnqueueRejectsDefaultAndWrongIsolationBeforeSql() {
    for (int isolation :
        List.of(
            TransactionDefinition.ISOLATION_DEFAULT,
            TransactionDefinition.ISOLATION_SERIALIZABLE)) {
      TransactionTemplate transaction = fixture.businessTransaction();
      transaction.setIsolationLevel(isolation);
      transaction.executeWithoutResult(
          status -> {
            assertEquals(
                isolation == TransactionDefinition.ISOLATION_DEFAULT
                    ? null
                    : Integer.valueOf(isolation),
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            assertCurrentTransactionEnqueueRejectedBeforeSql(fixture.store);
            status.setRollbackOnly();
          });
      fixture.assertNoBoundResources();
    }
    assertEquals(2, fixture.dataSource.connections.get());
  }

  @Test
  public void currentTransactionEnqueueRejectsDifferentDataSourceForTheSameDatabaseBeforeSql() {
    CommitFaultDataSource other =
        new CommitFaultDataSource(fixture.dataSource.getTargetDataSource());
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(other));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(10);
    transaction.executeWithoutResult(
        status -> {
          assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
          assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
          assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
          assertEquals(
              Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED),
              TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
          assertTrue(
              TransactionSynchronizationManager.getResource(other) instanceof ConnectionHolder);
          assertFalse(TransactionSynchronizationManager.hasResource(fixture.dataSource));
          assertCurrentTransactionEnqueueRejectedBeforeSql(fixture.store);
          status.setRollbackOnly();
        });

    assertEquals(1, other.connections.get());
    assertEquals(0, other.commits.get());
    assertEquals(1, other.rollbacks.get());
    assertEquals(0, fixture.dataSource.connections.get());
    assertFalse(TransactionSynchronizationManager.hasResource(other));
    fixture.assertNoBoundResources();
    assertEquals(0, fixture.queueCount());
    fixture.assertNoBoundResources();
  }

  private void assertCurrentTransactionEnqueueRejectedBeforeSql(JdbcAsyncJobStore store) {
    int connections = fixture.dataSource.connections.get();
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> store.enqueueNowInCurrentTransaction("jpa-enlisted-contract", "{}"));
    assertEquals(
        "Enlisted async job enqueue requires an active writable READ_COMMITTED transaction "
            + "bound to the configured queue DataSource",
        failure.getMessage());
    assertEquals(0, fixture.enqueueSqlCalls.get());
    assertEquals(0, fixture.inserts.get());
    assertEquals(connections, fixture.dataSource.connections.get());
    assertEquals(0, fixture.dataSource.commits.get());
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
  public void committedHeartbeatWithLostAcknowledgementRetainsTheLiveOwner() {
    assertUncertainLeaseMutation(LeaseMutation.HEARTBEAT, CommitFault.AFTER_COMMIT);
  }

  @Test
  public void rolledBackHeartbeatWithLostAcknowledgementPreservesTheExactLease() {
    assertUncertainLeaseMutation(LeaseMutation.HEARTBEAT, CommitFault.ROLLBACK_BEFORE_COMMIT);
  }

  @Test
  public void committedRetryWithLostAcknowledgementRequiresANewLease() {
    assertUncertainLeaseMutation(LeaseMutation.REQUEUE, CommitFault.AFTER_COMMIT);
  }

  @Test
  public void rolledBackRetryWithLostAcknowledgementPreservesTheExactRunningRow() {
    assertUncertainLeaseMutation(LeaseMutation.REQUEUE, CommitFault.ROLLBACK_BEFORE_COMMIT);
  }

  @Test
  public void committedFailureWithLostAcknowledgementRemainsTerminalUntilExplicitReplay() {
    assertUncertainLeaseMutation(LeaseMutation.FAIL, CommitFault.AFTER_COMMIT);
  }

  @Test
  public void rolledBackFailureWithLostAcknowledgementPreservesTheExactRunningRow() {
    assertUncertainLeaseMutation(LeaseMutation.FAIL, CommitFault.ROLLBACK_BEFORE_COMMIT);
  }

  private enum LeaseMutation {
    HEARTBEAT,
    REQUEUE,
    FAIL
  }

  private void assertUncertainLeaseMutation(LeaseMutation mutation, CommitFault fault) {
    String queue = "lease-mutation-commit";
    AsyncJobId id = fixture.store.enqueueNow(queue, "original");
    AsyncJobRecord before =
        fixture.store.claimNextJobs(queue, 1, "worker", Duration.ofMinutes(1)).getFirst();
    assertEquals(id, before.id());
    int previousCommits = fixture.dataSource.commits.get();
    int previousInjectedRollbacks = fixture.dataSource.injectedRollbacks.get();
    fixture.dataSource.failNextCommit(fault);

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> {
              boolean updated =
                  switch (mutation) {
                    case HEARTBEAT ->
                        fixture.store.heartbeat(
                            queue,
                            id,
                            before.workerId(),
                            before.leaseToken(),
                            Duration.ofMinutes(10));
                    case REQUEUE ->
                        fixture.store.requeueAfter(
                            queue,
                            id,
                            before.workerId(),
                            before.leaseToken(),
                            Duration.ofHours(1),
                            "replacement",
                            "transition error");
                    case FAIL ->
                        fixture.store.markFailed(
                            queue,
                            id,
                            before.workerId(),
                            before.leaseToken(),
                            "replacement",
                            "transition error");
                  };
              throw new AssertionError("An uncertain commit must throw, not return " + updated);
            });

    assertInjectedCommitFailure(failure);
    boolean committed = fault == CommitFault.AFTER_COMMIT;
    assertEquals(previousCommits + (committed ? 1 : 0), fixture.dataSource.commits.get());
    assertEquals(
        previousInjectedRollbacks + (committed ? 0 : 1),
        fixture.dataSource.injectedRollbacks.get());
    fixture.assertNoBoundResources();
    AsyncJobRecord observed = fixture.store.getByIds(List.of(id)).getFirst();
    if (!committed) {
      assertEquals("Rollback must preserve every field", before, observed);
    } else {
      assertFalse(observed.updatedDate().isBefore(before.updatedDate()));
      boolean renewal = mutation == LeaseMutation.HEARTBEAT;
      AsyncJobRecord expected =
          new AsyncJobRecord(
              id,
              queue,
              switch (mutation) {
                case HEARTBEAT -> AsyncJobStatus.RUNNING;
                case REQUEUE -> AsyncJobStatus.QUEUED;
                case FAIL -> AsyncJobStatus.FAILED;
              },
              mutation == LeaseMutation.REQUEUE
                  ? observed.updatedDate().plus(Duration.ofHours(1))
                  : before.availableAt(),
              renewal ? observed.updatedDate().plus(Duration.ofMinutes(10)) : null,
              renewal ? before.workerId() : null,
              renewal ? before.leaseToken() : null,
              renewal ? before.jobData() : "replacement",
              before.attemptCount(),
              renewal ? before.lastError() : "transition error",
              before.createdDate(),
              observed.updatedDate(),
              false);
      assertEquals("Commit must persist exactly the intended transition", expected, observed);
    }
    // Running, delayed retry and failed work must all stay unavailable to this peer.
    assertTrue(fixture.store.claimNextJobs(queue, 1, "peer", Duration.ofMinutes(1)).isEmpty());
    assertEquals(observed, fixture.store.getByIds(List.of(id)).getFirst());

    if (!committed || mutation == LeaseMutation.HEARTBEAT) {
      // Ambiguous renewal is not lease loss: the original owner can still complete.
      assertTrue(fixture.store.markDone(queue, id, before.workerId(), before.leaseToken(), "done"));
    } else {
      assertStaleLeaseCannotChangeRow(before, observed);
      if (mutation == LeaseMutation.REQUEUE) {
        // Advance only the fixture's availability, without depending on an hour of wall time.
        assertEquals(
            1,
            fixture.jdbc.update(
                "UPDATE async_job_queue SET available_at = created_date WHERE id = ?",
                Long.valueOf(id.value())));
      } else {
        // This is generic operator replay, not a supported Mojito task/admission recovery path.
        assertTrue(fixture.store.requeueFailedNow(queue, id, null));
      }
      AsyncJobRecord replacement =
          fixture
              .store
              .claimNextJobs(queue, 1, before.workerId(), Duration.ofMinutes(1))
              .getFirst();
      assertEquals(id, replacement.id());
      assertEquals(mutation == LeaseMutation.REQUEUE ? 2 : 1, replacement.attemptCount());
      assertEquals("replacement", replacement.jobData());
      assertEquals("transition error", replacement.lastError());
      assertFalse(before.leaseToken().equals(replacement.leaseToken()));
      assertStaleLeaseCannotChangeRow(before, replacement);
      assertTrue(
          fixture.store.markDone(
              queue, id, replacement.workerId(), replacement.leaseToken(), "done"));
    }
    AsyncJobRecord done = fixture.store.getByIds(List.of(id)).getFirst();
    assertEquals(AsyncJobStatus.DONE, done.status());
    assertEquals("done", done.jobData());
    assertNull(done.lastError());
    fixture.assertNoBoundResources();
  }

  private void assertStaleLeaseCannotChangeRow(AsyncJobRecord stale, AsyncJobRecord expected) {
    assertFalse(
        fixture.store.heartbeat(
            stale.queueName(),
            stale.id(),
            stale.workerId(),
            stale.leaseToken(),
            Duration.ofMinutes(1)));
    assertFalse(
        fixture.store.markDone(
            stale.queueName(), stale.id(), stale.workerId(), stale.leaseToken(), "stale"));
    assertFalse(
        fixture.store.requeueAfter(
            stale.queueName(),
            stale.id(),
            stale.workerId(),
            stale.leaseToken(),
            Duration.ZERO,
            "stale",
            "stale"));
    assertFalse(
        fixture.store.markFailed(
            stale.queueName(), stale.id(), stale.workerId(), stale.leaseToken(), "stale", "stale"));
    assertEquals(
        expected.withLeaseReclaimed(false), fixture.store.getByIds(List.of(stale.id())).getFirst());
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
  public void assetSubmissionDoesNotFailTaskAfterLostQueueCommitAcknowledgement() {
    assertUncertainAssetSubmission(CommitFault.AFTER_COMMIT, 1);
  }

  @Test
  public void assetSubmissionDoesNotGuessRollbackFromTheSameCommitException() {
    assertUncertainAssetSubmission(CommitFault.ROLLBACK_BEFORE_COMMIT, 0);
  }

  private void assertUncertainAssetSubmission(CommitFault fault, int expectedQueueRows) {
    PollableTask task = fixture.businessTransaction().execute(status -> fixture.persistTask());
    assertNotNull(task);
    assertEquals(1, fixture.taskCount(task.getId()));
    PollableTaskService tasks = mock(PollableTaskService.class);
    PollableTaskBlobStorage blobs = mock(PollableTaskBlobStorage.class);
    PollableTaskExceptionUtils exceptions = mock(PollableTaskExceptionUtils.class);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    when(tasks.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), null, 0, 3600))
        .thenReturn(task);
    ObjectMapper mapper = new ObjectMapper();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    try {
      AsyncJobQueueSubmissionService queue =
          new AsyncJobQueueSubmissionService(fixture.store, coordinator, meters);
      AssetLocalizeAsyncJobSubmissionService submission =
          new AssetLocalizeAsyncJobSubmissionService(
              tasks, blobs, exceptions, queue, mapper, meters);
      LocalizedAssetBody input = new LocalizedAssetBody();
      QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job =
          QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class).withInput(input).build();
      fixture.dataSource.failNextCommit(fault);

      RuntimeException failure =
          assertThrows(RuntimeException.class, () -> submission.scheduleJob(job));

      assertInjectedCommitFailure(failure);
      assertEquals(1, fixture.inserts.get());
      assertEquals(1, fixture.dataSource.faults.get());
      assertEquals(expectedQueueRows, fixture.queueCount());
      assertEquals(1, fixture.taskCount(task.getId()));
      if (expectedQueueRows == 1) {
        assertEquals(
            "queued",
            fixture.jdbc.queryForObject("SELECT status FROM async_job_queue", String.class));
        AssetLocalizeAsyncJobPayload payload =
            mapper.readValueUnchecked(
                fixture.jdbc.queryForObject("SELECT job_data FROM async_job_queue", String.class),
                AssetLocalizeAsyncJobPayload.class);
        assertEquals(task.getId(), payload.pollableTaskId());
      }
      verify(blobs).saveInput(task.getId(), input);
      // Real JDBC outcomes differ, but neither justifies a definitive failure compensation.
      verify(tasks, never()).finishTask(eq(task.getId()), isNull(), any(), isNull());
      verifyNoInteractions(exceptions, coordinator);
      assertEquals(
          1,
          meters
              .get("assetLocalizeAsyncJob.schedule")
              .tags("queueName", "assetlocalize", "result", "outcomeUnknown")
              .counter()
              .count(),
          0);
      fixture.assertNoBoundResources();
    } finally {
      meters.close();
    }
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

  @Test
  public void fanoutAcceptsOneAtomicBatchWithReadableFrozenInputs() throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    long parent = fanout.register();
    long owner =
        fixture
            .businessTransaction()
            .execute(
                status -> {
                  User actor = new User();
                  actor.setUsername("fanout-owner-" + UUID.randomUUID());
                  fixture.entityManager().persist(actor);
                  fixture.entityManager().find(PollableTask.class, parent).setCreatedByUser(actor);
                  fixture.entityManager().flush();
                  return actor.getId();
                });
    fanout.service.resume(parent);
    Map<String, Long> mappings = fanout.store.mappings(fanout.store.find(parent).orElseThrow());
    assertEquals(3, mappings.size());
    assertEquals("FINISHED", fanout.store.find(parent).orElseThrow().state());
    for (long child : mappings.values()) {
      LocalizedAssetBody input =
          fanout.mapper.readValueUnchecked(
              fanout.inputs.findInputJson(child).orElseThrow(), LocalizedAssetBody.class);
      assertEquals("frozen source", input.getContent());
      assertEquals(Long.valueOf(10), input.getAssetId());
      assertNull(input.getPullRunName());
      assertEquals(
          owner,
          fixture
              .jdbc
              .queryForObject(
                  "SELECT created_by_user_id FROM pollable_task WHERE id = ?", Long.class, child)
              .longValue());
      assertEquals(
          1,
          fixture
              .jdbc
              .queryForObject(
                  "SELECT COUNT(*) FROM async_job_queue q JOIN asset_localize_fanout_child c ON c.queue_job_id = q.id WHERE c.child_task_id = ?",
                  Integer.class,
                  child)
              .intValue());
    }
    assertEquals(
        3,
        fixture
            .jdbc
            .queryForObject(
                "SELECT expected_sub_task_number FROM pollable_task WHERE id = ?",
                Integer.class,
                parent)
            .intValue());
    assertNull(
        fixture.jdbc.queryForObject(
            "SELECT timeout FROM pollable_task WHERE id = ?", Long.class, parent));
  }

  @Test
  public void fanoutCommitThenThrowResumesOriginalChildIds() throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    long parent = fanout.register();
    fixture.dataSource.failNextCommit(CommitFault.AFTER_COMMIT);
    assertThrows(RuntimeException.class, () -> fanout.service.resume(parent));
    Map<String, Long> original = fanout.store.mappings(fanout.store.find(parent).orElseThrow());
    assertEquals(3, original.size());
    assertEquals("ACCEPTED", fanout.store.find(parent).orElseThrow().state());
    fanout.newService().resume(parent);
    assertEquals(original, fanout.store.mappings(fanout.store.find(parent).orElseThrow()));
    assertEquals(3, fanout.childCount(parent));
  }

  @Test
  public void fanoutRollbackThenThrowLeavesNoOrphanOrExecutableChild() throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    long parent = fanout.register();
    int before = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class);
    fixture.dataSource.failNextCommit(CommitFault.ROLLBACK_BEFORE_COMMIT);
    assertThrows(RuntimeException.class, () -> fanout.service.resume(parent));
    assertEquals(0, fanout.childCount(parent));
    assertEquals(
        before,
        fixture
            .jdbc
            .queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class)
            .intValue());
    assertEquals("PENDING", fanout.store.find(parent).orElseThrow().state());
    fanout.newService().resume(parent);
    assertEquals(3, fanout.childCount(parent));
  }

  @Test
  public void fanoutFailureAfterSecondInsertRollsBackWholeBatch() throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    long parent = fanout.register();
    AtomicInteger count = new AtomicInteger();
    fixture.afterInsert =
        connection -> {
          if (count.incrementAndGet() == 2) throw new IllegalStateException("second child failed");
        };
    assertThrows(RuntimeException.class, () -> fanout.service.resume(parent));
    assertEquals(0, fanout.childCount(parent));
    assertEquals(
        0,
        fixture
            .jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM asset_localize_fanout_child WHERE parent_task_id = ?",
                Integer.class,
                parent)
            .intValue());
    fixture.afterInsert = connection -> {};
    fanout.newService().resume(parent);
    assertEquals(3, fanout.childCount(parent));
  }

  @Test
  public void fanoutConcurrentResumesPublishSameMappingWithoutDuplicateJobs() throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    long parent = fanout.register();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> first = executor.submit(() -> fanout.service.resume(parent));
      Future<?> second = executor.submit(() -> fanout.newService().resume(parent));
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    }
    assertEquals(3, fanout.childCount(parent));
    assertEquals(
        3,
        fixture
            .jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM asset_localize_fanout_child WHERE parent_task_id = ?",
                Integer.class,
                parent)
            .intValue());
    assertEquals("FINISHED", fanout.store.find(parent).orElseThrow().state());
  }

  @Test
  public void fanoutOutputWriteThenThrowKeepsParentPendingAndRetriesPublicationOnly()
      throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    long parent = fanout.register();
    AtomicInteger publications = new AtomicInteger();
    AtomicReference<Map<String, Long>> first = new AtomicReference<>();
    doAnswer(
            invocation -> {
              MultiLocalizedAssetBody output = invocation.getArgument(1);
              Map<String, Long> map = Map.copyOf(output.getGenerateLocalizedAssetJobIds());
              if (publications.incrementAndGet() == 1) {
                first.set(map);
                throw new IllegalStateException("output reply lost");
              }
              assertEquals(first.get(), map);
              return null;
            })
        .when(fanout.taskBlobs)
        .saveOutput(any(), any());
    assertThrows(RuntimeException.class, () -> fanout.service.resume(parent));
    assertEquals("ACCEPTED", fanout.store.find(parent).orElseThrow().state());
    assertNull(
        fixture.jdbc.queryForObject(
            "SELECT finished_date FROM pollable_task WHERE id = ?",
            java.sql.Timestamp.class,
            parent));
    fanout.newService().resume(parent);
    assertEquals(3, fanout.childCount(parent));
    assertEquals(2, publications.get());
  }

  @Test
  public void fanoutFinishCommitUncertaintyNeverRecreatesChildren() throws Exception {
    for (CommitFault fault :
        List.of(CommitFault.AFTER_COMMIT, CommitFault.ROLLBACK_BEFORE_COMMIT)) {
      FanoutFixture fanout = new FanoutFixture();
      long parent = fanout.register();
      AtomicInteger publications = new AtomicInteger();
      doAnswer(
              invocation -> {
                if (publications.incrementAndGet() == 1) fixture.dataSource.failNextCommit(fault);
                return null;
              })
          .when(fanout.taskBlobs)
          .saveOutput(any(), any());
      assertThrows(RuntimeException.class, () -> fanout.service.resume(parent));
      Map<String, Long> original = fanout.store.mappings(fanout.store.find(parent).orElseThrow());
      fanout.newService().resume(parent);
      assertEquals(original, fanout.store.mappings(fanout.store.find(parent).orElseThrow()));
      assertEquals(3, fanout.childCount(parent));
      assertEquals("FINISHED", fanout.store.find(parent).orElseThrow().state());
    }
  }

  @Test
  public void fanoutRegistrationCommitUncertaintyHasUniqueDurableReceipt() throws Exception {
    for (CommitFault fault :
        List.of(CommitFault.AFTER_COMMIT, CommitFault.ROLLBACK_BEFORE_COMMIT)) {
      FanoutFixture fanout = new FanoutFixture();
      AssetLocalizeFanoutInput.Reference reference = fanout.inputs.stage(fanout.manifest());
      fixture.dataSource.failNextCommit(fault);
      assertThrows(RuntimeException.class, () -> fanout.store.register(reference));
      Optional<AssetLocalizeFanoutStore.Parent> accepted =
          fanout.store.findByInputName(reference.name());
      assertEquals(fault == CommitFault.AFTER_COMMIT, accepted.isPresent());
      if (accepted.isPresent()) {
        fanout.newService().resume(accepted.get().taskId());
        assertEquals(3, fanout.childCount(accepted.get().taskId()));
      }
    }
  }

  @Test
  public void fanoutCorruptManifestFailsClosedBeforeChildrenAndOnInputReads() throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    long parent = fanout.register();
    String name = fanout.store.find(parent).orElseThrow().input().name();
    byte[] original = fanout.objects.get(name);
    fanout.objects.put(name, new byte[] {1, 2, 3});
    assertThrows(IllegalStateException.class, () -> fanout.service.resume(parent));
    assertEquals(0, fanout.childCount(parent));
    assertThrows(IllegalStateException.class, () -> fanout.inputs.findInputJson(parent));
    fanout.objects.put(name, original);
    fanout.service.resume(parent);
    long child =
        fanout.store.mappings(fanout.store.find(parent).orElseThrow()).values().iterator().next();
    fanout.objects.remove(name);
    assertThrows(IllegalStateException.class, () -> fanout.inputs.findInputJson(child));
  }

  @Test
  public void fanoutLostTerminalCallbackRepairsRecordedChildrenWithoutReenqueue() throws Exception {
    FanoutFixture fanout = new FanoutFixture();
    // Real task service and transaction advice; repository operations enlist the production entity
    // in the same fixture manager, without requiring the full application or security context.
    EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(fixture.factory);
    PollableTaskRepository repository = mock(PollableTaskRepository.class);
    when(repository.findById(any(Long.class)))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(shared.find(PollableTask.class, invocation.getArgument(0))));
    when(repository.save(any(PollableTask.class)))
        .thenAnswer(invocation -> shared.merge(invocation.getArgument(0)));
    PollableTaskService targetTasks = new PollableTaskService();
    ReflectionTestUtils.setField(targetTasks, "objectMapper", fanout.mapper);
    ReflectionTestUtils.setField(targetTasks, "pollableTaskRepository", repository);
    ReflectionTestUtils.setField(targetTasks, "transactionManager", fixture.transactionManager);
    ReflectionTestUtils.setField(targetTasks, "entityManager", shared);
    ProxyFactory proxy = new ProxyFactory(targetTasks);
    proxy.addAdvice(
        new TransactionInterceptor(
            fixture.transactionManager, new AnnotationTransactionAttributeSource()));
    PollableTaskService tasks = (PollableTaskService) proxy.getProxy();
    PollableTaskBlobStorage taskBlobs = new PollableTaskBlobStorage();
    ReflectionTestUtils.setField(taskBlobs, "structuredBlobStorage", fanout.blobs);
    ReflectionTestUtils.setField(taskBlobs, "objectMapper", fanout.mapper);
    ReflectionTestUtils.setField(taskBlobs, "meterRegistry", new SimpleMeterRegistry());
    ReflectionTestUtils.setField(taskBlobs, "inputSources", List.of(fanout.inputs));
    AssetLocalizeAsyncJobOutputStorage outputs =
        new AssetLocalizeAsyncJobOutputStorage(fanout.blobs, taskBlobs, fanout.mapper);
    AssetLocalizeAsyncJobRepairService repair =
        new AssetLocalizeAsyncJobRepairService(
            fixture.store, tasks, new SimpleMeterRegistry(), outputs);
    AssetLocalizeFanoutService initial =
        new AssetLocalizeFanoutService(
            fanout.store,
            fanout.inputs,
            tasks,
            taskBlobs,
            mock(AssetRepository.class),
            mock(RepositoryLocaleRepository.class),
            repair);
    long parent = fanout.register();
    initial.resume(parent);
    Map<String, Long> original = fanout.store.mappings(fanout.store.find(parent).orElseThrow());
    Map<Long, byte[]> expected = new java.util.HashMap<>();
    for (long child : original.values()) {
      LocalizedAssetBody output = new LocalizedAssetBody();
      output.setAssetId(10L);
      output.setContent("stored winning output " + child);
      AssetLocalizeAsyncJobPayload winner = outputs.saveAttemptOutput(child, output);
      expected.put(
          child,
          fanout
              .mapper
              .writeValueAsStringUnchecked(output)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      fixture.jdbc.update(
          "UPDATE async_job_queue SET status = 'done', job_data = ? WHERE id = (SELECT queue_job_id FROM asset_localize_fanout_child WHERE child_task_id = ?)",
          fanout.mapper.writeValueAsStringUnchecked(winner),
          child);
      assertNull(tasks.getFreshPollableTask(child).getFinishedDate());
      assertFalse(taskBlobs.findOutputJson(child).isPresent());
    }
    int queuedRows =
        fixture.jdbc.queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class);
    AssetLocalizeFanoutService restarted =
        new AssetLocalizeFanoutService(
            fanout.store,
            fanout.inputs,
            tasks,
            taskBlobs,
            mock(AssetRepository.class),
            mock(RepositoryLocaleRepository.class),
            repair);
    restarted.resume(parent);
    assertEquals("COMPLETED", fanout.store.find(parent).orElseThrow().state());
    assertEquals(original, fanout.store.mappings(fanout.store.find(parent).orElseThrow()));
    assertEquals(
        queuedRows,
        fixture
            .jdbc
            .queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class)
            .intValue());
    for (long child : original.values()) {
      org.junit.Assert.assertArrayEquals(expected.get(child), taskBlobs.getOutputBytes(child));
      assertNotNull(tasks.getFreshPollableTask(child).getFinishedDate());
      assertNull(tasks.getFreshPollableTask(child).getErrorMessage());
    }
    restarted.resume(parent);
    assertEquals(3, fanout.childCount(parent));
  }

  private final class FanoutFixture {
    final ObjectMapper mapper = new ObjectMapper();
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AssetLocalizeFanoutStore store =
        new AssetLocalizeFanoutStore(
            fixture.jdbc, fixture.factory, fixture.transactionManager, fixture.store, mapper);
    final PollableTaskBlobStorage taskBlobs = mock(PollableTaskBlobStorage.class);
    final AssetLocalizeAsyncJobRepairService repair =
        mock(AssetLocalizeAsyncJobRepairService.class);
    final StructuredBlobStorage blobs = mock(StructuredBlobStorage.class);
    final AssetLocalizeFanoutInput inputs;
    final AssetLocalizeFanoutService service;

    FanoutFixture() {
      doAnswer(
              invocation -> {
                objects.put(
                    invocation.getArgument(1), ((byte[]) invocation.getArgument(2)).clone());
                return null;
              })
          .when(blobs)
          .putBytes(any(), any(), any(), eq(Retention.PERMANENT));
      when(blobs.getBytes(any(), any()))
          .thenAnswer(invocation -> Optional.ofNullable(objects.get(invocation.getArgument(1))));
      doAnswer(
              invocation -> {
                objects.put(
                    invocation.getArgument(1),
                    ((String) invocation.getArgument(2))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return null;
              })
          .when(blobs)
          .put(any(), any(), any(), any());
      when(blobs.getString(any(), any()))
          .thenAnswer(
              invocation ->
                  Optional.ofNullable(objects.get(invocation.getArgument(1)))
                      .map(bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
      inputs = new AssetLocalizeFanoutInput(fixture.jdbc, blobs, mapper);
      service = newService();
    }

    AssetLocalizeFanoutService newService() {
      return new AssetLocalizeFanoutService(
          store,
          inputs,
          mock(PollableTaskService.class),
          taskBlobs,
          mock(AssetRepository.class),
          mock(RepositoryLocaleRepository.class),
          repair);
    }

    AssetLocalizeFanoutInput.Manifest manifest() {
      MultiLocalizedAssetBody input = new MultiLocalizedAssetBody();
      input.setAssetId(10L);
      input.setSourceContent("frozen source");
      return new AssetLocalizeFanoutInput.Manifest(
          1,
          input,
          List.of(
              new AssetLocalizeFanoutInput.Slot(11L, "de", null),
              new AssetLocalizeFanoutInput.Slot(12L, "fr", "fr"),
              new AssetLocalizeFanoutInput.Slot(13L, "ja", null)));
    }

    long register() {
      return store.register(inputs.stage(manifest()));
    }

    int childCount(long parent) {
      return fixture.jdbc.queryForObject(
          "SELECT COUNT(*) FROM pollable_task WHERE parent_task_id = ?", Integer.class, parent);
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final AsyncJobQueueJdbcDialect dialect;
    private final AtomicInteger inserts = new AtomicInteger();
    private final AtomicInteger enqueueSqlCalls = new AtomicInteger();
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
                ? "db/migration/V113__Async_Job_Queue.sql"
                : "db/postgresql/migration/V113__Async_Job_Queue.sql";
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

      try (Connection connection = dataSource.getConnection()) {
        ScriptUtils.executeSqlScript(
            connection,
            new ClassPathResource(
                dialect == AsyncJobQueueJdbcDialect.POSTGRESQL
                    ? "db/postgresql/migration/V123__Asset_Localize_Fanout.sql"
                    : dialect == AsyncJobQueueJdbcDialect.HSQL
                        ? "asset-localize-fanout-hsql.sql"
                        : "db/migration/V123__Asset_Localize_Fanout.sql"));
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
            public <T> T queryForObject(
                String sql, SqlParameterSource parameters, RowMapper<T> rowMapper) {
              enqueueSqlCalls.incrementAndGet();
              return super.queryForObject(sql, parameters, rowMapper);
            }

            @Override
            public int update(
                String sql, SqlParameterSource parameters, KeyHolder keys, String[] keyColumns) {
              enqueueSqlCalls.incrementAndGet();
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

    private void assertIndependentTaskAndQueueCounts(long taskId, AsyncJobId jobId, int expected) {
      // Bypass Spring's bound connection and the commit-fault wrapper for this MVCC observer.
      try (Connection observer = dataSource.getTargetDataSource().getConnection()) {
        ConnectionHolder caller =
            (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
        if (caller != null) {
          assertNotSame(
              DataSourceUtils.getTargetConnection(caller.getConnection()),
              DataSourceUtils.getTargetConnection(observer));
        }
        assertTrue(observer.getAutoCommit());
        observer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertRowCount(
            observer, "SELECT COUNT(*) FROM pollable_task WHERE id = ?", taskId, expected);
        assertRowCount(
            observer,
            "SELECT COUNT(*) FROM async_job_queue WHERE id = ?",
            Long.parseLong(jobId.value()),
            expected);
      } catch (SQLException failure) {
        throw new AssertionError(
            "Independent connection could not observe task/queue visibility", failure);
      }
    }

    private void assertRowCount(Connection connection, String sql, long id, int expected)
        throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(10);
        statement.setLong(1, id);
        try (ResultSet rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(sql, expected, rows.getInt(1));
          assertFalse(rows.next());
        }
      }
    }

    private void assertNoBoundResources() {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
      assertFalse(TransactionSynchronizationManager.hasResource(factory));
      assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
      assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
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
}
