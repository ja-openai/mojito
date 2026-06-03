package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.Test;

public class JdbcPostgresAsyncJobQueueWakeupConnectionsTest {

  @Test
  public void ensureAutoCommitDoesNotMutateAutoCommitConnections() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(true);

    try (JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope ignored =
        JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection)) {}

    verify(connection, never()).setAutoCommit(true);
    verify(connection, never()).setAutoCommit(false);
  }

  @Test
  public void ensureAutoCommitRestoresManualCommitConnections() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(false);

    try (JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope ignored =
        JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection)) {
      verify(connection).setAutoCommit(true);
    }

    verify(connection).setAutoCommit(false);
  }

  @Test
  public void ensureAutoCommitRestoresManualCommitConnectionsOnlyOnce() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(false);
    JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope scope =
        JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection);

    scope.close();
    scope.close();

    verify(connection).setAutoCommit(true);
    verify(connection, times(1)).setAutoCommit(false);
  }

  @Test
  public void ensureAutoCommitDoesNotRestoreClosedConnections() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(true);

    try (JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope ignored =
        JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection)) {
      verify(connection).setAutoCommit(true);
    }

    verify(connection, never()).setAutoCommit(false);
  }

  @Test
  public void ensureAutoCommitRestoreFailuresDoNotThrow() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);
    when(connection.isClosed()).thenReturn(false);
    doThrow(new SQLException("restore down")).when(connection).setAutoCommit(false);

    try (JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope ignored =
        JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection)) {
      verify(connection).setAutoCommit(true);
    }
  }

  @Test
  public void ensureAutoCommitRecordsRestoreFailuresWhenMeterRegistryProvided() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    try {
      Connection connection = mock(Connection.class);
      when(connection.getAutoCommit()).thenReturn(false);
      when(connection.isClosed()).thenReturn(false);
      doThrow(new SQLException("restore down")).when(connection).setAutoCommit(false);

      try (JdbcPostgresAsyncJobQueueWakeupConnections.AutoCommitScope ignored =
          JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection, meterRegistry)) {
        verify(connection).setAutoCommit(true);
      }

      assertThat(
              meterRegistry
                  .get("asyncJobQueue.wakeup.connection.autoCommitRestore.failed")
                  .tag("provider", "postgres")
                  .counter()
                  .count())
          .isEqualTo(1);
    } finally {
      meterRegistry.close();
    }
  }
}
