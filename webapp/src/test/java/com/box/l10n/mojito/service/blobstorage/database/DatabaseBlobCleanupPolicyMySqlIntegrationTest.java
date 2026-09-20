package com.box.l10n.mojito.service.blobstorage.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.FlyWayConfig;
import com.box.l10n.mojito.entity.DatabaseBlobCleanupPolicy;
import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import com.box.l10n.mojito.service.DBUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;

/**
 * Actual Connector/J cancellation and JPA rollback; opt-in CI only, no query rewrites or faults.
 */
@RunWith(Parameterized.class)
public class DatabaseBlobCleanupPolicyMySqlIntegrationTest {

  @Parameterized.Parameters(name = "{0}")
  public static List<String> mysqlVersions() {
    return List.of("8.0", "8.4");
  }

  @Parameterized.Parameter public String mysqlVersion;

  @Test
  public void blockedSelectionIsCancelledAndDisablesPolicyWithoutRetry() throws Exception {
    enabled();
    try (Fixture fixture = new Fixture(mysqlVersion)) {
      fixture.seedPolicy("cleanup_test/");
      fixture.seedBlob(1, "cleanup_test/one", 10, 1L);
      var before = fixture.blobs();
      long connectionId = fixture.connectionId();
      // SKIP LOCKED avoids row-lock waits; this independent table lock blocks the real SELECT.
      try (Connection blocker = fixture.independentConnection();
          Statement lock = blocker.createStatement()) {
        lock.setQueryTimeout(5);
        lock.execute("LOCK TABLES mblob WRITE");
        try {
          fixture.runBounded();
          fixture.assertDisabledFailure("candidate_selection", 1, 0);
          fixture.assertReusableConnection(connectionId);
        } finally {
          lock.execute("UNLOCK TABLES");
        }
      }
      assertThat(fixture.blobs()).isEqualTo(before);
      fixture.assertRealCancellation(fixture.selects);
      assertThat(fixture.deletes).isEmpty();
    }
  }

  @Test
  public void guardedJpaDeleteCancellationRollsBackEarlierRowsAndReusesConnection()
      throws Exception {
    enabled();
    try (Fixture fixture = new Fixture(mysqlVersion)) {
      fixture.seedPolicy("cleanup_test/");
      fixture.seedBlob(1, "cleanup_test/one", 10, 1L);
      fixture.seedBlob(2, "cleanup_test/two", 10, 1L);
      var before = fixture.blobs();
      long connectionId = fixture.connectionId();
      fixture.jdbc.execute("SET @cleanup_delete_visits=0, @cleanup_rows_deleted=0");
      // Session variables survive rollback: AFTER DELETE proves one row was actually removed
      // before the second row blocks. Neither production SELECT nor DELETE is rewritten.
      fixture.jdbc.execute(
          """
          CREATE TRIGGER cleanup_before_delete BEFORE DELETE ON mblob FOR EACH ROW
          BEGIN
            SET @cleanup_delete_visits=COALESCE(@cleanup_delete_visits,0)+1;
            IF @cleanup_delete_visits=2 THEN DO SLEEP(30); END IF;
          END
          """);
      fixture.jdbc.execute(
          """
          CREATE TRIGGER cleanup_after_delete AFTER DELETE ON mblob FOR EACH ROW
          SET @cleanup_rows_deleted=COALESCE(@cleanup_rows_deleted,0)+1
          """);
      try {
        fixture.runBounded();
        fixture.assertDisabledFailure("delete", 2, 1);
        fixture.assertRealCancellation(fixture.deletes);
        fixture.assertReusableConnection(connectionId);
        assertThat(fixture.jdbc.queryForObject("SELECT @cleanup_delete_visits", Integer.class))
            .isEqualTo(2);
        assertThat(fixture.jdbc.queryForObject("SELECT @cleanup_rows_deleted", Integer.class))
            .isEqualTo(1);
        assertThat(fixture.blobs()).isEqualTo(before);
      } finally {
        fixture.jdbc.execute("DROP TRIGGER IF EXISTS cleanup_before_delete");
        fixture.jdbc.execute("DROP TRIGGER IF EXISTS cleanup_after_delete");
      }
    }
  }

