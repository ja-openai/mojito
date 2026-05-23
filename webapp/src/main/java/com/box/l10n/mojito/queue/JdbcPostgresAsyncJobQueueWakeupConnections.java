package com.box.l10n.mojito.queue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JdbcPostgresAsyncJobQueueWakeupConnections {

  static Logger logger = LoggerFactory.getLogger(JdbcPostgresAsyncJobQueueWakeupConnections.class);

  private JdbcPostgresAsyncJobQueueWakeupConnections() {}

  static AutoCommitScope ensureAutoCommit(Connection connection) throws SQLException {
    boolean originalAutoCommit = connection.getAutoCommit();
    if (!originalAutoCommit) {
      connection.setAutoCommit(true);
    }
    return new AutoCommitScope(connection, originalAutoCommit);
  }

  static final class AutoCommitScope implements AutoCloseable {
    private final Connection connection;
    private final boolean originalAutoCommit;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AutoCommitScope(Connection connection, boolean originalAutoCommit) {
      this.connection = connection;
      this.originalAutoCommit = originalAutoCommit;
    }

    @Override
    public void close() {
      if (originalAutoCommit || !closed.compareAndSet(false, true)) {
        return;
      }
      try {
        if (!connection.isClosed()) {
          connection.setAutoCommit(false);
        }
      } catch (SQLException exception) {
        logger.warn("Failed to restore PostgreSQL wakeup connection auto-commit", exception);
      }
    }
  }
}
