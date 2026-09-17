package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.assetExtraction.AssetExtractionByBranchRepository;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Executes the production template query without an asset_content table or external database. */
public class ReviewProjectDocumentRepositoryTest {
  @Test
  public void usesOnlySuccessfulBranchExtractionMetadataWithinProjectScope() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:review-documents-" + UUID.randomUUID() + ";sql.syntax_mys=true",
            "sa",
            "");
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.execute("create table locale (id bigint primary key, bcp47_tag varchar(35))");
    jdbc.execute("create table repository (id bigint primary key, source_locale_id bigint)");
    jdbc.update("insert into locale values (1, 'ja'), (2, 'en')");
    jdbc.update("insert into repository values (10, 1), (20, 2)");
    jdbc.execute(
        "create table asset (id bigint primary key, path varchar(255), repository_id bigint, deleted bit)");
    jdbc.execute(
        "create table branch (id bigint primary key, name varchar(255), repository_id bigint, deleted bit)");
    jdbc.execute(
        "create table asset_extraction (id bigint primary key, asset_id bigint, content_md5 varchar(32), filter_options_md5 varchar(32))");
    jdbc.execute(
        "create table asset_extraction_by_branch (id bigint primary key, asset_id bigint, branch_id bigint, asset_extraction_id bigint, deleted bit)");
    jdbc.execute(
        "create table tm_text_unit (id bigint primary key, asset_id bigint, name varchar(255), content varchar(4000))");
    jdbc.execute(
        "create table review_project_text_unit (id bigint primary key, review_project_id bigint, tm_text_unit_id bigint)");
    jdbc.update(
        "insert into asset values (1, 'page.mdx', 10, false), (2, 'unrelated.mdx', 20, false), (3, 'deleted.mdx', 10, true), (4, 'strings.json', 10, false)");
    jdbc.update(
        "insert into branch values (1, 'master', 10, false), (2, 'feature', 10, false), (3, 'deleted', 10, true), (4, 'retired', 10, false), (5, 'first-extraction-failed', 10, false), (6, 'no-content', 10, false), (7, 'other-repository', 20, false)");
    jdbc.update(
        "insert into tm_text_unit values (11, 1, 'title', 'Welcome'), (12, 2, 'secret', 'Other project'), (13, 3, 'deleted', 'Deleted'), (14, 4, 'json', 'JSON')");
    jdbc.update(
        "insert into review_project_text_unit values (101, 100, 11), (102, 200, 12), (103, 100, 13), (104, 100, 14)");
    jdbc.update(
        "insert into asset_extraction values (1, 1, 'master-md5', 'filter-md5'), (2, 1, 'feature-md5', 'filter-md5'), (3, 2, 'other-md5', 'filter-md5'), (4, 3, 'deleted-md5', 'filter-md5'), (5, 1, 'deleted-branch-md5', 'filter-md5'), (6, 1, 'retired-md5', 'filter-md5'), (7, 1, 'not-committed-md5', null), (8, 1, null, 'filter-md5'), (9, 4, 'json-md5', 'filter-md5')");
    jdbc.update(
        "insert into asset_extraction_by_branch values (1, 1, 1, 1, false), (2, 1, 2, 2, false), (3, 2, 7, 3, false), (4, 3, 1, 4, false), (5, 1, 3, 5, false), (6, 1, 4, 6, true), (7, 1, 5, 7, false), (8, 1, 6, 8, false), (9, 4, 1, 9, false)");
    jdbc.update("insert into asset values (5, 'shared/intro.mdx', 10, false)");
    jdbc.update("insert into asset_extraction values (10, 5, 'intro-md5', 'filter-md5')");
    jdbc.update("insert into asset_extraction_by_branch values (10, 5, 1, 10, false)");
    jdbc.update("insert into asset values (6, 'messages.mf2.json', 10, false)");
    jdbc.update("insert into asset_extraction values (11, 6, 'catalog-md5', 'filter-md5')");
    jdbc.update("insert into asset_extraction_by_branch values (11, 6, 1, 11, false)");
    jdbc.update("insert into tm_text_unit values (15, 6, 'calendar.date', 'On {$date :date}')");
    jdbc.update("insert into review_project_text_unit values (105, 100, 15)");

    LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(source);
    factory.setPackagesToScan("com.box.l10n.mojito.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    factory.afterPropertiesSet();
    try (EntityManager entityManager = factory.getObject().createEntityManager()) {
      JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
      var templates = repositories.getRepository(AssetExtractionByBranchRepository.class);
      var textUnits = repositories.getRepository(ReviewProjectTextUnitRepository.class);

      assertThat(templates.findDocumentTemplatesByProjectId(100L, PageRequest.of(0, 101)))
          .containsExactly(
              new ReviewProjectDocumentTemplate(
                  1L, "page.mdx", 10L, 2L, "feature", "feature-md5", "ja"),
              new ReviewProjectDocumentTemplate(
                  1L, "page.mdx", 10L, 1L, "master", "master-md5", "ja"));
      assertThat(templates.findDocumentTemplatesByProjectId(200L, PageRequest.of(0, 101)))
          .extracting(ReviewProjectDocumentTemplate::assetId)
          .containsExactly(2L);
      assertThat(templates.findDocumentTemplatesByProjectId(999L, PageRequest.of(0, 101)))
          .isEmpty();
      assertThat(textUnits.findDocumentTextUnitsByProjectId(100L, 10_000, PageRequest.of(0, 2_001)))
          .extracting(ReviewProjectDocumentTextUnit::reviewProjectTextUnitId)
          .containsExactly(103L, 105L, 101L);
      assertThat(textUnits.findDocumentTextUnitsByProjectId(100L, 10_000, PageRequest.of(0, 2)))
          .hasSize(2);
      // Cap SQL source payloads without returning a prefix that could falsely match a new source.
      assertThat(textUnits.findDocumentTextUnitsByProjectId(100L, 3, PageRequest.of(0, 2_001)))
          .allSatisfy(row -> assertThat(row.source()).isNull());
      // Included source context can resolve outside the project, but never outside its repo/branch.
      assertThat(templates.findDocumentTemplate(10L, 1L, "shared/intro.mdx"))
          .contains(
              new ReviewProjectDocumentTemplate(
                  5L, "shared/intro.mdx", 10L, 1L, "master", "intro-md5", "ja"));
      assertThat(templates.findDocumentTemplate(10L, 1L, "messages.mf2.json"))
          .contains(
              new ReviewProjectDocumentTemplate(
                  6L, "messages.mf2.json", 10L, 1L, "master", "catalog-md5", "ja"));
      assertThat(templates.findDocumentTemplatesByProjectId(200L, PageRequest.of(0, 101)))
          .extracting(ReviewProjectDocumentTemplate::sourceLocaleTag)
          .containsExactly("en");
      assertThat(templates.findDocumentTemplate(20L, 1L, "shared/intro.mdx")).isEmpty();
      assertThat(templates.findDocumentTemplate(10L, 2L, "shared/intro.mdx")).isEmpty();
      assertThat(templates.findDocumentTemplate(10L, 3L, "page.mdx")).isEmpty();
      assertThat(templates.findDocumentTemplate(10L, 5L, "page.mdx")).isEmpty();

      // A broken mapping must not bind the template to another asset or repository's branch.
      jdbc.update("update asset_extraction_by_branch set asset_extraction_id = 3 where id = 1");
      jdbc.update("update asset_extraction_by_branch set branch_id = 7 where id = 2");
      assertThat(templates.findDocumentTemplatesByProjectId(100L, PageRequest.of(0, 101)))
          .isEmpty();
    } finally {
      factory.destroy();
      jdbc.execute("shutdown");
    }
  }
}
