package com.box.l10n.mojito.queue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import org.junit.Test;

public class JdbcPostgresAsyncJobQueueWakeupConnectionsTest {

  @Test
  public void ensureAutoCommitDoesNotMutateAutoCommitConnections() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(true);

    JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection);

    verify(connection, never()).setAutoCommit(true);
  }

  @Test
  public void ensureAutoCommitEnablesManualCommitConnections() throws Exception {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(false);

    JdbcPostgresAsyncJobQueueWakeupConnections.ensureAutoCommit(connection);

    verify(connection).setAutoCommit(true);
  }
}
