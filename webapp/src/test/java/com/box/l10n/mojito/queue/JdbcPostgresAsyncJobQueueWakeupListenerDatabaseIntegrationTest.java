package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.containers.PostgreSQLContainer;

public class JdbcPostgresAsyncJobQueueWakeupListenerDatabaseIntegrationTest {

  @Test(timeout = 120_000)
  public void restartedDatabaseResubscribesTheSameListenerAndDeliversNewHints() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("connectTimeout", "1")
            .withUrlParam("socketTimeout", "2")) {
      int hostPort = DatabaseRestartTestSupport.startWithStablePort(postgres, 5432);
      String applicationName = "async-job-wakeup-database-restart-test";
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(postgres.getJdbcUrl());
      config.setUsername(postgres.getUsername());
      config.setPassword(postgres.getPassword());
      config.setMaximumPoolSize(1);
      config.setConnectionTimeout(250);
      config.setValidationTimeout(250);
      config.addDataSourceProperty("ApplicationName", applicationName);
      DriverManagerDataSource notifierDataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      try (Connection connection = notifierDataSource.getConnection()) {
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("db/postgresql/migration/V113__Async_Job_Queue.sql"));
      }
      HikariConfig workerConfig = new HikariConfig();
      workerConfig.setJdbcUrl(postgres.getJdbcUrl());
      workerConfig.setUsername(postgres.getUsername());
      workerConfig.setPassword(postgres.getPassword());
      workerConfig.setMaximumPoolSize(2);
      workerConfig.setConnectionTimeout(250);
      workerConfig.setValidationTimeout(250);
      ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
      scheduler.setPoolSize(1);
      scheduler.setAwaitTerminationMillis(5_000);
      scheduler.setThreadNamePrefix("listener-restart-poll-");
      SimpleMeterRegistry registry = new SimpleMeterRegistry();
      try (AutoCloseable registryCleanup = registry::close;
          HikariDataSource pool = new HikariDataSource(config);
          HikariDataSource workerPool = new HikariDataSource(workerConfig);
          AutoCloseable schedulerCleanup = scheduler::shutdown) {
        scheduler.initialize();
        JdbcAsyncJobStore store =
            new JdbcAsyncJobStore(
                new NamedParameterJdbcTemplate(workerPool),
                AsyncJobQueueJdbcDialect.POSTGRESQL,
                new DataSourceTransactionManager(workerPool));
        List<AsyncJobRecord> completed = new CopyOnWriteArrayList<>();
        AsyncJobHandler handler =
            new AsyncJobHandler() {
              @Override
              public String queueName() {
                return "listener-restart";
              }

              @Override
              public AsyncJobHandlerResult process(AsyncJobRecord job) {
                return AsyncJobHandlerResult.done(job.jobData() + " output");
              }

              @Override
              public void onJobDone(AsyncJobRecord job, AsyncJobHandlerResult result) {
                completed.add(job);
              }
            };
        AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
        properties.setStore("jdbc");
        AsyncJobQueueProperties.QueueSettings queueSettings =
            new AsyncJobQueueProperties.QueueSettings();
        queueSettings.setMaxConcurrency(1);
        queueSettings.setClaimBatchSize(1);
        queueSettings.setPollIntervalMs(25);
        queueSettings.setMaxPollIntervalMs(100);
        queueSettings.setPollJitterPercent(0);
        queueSettings.setShutdownAwaitTerminationMs(5_000);
        properties.getQueues().put(handler.queueName(), queueSettings);
        // The spy observes delivered hints; all coordinator/runtime/store behavior remains real.
        AsyncJobQueueCoordinator coordinator =
            spy(
                new AsyncJobQueueCoordinator(
                    store, properties, List.of(handler), scheduler, registry));
        AsyncJobQueueProperties.WakeupSettings settings =
            new AsyncJobQueueProperties.WakeupSettings();
        settings.setPostgresListenTimeoutMs(100);
        settings.setTriggerJitterMs(0);
        settings.setReconnectDelayMs(100);
        settings.setReconnectJitterPercent(0);
        JdbcPostgresAsyncJobQueueWakeupListener listener =
            new JdbcPostgresAsyncJobQueueWakeupListener(pool, settings, coordinator, registry);
        JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
            new JdbcPostgresAsyncJobQueueWakeupNotifier(
                notifierDataSource, settings.getPostgresChannel(), registry);
        int replacementPid;
        try {
          coordinator.start();
          listener.start();
          Instant previousStart;
          try (Connection admin = notifierDataSource.getConnection()) {
            awaitListeningSession(admin, applicationName, settings.getPostgresChannel(), 0);
            previousStart = postmasterStartedAt(admin);
          }
          AsyncJobId before = store.enqueueNow(handler.queueName(), "before restart");
          notifier.notifyJobAvailable(handler.queueName(), before);
          verify(coordinator, timeout(5_000)).triggerPollNow(handler.queueName());
          await("first job completes", () -> completed.size() == 1);
          assertCompletedJob(store, completed.getFirst(), before, "before restart output");
          assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
          Counter previousPollFailures = registry.find("asyncJobQueue.poll.failed").counter();
          double failureCount = previousPollFailures == null ? 0 : previousPollFailures.count();

          postgres
              .getDockerClient()
              .killContainerCmd(postgres.getContainerId())
              .withSignal("KILL")
              .exec();
          awaitListenResult(registry, "failed", 1);
          awaitDatabaseStopped(postgres);
          await(
              "the running worker also observes the database outage",
              () -> {
                Counter failures = registry.find("asyncJobQueue.poll.failed").counter();
                return failures != null && failures.count() > failureCount;
              });
          assertThat(listener.isRunning()).isTrue();
          assertThat(registry.get("asyncJobQueue.wakeup.listener.threadAlive").gauge().value())
              .isEqualTo(1);

          postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec();
          DatabaseRestartTestSupport.assertStablePort(postgres, 5432, hostPort);
          awaitListenResult(registry, "connected", 2);
          try (Connection admin = notifierDataSource.getConnection()) {
            assertThat(postmasterStartedAt(admin)).isAfter(previousStart);
            // A restarted server may reuse backend PIDs; observe its committed LISTEN anew.
            replacementPid =
                awaitListeningSession(admin, applicationName, settings.getPostgresChannel(), 0);
          }
          await(
              "the existing worker pool reconnects",
              () -> {
                try (Connection connection = workerPool.getConnection()) {
                  return connection.isValid(1);
                } catch (SQLException unavailable) {
                  return false;
                }
              });
          AsyncJobId after = store.enqueueNow(handler.queueName(), "after restart");
          notifier.notifyJobAvailable(handler.queueName(), after);
          verify(coordinator, timeout(5_000).times(2)).triggerPollNow(handler.queueName());
          await("the same worker completes new work after restart", () -> completed.size() == 2);
          assertCompletedJob(store, completed.get(1), after, "after restart output");
          assertCompletedJob(store, completed.getFirst(), before, "before restart output");
          assertThat(listener.isRunning()).isTrue();
          assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);

          listener.stop();
          awaitStopped(registry);
          AsyncJobId withoutHint = store.enqueueNow(handler.queueName(), "without listener");
          await("polling still works without a listener or hint", () -> completed.size() == 3);
          assertCompletedJob(store, completed.get(2), withoutHint, "without listener output");
          verify(coordinator, timeout(5_000).times(2)).triggerPollNow(handler.queueName());
          await(
              "the runtime releases handler and executor capacity",
              () ->
                  registry.get("asyncJobQueue.inflight").gauge().value() == 0
                      && registry.get("asyncJobQueue.executor.active").gauge().value() == 0
                      && registry.get("asyncJobQueue.executor.queued").gauge().value() == 0
                      && registry.get("asyncJobQueue.processing.latency").timer().count() == 3);
        } finally {
          try {
            listener.stop();
            awaitStopped(registry);
          } finally {
            coordinator.stop();
          }
        }

        assertThat(coordinator.isRunning()).isFalse();
        assertThat(workerPool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(workerPool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
        assertThat(registry.find("asyncJobQueue.inflight").gauge()).isNull();
        assertThat(completed).hasSize(3);
        assertThat(registry.get("asyncJobQueue.wakeup.listener.connected").gauge().value())
            .isZero();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        try (Connection connection = pool.getConnection();
            Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(2);
          assertThat(backendPid(connection)).isEqualTo(replacementPid);
          assertThat(connection.getAutoCommit()).isTrue();
          try (ResultSet channels = statement.executeQuery("SELECT pg_listening_channels()")) {
            assertThat(channels.next()).as("returned restarted session must not LISTEN").isFalse();
          }
        }
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isZero();
      }
    }
  }

  private void assertCompletedJob(
      JdbcAsyncJobStore store, AsyncJobRecord callback, AsyncJobId id, String output) {
    assertThat(callback.id()).isEqualTo(id);
    assertThat(callback.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(callback.attemptCount()).isEqualTo(1);
    assertThat(callback.jobData()).isEqualTo(output);
    AsyncJobRecord persisted = store.getByIds(List.of(id)).getFirst();
    assertThat(persisted.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(persisted.attemptCount()).isEqualTo(1);
    assertThat(persisted.jobData()).isEqualTo(output);
  }

  private void await(String description, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  private Instant postmasterStartedAt(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(2);
      try (ResultSet result = statement.executeQuery("SELECT pg_postmaster_start_time()")) {
        assertThat(result.next()).isTrue();
        return result.getTimestamp(1).toInstant();
      }
    }
  }

  private void awaitDatabaseStopped(PostgreSQLContainer<?> postgres) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (!postgres.isRunning()) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("Database did not stop");
  }

  @Test
  public void stoppedListenerReturnsUnsubscribedSessionToPool() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("connectTimeout", "5")
            .withUrlParam("socketTimeout", "30")) {
      postgres.start();
      for (boolean autoCommit : List.of(true, false)) {
        assertPooledSessionCleanup(postgres, autoCommit);
      }
    }
  }

  @Test
  public void terminatedPooledSessionReconnectsDespiteFailureCounterError() throws Exception {
    assumeTrue(Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
    try (PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("connectTimeout", "5")
            .withUrlParam("socketTimeout", "30")) {
      postgres.start();
      String applicationName = "async-job-wakeup-reconnect-test";
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(postgres.getJdbcUrl());
      config.setUsername(postgres.getUsername());
      config.setPassword(postgres.getPassword());
      config.setMaximumPoolSize(1);
      config.setConnectionTimeout(5_000);
      config.addDataSourceProperty("ApplicationName", applicationName);
      DriverManagerDataSource notifierDataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      FailingListenCounterRegistry registry = new FailingListenCounterRegistry();
      try (HikariDataSource pool = new HikariDataSource(config);
          Connection admin = notifierDataSource.getConnection()) {
        AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
        when(coordinator.hasEnabledConsumers()).thenReturn(true);
        AsyncJobQueueProperties.WakeupSettings settings =
            new AsyncJobQueueProperties.WakeupSettings();
        settings.setPostgresListenTimeoutMs(100);
        settings.setTriggerJitterMs(0);
        settings.setReconnectDelayMs(100);
        settings.setReconnectJitterPercent(0);
        JdbcPostgresAsyncJobQueueWakeupListener listener =
            new JdbcPostgresAsyncJobQueueWakeupListener(pool, settings, coordinator, registry);
        JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
            new JdbcPostgresAsyncJobQueueWakeupNotifier(
                notifierDataSource, settings.getPostgresChannel(), registry);
        int replacementPid;
        try {
          listener.start();
          int originalPid =
              awaitListeningSession(admin, applicationName, settings.getPostgresChannel(), 0);
          assertThat(originalPid).isNotEqualTo(backendPid(admin));
          assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
          notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42"));
          verify(coordinator, timeout(5_000)).triggerPollNow("assetlocalize");

          try (PreparedStatement terminate =
              admin.prepareStatement("SELECT pg_terminate_backend(?)")) {
            terminate.setQueryTimeout(5);
            terminate.setInt(1, originalPid);
            try (ResultSet result = terminate.executeQuery()) {
              assertThat(result.next()).isTrue();
              assertThat(result.getBoolean(1)).as("listener backend must be terminated").isTrue();
            }
          }

          replacementPid =
              awaitListeningSession(
                  admin, applicationName, settings.getPostgresChannel(), originalPid);
          assertThat(replacementPid).isNotEqualTo(originalPid);
          notifier.notifyJobAvailable("repo-stats", new AsyncJobId("43"));
          verify(coordinator, timeout(5_000)).triggerPollNow("repo-stats");
          assertThat(registry.failureCounterAttempted).isTrue();
          assertThat(listener.isRunning()).isTrue();
          assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
        } finally {
          listener.stop();
          awaitStopped(registry);
        }

        assertThat(listener.isRunning()).isFalse();
        assertThat(registry.get("asyncJobQueue.wakeup.listener.connected").gauge().value())
            .isZero();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        try (Connection connection = pool.getConnection();
            Statement statement = connection.createStatement()) {
          assertThat(backendPid(connection)).isEqualTo(replacementPid);
          assertThat(connection.getAutoCommit()).isTrue();
          try (ResultSet channels = statement.executeQuery("SELECT pg_listening_channels()")) {
            assertThat(channels.next())
                .as("returned replacement session must not LISTEN")
                .isFalse();
          }
        }
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
      } finally {
        registry.close();
      }
    }
  }

  private int awaitListeningSession(
      Connection admin, String applicationName, String channel, int previousPid) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    // Observe an idle session after LISTEN committed, independently of listener counters.
    try (PreparedStatement statement =
        admin.prepareStatement(
            "SELECT pid FROM pg_stat_activity WHERE application_name = ? AND pid <> ?"
                + " AND state = 'idle' AND query = ?")) {
      statement.setQueryTimeout(5);
      statement.setString(1, applicationName);
      statement.setInt(2, previousPid);
      statement.setString(
          3, "LISTEN " + JdbcPostgresAsyncJobQueueWakeupListener.quotedIdentifier(channel));
      while (System.nanoTime() < deadline) {
        try (ResultSet result = statement.executeQuery()) {
          if (result.next()) {
            int pid = result.getInt(1);
            assertThat(result.next()).as("only the one-slot pool may LISTEN").isFalse();
            return pid;
          }
        }
        TimeUnit.MILLISECONDS.sleep(10);
      }
    }
    throw new AssertionError("Listener did not LISTEN on a session other than PID " + previousPid);
  }

  private static class FailingListenCounterRegistry extends SimpleMeterRegistry {
    private final AtomicBoolean failureCounterAttempted = new AtomicBoolean();

    @Override
    protected Counter newCounter(Meter.Id id) {
      if (id.getName().equals("asyncJobQueue.wakeup.listen")
          && "failed".equals(id.getTag("result"))) {
        failureCounterAttempted.set(true);
        throw new IllegalStateException("test listen failure counter unavailable");
      }
      return super.newCounter(id);
    }
  }

  private void assertPooledSessionCleanup(PostgreSQLContainer<?> postgres, boolean autoCommit)
      throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername(postgres.getUsername());
    config.setPassword(postgres.getPassword());
    config.setMaximumPoolSize(1);
    config.setAutoCommit(autoCommit);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (HikariDataSource pool = new HikariDataSource(config)) {
      int backendPid;
      try (Connection connection = pool.getConnection()) {
        backendPid = backendPid(connection);
      }
      AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
      when(coordinator.hasEnabledConsumers()).thenReturn(true);
      AsyncJobQueueProperties.WakeupSettings settings =
          new AsyncJobQueueProperties.WakeupSettings();
      settings.setPostgresListenTimeoutMs(100);
      settings.setTriggerJitterMs(0);
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          new JdbcPostgresAsyncJobQueueWakeupListener(pool, settings, coordinator, registry);
      DriverManagerDataSource notifierDataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
          new JdbcPostgresAsyncJobQueueWakeupNotifier(
              notifierDataSource, settings.getPostgresChannel(), registry);
      for (int cycle = 1; cycle <= 2; cycle++) {
        try {
          listener.start();
          awaitListenResult(registry, "connected", cycle);
          notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42"));
          verify(coordinator, timeout(5_000).times(cycle)).triggerPollNow("assetlocalize");
        } finally {
          listener.stop();
          awaitStopped(registry);
        }

        try (Connection connection = pool.getConnection();
            Statement statement = connection.createStatement()) {
          assertThat(connection.getAutoCommit()).isEqualTo(autoCommit);
          assertThat(backendPid(connection)).isEqualTo(backendPid);
          try (ResultSet channels = statement.executeQuery("SELECT pg_listening_channels()")) {
            assertThat(channels.next()).as("returned session must no longer LISTEN").isFalse();
          }
        }
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
      }
    } finally {
      registry.close();
    }
  }

  private int backendPid(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private void awaitListenResult(SimpleMeterRegistry registry, String result, int expectedCount)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var counter = registry.find("asyncJobQueue.wakeup.listen").tag("result", result).counter();
      if (counter != null && counter.count() >= expectedCount) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("Listener did not record " + result + " " + expectedCount + " times");
  }

  private void awaitStopped(SimpleMeterRegistry registry) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (registry.get("asyncJobQueue.wakeup.listener.threadAlive").gauge().value() == 0) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("Listener did not stop");
  }
}
