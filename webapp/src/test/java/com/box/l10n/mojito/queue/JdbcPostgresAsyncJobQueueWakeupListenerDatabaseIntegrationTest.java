package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
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
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

public class JdbcPostgresAsyncJobQueueWakeupListenerDatabaseIntegrationTest {

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
          awaitConnected(registry, cycle);
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

  private void awaitConnected(SimpleMeterRegistry registry, int expectedCount)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var counter =
          registry.find("asyncJobQueue.wakeup.listen").tag("result", "connected").counter();
      if (counter != null && counter.count() >= expectedCount) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("Listener did not subscribe");
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
