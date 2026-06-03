package com.box.l10n.mojito.service.pullrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.box.l10n.mojito.entity.PullRunAsset;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * JDBC binding and woven batch transactions only, not ORM replacement or migration verification.
 */
@RunWith(Parameterized.class)
public class PullRunAssetServiceJdbcTest {

  private static final long ASSET_ID = 9_000_000_000L;
  private static final long LOCALE_ID = 8_000_000_000L;
  private static final String HSQL_URL = "jdbc:hsqldb:mem:pull_run_binding_" + UUID.randomUUID();
  private static final Map<String, JdbcDatabaseContainer<?>> CONTAINERS = new LinkedHashMap<>();

  @Parameterized.Parameters(name = "{0}, serverPrepared={1}")
  public static List<Object[]> databases() {
    List<Object[]> databases = new ArrayList<>();
    databases.add(new Object[] {"HSQL", false});
    if (Boolean.getBoolean("mojito.asyncJobQueue.testcontainers")) {
      databases.add(new Object[] {"MYSQL", false});
      databases.add(new Object[] {"MYSQL", true});
      databases.add(new Object[] {"POSTGRESQL", false});
      databases.add(new Object[] {"POSTGRESQL", true});
    }
    return databases;
  }

  @BeforeClass
  public static void openDatabases() throws Exception {
    try {
      createSchema(new JdbcTemplate(new DriverManagerDataSource(HSQL_URL, "sa", "")), "HSQL");
      if (Boolean.getBoolean("mojito.asyncJobQueue.testcontainers")) {
        CONTAINERS.put(
            "MYSQL",
            new MySQLContainer<>("mysql:8.4")
                .withConnectTimeoutSeconds(10)
                .withUrlParam("connectTimeout", "5000")
                .withUrlParam("socketTimeout", "30000"));
        CONTAINERS.put(
            "POSTGRESQL",
            new PostgreSQLContainer<>("postgres:16")
                .withConnectTimeoutSeconds(10)
                .withUrlParam("connectTimeout", "5")
                .withUrlParam("socketTimeout", "30"));
        for (Map.Entry<String, JdbcDatabaseContainer<?>> entry : CONTAINERS.entrySet()) {
          JdbcDatabaseContainer<?> container = entry.getValue();
          container.start();
          // Bound connection readiness only; schema SQL and later operations are not retried.
          try (Connection connection = container.createConnection("")) {
            createSchema(
                new JdbcTemplate(new SingleConnectionDataSource(connection, true)), entry.getKey());
          }
        }
      }
    } catch (Exception | Error failure) {
      try {
        closeDatabases();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  @AfterClass
  public static void closeDatabases() {
    try {
      // Attempt every cleanup, even if another container cannot be stopped.
      Throwable failure = null;
      for (JdbcDatabaseContainer<?> container : CONTAINERS.values()) {
        try {
          container.close();
        } catch (RuntimeException | Error cleanupFailure) {
          if (failure == null) {
            failure = cleanupFailure;
          } else if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
      }
      if (failure instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (failure instanceof Error error) {
        throw error;
      }
    } finally {
      CONTAINERS.clear();
      new JdbcTemplate(new DriverManagerDataSource(HSQL_URL, "sa", "")).execute("SHUTDOWN");
    }
  }

  private static void createSchema(JdbcTemplate jdbc, String database) {
    jdbc.execute("CREATE TABLE tm_text_unit_variant (id BIGINT PRIMARY KEY)");
    // Retain milliseconds to detect accidental precision loss as well as timezone conversion.
    String timestampType = database.equals("MYSQL") ? "DATETIME(3)" : "TIMESTAMP(3)";
    jdbc.execute(
        "CREATE TABLE pull_run_text_unit_variant ("
            + "pull_run_asset_id BIGINT NOT NULL, locale_id BIGINT NOT NULL, "
            + "tm_text_unit_variant_id BIGINT NOT NULL, created_date "
            + timestampType
            + " NOT NULL, output_bcp47_tag VARCHAR(20), "
            + "UNIQUE (pull_run_asset_id, locale_id, tm_text_unit_variant_id, output_bcp47_tag), "
            + "FOREIGN KEY (tm_text_unit_variant_id) REFERENCES tm_text_unit_variant(id))");
    jdbc.update(
        "INSERT INTO tm_text_unit_variant (id) VALUES "
            + LongStream.rangeClosed(1, 1000)
                .mapToObj(id -> "(" + id + ")")
                .collect(Collectors.joining(",")));
  }

  private final String database;
  private final boolean serverPrepared;
  private final PullRunAssetService service = new PullRunAssetService();
  private JdbcTemplate jdbc;
  private DataSource dataSource;
  private TransactionManager previousManager;
  private boolean adviceChanged;
  private int begins;
  private int commits;
  private int rollbacks;

  public PullRunAssetServiceJdbcTest(String database, boolean serverPrepared) {
    this.database = database;
    this.serverPrepared = serverPrepared;
  }

  @Before
  public void configureBatchTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    String expectedSessionZone = null;
    if (database.equals("HSQL")) {
      dataSource = new DriverManagerDataSource(HSQL_URL, "sa", "");
    } else {
      JdbcDatabaseContainer<?> container = CONTAINERS.get(database);
      DriverManagerDataSource target =
          new DriverManagerDataSource(
              container.getJdbcUrl(), container.getUsername(), container.getPassword());
      boolean useTokyo = ZonedDateTime.now().getOffset().getTotalSeconds() != 9 * 3600;
      Properties properties = new Properties();
      if (database.equals("MYSQL")) {
        expectedSessionZone = useTokyo ? "+09:00" : "-07:00";
        properties.setProperty("connectionTimeZone", expectedSessionZone);
        properties.setProperty("forceConnectionTimeZoneToSession", "true");
        properties.setProperty("preserveInstants", "true");
        properties.setProperty("useServerPrepStmts", Boolean.toString(serverPrepared));
      } else {
        expectedSessionZone = useTokyo ? "Asia/Tokyo" : "America/Phoenix";
        properties.setProperty("prepareThreshold", serverPrepared ? "-1" : "0");
      }
      target.setConnectionProperties(properties);
      dataSource =
          database.equals("POSTGRESQL")
              ? new SessionDataSource(target, expectedSessionZone)
              : target;
    }
    jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(10);
    if (expectedSessionZone != null) {
      assertThat(
              jdbc.queryForObject(
                  database.equals("MYSQL") ? "SELECT @@session.time_zone" : "SHOW TIME ZONE",
                  String.class))
          .isEqualTo(expectedSessionZone);
    }
    jdbc.update("DELETE FROM pull_run_text_unit_variant");
    service.jdbcTemplate = jdbc;
    DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
    manager.setTransactionExecutionListeners(
        List.of(
            new TransactionExecutionListener() {
              @Override
              public void afterBegin(TransactionExecution transaction, Throwable failure) {
                assertThat(failure).isNull();
                assertThat(transaction.isNewTransaction()).isTrue();
                begins++;
              }

              @Override
              public void afterCommit(TransactionExecution transaction, Throwable failure) {
                assertThat(failure).isNull();
                commits++;
              }

              @Override
              public void afterRollback(TransactionExecution transaction, Throwable failure) {
                assertThat(failure).isNull();
                rollbacks++;
              }
            }));
    // Exercise the production AspectJ join point, never a test-created ambient transaction.
    AnnotationTransactionAspect aspect = AnnotationTransactionAspect.aspectOf();
    previousManager = aspect.getTransactionManager();
    aspect.setTransactionManager(manager);
    adviceChanged = true;
  }

  @After
  public void restoreAdvice() {
    try {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if (dataSource != null) {
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
      }
    } finally {
      if (adviceChanged) {
        AnnotationTransactionAspect.aspectOf().setTransactionManager(previousManager);
      }
    }
  }

  @Test
  public void nullLiteralNullAndQuotedTagsRemainDistinctAcrossMultirowInserts() {
    insert(List.of(1L, 2L), null);
    insert(List.of(3L, 4L), "null");
    insert(List.of(5L, 6L), "fr'CA");

    List<Row> rows = rows();
    assertThat(rows).hasSize(6);
    assertThat(rows)
        .filteredOn(row -> row.tag() == null)
        .extracting(Row::variantId)
        .containsExactly(1L, 2L);
    assertThat(rows)
        .filteredOn(row -> "null".equals(row.tag()))
        .extracting(Row::variantId)
        .containsExactly(3L, 4L);
    assertThat(rows)
        .filteredOn(row -> "fr'CA".equals(row.tag()))
        .extracting(Row::variantId)
        .containsExactly(5L, 6L);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.assetId()).isEqualTo(ASSET_ID);
              assertThat(row.localeId()).isEqualTo(LOCALE_ID);
            });
    assertTransactions(3, 3, 0);
  }

  @Test
  public void createdDateRetainsJvmLocalFieldsAndMillisecondPrecision() {
    LocalDateTime before = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    insert(List.of(1L, 2L), null);
    LocalDateTime after = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

    List<Row> rows = rows();
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).createdDate()).isBetween(before, after);
    assertThat(rows.get(0).createdDate().getNano() % 1_000_000).isZero();
    assertThat(rows.get(1).createdDate()).isEqualTo(rows.get(0).createdDate());
    assertTransactions(1, 1, 0);
  }

  @Test
  public void fullThousandRowBatchBindsEveryParameter() {
    List<Long> ids = LongStream.rangeClosed(1, 1000).boxed().toList();
    insert(ids, null);

    assertThat(rows()).extracting(Row::variantId).containsExactlyElementsOf(ids);
    assertTransactions(1, 1, 0);
  }

  @Test
  public void invalidForeignKeyRollsBackBatchButNotEarlierCommittedBatch() {
    insert(List.of(1000L), "existing");

    assertThrows(DataIntegrityViolationException.class, () -> insert(List.of(1L, 1001L, 2L), null));

    assertThat(rows()).extracting(Row::variantId).containsExactly(1000L);
    assertThat(rows().get(0).tag()).isEqualTo("existing");
    assertTransactions(2, 1, 1);
  }

  private void insert(List<Long> ids, String tag) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    PullRunAsset asset = new PullRunAsset();
    asset.setId(ASSET_ID);
    service.saveTextUnitVariantsMultiRowBatch(asset, LOCALE_ID, ids, tag);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  private void assertTransactions(int expectedBegins, int expectedCommits, int expectedRollbacks) {
    assertThat(begins).isEqualTo(expectedBegins);
    assertThat(commits).isEqualTo(expectedCommits);
    assertThat(rollbacks).isEqualTo(expectedRollbacks);
  }

  private List<Row> rows() {
    return jdbc.query(
        "SELECT pull_run_asset_id, locale_id, tm_text_unit_variant_id, created_date, output_bcp47_tag "
            + "FROM pull_run_text_unit_variant ORDER BY tm_text_unit_variant_id",
        (result, index) ->
            new Row(
                result.getLong(1),
                result.getLong(2),
                result.getLong(3),
                result.getObject(4, LocalDateTime.class),
                result.getString(5)));
  }

  private record Row(
      long assetId, long localeId, long variantId, LocalDateTime createdDate, String tag) {}

  private static final class SessionDataSource extends DelegatingDataSource {
    private final String timeZone;

    private SessionDataSource(DataSource target, String timeZone) {
      super(target);
      this.timeZone = timeZone;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return configure(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return configure(super.getConnection(username, password));
    }

    private Connection configure(Connection connection) throws SQLException {
      // The PostgreSQL driver sets its own startup TimeZone; apply the test zone afterward.
      try (Statement statement = connection.createStatement()) {
        statement.setQueryTimeout(10);
        statement.execute("SET TIME ZONE '" + timeZone + "'");
        return connection;
      } catch (SQLException failure) {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    }
  }
}
