package com.box.l10n.mojito.queue;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

final class JdbcPostgresAsyncJobQueueWakeupConnections {

  static Logger logger = LoggerFactory.getLogger(JdbcPostgresAsyncJobQueueWakeupConnections.class);

  private JdbcPostgresAsyncJobQueueWakeupConnections() {}

  static DataSource independentDataSource(DataSource dataSource) {
    Set<DataSource> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    // Wakeup auto-commit must never commit a transaction resumed after queue admission.
    // Preserve lazy/routing/pool behavior; bypass only Spring's thread-bound borrowing.
    while (dataSource instanceof TransactionAwareDataSourceProxy proxy) {
      if (!visited.add(dataSource)) {
        throw new IllegalArgumentException("Cyclic transaction-aware wakeup DataSource proxy");
      }
      dataSource = proxy.getTargetDataSource();
    }
    Objects.requireNonNull(dataSource, "wakeup DataSource target is required");
    if (dataSource instanceof SingleConnectionDataSource) {
      throw new IllegalArgumentException("PostgreSQL wakeups require independent connections");
    }
    return dataSource;
  }

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
