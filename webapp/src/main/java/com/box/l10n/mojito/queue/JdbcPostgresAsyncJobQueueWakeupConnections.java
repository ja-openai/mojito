package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JdbcPostgresAsyncJobQueueWakeupConnections {

  static Logger logger = LoggerFactory.getLogger(JdbcPostgresAsyncJobQueueWakeupConnections.class);

  private JdbcPostgresAsyncJobQueueWakeupConnections() {}

  static AutoCommitScope ensureAutoCommit(Connection connection) throws SQLException {
    return ensureAutoCommit(connection, null);
  }

  static AutoCommitScope ensureAutoCommit(Connection connection, MeterRegistry meterRegistry)
      throws SQLException {
    boolean originalAutoCommit = connection.getAutoCommit();
    if (!originalAutoCommit) {
      connection.setAutoCommit(true);
    }
    return new AutoCommitScope(connection, originalAutoCommit, meterRegistry);
  }

  static final class AutoCommitScope implements AutoCloseable {
    private final Connection connection;
    private final boolean originalAutoCommit;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AutoCommitScope(
        Connection connection, boolean originalAutoCommit, MeterRegistry meterRegistry) {
      this.connection = connection;
      this.originalAutoCommit = originalAutoCommit;
      this.meterRegistry = meterRegistry;
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
        if (meterRegistry != null) {
          meterRegistry
              .counter(
                  "asyncJobQueue.wakeup.connection.autoCommitRestore.failed",
                  "provider",
                  "postgres")
              .increment();
        }
      }
    }
  }
}
