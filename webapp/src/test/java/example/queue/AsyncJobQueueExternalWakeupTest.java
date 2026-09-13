package example.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.queue.AsyncJobHandler;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobQueueSubmissionService;
import com.box.l10n.mojito.queue.AsyncJobQueueWakeupConfiguration;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.AsyncJobStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.ref.Reference;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** Optional wakeups through public configuration, with independent producer/consumer contexts. */
public class AsyncJobQueueExternalWakeupTest {

  @Test
  public void transactionAwareProducerHintPreservesCallerCommitAndRollback() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgres()) {
      DataSource dataSource = startDatabase(container);
      JdbcTemplate business = new JdbcTemplate(dataSource);
      business.execute("CREATE TABLE business_marker (id INTEGER PRIMARY KEY)");
      // A distinct DataSource prevents these observations from joining the caller's transaction.
      JdbcTemplate observer =
          new JdbcTemplate(
              new DriverManagerDataSource(
                  container.getJdbcUrl(), container.getUsername(), container.getPassword()));
      DataSource proxy = new TransactionAwareDataSourceProxy(dataSource);
      try (Node producer = new Node(proxy)) {
        producer.enableWakeup(proxy);
        producer.registerHandler(false);
        producer.context.refresh();
        TransactionTemplate transaction =
            new TransactionTemplate(producer.context.getBean(DataSourceTransactionManager.class));
        for (boolean rollback : new boolean[] {true, false}) {
          AsyncJobId id =
              transaction.execute(
                  status -> {
                    business.update("INSERT INTO business_marker VALUES (1)");
                    AsyncJobId submitted = producer.submit();
                    assertThat(
                            observer.queryForObject(
                                "SELECT COUNT(*) FROM business_marker", Integer.class))
                        .as("notification must not commit the caller's write")
                        .isZero();
                    if (rollback) {
                      status.setRollbackOnly();
                    }
                    return submitted;
                  });
          assertThat(observer.queryForObject("SELECT COUNT(*) FROM business_marker", Integer.class))
              .isEqualTo(rollback ? 0 : 1);
          assertThat(producer.record(id).status()).isEqualTo(AsyncJobStatus.QUEUED);
        }
        assertThat(
                producer.counter(
                    "asyncJobQueue.wakeup.notify",
                    "queueName",
                    "example",
                    "provider",
                    "postgres",
                    "result",
                    "succeeded"))
            .isEqualTo(2);
        assertThat(
                producer.counter(
                    "asyncJobQueue.wakeup.notify",
                    "queueName",
                    "example",
                    "provider",
                    "postgres",
                    "result",
                    "failed"))
            .isZero();
        producer.assertNoConsumerResources();
      }
    }
  }

  @Test
  public void disabledOptionalWakeupRequiresNoInfrastructure() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(
          AsyncJobQueueExternalBootstrapTest.ConsumerConfiguration.class,
          AsyncJobQueueWakeupConfiguration.class);
      TestPropertyValues.of("l10n.org.async-job-queue.wakeup.mode=postgres-listen-notify")
          .applyTo(context);
      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobStore.class)).isEmpty();
      assertThat(context.getBeansOfType(AsyncJobQueueWakeupConfiguration.class)).isEmpty();
      assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }
  }

  @Test
  public void producerWithoutHandlerWakesIndependentPostgresConsumer() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgres()) {
      DataSource dataSource = startDatabase(container);
      try (Node consumer = new Node(dataSource);
          Node producer = new Node(dataSource)) {
        consumer.enableWakeup(dataSource);
        consumer.registerHandler(true);
        long startedAt = System.nanoTime();
        consumer.context.refresh();
        producer.enableWakeup(dataSource);
        producer.context.refresh();

        // Wait past the consumer's initial empty poll. The next periodic poll is a minute away.
        await(
            () ->
                consumer.counter("asyncJobQueue.poll.empty", "queueName", "example") >= 1
                    && consumer.gauge("asyncJobQueue.poll.scheduled") == 1
                    && consumer.gauge("asyncJobQueue.poll.active") == 0
                    && consumer.counter(
                            "asyncJobQueue.wakeup.listen",
                            "provider",
                            "postgres",
                            "result",
                            "connected")
                        >= 1);
        producer.assertNoConsumerResources();

        AsyncJobId id = producer.submit();
        await(() -> consumer.record(id).status() == AsyncJobStatus.DONE);
        await(
            () ->
                consumer.counter(
                        "asyncJobQueue.wakeup.received",
                        "queueName",
                        "example",
                        "provider",
                        "postgres",
                        "result",
                        "triggered")
                    == 1);
        assertThat(System.nanoTime() - startedAt).isLessThan(TimeUnit.SECONDS.toNanos(10));
        assertThat(consumer.record(id).attemptCount()).isEqualTo(1);
        assertThat(consumer.calls.get()).isEqualTo(1);
        assertThat(
                producer.counter(
                    "asyncJobQueue.wakeup.notify",
                    "queueName",
                    "example",
                    "provider",
                    "postgres",
                    "result",
                    "succeeded"))
            .isEqualTo(1);
        assertThat(
                producer.counter(
                    "asyncJobQueue.trigger.missed",
                    "queueName",
                    "example",
                    "reason",
                    "missingRuntime"))
            .isEqualTo(1);
        producer.assertNoConsumerResources();
      }
    }
  }

  @Test
  public void failedNotificationPreservesJobAfterDisabledProducerCloses() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container = postgres()) {
      DataSource dataSource = startDatabase(container);
      DataSource unavailableNotifications = mock(DataSource.class);
      when(unavailableNotifications.getConnection())
          .thenThrow(new SQLException("notification connection unavailable"));
      AsyncJobId id;
      try (Node producer = new Node(dataSource)) {
        producer.enableWakeup(unavailableNotifications);
        producer.registerHandler(false);
        producer.context.refresh();
        verifyNoInteractions(unavailableNotifications);
        producer.assertNoConsumerResources();

        id = producer.submit();
        assertThat(producer.record(id).status()).isEqualTo(AsyncJobStatus.QUEUED);
        assertThat(producer.record(id).attemptCount()).isZero();
        assertThat(
                producer.counter(
                    "asyncJobQueue.wakeup.notify",
                    "queueName",
                    "example",
                    "provider",
                    "postgres",
                    "result",
                    "failed"))
            .isEqualTo(1);
        assertThat(
                producer.counter(
                    "asyncJobQueue.enqueue", "queueName", "example", "result", "succeeded"))
            .isEqualTo(1);
        assertThat(
                producer.counter(
                    "asyncJobQueue.trigger.missed",
                    "queueName",
                    "example",
                    "reason",
                    "consumerDisabled"))
            .isEqualTo(1);
        producer.assertNoConsumerResources();
      }
      verify(unavailableNotifications).getConnection();
      verifyNoMoreInteractions(unavailableNotifications);

      // A later polling-only consumer recovers the row without a producer or delivered hint.
      try (Node consumer = new Node(dataSource)) {
        consumer.registerHandler(true);
        consumer.context.refresh();
        await(() -> consumer.record(id).status() == AsyncJobStatus.DONE);
        assertThat(consumer.record(id).attemptCount()).isEqualTo(1);
        assertThat(consumer.calls.get()).isEqualTo(1);
        assertThat(consumer.registry.find("asyncJobQueue.wakeup.listener.running").gauge())
            .isNull();
      }
    }
  }

  private static PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>("postgres:16")
        .withConnectTimeoutSeconds(10)
        .withUrlParam("connectTimeout", "5")
        .withUrlParam("socketTimeout", "30");
  }

  private static DataSource startDatabase(PostgreSQLContainer<?> container) throws Exception {
    container.start();
    try (Connection connection = container.createConnection("")) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/postgresql/migration/V113__Async_Job_Queue.sql"));
    }
    return new DriverManagerDataSource(
        container.getJdbcUrl(), container.getUsername(), container.getPassword());
  }

  private static void assumeContainerTestsEnabled() {
    assumeTrue(
        "Set -Dmojito.asyncJobQueue.testcontainers=true for real database checks",
        Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static final class Node implements AutoCloseable {
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final AtomicInteger calls = new AtomicInteger();
    final Set<Thread> handlerThreads = ConcurrentHashMap.newKeySet();

    Node(DataSource dataSource) {
      scheduler.setPoolSize(1);
      scheduler.setRemoveOnCancelPolicy(true);
      scheduler.initialize();
      context.register(AsyncJobQueueExternalBootstrapTest.ConsumerConfiguration.class);
      context.registerBean(
          NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource));
      context.registerBean(
          DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
      context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
      context.getBeanFactory().registerSingleton("meterRegistry", registry);
      TestPropertyValues.of(
              "l10n.org.async-job-queue.enabled=true",
              "l10n.org.async-job-queue.store=jdbc",
              "l10n.org.async-job-queue.jdbc-dialect=postgresql",
              "l10n.org.async-job-queue.queues.example.max-concurrency=1",
              "l10n.org.async-job-queue.queues.example.claim-batch-size=1",
              "l10n.org.async-job-queue.queues.example.poll-interval-ms=60000",
              "l10n.org.async-job-queue.queues.example.max-poll-interval-ms=60000",
              "l10n.org.async-job-queue.queues.example.poll-jitter-percent=0",
              "l10n.org.async-job-queue.queues.example.shutdown-await-termination-ms=5000")
          .applyTo(context);
    }

    void enableWakeup(DataSource dataSource) {
      context.register(AsyncJobQueueWakeupConfiguration.class);
      context.registerBean(DataSource.class, () -> dataSource);
      TestPropertyValues.of(
              "l10n.org.async-job-queue.wakeup.mode=postgres-listen-notify",
              "l10n.org.async-job-queue.wakeup.postgres-listen-timeout-ms=100",
              "l10n.org.async-job-queue.wakeup.trigger-jitter-ms=0")
          .applyTo(context);
    }

    void registerHandler(boolean enabled) {
      TestPropertyValues.of("l10n.org.async-job-queue.queues.example.consumer-enabled=" + enabled)
          .applyTo(context);
      context.registerBean(
          AsyncJobHandler.class,
          () ->
              new AsyncJobHandler() {
                @Override
                public String queueName() {
                  return "example";
                }

                @Override
                public AsyncJobHandlerResult process(AsyncJobRecord record) {
                  handlerThreads.add(Thread.currentThread());
                  calls.incrementAndGet();
                  return AsyncJobHandlerResult.done("opaque-output");
                }
              });
    }

    AsyncJobId submit() {
      return context
          .getBean(AsyncJobQueueSubmissionService.class)
          .enqueueNow("example", "opaque-input");
    }

    AsyncJobRecord record(AsyncJobId id) {
      return context.getBean(AsyncJobStore.class).getByIds(List.of(id)).getFirst();
    }

    double counter(String name, String... tags) {
      Counter counter = registry.find(name).tags(tags).counter();
      return counter == null ? 0 : counter.count();
    }

    double gauge(String name) {
      return registry.get(name).gauge().value();
    }

    void assertNoConsumerResources() {
      assertThat(calls.get()).isZero();
      assertThat(scheduler.getScheduledThreadPoolExecutor().getTaskCount()).isZero();
      assertThat(registry.find("asyncJobQueue.inflight").gauges()).isEmpty();
      assertThat(gauge("asyncJobQueue.wakeup.listener.running")).isZero();
      assertThat(gauge("asyncJobQueue.wakeup.listener.connected")).isZero();
      assertThat(gauge("asyncJobQueue.wakeup.listener.threadAlive")).isZero();
    }

    @Override
    public void close() throws Exception {
      List<SmartLifecycle> lifecycleBeans = List.of();
      try {
        if (context.isActive()) {
          // Gauges use weak references after the context releases its singletons.
          lifecycleBeans = List.copyOf(context.getBeansOfType(SmartLifecycle.class).values());
        }
        context.close();
        if (registry.find("asyncJobQueue.wakeup.listener.threadAlive").gauge() != null) {
          await(() -> gauge("asyncJobQueue.wakeup.listener.threadAlive") == 0);
          assertThat(gauge("asyncJobQueue.wakeup.listener.connected")).isZero();
        }
      } finally {
        Reference.reachabilityFence(lifecycleBeans);
        try {
          scheduler.shutdown();
          assertThat(
                  scheduler.getScheduledThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS))
              .isTrue();
          for (Thread thread : handlerThreads) {
            thread.join(5_000);
            assertThat(thread.isAlive()).isFalse();
          }
        } finally {
          registry.close();
        }
      }
    }
  }
}
