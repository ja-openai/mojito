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
  public void handleNotificationPropagatesFatalTriggerErrorsWithoutFailureCounter() {
    FatalTestError fatalTestError = new FatalTestError("fatal trigger");
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    doThrow(fatalTestError).when(coordinator).triggerPollNow("assetlocalize");
    JdbcPostgresAsyncJobQueueWakeupListener listener = listener(coordinator);

    assertThatThrownBy(() -> listener.handleNotification("mojito_async_job_queue", "assetlocalize"))
        .isSameAs(fatalTestError);

    assertThat(receivedCounter("assetlocalize", "triggerFailed")).isNull();
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
  public void listenerHealthGaugesExposeLifecycleAndConnectionState() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    CountDownLatch notificationPollStarted = new CountDownLatch(1);
    AtomicInteger keepPolling = new AtomicInteger(1);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            invocation -> {
              notificationPollStarted.countDown();
              while (keepPolling.get() == 1) {
                try {
                  TimeUnit.MILLISECONDS.sleep(5);
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  return null;
                }
              }
              return null;
            });
    AsyncJobQueueProperties.WakeupSettings wakeupSettings =
        new AsyncJobQueueProperties.WakeupSettings();
    wakeupSettings.setPostgresListenTimeoutMs(500);
    wakeupSettings.setReconnectDelayMs(1);
    JdbcPostgresAsyncJobQueueWakeupListener listener =
        new JdbcPostgresAsyncJobQueueWakeupListener(
            dataSource, wakeupSettings, mock(AsyncJobQueueCoordinator.class), meterRegistry);

    assertListenerGaugeValue("asyncJobQueue.wakeup.listener.running", 0);
    assertListenerGaugeValue("asyncJobQueue.wakeup.listener.connected", 0);
    assertListenerGaugeValue("asyncJobQueue.wakeup.listener.threadAlive", 0);

    try {
      listener.start();

      assertThat(notificationPollStarted.await(1, TimeUnit.SECONDS)).isTrue();
      waitForListenerGaugeValue("asyncJobQueue.wakeup.listener.running", 1);
      waitForListenerGaugeValue("asyncJobQueue.wakeup.listener.connected", 1);
      waitForListenerGaugeValue("asyncJobQueue.wakeup.listener.threadAlive", 1);
    } finally {
      keepPolling.set(0);
      listener.stop();
    }
    waitForListenerGaugeValue("asyncJobQueue.wakeup.listener.running", 0);
    waitForListenerGaugeValue("asyncJobQueue.wakeup.listener.connected", 0);
    waitForListenerGaugeValue("asyncJobQueue.wakeup.listener.threadAlive", 0);
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
  public void stopRecordsAutoCommitRestoreFailureAfterListening() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(false);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    doThrow(new SQLException("restore unavailable")).when(connection).setAutoCommit(false);
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
      verify(statement, timeout(1_000)).execute("LISTEN \"mojito_async_job_queue\"");
    } finally {
      listener.stop();
    }

    verify(connection, timeout(1_000)).setAutoCommit(false);
    assertAutoCommitRestoreFailureCounter(1);
  }

  @Test
  public void stopRecordsConnectionCloseFailureAfterListening() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    Statement statement = mock(Statement.class);
    PGConnection pgConnection = mock(PGConnection.class);
    CountDownLatch notificationPollStarted = new CountDownLatch(1);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.createStatement()).thenReturn(statement);
    when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
    doThrow(new SQLException("close unavailable")).when(connection).close();
    when(pgConnection.getNotifications(anyInt()))
        .thenAnswer(
            invocation -> {
              notificationPollStarted.countDown();
              try {
                Thread.sleep(1_000);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return null;
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

    assertSimpleCounter("asyncJobQueue.wakeup.listener.close.failed", 1);
    waitForListenerGaugeValue("asyncJobQueue.wakeup.listener.connected", 0);
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
  public void stopRecordsInterruptedJoinAndPreservesInterruptFlag() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    CountDownLatch getConnectionStarted = new CountDownLatch(1);
    CountDownLatch unblockGetConnection = new CountDownLatch(1);
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
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

      Thread.currentThread().interrupt();
      listener.stop();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertSimpleCounter("asyncJobQueue.wakeup.listener.stopInterrupted", 1);
      assertListenerGaugeValue("asyncJobQueue.wakeup.listener.running", 0);
      assertListenerGaugeValue("asyncJobQueue.wakeup.listener.connected", 0);
    } finally {
      Thread.interrupted();
      unblockGetConnection.countDown();
      listener.stop();
    }
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
      assertListenerGaugeValue("asyncJobQueue.wakeup.listener.running", 0);
      assertListenerGaugeValue("asyncJobQueue.wakeup.listener.connected", 0);
      assertListenerGaugeValue("asyncJobQueue.wakeup.listener.threadAlive", 1);
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
    Counter counter = receivedCounter(queueName, result);
    assertThat(counter == null ? 0 : counter.count()).isEqualTo(count);
  }

  private Counter receivedCounter(String queueName, String result) {
    return meterRegistry
        .find("asyncJobQueue.wakeup.received")
        .tag("queueName", queueName)
        .tag("provider", "postgres")
        .tag("result", result)
        .counter();
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

  private void assertAutoCommitRestoreFailureCounter(double count) {
    Counter counter =
        meterRegistry
            .find("asyncJobQueue.wakeup.connection.autoCommitRestore.failed")
            .tag("provider", "postgres")
            .counter();
    assertThat(counter == null ? 0 : counter.count()).isEqualTo(count);
  }

  private void waitForListenerGaugeValue(String name, double expectedValue)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < deadline) {
      if (listenerGaugeValue(name) == expectedValue) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertListenerGaugeValue(name, expectedValue);
  }

  private void assertListenerGaugeValue(String name, double expectedValue) {
    assertThat(listenerGaugeValue(name)).isEqualTo(expectedValue);
  }

  private double listenerGaugeValue(String name) {
    return meterRegistry
        .get(name)
        .tag("provider", "postgres")
        .tag("channel", "mojito_async_job_queue")
        .gauge()
        .value();
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

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