  @Test
  public void successfulBatchPreservesExpiryAndTaskReferenceGuards() throws Exception {
    enabled();
    try (Fixture fixture = new Fixture(mysqlVersion)) {
      fixture.seedPolicy("pollable_task/");
      fixture.seedTask(101, true, null, 0);
      fixture.seedTask(102, false, null, 0);
      fixture.seedTask(103, true, null, 0);
      fixture.seedTask(104, true, 103L, 0);
      fixture.seedTask(105, true, null, 1);
      fixture.seedTask(106, true, null, 0);
      fixture.seedTask(107, true, null, 0);
      fixture.seedBlob(1, "pollable_task/101/input", 10, 1L);
      fixture.seedBlob(2, "pollable_task/101/output", 10, 1L);
      fixture.seedBlob(3, "pollable_task/102/input", 10, 1L);
      fixture.seedBlob(4, "pollable_task/103/output", 10, 1L);
      fixture.seedBlob(5, "pollable_task/104/output", 10, 1L);
      fixture.seedBlob(6, "pollable_task/105/output", 10, 1L);
      fixture.seedBlob(7, "pollable_task/999/output", 10, 1L);
      fixture.seedBlob(8, "pollable_task/unknown/output", 10, 1L);
      fixture.seedBlob(9, "pollable_task/106/input", 10, 20 * 86400L);
      fixture.seedBlob(10, "pollable_task/106/output", 10, null);
      fixture.seedBlob(11, "pollable_task/107/input", 1, 1L);
      var protectedRows = fixture.blobs().subList(2, 11);
      long connectionId = fixture.connectionId();

      fixture.runBounded();

      assertThat(fixture.blobs()).isEqualTo(protectedRows);
      DatabaseBlobCleanupPolicy policy = fixture.policies.findById(1L).orElseThrow();
      assertThat(policy.getStatus()).isEqualTo("PAUSED");
      assertThat(policy.getTotalDeletedCount()).isEqualTo(19);
      assertThat(policy.getLastDeletedCount()).isEqualTo(2);
      assertThat(policy.getLastError()).isNull();
      assertThat(
              fixture
                  .metrics
                  .get("DatabaseBlobStorage.policyCleanup.deletedRows")
                  .counter()
                  .count())
          .isEqualTo(2);
      assertThat(fixture.selects).hasSize(2);
      assertThat(fixture.deletes).hasSize(1);
      fixture.assertReusableConnection(connectionId);
      verifyNoInteractions(fixture.scheduler);
    }
  }

  @Test
  public void lockingRecheckPreservesCandidatesChangedByAnotherConnection() throws Exception {
    enabled();
    try (Fixture fixture = new Fixture(mysqlVersion)) {
      fixture.seedPolicy("pollable_task/");
      for (int id = 1; id <= 7; id++) {
        fixture.seedTask(100 + id, true, null, 0);
        fixture.seedBlob(id, "pollable_task/" + (100 + id) + "/input", 10, 1L);
      }
      fixture.afterCandidates =
          () -> {
            // The nonlocking candidate read must allow these independent committed changes.
            try (Connection connection = fixture.independentConnection();
                Statement update = connection.createStatement()) {
              update.setQueryTimeout(5);
              update.executeUpdate("UPDATE mblob SET expire_after_seconds=NULL WHERE id=1");
              update.executeUpdate("UPDATE mblob SET expire_after_seconds=2592000 WHERE id=2");
              update.executeUpdate("UPDATE pollable_task SET finished_date=NULL WHERE id=103");
              update.executeUpdate("UPDATE pollable_task SET parent_task_id=104 WHERE id=105");
              update.executeUpdate("UPDATE mblob SET name='other_prefix/moved' WHERE id=5");
              update.executeUpdate(
                  "UPDATE mblob SET created_date=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE id=6");
            }
          };

      fixture.runBounded();

      assertThat(fixture.blobs())
          .extracting(row -> ((Number) row.get("id")).longValue())
          .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
      DatabaseBlobCleanupPolicy policy = fixture.policies.findById(1L).orElseThrow();
      assertThat(policy.getTotalDeletedCount()).isEqualTo(18);
      assertThat(policy.getStatus()).isEqualTo("PAUSED");
      assertThat(fixture.selects).hasSize(2);
      assertThat(fixture.deletes).hasSize(1);
    }
  }

