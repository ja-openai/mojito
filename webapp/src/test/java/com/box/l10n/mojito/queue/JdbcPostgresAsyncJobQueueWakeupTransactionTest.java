package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real JDBC rollback controls; HSQL deliberately rejects the PostgreSQL notification SQL. */
public class JdbcPostgresAsyncJobQueueWakeupTransactionTest {

  @Test
  public void rawDataSourceNotificationFailurePreservesCallerRollback() throws Exception {
    assertCallerRollback(0, false);
  }

  @Test
  public void transactionAwareNotificationFailurePreservesCallerRollback() throws Exception {
    assertCallerRollback(1, false);
  }

  @Test
  public void nestedTransactionAwareNotificationFailurePreservesCallerRollback() throws Exception {
    assertCallerRollback(2, false);
  }

  @Test
  public void transactionAwareLazyNotificationFailurePreservesCallerRollback() throws Exception {
    assertCallerRollback(1, true);
  }

  private void assertCallerRollback(int proxyDepth, boolean lazy) throws Exception {
    var config = new AsyncJobQueueJdbcTransactionConfigurationTest.ExplicitTransactionTestConfig();
    DataSource raw = config.dataSource();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try {
      config.createAsyncJobQueueSchema(raw);
      new JdbcTemplate(raw).execute("CREATE TABLE business_marker (id INTEGER PRIMARY KEY)");
      DataSource transactional = lazy ? new LazyConnectionDataSourceProxy(raw) : raw;
      DataSource wakeup = transactional;
      for (int i = 0; i < proxyDepth; i++) {
        wakeup = new TransactionAwareDataSourceProxy(wakeup);
      }
      DataSourceTransactionManager manager = new DataSourceTransactionManager(transactional);
      JdbcAsyncJobStore store =
          new JdbcAsyncJobStore(
              new NamedParameterJdbcTemplate(wakeup), AsyncJobQueueJdbcDialect.HSQL, manager);
      var notifier = new JdbcPostgresAsyncJobQueueWakeupNotifier(wakeup, "queue_hints", registry);
      var submission =
          new AsyncJobQueueSubmissionService(
              store, mock(AsyncJobQueueCoordinator.class), notifier, registry, Clock.systemUTC());
      AtomicReference<AsyncJobId> accepted = new AtomicReference<>();

      new TransactionTemplate(manager)
          .executeWithoutResult(
              status -> {
                Connection caller = DataSourceUtils.getConnection(transactional);
                Object resource = TransactionSynchronizationManager.getResource(transactional);
                try {
                  new JdbcTemplate(transactional).update("INSERT INTO business_marker VALUES (1)");
                  accepted.set(submission.enqueueNow("example", "opaque-payload"));
                  assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                      .isTrue();
                  assertThat(TransactionSynchronizationManager.getResource(transactional))
                      .isSameAs(resource);
                  assertThat(((ConnectionHolder) resource).getConnection()).isSameAs(caller);
                  assertThat(
                          new JdbcTemplate(transactional)
                              .queryForObject(
                                  "SELECT COUNT(*) FROM business_marker", Integer.class))
                      .isEqualTo(1);
                  status.setRollbackOnly();
                } finally {
                  DataSourceUtils.releaseConnection(caller, transactional);
                }
              });

      assertThat(accepted.get()).isNotNull();
      assertThat(
              registry
                  .get("asyncJobQueue.wakeup.notify")
                  .tags("queueName", "example", "provider", "postgres", "result", "failed")
                  .counter()
                  .count())
          .isEqualTo(1);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
      try (Connection observer = raw.getConnection();
          Statement statement = observer.createStatement()) {
        try (ResultSet jobs = statement.executeQuery("SELECT id, status FROM async_job_queue")) {
          assertThat(jobs.next()).isTrue();
          assertThat(Long.toString(jobs.getLong("id"))).isEqualTo(accepted.get().value());
          assertThat(jobs.getString("status")).isEqualTo("queued");
          assertThat(jobs.next()).isFalse();
        }
        try (ResultSet marker = statement.executeQuery("SELECT COUNT(*) FROM business_marker")) {
          assertThat(marker.next()).isTrue();
          assertThat(marker.getInt(1)).as("caller business write must roll back").isZero();
        }
      }
    } finally {
      registry.close();
      new JdbcTemplate(raw).execute("SHUTDOWN");
    }
  }
}
