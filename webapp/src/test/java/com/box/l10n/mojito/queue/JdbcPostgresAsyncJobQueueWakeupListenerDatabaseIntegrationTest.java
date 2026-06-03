package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
