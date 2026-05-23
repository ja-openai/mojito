package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Test;

public class JdbcPostgresAsyncJobQueueWakeupNotifierTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void notifyJobAvailablePublishesQueuePayload() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement preparedStatement = mock(PreparedStatement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement("SELECT pg_notify(?, ?)")).thenReturn(preparedStatement);
    JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
        new JdbcPostgresAsyncJobQueueWakeupNotifier(
            dataSource, "mojito_async_job_queue", meterRegistry);

    notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42"));

    verify(preparedStatement).setString(1, "mojito_async_job_queue");
    verify(preparedStatement).setString(2, "assetlocalize");
    verify(preparedStatement).execute();
    verify(preparedStatement).close();
    verify(connection).close();
    assertNotifyCounter("assetlocalize", "succeeded", 1);
  }

  @Test
  public void notifyJobAvailableEnablesAutoCommitBeforePublishing() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement preparedStatement = mock(PreparedStatement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(false);
    when(connection.prepareStatement("SELECT pg_notify(?, ?)")).thenReturn(preparedStatement);
    JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
        new JdbcPostgresAsyncJobQueueWakeupNotifier(
            dataSource, "mojito_async_job_queue", meterRegistry);

    notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42"));

    verify(connection).setAutoCommit(true);
    verify(preparedStatement).execute();
    verify(connection).setAutoCommit(false);
    assertNotifyCounter("assetlocalize", "succeeded", 1);
  }

  @Test
  public void notifyJobAvailableTreatsAutoCommitRestoreFailureAsSuccessfulNotify()
      throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    PreparedStatement preparedStatement = mock(PreparedStatement.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(false);
    when(connection.prepareStatement("SELECT pg_notify(?, ?)")).thenReturn(preparedStatement);
    doThrow(new SQLException("restore unavailable")).when(connection).setAutoCommit(false);
    JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
        new JdbcPostgresAsyncJobQueueWakeupNotifier(
            dataSource, "mojito_async_job_queue", meterRegistry);

    notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42"));

    verify(connection).setAutoCommit(true);
    verify(preparedStatement).execute();
    verify(connection).setAutoCommit(false);
    verify(connection).close();
    assertNotifyCounter("assetlocalize", "succeeded", 1);
    assertNoNotifyCounter("assetlocalize", "failed");
  }

  @Test
  public void notifyJobAvailableRecordsFailureWithoutThrowing() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new IllegalStateException("database unavailable"));
    JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
        new JdbcPostgresAsyncJobQueueWakeupNotifier(
            dataSource, "mojito_async_job_queue", meterRegistry);

    notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42"));

    assertNotifyCounter("assetlocalize", "failed", 1);
  }

  @Test
  public void notifyJobAvailablePropagatesFatalErrorsWithoutFailureCounter() throws Exception {
    FatalTestError fatalTestError = new FatalTestError("fatal notify");
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(fatalTestError);
    JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
        new JdbcPostgresAsyncJobQueueWakeupNotifier(
            dataSource, "mojito_async_job_queue", meterRegistry);

    assertThatThrownBy(() -> notifier.notifyJobAvailable("assetlocalize", new AsyncJobId("42")))
        .isSameAs(fatalTestError);

    assertThat(
            meterRegistry
                .find("asyncJobQueue.wakeup.notify")
                .tag("queueName", "assetlocalize")
                .tag("provider", "postgres")
                .tag("result", "failed")
                .counter())
        .isNull();
  }

  @Test
  public void notifyJobAvailableValidatesBoundedInputsBeforeSql() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    JdbcPostgresAsyncJobQueueWakeupNotifier notifier =
        new JdbcPostgresAsyncJobQueueWakeupNotifier(
            dataSource, "mojito_async_job_queue", meterRegistry);

    assertThatThrownBy(() -> notifier.notifyJobAvailable("bad queue", new AsyncJobId("42")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queueName");

    verify(dataSource, never()).getConnection();
  }

  @Test
  public void constructorRejectsUnsafeChannel() {
    assertThatThrownBy(
            () ->
                new JdbcPostgresAsyncJobQueueWakeupNotifier(
                    mock(DataSource.class), "bad-channel", meterRegistry))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PostgreSQL-safe identifier");
  }

  private void assertNotifyCounter(String queueName, String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.wakeup.notify")
                .tag("queueName", queueName)
                .tag("provider", "postgres")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertNoNotifyCounter(String queueName, String result) {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.wakeup.notify")
                .tag("queueName", queueName)
                .tag("provider", "postgres")
                .tag("result", result)
                .counter())
        .isNull();
  }

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
