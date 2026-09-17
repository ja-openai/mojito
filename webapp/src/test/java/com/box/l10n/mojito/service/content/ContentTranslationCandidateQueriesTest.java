package com.box.l10n.mojito.service.content;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Reads the branch revision, never the merged extraction, without content or TM tables. */
public class ContentTranslationCandidateQueriesTest {
  @Test
  public void readsOnlySuccessfulSupportedAssetsOnTheExactActiveBranch() {
    var source =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:candidate-source-" + UUID.randomUUID() + ";sql.syntax_mys=true",
            "sa",
            "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("create table repository (id bigint primary key, deleted bit)");
    jdbc.execute(
        "create table asset (id bigint primary key, path varchar(255), repository_id bigint, deleted bit, last_successful_asset_extraction_id bigint)");
    jdbc.execute("create table branch (id bigint primary key, repository_id bigint, deleted bit)");
    jdbc.execute(
        "create table asset_extraction (id bigint primary key, asset_id bigint, content_md5 varchar(32), filter_options_md5 varchar(32))");
    jdbc.execute(
        "create table asset_extraction_by_branch (id bigint primary key, asset_id bigint, branch_id bigint, asset_extraction_id bigint, deleted bit)");
    jdbc.update("insert into repository values (10,false),(20,false)");
    jdbc.update("insert into branch values (1,10,false),(2,10,false),(3,20,false)");
    jdbc.update(
        "insert into asset values (1,'page.mdx',10,false,100),(2,'messages.mf2.json',10,false,200),(3,'strings.json',10,false,300)");
    jdbc.update(
        "insert into asset_extraction values (100,1,'merged','filter'),(101,1,'main','filter'),(102,1,'feature','filter'),(201,2,'catalog','filter'),(301,3,'json','filter')");
    jdbc.update(
        "insert into asset_extraction_by_branch values (1,1,1,101,false),(2,1,2,102,false),(3,2,1,201,false),(4,3,1,301,false)");
    var factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(source);
    factory.setPackagesToScan("com.box.l10n.mojito.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
    factory.afterPropertiesSet();
    try (EntityManager entityManager = factory.getObject().createEntityManager()) {
      var queries = new ContentTranslationCandidateQueries(entityManager);
      assertThat(queries.findCurrentSource(10L, 1L, 1L))
          .isEqualTo(new ContentTranslationCandidate.Source(10L, 1L, 1L, "page.mdx", 101L, "main"));
      assertThat(queries.findCurrentSource(10L, 2L, 1L).assetExtractionId()).isEqualTo(102L);
      assertThat(queries.findCurrentSource(10L, 1L, 2L).assetContentMd5()).isEqualTo("catalog");
      assertThat(queries.findCurrentSource(10L, 1L, 3L)).isNull();
      assertThat(queries.findCurrentSource(20L, 1L, 1L)).isNull();
      assertThat(queries.findCurrentSource(10L, 3L, 1L)).isNull();
      jdbc.update("update asset_extraction set content_md5=null where id=101");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
      jdbc.update(
          "update asset_extraction set content_md5='new', filter_options_md5=null where id=101");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
      jdbc.update("update asset_extraction set filter_options_md5='filter' where id=101");
      assertThat(queries.findCurrentSource(10L, 1L, 1L).assetContentMd5()).isEqualTo("new");
      jdbc.update("update asset_extraction_by_branch set asset_extraction_id=201 where id=1");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
      jdbc.update(
          "update asset_extraction_by_branch set asset_extraction_id=101, deleted=true where id=1");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
      jdbc.update("update asset_extraction_by_branch set deleted=false where id=1");
      jdbc.update("update branch set deleted=true where id=1");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
      jdbc.update("update branch set deleted=false, repository_id=20 where id=1");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
      jdbc.update("update branch set repository_id=10 where id=1");
      jdbc.update("update asset set deleted=true where id=1");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
      jdbc.update("update asset set deleted=false where id=1");
      jdbc.update("update repository set deleted=true where id=10");
      assertThat(queries.findCurrentSource(10L, 1L, 1L)).isNull();
    } finally {
      factory.destroy();
      jdbc.execute("shutdown");
    }
  }
}
