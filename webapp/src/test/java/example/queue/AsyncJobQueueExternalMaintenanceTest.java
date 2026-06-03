package example.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.box.l10n.mojito.queue.AsyncJobQueueConfiguration;
import com.box.l10n.mojito.queue.AsyncJobQueueProperties;
import com.box.l10n.mojito.queue.AsyncJobQueueRetentionCleaner;
import com.box.l10n.mojito.queue.AsyncJobQueueStatusMetricsReporter;
import com.box.l10n.mojito.queue.AsyncJobStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.Test;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** Explicit public maintenance wiring, without Mojito scanning or a local job consumer. */
public class AsyncJobQueueExternalMaintenanceTest {

  @Test
  public void disabledQueueDoesNotScheduleMaintenanceOrRequireInfrastructure() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(MaintenanceConfiguration.class, SchedulingConfiguration.class);
      TestPropertyValues.of("l10n.org.async-job-queue.retention.enabled=true").applyTo(context);
      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobStore.class)).isEmpty();
      assertThat(context.getBeansOfType(AsyncJobQueueRetentionCleaner.class)).isEmpty();
      assertThat(context.getBeansOfType(AsyncJobQueueStatusMetricsReporter.class)).isEmpty();
      assertThat(scheduledTasks(context).getScheduledTasks()).isEmpty();
      assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
      assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
    }
  }

  @Test
  public void maintenanceImportDoesNotEnableSchedulingByItself() {
    DataSource dataSource = mock(DataSource.class);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (AnnotationConfigApplicationContext context = context("mysql", dataSource, registry)) {
      TestPropertyValues.of("l10n.org.async-job-queue.retention.enabled=true").applyTo(context);
      context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
      context.refresh();

      assertThat(context.getBean(AsyncJobQueueRetentionCleaner.class)).isNotNull();
      assertThat(context.getBean(AsyncJobQueueStatusMetricsReporter.class)).isNotNull();
      assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
      verifyNoInteractions(dataSource, scheduler);
    } finally {
      registry.close();
    }
  }

  @Test
  public void disableschedulingProfileSuppressesMaintenanceDespiteEnableFlags() {
    DataSource dataSource = mock(DataSource.class);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (AnnotationConfigApplicationContext context = context("mysql", dataSource, registry)) {
      context.getEnvironment().setActiveProfiles("disablescheduling");
      context.register(SchedulingConfiguration.class);
      context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
      TestPropertyValues.of("l10n.org.async-job-queue.retention.enabled=true").applyTo(context);
      context.refresh();

      assertThat(context.getBean(AsyncJobStore.class)).isNotNull();
      assertThat(context.getBeansOfType(AsyncJobQueueRetentionCleaner.class)).isEmpty();
      assertThat(context.getBeansOfType(AsyncJobQueueStatusMetricsReporter.class)).isEmpty();
      assertThat(scheduledTasks(context).getScheduledTasks()).isEmpty();
      verifyNoInteractions(dataSource, scheduler);
    } finally {
      registry.close();
    }
  }

  @Test
  public void mysqlMaintenanceIsExplicitBoundedAndIndependentOfConsumers() throws Exception {
    assumeContainerTestsEnabled();
    try (MySQLContainer<?> container =
        new MySQLContainer<>("mysql:8.4")
            .withConnectTimeoutSeconds(10)
            .withUrlParam("connectTimeout", "5000")
            .withUrlParam("socketTimeout", "30000")) {
      assertMaintenanceContract(container, "mysql", "db/migration/V109__Async_Job_Queue.sql");
    }
  }

  @Test
  public void postgresMaintenanceIsExplicitBoundedAndIndependentOfConsumers() throws Exception {
    assumeContainerTestsEnabled();
    try (PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>("postgres:16")
            .withConnectTimeoutSeconds(10)
            .withUrlParam("connectTimeout", "5")
            .withUrlParam("socketTimeout", "30")) {
      assertMaintenanceContract(
          container, "postgresql", "db/postgresql/migration/V109__Async_Job_Queue.sql");
    }
  }

  private static void assertMaintenanceContract(
      JdbcDatabaseContainer<?> container, String dialect, String migration) throws Exception {
    container.start();
    try (Connection connection = container.createConnection("")) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
    }
    DataSource dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    seed(jdbc, 1, "example", "done", true);
    seed(jdbc, 2, "example", "done", true);
    seed(jdbc, 3, "example", "done", true);
    seed(jdbc, 4, "example", "failed", true);
    seed(jdbc, 5, "example", "failed", true);
    seed(jdbc, 6, "example", "done", false);
    seed(jdbc, 7, "example", "failed", false);
    seed(jdbc, 8, "example", "queued", true);
    seed(jdbc, 9, "example", "queued", true);
    jdbc.update(
        "UPDATE async_job_queue SET status = 'running', attempt_count = 1, "
            + "lease_until = '2001-01-01 00:00:00', worker_id = 'fixture', lease_token = 'fixture', "
            + "updated_date = '2001-01-01 00:00:00' WHERE id = 9");
    seed(jdbc, 10, "other", "done", true);

    assertScheduledPass(dataSource, dialect, jdbc, false, 0);
    assertThat(ids(jdbc)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    assertScheduledPass(dataSource, dialect, jdbc, true, 1);
    assertThat(ids(jdbc)).containsExactly(2L, 3L, 5L, 6L, 7L, 8L, 9L, 10L);

    // Remove remaining eligible fixtures so batch limiting cannot mask missing retention filters.
    assertThat(jdbc.update("DELETE FROM async_job_queue WHERE id IN (2, 3, 5)")).isEqualTo(3);
    assertScheduledPass(dataSource, dialect, jdbc, true, 0);
    assertThat(ids(jdbc)).containsExactly(6L, 7L, 8L, 9L, 10L);
  }

  private static void assertScheduledPass(
      DataSource dataSource,
      String dialect,
      JdbcTemplate jdbc,
      boolean retentionEnabled,
      int expectedDeletedPerStatus)
      throws Exception {
    long doneBefore =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM async_job_queue WHERE queue_name = 'example' AND status = 'done'",
            Long.class);
    long failedBefore =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM async_job_queue WHERE queue_name = 'example' AND status = 'failed'",
            Long.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.initialize();
    try (AnnotationConfigApplicationContext context = context(dialect, dataSource, registry)) {
      context.register(SchedulingConfiguration.class);
      context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
      if (retentionEnabled) {
        TestPropertyValues.of("l10n.org.async-job-queue.retention.enabled=true").applyTo(context);
      }
      long startedAt = System.nanoTime();
      context.refresh();
      ScheduledAnnotationBeanPostProcessor scheduledTasks = scheduledTasks(context);
      assertThat(scheduledTasks.getScheduledTasks()).hasSize(retentionEnabled ? 2 : 1);
      assertThat(context.getBeansOfType(AsyncJobQueueRetentionCleaner.class))
          .hasSize(retentionEnabled ? 1 : 0);

      // One scheduler thread: this barrier runs after both immediately scheduled callbacks.
      scheduler.schedule(() -> {}, Instant.now()).get(5, TimeUnit.SECONDS);
      await(
          () ->
              gauge(registry, "asyncJobQueue.status", "status", "done")
                      == doneBefore - expectedDeletedPerStatus
                  && gauge(registry, "asyncJobQueue.status", "status", "failed")
                      == failedBefore - expectedDeletedPerStatus
                  && gauge(registry, "asyncJobQueue.status", "status", "queued") == 1
                  && gauge(registry, "asyncJobQueue.status", "status", "running") == 1
                  && gauge(registry, "asyncJobQueue.ready.count") == 1
                  && gauge(registry, "asyncJobQueue.running.expired.count") == 1);
      assertThat(counter(registry, "done")).isEqualTo(expectedDeletedPerStatus);
      assertThat(counter(registry, "failed")).isEqualTo(expectedDeletedPerStatus);
      assertThat(registry.find("asyncJobQueue.retention.failed").counters()).isEmpty();
      assertThat(registry.find("asyncJobQueue.statusMetrics.failed").counters()).isEmpty();
      if (expectedDeletedPerStatus == 0) {
        assertThat(registry.find("asyncJobQueue.retention.deleted").counters()).isEmpty();
      }
      assertThat(System.nanoTime() - startedAt).isLessThan(TimeUnit.SECONDS.toNanos(10));

      context.close();
      assertThat(scheduledTasks.getScheduledTasks()).isEmpty();
      assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
      assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    } finally {
      try {
        scheduler.shutdown();
        assertThat(
                scheduler.getScheduledThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS))
            .isTrue();
      } finally {
        registry.close();
      }
    }
  }

  private static AnnotationConfigApplicationContext context(
      String dialect, DataSource dataSource, SimpleMeterRegistry registry) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.register(MaintenanceConfiguration.class);
    context.registerBean(
        NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(dataSource));
    context.registerBean(
        DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
    context.getBeanFactory().registerSingleton("meterRegistry", registry);
    TestPropertyValues.of(
            "l10n.org.async-job-queue.enabled=true",
            "l10n.org.async-job-queue.store=jdbc",
            "l10n.org.async-job-queue.jdbc-dialect=" + dialect,
            "l10n.org.async-job-queue.queues.example.consumer-enabled=false",
            "l10n.org.async-job-queue.retention.interval-ms=3600000",
            "l10n.org.async-job-queue.retention.batch-size=1",
            "l10n.org.async-job-queue.status-metrics-interval-ms=10")
        .applyTo(context);
    return context;
  }

  private static void seed(JdbcTemplate jdbc, long id, String queue, String status, boolean old) {
    jdbc.update(
        "INSERT INTO async_job_queue (id, queue_name, status, available_at, job_data, last_error, "
            + "created_date, updated_date) VALUES (?, ?, ?, '2001-01-01 00:00:00', 'opaque-input', ?, "
            + "'2001-01-01 00:00:00', "
            + (old ? "'2001-01-01 00:00:00'" : "CURRENT_TIMESTAMP")
            + ")",
        id,
        queue,
        status,
        "failed".equals(status) ? "fixture failure" : null);
  }

  private static List<Long> ids(JdbcTemplate jdbc) {
    return jdbc.queryForList("SELECT id FROM async_job_queue ORDER BY id", Long.class);
  }

  private static double gauge(SimpleMeterRegistry registry, String name, String... tags) {
    Gauge gauge = registry.find(name).tag("queueName", "example").tags(tags).gauge();
    return gauge == null ? Double.NaN : gauge.value();
  }

  private static double counter(SimpleMeterRegistry registry, String status) {
    Counter counter =
        registry
            .find("asyncJobQueue.retention.deleted")
            .tags("queueName", "example", "status", status)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  private static ScheduledAnnotationBeanPostProcessor scheduledTasks(
      AnnotationConfigApplicationContext context) {
    return context.getBean(ScheduledAnnotationBeanPostProcessor.class);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
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
    AsyncJobQueueRetentionCleaner.class,
    AsyncJobQueueStatusMetricsReporter.class
  })
  static class MaintenanceConfiguration {}

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingConfiguration {}
}
