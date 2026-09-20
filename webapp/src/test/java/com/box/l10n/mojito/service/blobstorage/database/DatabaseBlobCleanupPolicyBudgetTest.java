package com.box.l10n.mojito.service.blobstorage.database;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.DBUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Real HSQL transactions and SQL, with deterministic clock/driver failures; no external services.
 */
public class DatabaseBlobCleanupPolicyBudgetTest {
  private final DatabaseBlobCleanupPolicyRepository policies =
      mock(DatabaseBlobCleanupPolicyRepository.class);
  private final MBlobRepository blobs = mock(MBlobRepository.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final AtomicLong clock = new AtomicLong();
  private final AtomicBoolean failCommit = new AtomicBoolean();
  private final List<Integer> selectionTimeouts = new ArrayList<>();
  private final List<Integer> eligibilityTimeouts = new ArrayList<>();
  private JdbcTemplate jdbc;
  private DatabaseBlobCleanupPolicyService service;
  private Runnable afterSelection = () -> {};
  private Runnable afterDelete = () -> {};
  private boolean selectionTimeout;
  private boolean eligibilityTimeout;
  private boolean failProgress;
  private boolean failDisable;
  private boolean failStart;
  private boolean failFinish;
  private Logger logger;
  private Level previousLevel;
  private ListAppender<ILoggingEvent> logs;

  @Before
  public void setup() {
    JDBCDataSource database = new JDBCDataSource();
    database.setUrl("jdbc:hsqldb:mem:cleanup_budget_" + UUID.randomUUID());
    database.setUser("SA");
    database.setPassword("");
    DataSource source = instrument(database);
    jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(3); // Cleanup must not change another caller's setting.
    jdbc.execute("set database sql syntax mys true");
    jdbc.execute(
        "create table mblob (id bigint primary key, name varchar(255), created_date timestamp, expire_after_seconds bigint)");
    jdbc.execute(
        "create table pollable_task (id bigint primary key, finished_date timestamp, parent_task_id bigint, expected_sub_task_number int)");
    jdbc.execute(
        "create table policy (enabled boolean, status varchar(32), stop_requested boolean, last_count bigint, total bigint, started timestamp, finished timestamp, error varchar(2048))");
    jdbc.update("insert into policy values (true, 'IDLE', false, 0, 17, null, null, null)");
    jdbc.update(
        "insert into mblob values (1, 'budget_test/one', ?, 1)",
        Timestamp.from(ZonedDateTime.now().minusDays(10).toInstant()));
    when(policies.findById(1L)).thenAnswer(call -> Optional.of(readPolicy()));
    when(policies.findByEnabledTrueOrderByPrefixAsc())
        .thenAnswer(call -> readPolicy().isEnabled() ? List.of(readPolicy()) : List.of());
    when(policies.save(any())).thenAnswer(call -> savePolicy(call.getArgument(0)));
    when(blobs.deleteExpiredByIds(anyList(), any()))
        .thenAnswer(
            call -> {
              List<Long> ids = call.getArgument(0);
              ZonedDateTime now = call.getArgument(1);
              assertEquals(List.of(1L), ids);
              int count =
                  jdbc.update(
                      "delete from mblob where id = ? and timestampadd(second, expire_after_seconds, created_date) < ? "
                          + MBlobRepository.CLEANUP_TASK_SAFETY_PREDICATE,
                      ids.getFirst(),
                      Timestamp.from(now.toInstant()));
              afterDelete.run();
              return count;
            });
    service =
        spy(
            new DatabaseBlobCleanupPolicyService(
                policies,
                blobs,
                jdbc,
                mock(DBUtils.class),
                mock(QuartzSchedulerManager.class),
                metrics,
                new DataSourceTransactionManager(source)));
    doAnswer(call -> clock.get()).when(service).nanoTime();
    logger = (Logger) LoggerFactory.getLogger(DatabaseBlobCleanupPolicyService.class);
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
  }

  @After
  public void close() {
    logger.detachAppender(logs);
    logger.setLevel(previousLevel);
    logs.stop();
    jdbc.execute("shutdown");
    metrics.close();
  }

  @Test
  public void timeoutReachesActualSelectionWithoutChangingSharedJdbc() {
    service.runPolicy(1);
    assertEquals(List.of(10), selectionTimeouts);
    assertEquals(3, jdbc.getQueryTimeout());
    assertEquals(0, blobCount());
    assertEquals(18, readPolicy().getTotalDeletedCount());
    assertEquals("PAUSED", readPolicy().getStatus());
    assertTrue(
        phaseLog().contains("selectedRows=1, deletedRows=1, transactionCommitConfirmed=true"));
    assertTrue(
        phaseLog()
            .contains(
                "selectionMs=0, deleteMs=0, transactionMs=0, finalizationMs=0, progressMs=0"));
  }

  @Test
  public void eligibilityProbeHasBudgetEvenOutsideTransaction() {
    jdbc.update("delete from mblob");
    eligibilityTimeout = true;
    service.runPolicy(1);
    assertEquals(List.of(10), eligibilityTimeouts);
    assertDisabledFailure(17);
    assertTrue(phaseLog().contains("phase=eligibility"));
  }

  @Test
  public void selectionTimeoutDisablesAndNeverRetriesOrDeletes() {
    selectionTimeout = true;
    service.runPolicy(1);
    service.runEnabledPolicies();
    assertEquals(List.of(10), selectionTimeouts);
    verify(blobs, never()).deleteExpiredByIds(anyList(), any());
    assertEquals(1, blobCount());
    assertDisabledFailure(17);
    assertTrue(phaseLog().contains("phase=selection, failed=true"));
  }

  @Test
  public void expiredMonotonicBudgetAfterSelectionNeverStartsDelete() {
    afterSelection = () -> clock.set(TimeUnit.SECONDS.toNanos(10));
    service.runPolicy(1);
    verify(blobs, never()).deleteExpiredByIds(anyList(), any());
    assertEquals(1, blobCount());
    assertDisabledFailure(17);
    assertTrue(phaseLog().contains("selectionMs=10000"));
  }

  @Test
  public void expiredBudgetAfterDeleteRollsBackRowsAndCounters() {
    afterDelete = () -> clock.set(TimeUnit.SECONDS.toNanos(10));
    service.runPolicy(1);
    verify(blobs, times(1)).deleteExpiredByIds(anyList(), any());
    assertEquals(1, blobCount());
    assertDisabledFailure(17);
    assertTrue(
        phaseLog()
            .contains(
                "phase=delete, failed=true, selectedRows=1, deletedRows=1, transactionCommitConfirmed=false"));
  }

  @Test
  public void wrappedTimeoutNeverUsesLockRetryPath() {
    afterDelete =
        () -> {
          throw new CannotAcquireLockException(
              "outer lock classification", new QueryTimeoutException("driver timeout"));
        };
    service.runPolicy(1);
    verify(blobs, times(1)).deleteExpiredByIds(anyList(), any());
    assertEquals(1, blobCount());
    assertDisabledFailure(17);
  }

  @Test
  public void normalPrecommitLockConflictStillRetriesAfterRollback() {
    AtomicBoolean first = new AtomicBoolean(true);
    afterDelete =
        () -> {
          if (first.getAndSet(false)) throw new CannotAcquireLockException("synthetic conflict");
        };
    service.runPolicy(1);
    verify(blobs, times(2)).deleteExpiredByIds(anyList(), any());
    assertEquals(0, blobCount());
    assertEquals(18, readPolicy().getTotalDeletedCount());
    assertEquals("PAUSED", readPolicy().getStatus());
  }

  @Test
  public void commitAcknowledgementFailureDoesNotRepeatCommittedDeletionOrClaimCounters() {
    afterDelete = () -> failCommit.set(true);
    service.runPolicy(1);
    service.runEnabledPolicies();
    verify(blobs, times(1)).deleteExpiredByIds(anyList(), any());
    assertEquals(0, blobCount()); // Actual commit happened; acknowledgement was lost.
    assertDisabledFailure(17);
    assertTrue(phaseLog().contains("phase=transaction_finalization, failed=true"));
    assertTrue(
        phaseLog().contains("transactionCommitConfirmed=false")); // Not confirmed to the caller.
  }

  @Test
  public void progressFailureRollsBackCountersAndNeverRepeatsCommittedDeletion() {
    failProgress = true;
    service.runPolicy(1);
    service.runEnabledPolicies();
    verify(blobs, times(1)).deleteExpiredByIds(anyList(), any());
    assertEquals(0, blobCount());
    assertDisabledFailure(17);
    assertTrue(phaseLog().contains("phase=progress, failed=true"));
    assertTrue(phaseLog().contains("transactionCommitConfirmed=true"));
    assertNull(metrics.find("DatabaseBlobStorage.policyCleanup.deletedRows").counter());
  }

  @Test
  public void failedDisablePropagatesAttentionInsteadOfClaimingSuccess() {
    selectionTimeout = true;
    failDisable = true;
    assertThrows(QueryTimeoutException.class, () -> service.runPolicy(1));
    assertTrue(readPolicy().isEnabled());
    assertEquals("RUNNING", readPolicy().getStatus());
    assertEquals(1, blobCount());
  }

  @Test
  public void startWriteTimeoutDisablesWithoutSelectingOrResettingCommittedCounts() {
    failStart = true;
    service.runPolicy(1);
    assertTrue(selectionTimeouts.isEmpty());
    assertEquals(1, blobCount());
    assertDisabledFailure(17);
    assertTrue(phaseLog().contains("phase=start, failed=true"));
  }

  @Test
  public void finishWriteTimeoutDisablesWithoutRepeatingCommittedBatch() {
    failFinish = true;
    service.runPolicy(1);
    service.runEnabledPolicies();
    verify(blobs, times(1)).deleteExpiredByIds(anyList(), any());
    assertEquals(0, blobCount());
    DatabaseBlobCleanupPolicy failed = readPolicy();
    assertFalse(failed.isEnabled());
    assertEquals("FAILED", failed.getStatus());
    assertEquals(18, failed.getTotalDeletedCount());
    assertEquals(1, failed.getLastDeletedCount());
    assertTrue(phaseLog().contains("phase=finish, failed=true"));
  }

  @Test
  public void repeatedStopPreservesInflightStatusUntilWorkerFinishes() {
    jdbc.update("update policy set status='RUNNING'");
    assertEquals("STOP_REQUESTED", service.stopPolicy(1).getStatus());
    assertEquals("STOP_REQUESTED", service.stopPolicy(1).getStatus());
    assertFalse(readPolicy().isEnabled());
    assertTrue(readPolicy().isStopRequested());
    assertThrows(IllegalArgumentException.class, () -> service.deletePolicy(1));
  }

  private void assertDisabledFailure(long total) {
    DatabaseBlobCleanupPolicy policy = readPolicy();
    assertFalse(policy.isEnabled());
    assertEquals("FAILED", policy.getStatus());
    assertEquals(0, policy.getLastDeletedCount());
    assertEquals(total, policy.getTotalDeletedCount());
    assertTrue(policy.getLastError().contains("reconcile deleted rows"));
  }

  private int blobCount() {
    return jdbc.queryForObject("select count(*) from mblob", Integer.class);
  }

  private String phaseLog() {
    return logs.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.startsWith("Database blob cleanup phases:"))
        .reduce((a, b) -> b)
        .orElseThrow();
  }

