package com.box.l10n.mojito.service.repository;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public class GlobalGenerationPersistenceTest {

  @Test
  public void embeddedSchemaSeedsDefaultAndPreservesSavedExclusionsOnReinitialization() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:global-generation-embedded-" + UUID.randomUUID(), "sa", "");
    var jdbc = new JdbcTemplate(dataSource);
    var locales = mock(LocaleService.class);
    Locale french = new Locale();
    french.setBcp47Tag("fr-FR");
    when(locales.findByBcp47Tag("fr-FR")).thenReturn(french);
    var schema = new ResourceDatabasePopulator(new ClassPathResource("db/hsql/schema.sql"));
    try {
      schema.execute(dataSource);
      var service = new GlobalGenerationService(jdbc, locales);
      assertEquals(List.of(), service.getExcludedLocaleTags());

      service.updateExcludedLocaleTags(List.of("fr-FR"));
      schema.execute(dataSource);

      assertEquals(
          List.of("fr-FR"),
          new GlobalGenerationService(new JdbcTemplate(dataSource), locales)
              .getExcludedLocaleTags());
      assertEquals(
          Integer.valueOf(1),
          jdbc.queryForObject("select count(*) from generation_settings", Integer.class));
    } finally {
      jdbc.execute("shutdown");
    }
  }

  @Test
  public void migrationSeedsOneEmptySettingAndReplacementsSurviveServiceRecreation() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:global-generation-" + UUID.randomUUID() + ";sql.syntax_mys=true",
            "sa",
            "");
    var jdbc = new JdbcTemplate(dataSource);
    var locales = mock(LocaleService.class);
    Locale french = new Locale();
    french.setBcp47Tag("fr-FR");
    when(locales.findByBcp47Tag("fr-FR")).thenReturn(french);
    try {
      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V129__Generation_Settings.sql"))
          .execute(dataSource);
      var service = new GlobalGenerationService(jdbc, locales);
      assertEquals(List.of(), service.getExcludedLocaleTags());
      assertEquals(
          Integer.valueOf(1),
          jdbc.queryForObject("select count(*) from generation_settings", Integer.class));
      assertEquals(
          "[]",
          jdbc.queryForObject(
              "select excluded_locale_tags_json from generation_settings where id = 1",
              String.class));

      service.updateExcludedLocaleTags(List.of("fr-FR"));
      assertEquals(
          "[\"fr-FR\"]",
          jdbc.queryForObject(
              "select excluded_locale_tags_json from generation_settings where id = 1",
              String.class));
      var restarted = new GlobalGenerationService(new JdbcTemplate(dataSource), locales);
      assertEquals(List.of("fr-FR"), restarted.getExcludedLocaleTags());

      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V129__Generation_Settings.sql"))
          .execute(dataSource);
      assertEquals(List.of("fr-FR"), restarted.getExcludedLocaleTags());

      restarted.updateExcludedLocaleTags(List.of());
      assertEquals(List.of(), service.getExcludedLocaleTags());
      assertEquals(
          Integer.valueOf(1),
          jdbc.queryForObject("select count(*) from generation_settings", Integer.class));
    } finally {
      jdbc.execute("shutdown");
    }
  }
}
