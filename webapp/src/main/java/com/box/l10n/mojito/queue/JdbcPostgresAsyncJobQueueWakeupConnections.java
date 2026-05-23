package com.box.l10n.mojito.queue;

import java.sql.Connection;
import java.sql.SQLException;

final class JdbcPostgresAsyncJobQueueWakeupConnections {

  private JdbcPostgresAsyncJobQueueWakeupConnections() {}

  static void ensureAutoCommit(Connection connection) throws SQLException {
    if (!connection.getAutoCommit()) {
      connection.setAutoCommit(true);
    }
  }
}
