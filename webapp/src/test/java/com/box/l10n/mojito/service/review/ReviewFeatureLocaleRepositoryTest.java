package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

public class ReviewFeatureLocaleRepositoryTest {

  @Test
  public void featureUnionUsesSameNonRootAndUndeletedLocaleScopeAsRunner() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:review-feature-locales-" + UUID.randomUUID() + ";sql.syntax_mys=true",
            "sa",
            "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table review_feature (id bigint primary key, enabled bit not null)");
    jdbc.execute(
        "create table repository (id bigint primary key, deleted bit not null, hidden bit not null)");
    jdbc.execute("create table locale (id bigint primary key, bcp47_tag varchar(64) not null)");
    jdbc.execute(
        """
        create table repository_locale (
            id bigint primary key, repository_id bigint not null, locale_id bigint not null,
            parent_locale bigint, to_be_fully_translated bit not null)
        """);
    jdbc.execute(
        "create table review_feature_repository (review_feature_id bigint, repository_id bigint)");
    jdbc.update("insert into review_feature values (1, true), (2, false), (3, true)");
    jdbc.update(
        "insert into repository values (10, false, false), (11, false, true), (12, true, false), (13, false, false)");
    jdbc.update(
        "insert into locale values (1, 'en'), (2, 'fr-FR'), (3, 'he'), (4, 'ar'), (5, 'de-DE'), (6, 'es')");
    jdbc.update(
        """
        insert into repository_locale values
            (100, 10, 1, null, false), (101, 10, 2, 100, true), (102, 10, 3, 100, false),
            (110, 11, 1, null, false), (111, 11, 4, 110, true), (112, 11, 2, 110, true),
            (120, 12, 1, null, false), (121, 12, 5, 120, true),
            (130, 13, 1, null, false), (131, 13, 6, 130, true)
        """);
    jdbc.update(
        "insert into review_feature_repository values (1, 10), (1, 12), (2, 10), (2, 11), (3, 13)");

    LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(dataSource);
    factory.setPackagesToScan("com.box.l10n.mojito.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    factory.afterPropertiesSet();
    try {
      EntityManagerFactory entityManagerFactory = factory.getObject();
      assertNotNull(entityManagerFactory);
      try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
        ReviewFeatureRepository repository =
            new JpaRepositoryFactory(entityManager).getRepository(ReviewFeatureRepository.class);

        List<ReviewFeatureLocaleRow> union =
            repository.findNonRootLocaleRowsByFeatureIds(List.of(1L, 2L));
        // Shared repositories/locales deduplicate; inherited locales remain eligible. Only
        // deleted repositories and root locales are excluded, matching automated creation.
        assertEquals(3, union.size());
        assertEquals(Set.of("fr-FR", "he", "ar"), tags(union));
        assertEquals(Set.of("fr-FR", "he"), tags(repository.findNonRootLocaleRowsByFeatureId(1L)));
        assertEquals(Set.of(), tags(repository.findNonRootLocaleRowsByFeatureIds(List.of(99L))));
      }
    } finally {
      factory.destroy();
      jdbc.execute("shutdown");
    }
  }

  private Set<String> tags(List<ReviewFeatureLocaleRow> rows) {
    return rows.stream().map(ReviewFeatureLocaleRow::bcp47Tag).collect(Collectors.toSet());
  }
}
