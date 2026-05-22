package com.box.l10n.mojito.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.Assume;
import org.junit.Test;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

public class PostgresFlywayMigrationTest {

  @Test
  public void migratesPostgresSchema() throws Exception {
    assumePostgresTestsEnabled();

    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
      postgres.start();

      Flyway.configure()
          .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
          .locations("classpath:db/migration/postgresql")
          .load()
          .migrate();

      try (Connection connection =
          DriverManager.getConnection(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
        assertThat(countRows(connection, "select count(*) from locale")).isGreaterThan(900);
        assertThat(countRows(connection, "select count(*) from plural_form")).isEqualTo(6);
        assertThat(countRows(connection, "select count(*) from \"user\"")).isEqualTo(0);
        assertThat(countRows(connection, "select count(*) from \"commit\"")).isEqualTo(0);
        assertThat(
                countRows(
                    connection,
                    "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name = 'spring_session_v2'"))
            .isEqualTo(1);
        assertThat(
                countRows(
                    connection,
                    "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name = 'qrtz_triggers'"))
            .isEqualTo(1);
        assertThat(columnType(connection, "spring_session_v2_attributes", "attribute_bytes"))
            .isEqualTo("bytea");
        assertThat(columnType(connection, "qrtz_job_details", "job_data")).isEqualTo("bytea");
        assertThat(columnType(connection, "qrtz_triggers", "job_data")).isEqualTo("bytea");
        assertThat(columnType(connection, "qrtz_blob_triggers", "blob_data")).isEqualTo("bytea");
      }

      DataSource dataSource =
          new DriverManagerDataSource(
              postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
      assertQuartzPostgresDelegateCanPersistTriggers(dataSource);
      assertSpringSessionJdbcCanPersistAttributes(dataSource);
    }
  }

  private void assertQuartzPostgresDelegateCanPersistTriggers(DataSource dataSource)
      throws Exception {
    DriverManagerDataSource driverManagerDataSource = (DriverManagerDataSource) dataSource;
    Properties properties = new Properties();
    properties.setProperty("org.quartz.scheduler.instanceName", "postgresSmoke");
    properties.setProperty("org.quartz.scheduler.instanceId", "AUTO");
    properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
    properties.setProperty("org.quartz.threadPool.threadCount", "1");
    properties.setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
    properties.setProperty(
        "org.quartz.jobStore.driverDelegateClass",
        "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
    properties.setProperty("org.quartz.jobStore.isClustered", "true");
    properties.setProperty("org.quartz.jobStore.useProperties", "true");
    properties.setProperty("org.quartz.jobStore.dataSource", "myDS");
    properties.setProperty("org.quartz.dataSource.myDS.provider", "hikaricp");
    properties.setProperty("org.quartz.dataSource.myDS.driver", "org.postgresql.Driver");
    properties.setProperty("org.quartz.dataSource.myDS.URL", driverManagerDataSource.getUrl());
    properties.setProperty(
        "org.quartz.dataSource.myDS.user", driverManagerDataSource.getUsername());
    properties.setProperty(
        "org.quartz.dataSource.myDS.password", driverManagerDataSource.getPassword());
    properties.setProperty("org.quartz.dataSource.myDS.maxConnections", "2");
    properties.setProperty("org.quartz.dataSource.myDS.validationQuery", "select 1");

    Scheduler scheduler = new StdSchedulerFactory(properties).getScheduler();
    try {
      scheduler.clear();
      scheduler.scheduleJob(
          newJob(NoOpQuartzJob.class).withIdentity("postgres-smoke-job", "POSTGRES_SMOKE").build(),
          newTrigger()
              .withIdentity("postgres-smoke-trigger", "POSTGRES_SMOKE")
              .startAt(Date.from(Instant.now().plus(Duration.ofHours(1))))
              .build());
    } finally {
      scheduler.shutdown(true);
    }

    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from qrtz_job_details where sched_name = 'postgresSmoke'",
                Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from qrtz_triggers where sched_name = 'postgresSmoke'",
                Long.class))
        .isEqualTo(1L);
  }

  private void assertSpringSessionJdbcCanPersistAttributes(DataSource dataSource) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    JdbcIndexedSessionRepository sessionRepository =
        new JdbcIndexedSessionRepository(
            jdbcTemplate, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    sessionRepository.setTableName("SPRING_SESSION_V2");
    sessionRepository.setCleanupCron("-");
    sessionRepository.afterPropertiesSet();
    @SuppressWarnings({"rawtypes", "unchecked"})
    FindByIndexNameSessionRepository<Session> repository =
        (FindByIndexNameSessionRepository) sessionRepository;
    try {
      Session session = repository.createSession();
      session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "admin");
      session.setAttribute("probe", "value");
      repository.save(session);

      Session reloaded = repository.findById(session.getId());
      assertThat(reloaded).isNotNull();
      assertThat(reloaded.<String>getAttribute("probe")).isEqualTo("value");
      assertThat(
              repository.findByIndexNameAndIndexValue(
                  FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "admin"))
          .containsKey(session.getId());

      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from spring_session_v2_attributes "
                      + "where attribute_name = 'probe' and attribute_bytes is not null",
                  Long.class))
          .isEqualTo(1L);
    } finally {
      sessionRepository.destroy();
    }
  }

  private void assumePostgresTestsEnabled() {
    Assume.assumeTrue(
        "Set -Dmojito.test.postgres=true to run Postgres migration smoke tests",
        Boolean.getBoolean("mojito.test.postgres"));
    Assume.assumeTrue(
        "A listening Docker socket or DOCKER_HOST is required for Postgres Testcontainers",
        dockerEndpointLooksAvailable());
  }

  private boolean dockerEndpointLooksAvailable() {
    String dockerHost = System.getenv("DOCKER_HOST");
    if (dockerHost != null && !dockerHost.isBlank()) {
      if (dockerHost.startsWith("unix://")) {
        return canConnectToUnixSocket(Path.of(dockerHost.substring("unix://".length())));
      }

      return true;
    }

    return canConnectToUnixSocket(Path.of("/var/run/docker.sock"))
        || canConnectToUnixSocket(
            Path.of(System.getProperty("user.home"), ".docker/run/docker.sock"));
  }

  private boolean canConnectToUnixSocket(Path socketPath) {
    try (SocketChannel socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      return socketChannel.connect(UnixDomainSocketAddress.of(socketPath));
    } catch (IOException e) {
      return false;
    }
  }

  private long countRows(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private String columnType(Connection connection, String tableName, String columnName)
      throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select data_type from information_schema.columns "
                    + "where table_schema = 'public' "
                    + "and table_name = '"
                    + tableName
                    + "' "
                    + "and column_name = '"
                    + columnName
                    + "'")) {
      resultSet.next();
      return resultSet.getString(1);
    }
  }

  public static class NoOpQuartzJob implements Job {
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {}
  }
}
