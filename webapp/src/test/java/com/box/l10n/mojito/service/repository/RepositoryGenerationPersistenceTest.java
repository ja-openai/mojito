package com.box.l10n.mojito.service.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

public class RepositoryGenerationPersistenceTest {

  @Test
  public void independentSettingsRoundTripWithoutChangingRepositoryOrAuditTables() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:repository-generation-" + UUID.randomUUID() + ";sql.syntax_mys=true",
            "sa",
            "");
    var jdbc = new JdbcTemplate(dataSource);
    var factory = new LocalContainerEntityManagerFactoryBean();
    try {
      jdbc.execute("create table repository (id bigint primary key, name varchar(255))");
      jdbc.execute(
          "create table repository_aud (id bigint, rev integer, name varchar(255), primary key (id, rev))");
      jdbc.update("insert into repository (id, name) values (7, 'example-project')");
      jdbc.update("insert into repository_aud (id, rev, name) values (7, 1, 'example-project')");
      Map<String, Object> repositoryBefore = jdbc.queryForMap("select * from repository");
      Map<String, Object> auditBefore = jdbc.queryForMap("select * from repository_aud");

      new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V129__Generation_Settings.sql"))
          .execute(dataSource);

      factory.setDataSource(dataSource);
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setManagedTypes(
          PersistenceManagedTypes.of(RepositoryGenerationSettings.class.getName()));
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
      factory.afterPropertiesSet();
      EntityManagerFactory entityManagerFactory = factory.getObject();
      assertNotNull(entityManagerFactory);

      try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
        assertNull(entityManager.find(RepositoryGenerationSettings.class, 7L));
        entityManager.getTransaction().begin();
        RepositoryGenerationSettings settings = new RepositoryGenerationSettings();
        settings.setRepositoryId(7L);
        settings.setExcludedLocaleTags(List.of("fr", "ja-JP"));
        entityManager.persist(settings);
        entityManager.getTransaction().commit();
      }
      assertEquals(
          "[\"fr\",\"ja-JP\"]",
          jdbc.queryForObject(
              "select excluded_locale_tags_json from repository_generation_settings where repository_id = 7",
              String.class));

      RepositoryGenerationSettings detached;
      try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
        detached = entityManager.find(RepositoryGenerationSettings.class, 7L);
      }
      assertEquals(List.of("fr", "ja-JP"), detached.getExcludedLocaleTags());

      try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
        entityManager.getTransaction().begin();
        entityManager.find(RepositoryGenerationSettings.class, 7L).setExcludedLocaleTags(List.of());
        entityManager.getTransaction().commit();
      }
      try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
        assertEquals(
            List.of(),
            entityManager.find(RepositoryGenerationSettings.class, 7L).getExcludedLocaleTags());
      }
      assertEquals(repositoryBefore, jdbc.queryForMap("select * from repository"));
      assertEquals(auditBefore, jdbc.queryForMap("select * from repository_aud"));
    } finally {
      factory.destroy();
      jdbc.execute("shutdown");
    }
  }
}