  @Test
  public void partialLockedCandidatePageDeletesOnlyUnlockedRows() throws Exception {
    enabled();
    try (Fixture fixture = new Fixture(mysqlVersion)) {
      fixture.seedPolicy("cleanup_test/");
      fixture.seedBlob(1, "cleanup_test/one", 10, 1L);
      fixture.seedBlob(2, "cleanup_test/two", 10, 1L);
      long connectionId = fixture.connectionId();
      try (Connection blocker = fixture.independentConnection();
          Statement lock = blocker.createStatement()) {
        blocker.setAutoCommit(false);
        lock.setQueryTimeout(5);
        lock.executeQuery("SELECT id FROM mblob WHERE id=1 FOR UPDATE").close();
        try {
          fixture.runBounded();
          DatabaseBlobCleanupPolicy policy = fixture.policies.findById(1L).orElseThrow();
          assertThat(policy.getStatus()).isEqualTo("PAUSED");
          assertThat(policy.getTotalDeletedCount()).isEqualTo(18);
          assertThat(fixture.blobs())
              .extracting(row -> ((Number) row.get("id")).longValue())
              .containsExactly(1L);
          assertThat(fixture.selects).hasSize(2);
          assertThat(fixture.deletes).hasSize(1);
          fixture.assertReusableConnection(connectionId);
        } finally {
          blocker.rollback();
        }
      }
    }
  }

  @Test
  public void allLockedCandidatesDoNotMeanDrainedOrTriggerUnboundedRefill() throws Exception {
    enabled();
    try (Fixture fixture = new Fixture(mysqlVersion)) {
      fixture.seedPolicy("cleanup_test/");
      fixture.jdbc.update("UPDATE mblob_cleanup_policy SET max_retries=0,batch_size=1 WHERE id=1");
      fixture.seedBlob(1, "cleanup_test/one", 10, 1L);
      fixture.seedBlob(2, "cleanup_test/two", 10, 1L);
      try (Connection blocker = fixture.independentConnection();
          Statement lock = blocker.createStatement()) {
        blocker.setAutoCommit(false);
        lock.setQueryTimeout(5);
        lock.executeQuery("SELECT id FROM mblob WHERE id=1 FOR UPDATE").close();
        try {
          fixture.runBounded();
          DatabaseBlobCleanupPolicy policy = fixture.policies.findById(1L).orElseThrow();
          assertThat(policy.getStatus()).isEqualTo("FAILED");
          assertThat(policy.getLastError()).contains("Eligible rows remain");
          assertThat(policy.getTotalDeletedCount()).isEqualTo(17);
          assertThat(fixture.blobs()).hasSize(2);
          assertThat(fixture.selects).hasSize(2);
          assertThat(fixture.deletes).isEmpty();
        } finally {
          blocker.rollback();
        }
      }
    }
  }

  private void enabled() {
    assumeTrue(
        "Enable with -Dmojito.asyncJobQueue.testcontainers=true",
        Boolean.getBoolean("mojito.asyncJobQueue.testcontainers"));
  }

  private static final class Execution {
    final int timeoutSeconds;
    SQLException failure;

    Execution(int timeoutSeconds) {
      this.timeoutSeconds = timeoutSeconds;
    }
  }

  @FunctionalInterface
  private interface CandidateAction {
    void run() throws SQLException;
  }