  private DatabaseBlobCleanupPolicy readPolicy() {
    return jdbc.queryForObject(
        "select * from policy",
        (rs, row) -> {
          DatabaseBlobCleanupPolicy p = new DatabaseBlobCleanupPolicy();
          p.setId(1L);
          p.setPrefix("budget_test/");
          p.setEnabled(rs.getBoolean("enabled"));
          p.setStatus(rs.getString("status"));
          p.setStopRequested(rs.getBoolean("stop_requested"));
          p.setMaxBatchesPerRun(1);
          p.setBatchSize(1);
          p.setPauseMillis(0);
          p.setMaxRetries(2);
          p.setLastDeletedCount(rs.getLong("last_count"));
          p.setTotalDeletedCount(rs.getLong("total"));
          p.setLastError(rs.getString("error"));
          Timestamp start = rs.getTimestamp("started"), finish = rs.getTimestamp("finished");
          p.setLastStartedDate(start == null ? null : start.toInstant().atZone(ZoneOffset.UTC));
          p.setLastFinishedDate(finish == null ? null : finish.toInstant().atZone(ZoneOffset.UTC));
          return p;
        });
  }

  private DatabaseBlobCleanupPolicy savePolicy(DatabaseBlobCleanupPolicy p) {
    jdbc.update(
        "update policy set enabled=?, status=?, stop_requested=?, last_count=?, total=?, started=?, finished=?, error=?",
        p.isEnabled(),
        p.getStatus(),
        p.isStopRequested(),
        p.getLastDeletedCount(),
        p.getTotalDeletedCount(),
        p.getLastStartedDate() == null ? null : Timestamp.from(p.getLastStartedDate().toInstant()),
        p.getLastFinishedDate() == null
            ? null
            : Timestamp.from(p.getLastFinishedDate().toInstant()),
        p.getLastError());
    if ((failProgress && p.getLastDeletedCount() > 0)
        || (failDisable && !p.isEnabled())
        || (failStart && "RUNNING".equals(p.getStatus()))
        || (failFinish && "PAUSED".equals(p.getStatus()))) {
      throw new QueryTimeoutException("synthetic write timeout");
    }
    return p;
  }

