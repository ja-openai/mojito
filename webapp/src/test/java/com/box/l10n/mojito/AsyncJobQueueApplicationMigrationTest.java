package com.box.l10n.mojito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/** Exercises Boot's production migration strategy, not the full application or queue workers. */
@RunWith(Parameterized.class)
public class AsyncJobQueueApplicationMigrationTest {

  private static final String ENABLE_PROPERTY = "mojito.asyncJobQueue.testcontainers";

  @Parameterized.Parameters(name = "{0}")
  public static List<String> mysqlVersions() {
    return List.of("8.0", "8.4");
  }

  @Parameterized.Parameter public String mysqlVersion = "8.4";

  @Test
  public void bootInstallsFullApplicationThroughV113AndRestartsWithoutChanges() {
    assumeTrue("Enable with -D" + ENABLE_PROPERTY + "=true", Boolean.getBoolean(ENABLE_PROPERTY));
    try (MySQLContainer<?> container = mysqlContainer()) {
      container.start();
      DataSource dataSource = dataSource(container);
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);

      runBootMigration(dataSource, "113", flyway -> assertMigrated(flyway, 112, "113"));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM async_job_queue", Long.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM locale", Long.class)).isPositive();
      seedLegacyRows(jdbc);
      seedQueueRow(jdbc);
      var history = history(jdbc);
      var legacy = legacyRows(jdbc);
      var queue = jdbc.queryForList("SELECT * FROM async_job_queue ORDER BY id");

      runBootMigration(dataSource, "113", flyway -> assertMigrated(flyway, 112, "113"));
      assertThat(history(jdbc)).isEqualTo(history);
      assertThat(legacyRows(jdbc)).isEqualTo(legacy);
      assertThat(jdbc.queryForList("SELECT * FROM async_job_queue ORDER BY id")).isEqualTo(queue);
    }
  }

  @Test
  public void bootUpgradesPopulatedV112WithOnlyQueueMigrationAndPreservesHistoryOnRestart() {
    assumeTrue("Enable with -D" + ENABLE_PROPERTY + "=true", Boolean.getBoolean(ENABLE_PROPERTY));
    try (MySQLContainer<?> container = mysqlContainer()) {
      container.start();
      DataSource dataSource = dataSource(container);
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);

      // Use the maintained application chain at the master boundary, including Java migrations.
      runBootMigration(dataSource, "112", flyway -> assertMigrated(flyway, 111, "112"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.tables"
                      + " WHERE table_schema = DATABASE() AND table_name = 'async_job_queue'",
                  Long.class))
          .isZero();
      seedLegacyRows(jdbc);
      var baselineHistory = history(jdbc);
      var legacy = legacyRows(jdbc);

      runBootMigration(dataSource, "113", flyway -> assertMigrated(flyway, 112, "113"));
      var upgradedHistory = history(jdbc);
      assertThat(upgradedHistory).hasSize(baselineHistory.size() + 1);
      assertThat(upgradedHistory.subList(0, baselineHistory.size())).isEqualTo(baselineHistory);
      assertThat(upgradedHistory.getLast())
          .containsEntry("version", "113")
          .containsEntry("script", "V113__Async_Job_Queue.sql");
      assertThat(legacyRows(jdbc)).isEqualTo(legacy);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM async_job_queue", Long.class)).isZero();
      seedQueueRow(jdbc);
      var queue = jdbc.queryForList("SELECT * FROM async_job_queue ORDER BY id");

      runBootMigration(dataSource, "113", flyway -> assertMigrated(flyway, 112, "113"));
      assertThat(history(jdbc)).isEqualTo(upgradedHistory);
      assertThat(legacyRows(jdbc)).isEqualTo(legacy);
      assertThat(jdbc.queryForList("SELECT * FROM async_job_queue ORDER BY id")).isEqualTo(queue);
    }
  }

  private void runBootMigration(DataSource dataSource, String target, Consumer<Flyway> assertions) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
        .withUserConfiguration(FlyWayConfig.class)
        .withBean(DataSource.class, () -> dataSource)
        .withPropertyValues(
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.target=" + target,
            "spring.flyway.validate-migration-naming=true",
            "spring.flyway.clean-disabled=true",
            "spring.flyway.baseline-on-migrate=false",
            "l10n.flyway.clean=false",
            "l10n.flyway.repair=false",
            "l10n.org.async-job-queue.enabled=false",
            "l10n.org.async-job-queue.store=jdbc",
            "l10n.org.async-job-queue.asset-localize.enabled=false",
            "l10n.org.async-job-queue.asset-localize.producer-enabled=false",
            "l10n.org.async-job-queue.asset-localize.fanout-enabled=false",
            "l10n.org.async-job-queue.queues.assetlocalize.consumer-enabled=false",
            "l10n.org.async-job-queue.retention.enabled=false")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(Flyway.class)
                  .hasSingleBean(FlyWayConfig.class)
                  .hasSingleBean(FlywayMigrationStrategy.class)
                  .hasSingleBean(FlywayMigrationInitializer.class);
              FlyWayConfig strategyConfiguration = context.getBean(FlyWayConfig.class);
              assertThat(strategyConfiguration.isClean()).isFalse();
              assertThat(strategyConfiguration.isRepair()).isFalse();
              assertThat(context.getBean(FlywayMigrationStrategy.class))
                  .isSameAs(strategyConfiguration.cleanMigrateStrategy());
              // Boot's initializer has already invoked the real strategy. Never migrate manually.
              Flyway flyway = context.getBean(Flyway.class);
              assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
              assertions.accept(flyway);
              flyway.validate();
            });
  }

  private void assertMigrated(Flyway flyway, int count, String version) {
    MigrationInfo[] applied = flyway.info().applied();
    assertThat(applied)
        .hasSize(count)
        .allSatisfy(
            migration -> assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
    assertThat(applied)
        .filteredOn(migration -> migration.getType() == CoreMigrationType.SQL)
        .hasSize(count - 2)
        .allSatisfy(migration -> assertThat(migration.getChecksum()).isNotNull());
    assertThat(applied)
        .filteredOn(migration -> migration.getType() == CoreMigrationType.JDBC)
        .extracting(MigrationInfo::getVersion)
        .containsExactly(MigrationVersion.fromVersion("9"), MigrationVersion.fromVersion("56"));
    assertThat(flyway.info().current().getVersion())
        .isEqualTo(MigrationVersion.fromVersion(version));
    if ("113".equals(version)) {
      assertThat(flyway.info().current().getScript()).isEqualTo("V113__Async_Job_Queue.sql");
      assertThat(flyway.info().pending()).isEmpty();
    }
  }

  private void seedLegacyRows(JdbcTemplate jdbc) {
    jdbc.update(
        "INSERT INTO pollable_task"
            + " (id, created_date, last_modified_date, expected_sub_task_number, finished_date,"
            + " message, name, timeout)"
            + " VALUES (900001, '2026-01-01 00:00:00', '2026-01-01 00:01:00', 0,"
            + " '2026-01-01 00:01:00', 'legacy output ready', 'legacy-quartz-localization', -1)");
    jdbc.update(
        "INSERT INTO mblob (id, created_date, content, expire_after_seconds, name)"
            + " VALUES (900001, '2026-01-01 00:00:00', ?, 86400, 'migration-legacy-input')",
        "legacy input: café".getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "INSERT INTO ai_review_request_usage"
            + " (id, pollable_task_id, locale, surface, request_type, profile_id, model_name,"
            + " status, started_at, finished_at, duration_ms, request_json, response_json)"
            + " VALUES (900001, 900001, 'fr-FR', 'review-project', 'interactive', 'test',"
            + " 'test-model', 'completed', '2026-01-01 00:00:00', '2026-01-01 00:01:00',"
            + " 60000, '{\"fixture\":true}', '{\"preserved\":true}')");
  }

  private void seedQueueRow(JdbcTemplate jdbc) {
    jdbc.update(
        "INSERT INTO async_job_queue (queue_name, status, available_at, job_data)"
            + " VALUES ('migration-rehearsal', 'queued', '2026-01-01 00:00:00',"
            + " '{\"fixture\":true}')");
  }

  private List<Map<String, Object>> history(JdbcTemplate jdbc) {
    return jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
  }

  private Map<String, List<Map<String, Object>>> legacyRows(JdbcTemplate jdbc) {
    return Map.of(
        "tasks", jdbc.queryForList("SELECT * FROM pollable_task ORDER BY id"),
        "blobs",
            jdbc.queryForList(
                "SELECT id, created_date, HEX(content) AS content_hex, expire_after_seconds, name"
                    + " FROM mblob ORDER BY id"),
        "usage", jdbc.queryForList("SELECT * FROM ai_review_request_usage ORDER BY id"),
        "locales", jdbc.queryForList("SELECT * FROM locale ORDER BY id"));
  }

  private MySQLContainer<?> mysqlContainer() {
    return new MySQLContainer<>("mysql:" + mysqlVersion)
        .withConnectTimeoutSeconds(10)
        .withUrlParam("connectTimeout", "5000")
        .withUrlParam("socketTimeout", "30000");
  }

  private DataSource dataSource(MySQLContainer<?> container) {
    DataSource dataSource =
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
    assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT VERSION()", String.class))
        .startsWith(mysqlVersion + ".");
    return dataSource;
  }
}