  private static final class Fixture implements AutoCloseable {
    final MySQLContainer<?> database;
    HikariDataSource pool;
    JdbcTemplate jdbc;
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final QuartzSchedulerManager scheduler = mock(QuartzSchedulerManager.class);
    final List<Execution> selects = new ArrayList<>();
    final List<Execution> deletes = new ArrayList<>();
    LocalContainerEntityManagerFactoryBean factoryBean;
    DatabaseBlobCleanupPolicyRepository policies;
    DatabaseBlobCleanupPolicyService service;
    CandidateAction afterCandidates = () -> {};

    Fixture(String version) {
      database =
          new MySQLContainer<>("mysql:" + version)
              .withConnectTimeoutSeconds(10)
              .withStartupTimeout(Duration.ofSeconds(60))
              .withCommand("--log-bin-trust-function-creators=1")
              .withUrlParam("connectTimeout", "5000")
              .withUrlParam("socketTimeout", "20000")
              .withUrlParam("queryTimeoutKillsConnection", "false");
      try {
        database.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(database.getJdbcUrl());
        config.setUsername(database.getUsername());
        config.setPassword(database.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setMaxLifetime(0);
        pool = new HikariDataSource(config);
        DataSource source = observe(pool);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith(version + ".");
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
            .withUserConfiguration(FlyWayConfig.class)
            .withBean(DataSource.class, () -> source)
            .withPropertyValues(
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.clean-disabled=true",
                "spring.flyway.baseline-on-migrate=false",
                "l10n.flyway.clean=false",
                "l10n.flyway.repair=false")
            .run(context -> assertThat(context).hasNotFailed());
        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setManagedTypes(
            PersistenceManagedTypes.of(
                MBlob.class.getName(), DatabaseBlobCleanupPolicy.class.getName()));
        factoryBean.setJpaPropertyMap(
            Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD",
                "jakarta.persistence.validation.mode", "none"));
        factoryBean.afterPropertiesSet();
        var factory = factoryBean.getObject();
        assertThat(factory).isNotNull();
        var transactionManager = new JpaTransactionManager(factory);
        transactionManager.setDataSource(source);
        transactionManager.afterPropertiesSet();
        var repositories =
            new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
        policies = repositories.getRepository(DatabaseBlobCleanupPolicyRepository.class);
        var blobs = repositories.getRepository(MBlobRepository.class);
        DBUtils dbUtils = mock(DBUtils.class);
        when(dbUtils.isMysql()).thenReturn(true);
        service =
            new DatabaseBlobCleanupPolicyService(
                policies, blobs, jdbc, dbUtils, scheduler, metrics, transactionManager);
        selects.clear();
        deletes.clear();
      } catch (RuntimeException | Error failure) {
        try {
          close();
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    void seedPolicy(String prefix) {
      jdbc.update(
          """
          INSERT INTO mblob_cleanup_policy
            (id,prefix,enabled,retention_days,batch_size,max_batches_per_run,pause_millis,
             max_retries,status,total_deleted_count)
          VALUES (1,?,true,3,20,1,0,2,'IDLE',17)
          """,
          prefix);
    }

    void seedBlob(long id, String name, int daysOld, Long ttl) {
      jdbc.update(
          "INSERT INTO mblob (id,name,created_date,expire_after_seconds,content)"
              + " VALUES (?,?,TIMESTAMPADD(DAY,?,CURRENT_TIMESTAMP),?,X'66697874757265')",
          id,
          name,
          -daysOld,
          ttl);
    }

    void seedTask(long id, boolean finished, Long parent, int expectedChildren) {
      jdbc.update(
          """
          INSERT INTO pollable_task
            (id,created_date,last_modified_date,finished_date,name,parent_task_id,expected_sub_task_number,timeout)
          VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,IF(?,CURRENT_TIMESTAMP,NULL),'cleanup-fixture',?,?,-1)
          """,
          id,
          finished,
          parent,
          expectedChildren);
    }

    List<Map<String, Object>> blobs() {
      return jdbc.queryForList(
          "SELECT id,name,created_date,expire_after_seconds,HEX(content) AS content_hex FROM mblob ORDER BY id");
    }

    Connection independentConnection() throws SQLException {
      return DriverManager.getConnection(
          database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    long connectionId() {
      return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    void runBounded() throws Exception {
      var worker = Executors.newSingleThreadExecutor();
      try {
        long started = System.nanoTime();
        worker
            .submit(
                () -> {
                  service.runPolicy(1);
                  Map<Object, Object> resources =
                      TransactionSynchronizationManager.getResourceMap();
                  assertThat(resources).isEmpty();
                  assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                      .isFalse();
                  assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                })
            .get(30, TimeUnit.SECONDS);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .isLessThan(Duration.ofSeconds(25));
      } finally {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
      }
    }

    void assertDisabledFailure(String phase, int selections, int deletions) {
      DatabaseBlobCleanupPolicy policy = policies.findById(1L).orElseThrow();
      assertThat(policy.isEnabled()).isFalse();
      assertThat(policy.isStopRequested()).isFalse();
      assertThat(policy.getStatus()).isEqualTo("FAILED");
      assertThat(policy.getTotalDeletedCount()).isEqualTo(17);
      assertThat(policy.getLastDeletedCount()).isZero();
      assertThat(policy.getLastFinishedDate()).isNotNull();
      assertThat(policy.getLastError()).contains("during " + phase, "reconcile deleted rows");
      assertThat(metrics.find("DatabaseBlobStorage.policyCleanup.deletedRows").counter()).isNull();
      service.runEnabledPolicies();
      assertThat(selects).hasSize(selections);
      assertThat(deletes).hasSize(deletions);
      verifyNoInteractions(scheduler);
    }

    void assertRealCancellation(List<Execution> executions) {
      assertThat(executions).hasSize(1);
      Execution execution = executions.getFirst();
      assertThat(execution.timeoutSeconds)
          .isBetween(1, DatabaseBlobCleanupPolicyService.EXECUTION_BUDGET_SECONDS);
      assertThat((Throwable) execution.failure).isInstanceOf(SQLTimeoutException.class);
    }

    void assertReusableConnection(long connectionId) {
      assertThat(connectionId()).isEqualTo(connectionId);
      Integer probe = jdbc.queryForObject("SELECT 1", Integer.class);
      assertThat(probe).isEqualTo(1);
      assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isEqualTo(1);
      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
      assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
    }

    DataSource observe(DataSource target) {
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
                      if (!method.getName().equals("prepareStatement")) return result;
                      String sql =
                          ((String) args[0]).stripLeading().toLowerCase(java.util.Locale.ROOT);
                      boolean select = sql.startsWith("select id from mblob");
                      boolean delete = sql.startsWith("delete from mblob where id in");
                      if (!select && !delete) return result;
                      PreparedStatement statement = (PreparedStatement) result;
                      return Proxy.newProxyInstance(
                          getClass().getClassLoader(),
                          new Class<?>[] {PreparedStatement.class},
                          (ps, operation, values) -> {
                            Execution execution = null;
                            if (operation.getName().equals("executeQuery")
                                || operation.getName().equals("executeUpdate")) {
                              execution = new Execution(statement.getQueryTimeout());
                              (select ? selects : deletes).add(execution);
                            }
                            try {
                              Object value = operation.invoke(statement, values);
                              if (select
                                  && !sql.contains("where id in (")
                                  && operation.getName().equals("executeQuery")) {
                                afterCandidates.run();
                              }
                              return value;
                            } catch (InvocationTargetException failure) {
                              if (execution != null
                                  && failure.getCause() instanceof SQLException sqlFailure)
                                execution.failure = sqlFailure;
                              throw failure.getCause();
                            }
                          });
                    } catch (InvocationTargetException failure) {
                      throw failure.getCause();
                    }
                  });
        }
      };
    }

    @Override
    public void close() {
      try {
        if (factoryBean != null) factoryBean.destroy();
      } finally {
        try {
          if (pool != null) pool.close();
          metrics.close();
        } finally {
          database.close();
        }
      }
    }
  }
}