  private DataSource instrument(DataSource target) {
    return new DelegatingDataSource(target) {
      @Override
      public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        return (Connection)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("commit") && failCommit.getAndSet(false)) {
                      throw new SQLException("synthetic lost commit acknowledgement", "08006");
                    }
                    if (method.getName().equals("prepareStatement")) {
                      String sql = (String) args[0];
                      PreparedStatement statement = (PreparedStatement) result;
                      return Proxy.newProxyInstance(
                          getClass().getClassLoader(),
                          new Class<?>[] {PreparedStatement.class},
                          (ps, operation, values) -> {
                            boolean select = sql.startsWith("select id from mblob");
                            boolean eligible =
                                sql.startsWith("select count(*) from (select id from mblob");
                            if (operation.getName().equals("setQueryTimeout")) {
                              if (select) selectionTimeouts.add((Integer) values[0]);
                              if (eligible) eligibilityTimeouts.add((Integer) values[0]);
                            }
                            if (operation.getName().equals("executeQuery")
                                && ((select && selectionTimeout)
                                    || (eligible && eligibilityTimeout))) {
                              throw new SQLTimeoutException("synthetic statement timeout");
                            }
                            try {
                              Object value = operation.invoke(statement, values);
                              if (select && operation.getName().equals("executeQuery"))
                                afterSelection.run();
                              return value;
                            } catch (InvocationTargetException failure) {
                              throw failure.getCause();
                            }
                          });
                    }
                    return result;
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
      }
    };
  }
}
