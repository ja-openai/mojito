package example.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.queue.AsyncJobHandler;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobPermanentFailureException;
import com.box.l10n.mojito.queue.AsyncJobQueueConfiguration;
import com.box.l10n.mojito.queue.AsyncJobQueueCoordinator;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService;
import com.box.l10n.mojito.queue.AsyncJobQueueProperties;
import com.box.l10n.mojito.queue.AsyncJobQueueSubmissionService;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.AsyncJobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Public consumer contract: no package-private queue types or Mojito application configuration. */
public class AsyncJobQueueExternalBootstrapTest {

  @Test
  public void disabledBootstrapRequiresNoDatabaseOrExecutionInfrastructure() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(ConsumerConfiguration.class);
      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobStore.class)).isEmpty();
      assertThat(context.getBeansOfType(AsyncJobQueueCoordinator.class)).isEmpty();
      assertThat(context.getBeansOfType(AsyncJobQueueSubmissionService.class)).isEmpty();
      assertThat(context.getBeansOfType(AsyncJobQueueInspectionService.class)).isEmpty();
      assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
      assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
    }
  }

  @Test
  public void jdbcBootstrapRequiresExplicitTransactionManager() {
    DataSource dataSource = mock(DataSource.class);
    try (AnnotationConfigApplicationContext context = context("mysql")) {
      context.registerBean(
          NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource));

      assertThatThrownBy(context::refresh)
          .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
          .hasStackTraceContaining(PlatformTransactionManager.class.getName());
      verifyNoInteractions(dataSource);
    }
  }

  @Test
  public void jdbcBootstrapRejectsAManagerForAnotherDataSourceBeforeAccess() {
    DataSource dataSource = mock(DataSource.class);
    DataSource unrelated = mock(DataSource.class);
    try (AnnotationConfigApplicationContext context = context("mysql")) {
      registerDatabase(context, dataSource, new DataSourceTransactionManager(unrelated));

      assertThatThrownBy(context::refresh)
          .hasRootCauseInstanceOf(IllegalArgumentException.class)
          .rootCause()
          .hasMessageContaining("same DataSource");
      verifyNoInteractions(dataSource, unrelated);
    }
  }

  @Test
  public void invalidRuntimeSettingsFailBeforeDatabaseAccess() {
    DataSource dataSource = mock(DataSource.class);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (AnnotationConfigApplicationContext context = context("mysql")) {
      registerDatabase(context, dataSource, new DataSourceTransactionManager(dataSource));
      registerExecution(context, scheduler, registry);
      context.registerBean("handler", AsyncJobHandler.class, () -> handler("example", null, null));
      TestPropertyValues.of("l10n.org.async-job-queue.queues.example.max-concurrency=0")
          .applyTo(context);

      assertThatThrownBy(context::refresh).hasRootCauseMessage("maxConcurrency must be > 0");
      verifyNoInteractions(dataSource, scheduler);
    } finally {
      registry.close();
    }
  }

  @Test
  public void partialStartupFailureCancelsPreviouslyScheduledPolls() {
    DataSource dataSource = mock(DataSource.class);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    ScheduledFuture<?> initialPoll = mock(ScheduledFuture.class);
    when(scheduler.schedule(any(Runnable.class), any(Date.class)))
        .thenAnswer(invocation -> initialPoll)
        .thenThrow(new RejectedExecutionException("second queue rejected"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (AnnotationConfigApplicationContext context = context("mysql")) {
      registerDatabase(context, dataSource, new DataSourceTransactionManager(dataSource));
      registerExecution(context, scheduler, registry);
      context.registerBean(
          "firstHandler", AsyncJobHandler.class, () -> handler("first", null, null));
      context.registerBean(
          "secondHandler", AsyncJobHandler.class, () -> handler("second", null, null));

      assertThatThrownBy(context::refresh).hasRootCauseMessage("second queue rejected");
      verify(initialPoll).cancel(false);
      assertThat(registry.find("asyncJobQueue.inflight").gauges()).isEmpty();
      verifyNoInteractions(dataSource);
    } finally {
      registry.close();
    }
  }

  @Test
  public void mysqlConsumerRunsWithoutMojitoApplication() throws Exception {
    assumeContainerTestsEnabled();
    try (MySQLContainer<?> container =
        new MySQLContainer<>("mysql:8.4")
            .withConnectTimeoutSeconds(10)
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "30000")) {
      assertConsumerContract(container, "mysql", "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void postgresqlConsumerRunsWithoutMojitoApplication() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>("postgres:16")
            .withConnectTimeoutSeconds(10)
            .withUrlParam("connectTimeout", "5")
            .withUrlParam("socketTimeout", "30")) {
      assertConsumerContract(
          container, "postgresql", "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
  }

  private void assertConsumerContract(
      JdbcDatabaseContainer<?> container, String dialect, String migration) throws Exception {
    container.start();
    try (Connection connection = container.createConnection("")) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
    }
    DataSource dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE TABLE consumer_transaction_marker (id BIGINT PRIMARY KEY)");
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.initialize();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Set<Thread> handlerThreads = ConcurrentHashMap.newKeySet();
    AtomicBoolean handlerSawTransaction = new AtomicBoolean();
    AtomicReference<AsyncJobRecord> permanentFailureRecord = new AtomicReference<>();
    AtomicReference<Throwable> permanentFailureCause = new AtomicReference<>();
    CountDownLatch failedCallback = new CountDownLatch(1);
    try (AutoCloseable infrastructure =
            () -> {
              try {
                scheduler.shutdown();
                assertThat(
                        scheduler
                            .getScheduledThreadPoolExecutor()
                            .awaitTermination(10, TimeUnit.SECONDS))
                    .isTrue();
                for (Thread thread : handlerThreads) {
                  thread.join(5_000);
                  assertThat(thread.isAlive()).isFalse();
                }
              } finally {
                registry.close();
              }
            };
        AnnotationConfigApplicationContext context = context(dialect)) {
      registerDatabase(context, dataSource, transactionManager);
      registerExecution(context, scheduler, registry);
      AsyncJobHandler successHandler = handler("example", handlerThreads, handlerSawTransaction);
      context.registerBean(
          "handler",
          AsyncJobHandler.class,
          () ->
              new AsyncJobHandler() {
                @Override
                public String queueName() {
                  return successHandler.queueName();
                }

                @Override
                public AsyncJobHandlerResult process(AsyncJobRecord record) throws Exception {
                  AsyncJobHandlerResult result = successHandler.process(record);
                  if (record.jobData().equals("permanent-failure")) {
                    throw new AsyncJobPermanentFailureException("consumer rejected business task");
                  }
                  return result;
                }

                @Override
                public void onJobFailedPermanently(
                    AsyncJobRecord record, Throwable failure, String lastError) {
                  permanentFailureRecord.set(record);
                  permanentFailureCause.set(failure);
                  failedCallback.countDown();
                }
              });
      context.refresh();
      AsyncJobQueueCoordinator coordinator = context.getBean(AsyncJobQueueCoordinator.class);
      AsyncJobQueueSubmissionService submission =
          context.getBean(AsyncJobQueueSubmissionService.class);
      AsyncJobStore store = context.getBean(AsyncJobStore.class);
      AsyncJobQueueProperties properties = context.getBean(AsyncJobQueueProperties.class);
      assertThat(properties.getStore()).isEqualTo("jdbc");
      assertThat(properties.getJdbcDialect()).isEqualTo(dialect);
      assertThat(properties.getQueues().get("example").getMaxConcurrency()).isEqualTo(1);
      assertThat(coordinator.isRunning()).isTrue();

      AtomicReference<AsyncJobId> admitted = new AtomicReference<>();
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(
              status -> {
                jdbc.update("INSERT INTO consumer_transaction_marker (id) VALUES (1)");
                admitted.set(submission.enqueueNow("example", "opaque-input"));
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                status.setRollbackOnly();
              });
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM consumer_transaction_marker", Integer.class))
          .isZero();
      awaitDone(store, admitted.get());
      assertThat(
              context
                  .getBean(AsyncJobQueueInspectionService.class)
                  .getJob("example", admitted.get().value())
                  .status())
          .isEqualTo("done");
      assertThat(handlerSawTransaction.get()).isFalse();

      AsyncJobId permanentId = submission.enqueueNow("example", "permanent-failure");
      assertThat(failedCallback.await(5, TimeUnit.SECONDS)).isTrue();
      AsyncJobRecord failed = store.getByIds(List.of(permanentId)).getFirst();
      assertThat(failed.status()).isEqualTo(AsyncJobStatus.FAILED);
      assertThat(failed.attemptCount()).isEqualTo(1);
      assertThat(failed.jobData()).isEqualTo("permanent-failure");
      assertThat(failed.lastError())
          .isEqualTo(
              AsyncJobPermanentFailureException.class.getName()
                  + ": consumer rejected business task");
      assertThat(failed.workerId()).isNull();
      assertThat(failed.leaseToken()).isNull();
      assertThat(failed.leaseUntil()).isNull();
      assertThat(permanentFailureRecord.get().id()).isEqualTo(permanentId);
      assertThat(permanentFailureRecord.get().status()).isEqualTo(AsyncJobStatus.FAILED);
      assertThat(permanentFailureCause.get())
          .isExactlyInstanceOf(AsyncJobPermanentFailureException.class);
      assertThat(handlerSawTransaction.get()).isFalse();

      coordinator.stop();
      assertThat(coordinator.isRunning()).isFalse();
      assertThat(scheduler.getScheduledThreadPoolExecutor().isShutdown()).isFalse();
      assertThat(scheduler.schedule(() -> {}, Instant.now()).get(5, TimeUnit.SECONDS)).isNull();
      assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
      registry.counter("consumer.still.usable").increment();
      assertThat(registry.get("consumer.still.usable").counter().count()).isEqualTo(1);
      assertThat(registry.find("asyncJobQueue.inflight").gauges()).isEmpty();

      coordinator.start();
      awaitDone(store, submission.enqueueNow("example", "after-restart"));
      assertThat(coordinator.isRunning()).isTrue();
    }
  }

  private static AnnotationConfigApplicationContext context(String dialect) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(
            "l10n.org.async-job-queue.enabled=true",
            "l10n.org.async-job-queue.store=jdbc",
            "l10n.org.async-job-queue.jdbc-dialect=" + dialect,
            "l10n.org.async-job-queue.queues.example.max-concurrency=1",
            "l10n.org.async-job-queue.queues.example.claim-batch-size=1",
            "l10n.org.async-job-queue.queues.example.poll-interval-ms=10",
            "l10n.org.async-job-queue.queues.example.max-poll-interval-ms=50",
            "l10n.org.async-job-queue.queues.example.lease-duration-ms=30000",
            "l10n.org.async-job-queue.queues.example.heartbeat-interval-ms=1000",
            "l10n.org.async-job-queue.queues.example.shutdown-await-termination-ms=5000")
        .applyTo(context);
    context.register(ConsumerConfiguration.class);
    return context;
  }

  private static void registerDatabase(
      AnnotationConfigApplicationContext context,
      DataSource dataSource,
      DataSourceTransactionManager transactionManager) {
    context.registerBean(
        NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource));
    context.registerBean(DataSourceTransactionManager.class, () -> transactionManager);
  }

  private static void registerExecution(
      AnnotationConfigApplicationContext context,
      TaskScheduler scheduler,
      SimpleMeterRegistry registry) {
    context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
    context.getBeanFactory().registerSingleton("meterRegistry", registry);
  }

  private static AsyncJobHandler handler(
      String queueName, Set<Thread> threads, AtomicBoolean sawTransaction) {
    return new AsyncJobHandler() {
      @Override
      public String queueName() {
        return queueName;
      }

      @Override
      public AsyncJobHandlerResult process(AsyncJobRecord record) {
        if (threads != null) {
          threads.add(Thread.currentThread());
          if (TransactionSynchronizationManager.isActualTransactionActive()) {
            sawTransaction.set(true);
          }
        }
        return AsyncJobHandlerResult.done("opaque-output");
      }
    };
  }

  private static void awaitDone(AsyncJobStore store, AsyncJobId id) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    AsyncJobRecord record;
    do {
      record = store.getByIds(List.of(id)).getFirst();
      if (record.status() == AsyncJobStatus.DONE) {
        assertThat(record.attemptCount()).isEqualTo(1);
        assertThat(record.jobData()).isEqualTo("opaque-output");
        return;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    assertThat(record.status()).isEqualTo(AsyncJobStatus.DONE);
  }

  private static void assumeContainerTestsEnabled() {
    assumeTrue(
        "Set -Dmojito.asyncJobQueue.testcontainers=true for real database checks",
        Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AsyncJobQueueProperties.class)
  @Import({
    AsyncJobQueueConfiguration.class,
    AsyncJobQueueCoordinator.class,
    AsyncJobQueueSubmissionService.class,
    AsyncJobQueueInspectionService.class
  })
  static class ConsumerConfiguration {}
}
