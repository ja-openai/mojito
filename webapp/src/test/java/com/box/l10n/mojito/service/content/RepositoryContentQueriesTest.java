package com.box.l10n.mojito.service.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Production HQL against HSQL, deliberately without review-project or asset-content tables. */
public class RepositoryContentQueriesTest {
  @Test
  public void listsCompositionRootsAndReadsTargetsFromExactBranchExtraction() {
    var source =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:content-" + UUID.randomUUID() + ";sql.syntax_mys=true", "sa", "");
    var jdbc = new JdbcTemplate(source);
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
        "create table asset_text_unit (id bigint primary key, asset_extraction_id bigint, do_not_translate bit)");
    jdbc.execute(
        "create table tm_text_unit (id bigint primary key, asset_id bigint, name varchar(255), content varchar(4000))");
    jdbc.execute(
        "create table asset_text_unit_to_tm_text_unit (id bigint primary key, asset_extraction_id bigint, asset_text_unit_id bigint, tm_text_unit_id bigint)");
    jdbc.execute(
        "create table tm_text_unit_current_variant (id bigint primary key, tm_text_unit_id bigint, locale_id bigint, tm_text_unit_variant_id bigint)");
    jdbc.execute(
        "create table tm_text_unit_variant (id bigint primary key, tm_text_unit_id bigint, locale_id bigint, content varchar(4000), status varchar(255))");
    jdbc.update(
        "insert into asset values (1,'index.mdx',10,false),(2,'module.mdx',10,false),(3,'other.mdx',20,false),(4,'deleted.mdx',10,true),(5,'a_%.MDX',10,false),(6,'pending.mdx',10,false),(7,'strings.json',10,false)");
    jdbc.update(
        "insert into branch values (1,null,10,false),(2,'feature',10,false),(3,null,20,false),(4,'deleted',10,true)");
    jdbc.update(
        "insert into asset_extraction values (1,1,'index','filter'),(2,2,'main','filter'),(3,2,'feature','filter'),(4,3,'other','filter'),(5,4,'deleted','filter'),(6,5,'literal','filter'),(7,6,'pending',null),(8,7,'json','filter')");
    jdbc.update(
        "insert into asset_extraction_by_branch values (1,1,1,1,false),(2,2,1,2,false),(3,2,2,3,false),(4,3,3,4,false),(5,4,1,5,false),(6,5,1,6,false),(7,6,1,7,false),(8,7,1,8,false),(9,2,4,2,false)");
    jdbc.update(
        "insert into tm_text_unit values (20,2,'title','Main source'),(21,2,'title','Feature source'),(22,2,'no.target','No target')");
    jdbc.update("insert into asset_text_unit values (20,2,false),(21,3,false),(22,2,false)");
    jdbc.update(
        "insert into asset_text_unit_to_tm_text_unit values (20,2,20,20),(21,3,21,21),(22,2,22,22)");
    jdbc.update(
        "insert into tm_text_unit_variant values (200,20,11,'Principal','APPROVED'),(201,21,11,'Fonctionnalité','REVIEW_NEEDED'),(202,20,12,'Hauptseite','APPROVED')");
    jdbc.update(
        "insert into tm_text_unit_current_variant values (200,20,11,200),(201,21,11,201),(202,20,12,202)");
    var factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(source);
    factory.setPackagesToScan("com.box.l10n.mojito.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    factory.afterPropertiesSet();
    try (EntityManager entityManager = factory.getObject().createEntityManager()) {
      var queries = new RepositoryContentQueries(entityManager);
      assertThat(queries.list(10L, 1L, "", 0, 10))
          .allSatisfy(template -> assertThat(template.sourceLocaleTag()).isEqualTo("ja"));
      assertThat(queries.find(20L, 3L, 3L).sourceLocaleTag()).isEqualTo("en");
      assertThat(queries.list(10L, 1L, "", 0, 10))
          .extracting(ReviewProjectDocumentTemplate::assetId)
          .containsExactly(5L, 1L, 2L, 6L);
      assertThat(queries.list(10L, 1L, "", 1, 2))
          .extracting(ReviewProjectDocumentTemplate::assetId)
          .containsExactly(1L, 2L);
      assertThat(queries.list(10L, 1L, "_%", 0, 10))
          .extracting(ReviewProjectDocumentTemplate::assetId)
          .containsExactly(5L);
      assertThat(queries.find(10L, 1L, 1L))
          .isNotNull(); // No strings at all: composition-only root.
      assertThat(queries.find(10L, 1L, 6L).contentMd5()).isNull();
      assertThat(queries.find(20L, 1L, 2L)).isNull();
      assertThat(queries.find(10L, 4L, 2L)).isNull();
      var main = queries.find(10L, 1L, 2L);
      var feature = queries.find(10L, 2L, 2L);
      assertThat(queries.textUnits(main, 11L, List.of("title", "no.target"), 100))
          .extracting(RepositoryContentTextUnit::targetContent)
          .containsExactly("Principal", null);
      assertThat(queries.textUnits(feature, 11L, List.of("title"), 100))
          .extracting(RepositoryContentTextUnit::targetContent)
          .containsExactly("Fonctionnalité");
      assertThat(queries.textUnits(main, 12L, List.of("title"), 4))
          .extracting(RepositoryContentTextUnit::targetContent)
          .containsExactly("Haup");
      // Non-translatable extraction members remain source context even if an old target exists.
      jdbc.update("update asset_text_unit set do_not_translate=true where id=20");
      assertThat(queries.textUnits(main, 11L, List.of("title", "no.target"), 100))
          .extracting(RepositoryContentTextUnit::tmTextUnitId)
          .containsExactly(22L);
      // A concurrent extraction update makes the rendered template stale: do not bind newer rows.
      jdbc.update("update asset_extraction_by_branch set asset_extraction_id=3 where id=2");
      assertThat(queries.textUnits(main, 11L, List.of("title"), 100)).isEmpty();
      // Broken cross-asset and cross-repository references never authorize content.
      jdbc.update("update asset_extraction_by_branch set asset_extraction_id=4 where id=2");
      assertThat(queries.find(10L, 1L, 2L)).isNull();
    } finally {
      factory.destroy();
      jdbc.execute("shutdown");
    }
  }
}
