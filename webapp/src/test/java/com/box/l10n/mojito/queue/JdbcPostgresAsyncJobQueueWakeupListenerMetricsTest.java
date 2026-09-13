package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.postgresql.PGConnection;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(Parameterized.class)
public class JdbcPostgresAsyncJobQueueWakeupListenerMetricsTest {

  @Parameterized.Parameters(name = "registration={0}, assertionError={1}")
  public static List<Object[]> parameters() {
    return List.of(
        new Object[] {true, false}, new Object[] {false, false},
        new Object[] {true, true}, new Object[] {false, true});
  }

  private final boolean registrationFailure;
  private final boolean assertionError;

  public JdbcPostgresAsyncJobQueueWakeupListenerMetricsTest(
      boolean registrationFailure, boolean assertionError) {
    this.registrationFailure = registrationFailure;
    this.assertionError = assertionError;
  }

  @Test
  public void failedListenMetricCannotTerminateReconnectLoop() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    CountDownLatch reconnected = new CountDownLatch(1);
    CountDownLatch releaseConnection = new CountDownLatch(1);
    AtomicInteger connectionAttempts = new AtomicInteger();
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              if (connectionAttempts.incrementAndGet() > 1) {
                reconnected.countDown();
                awaitUninterruptibly(releaseConnection);
              }
              throw new SQLException("database unavailable");
            });
    try (FailingCounterRegistry registry = registry("asyncJobQueue.wakeup.listen")) {
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          listener(dataSource, enabledCoordinator(), registry);
      Thread thread = null;
      try {
        listener.start();
        thread = listenerThread(listener);
        assertThat(reconnected.await(2, TimeUnit.SECONDS)).as("reconnect attempted").isTrue();
        assertThat(listener.isRunning()).isTrue();
        assertThat(thread.isAlive()).isTrue();
        assertThat(registry.failures).hasValue(1);
      } finally {
        listener.stop();
        releaseConnection.countDown();
        join(thread);
      }
    }
  }

  @Test
  public void connectedMetricCannotDisconnectListeningSession() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PGConnection pgConnection = mock(PGConnection.class);
    Statement statement = mock(Statement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch releaseRead = new CountDownLatch(1);
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            invocation -> {
              reading.countDown();
              awaitUninterruptibly(releaseRead);
              return null;
            });
    try (FailingCounterRegistry registry = registry("asyncJobQueue.wakeup.listen")) {
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          listener(dataSource, enabledCoordinator(), registry);
      Thread thread = null;
      try {
        listener.start();
        thread = listenerThread(listener);
        assertThat(reading.await(2, TimeUnit.SECONDS))
            .as("subscribed session reads hints")
            .isTrue();
        assertThat(listener.isRunning()).isTrue();
        assertThat(registry.failures).hasValue(1);
        verify(dataSource).getConnection();
      } finally {
        listener.stop();
        releaseRead.countDown();
        join(thread);
      }
      verify(statement).execute("LISTEN \"mojito_async_job_queue\"");
      verify(statement).execute("UNLISTEN \"mojito_async_job_queue\"");
      verify(connection).close();
    }
  }

  @Test
  public void notificationMetricFailureDoesNotDiscardOtherQueues() throws Exception {
    AsyncJobQueueCoordinator coordinator = enabledCoordinator();
    try (FailingCounterRegistry registry = registry("asyncJobQueue.wakeup.received")) {
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          listener(mock(DataSource.class), coordinator, registry);

      listener.handleNotifications(
          new Object[] {
            notification("other_channel", "assetlocalize"),
            notification("mojito_async_job_queue", "invalid queue"),
            notification("mojito_async_job_queue", "assetlocalize"),
            notification("mojito_async_job_queue", "assetlocalize"),
            notification("mojito_async_job_queue", "repo-stats")
          });

      verify(coordinator).triggerPollNow("assetlocalize");
      verify(coordinator).triggerPollNow("repo-stats");
      assertThat(registry.failures).hasValue(5);
    }
  }

  @Test
  public void triggerFailureMetricDoesNotDiscardOtherQueues() throws Exception {
    AsyncJobQueueCoordinator coordinator = enabledCoordinator();
    doThrow(new IllegalStateException("scheduler unavailable"))
        .when(coordinator)
        .triggerPollNow("assetlocalize");
    try (FailingCounterRegistry registry = registry("asyncJobQueue.wakeup.received")) {
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          listener(mock(DataSource.class), coordinator, registry);

      listener.handleNotifications(
          new Object[] {
            notification("mojito_async_job_queue", "assetlocalize"),
            notification("mojito_async_job_queue", "repo-stats")
          });

      verify(coordinator).triggerPollNow("assetlocalize");
      verify(coordinator).triggerPollNow("repo-stats");
      assertThat(registry.failures).hasValue(2);
    }
  }

  @Test
  public void stopMetricCannotSkipCallbackOrAllowConcurrentListener() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    CountDownLatch connectionStarted = new CountDownLatch(1);
    CountDownLatch releaseConnection = new CountDownLatch(1);
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              connectionStarted.countDown();
              awaitUninterruptibly(releaseConnection);
              throw new SQLException("database unavailable");
            });
    try (FailingCounterRegistry registry = registry("asyncJobQueue.wakeup.listener.")) {
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          listener(dataSource, enabledCoordinator(), registry);
      Thread thread = null;
      try {
        listener.start();
        thread = listenerThread(listener);
        assertThat(connectionStarted.await(2, TimeUnit.SECONDS)).isTrue();
        AtomicInteger callbackCalls = new AtomicInteger();

        listener.stop(callbackCalls::incrementAndGet);

        assertThat(callbackCalls).hasValue(1);
        assertThat(listener.isRunning()).isFalse();
        assertThat(thread.isAlive()).isTrue();
        assertThatThrownBy(listener::start)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("previous listener thread is still stopping");
        assertThat(registry.failures).hasValue(2);
        verify(dataSource).getConnection();
      } finally {
        releaseConnection.countDown();
        listener.stop();
        join(thread);
      }
    }
  }

  private JdbcPostgresAsyncJobQueueWakeupListener listener(
      DataSource dataSource, AsyncJobQueueCoordinator coordinator, SimpleMeterRegistry registry) {
    AsyncJobQueueProperties.WakeupSettings settings = new AsyncJobQueueProperties.WakeupSettings();
    settings.setTriggerJitterMs(0);
    settings.setReconnectDelayMs(1);
    settings.setReconnectJitterPercent(0);
    settings.setPostgresListenTimeoutMs(1);
    return new JdbcPostgresAsyncJobQueueWakeupListener(dataSource, settings, coordinator, registry);
  }

  private AsyncJobQueueCoordinator enabledCoordinator() {
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    when(coordinator.hasEnabledConsumers()).thenReturn(true);
    return coordinator;
  }

  private JdbcPostgresAsyncJobQueueWakeupListenerTest.TestNotification notification(
      String channel, String payload) {
    return new JdbcPostgresAsyncJobQueueWakeupListenerTest.TestNotification(channel, payload);
  }

  private Thread listenerThread(JdbcPostgresAsyncJobQueueWakeupListener listener) {
    return (Thread) ReflectionTestUtils.getField(listener, "listenerThread");
  }

  private static void join(Thread thread) throws InterruptedException {
    if (thread != null) {
      thread.join(2_000);
      assertThat(thread.isAlive()).as("listener thread cleaned up").isFalse();
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      try {
        if (latch.await(10, TimeUnit.MILLISECONDS)) {
          return;
        }
      } catch (InterruptedException ignored) {
        // Simulate a JDBC socket call that stop cannot interrupt.
      }
    }
    throw new AssertionError("test did not release JDBC call");
  }

  private FailingCounterRegistry registry(String prefix) {
    return new FailingCounterRegistry(prefix);
  }

  private class FailingCounterRegistry extends SimpleMeterRegistry implements AutoCloseable {
    private final String prefix;
    private final Counter brokenCounter = mock(Counter.class);
    private final AtomicInteger failures = new AtomicInteger();

    FailingCounterRegistry(String prefix) {
      this.prefix = prefix;
      Throwable failure =
          assertionError
              ? new AssertionError("counter unavailable")
              : new IllegalStateException("counter unavailable");
      doThrow(failure).when(brokenCounter).increment();
    }

    @Override
    public Counter counter(String name, String... tags) {
      if (!name.equals(prefix) && !(prefix.endsWith(".") && name.startsWith(prefix))) {
        return super.counter(name, tags);
      }
      failures.incrementAndGet();
      if (registrationFailure) {
        if (assertionError) {
          throw new AssertionError("counter registration unavailable");
        }
        throw new IllegalStateException("counter registration unavailable");
      }
      return brokenCounter;
    }
  }
}
