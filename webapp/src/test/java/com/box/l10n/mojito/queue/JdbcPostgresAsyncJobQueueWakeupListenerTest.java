package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Test;
import org.postgresql.PGConnection;

public class JdbcPostgresAsyncJobQueueWakeupListenerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void handleNotificationTriggersMatchingQueue() {
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    JdbcPostgresAsyncJobQueueWakeupListener listener = listener(coordinator);

    listener.handleNotification("mojito_async_job_queue", "assetlocalize");

    verify(coordinator).triggerPollNow("assetlocalize");
    assertReceivedCounter("assetlocalize", "triggered", 1);
  }

  @Test
  public void handleNotificationIgnoresWrongChannel() {
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    JdbcPostgresAsyncJobQueueWakeupListener listener = listener(coordinator);

    listener.handleNotification("other_channel", "assetlocalize");

    verify(coordinator, never()).triggerPollNow("assetlocalize");
    assertReceivedCounter("unknown", "wrongChannel", 1);
  }

  @Test
  public void handleNotificationIgnoresInvalidQueuePayload() {
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    JdbcPostgresAsyncJobQueueWakeupListener listener = listener(coordinator);

    listener.handleNotification("mojito_async_job_queue", "asset localize");

    verify(coordinator, never()).triggerPollNow("asset localize");
    assertReceivedCounter("unknown", "invalidPayload", 1);
  }

  @Test
  public void handleNotificationRecordsTriggerFailureWithoutThrowing() {
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    doThrow(new IllegalStateException("scheduler unavailable"))
        .when(coordinator)
        .triggerPollNow("assetlocalize");
    JdbcPostgresAsyncJobQueueWakeupListener listener = listener(coordinator);

    listener.handleNotification("mojito_async_job_queue", "assetlocalize");

    assertReceivedCounter("assetlocalize", "triggerFailed", 1);
  }

  @Test
  public void handleNotificationSkipsTriggerWhenJitterSleepIsInterrupted() {
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    JdbcPostgresAsyncJobQueueWakeupListener listener =
        new JdbcPostgresAsyncJobQueueWakeupListener(
            mock(DataSource.class), wakeupSettings, coordinator, meterRegistry) {
          @Override
          long triggerJitterDelayMs() {
            return 1_000;
          }
        };

    Thread.currentThread().interrupt();
    try {
      listener.handleNotification("mojito_async_job_queue", "assetlocalize");
    } finally {
      Thread.interrupted();
    }

    verify(coordinator, never()).triggerPollNow("assetlocalize");
    assertReceivedCounter("assetlocalize", "triggerInterrupted", 1);
  }

  @Test
  public void triggerJitterDelayStaysWithinConfiguredRange() {
    assertThat(JdbcPostgresAsyncJobQueueWakeupListener.randomTriggerJitterDelayMs(0)).isZero();

    for (int i = 0; i < 100; i++) {
      assertThat(JdbcPostgresAsyncJobQueueWakeupListener.randomTriggerJitterDelayMs(25))
          .isBetween(0L, 25L);
    }
  }

  @Test
  public void handleNotificationsCoalescesDuplicateQueuePayloadsInBatch() throws Exception {
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AtomicInteger jitterCalls = new AtomicInteger();
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    wakeupSettings.setTriggerJitterMs(0);
    JdbcPostgresAsyncJobQueueWakeupListener listener =
        new JdbcPostgresAsyncJobQueueWakeupListener(
            mock(DataSource.class), wakeupSettings, coordinator, meterRegistry) {
          @Override
          long triggerJitterDelayMs() {
            jitterCalls.incrementAndGet();
            return 0;
          }
        };

    listener.handleNotifications(
        new Object[] {
          new TestNotification("mojito_async_job_queue", "assetlocalize"),
          new TestNotification("mojito_async_job_queue", "assetlocalize"),
          new TestNotification("mojito_async_job_queue", "repo-stats"),
          new TestNotification("other_channel", "assetlocalize"),
          new TestNotification("mojito_async_job_queue", "asset localize")
        });

    verify(coordinator).triggerPollNow("assetlocalize");
    verify(coordinator).triggerPollNow("repo-stats");
    verifyNoMoreInteractions(coordinator);
    assertThat(jitterCalls).hasValue(1);
    assertReceivedCounter("assetlocalize", "duplicate", 1);
    assertReceivedCounter("assetlocalize", "triggered", 1);
    assertReceivedCounter("repo-stats", "triggered", 1);
    assertReceivedCounter("unknown", "wrongChannel", 1);
    assertReceivedCounter("unknown", "invalidPayload", 1);
  }

  @Test
  public void quotedIdentifierAllowsOnlySafeIdentifiers() {
    assertThat(JdbcPostgresAsyncJobQueueWakeupListener.quotedIdentifier("mojito_async_job_queue"))
        .isEqualTo("\"mojito_async_job_queue\"");

    assertThatThrownBy(() -> JdbcPostgresAsyncJobQueueWakeupListener.quotedIdentifier("bad-name"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PostgreSQL-safe identifier");
  }

  @Test
  public void startEnablesAutoCommitBeforeListening() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(false);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            invocation -> {
              try {
                Thread.sleep(10);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return null;
            });
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    wakeupSettings.setPostgresListenTimeoutMs(1);
    wakeupSettings.setReconnectDelayMs(1);
    JdbcPostgresAsyncJobQueueWakeupListener listener =
        new JdbcPostgresAsyncJobQueueWakeupListener(
            dataSource, wakeupSettings, mock(AsyncJobQueueCoordinator.class), meterRegistry);

    try {
      listener.start();

      verify(connection, timeout(1_000)).setAutoCommit(true);
      verify(statement, timeout(1_000)).execute("LISTEN \"mojito_async_job_queue\"");
    } finally {
      listener.stop();
    }
    verify(connection, timeout(1_000)).setAutoCommit(false);
  }

  @Test
  public void stopDoesNotRecordExpectedSocketCloseAsListenFailure() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    CountDownLatch notificationPollStarted = new CountDownLatch(1);
    CountDownLatch connectionClosed = new CountDownLatch(1);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              connectionClosed.countDown();
              return null;
            })
        .when(connection)
        .close();
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            invocation -> {
              notificationPollStarted.countDown();
              connectionClosed.await(1, TimeUnit.SECONDS);
              throw new SQLException("socket closed");
            });
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    wakeupSettings.setPostgresListenTimeoutMs(100);
    wakeupSettings.setReconnectDelayMs(1);
    JdbcPostgresAsyncJobQueueWakeupListener listener =
        new JdbcPostgresAsyncJobQueueWakeupListener(
            dataSource, wakeupSettings, mock(AsyncJobQueueCoordinator.class), meterRegistry);

    listener.start();
    assertThat(notificationPollStarted.await(1, TimeUnit.SECONDS)).isTrue();
    listener.stop();

    assertListenCounter("connected", 1);
    assertListenCounter("failed", 0);
  }

  @Test
  public void startRejectsDuplicateListenerWhenPreviousStopTimedOut() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    CountDownLatch getConnectionStarted = new CountDownLatch(1);
    CountDownLatch unblockGetConnection = new CountDownLatch(1);
    AtomicInteger connectionAttempts = new AtomicInteger();
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              connectionAttempts.incrementAndGet();
              getConnectionStarted.countDown();
              boolean unblocked = false;
              while (!unblocked) {
                try {
                  unblocked = unblockGetConnection.await(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                  // Simulate a driver call that ignores interruption until the socket unblocks.
                }
              }
              throw new SQLException("connection unavailable");
            });
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    wakeupSettings.setPostgresListenTimeoutMs(1);
    wakeupSettings.setReconnectDelayMs(1);
    JdbcPostgresAsyncJobQueueWakeupListener listener =
        new JdbcPostgresAsyncJobQueueWakeupListener(
            dataSource, wakeupSettings, mock(AsyncJobQueueCoordinator.class), meterRegistry);

    try {
      listener.start();
      assertThat(getConnectionStarted.await(1, TimeUnit.SECONDS)).isTrue();
      listener.stop();

      assertSimpleCounter("asyncJobQueue.wakeup.listener.stopTimeout", 1);
      assertThatThrownBy(listener::start)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("previous listener thread is still stopping");
      assertSimpleCounter("asyncJobQueue.wakeup.listener.startBlocked", 1);
      assertThat(connectionAttempts).hasValue(1);
    } finally {
      unblockGetConnection.countDown();
      listener.stop();
    }
  }

  private JdbcPostgresAsyncJobQueueWakeupListener listener(
      AsyncJobQueueCoordinator asyncJobQueueCoordinator) {
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    wakeupSettings.setTriggerJitterMs(0);
    return new JdbcPostgresAsyncJobQueueWakeupListener(
        mock(DataSource.class), wakeupSettings, asyncJobQueueCoordinator, meterRegistry);
  }

  private void assertReceivedCounter(String queueName, String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.wakeup.received")
                .tag("queueName", queueName)
                .tag("provider", "postgres")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertListenCounter(String result, double count) {
    Counter counter =
        meterRegistry
            .find("asyncJobQueue.wakeup.listen")
            .tag("provider", "postgres")
            .tag("result", result)
            .counter();
    assertThat(counter == null ? 0 : counter.count()).isEqualTo(count);
  }

  private void assertSimpleCounter(String name, double count) {
    Counter counter = meterRegistry.find(name).counter();
    assertThat(counter == null ? 0 : counter.count()).isEqualTo(count);
  }

  public static class TestNotification {
    private final String name;
    private final String parameter;

    TestNotification(String name, String parameter) {
      this.name = name;
      this.parameter = parameter;
    }

    public String getName() {
      return name;
    }

    public String getParameter() {
      return parameter;
    }
  }
}
