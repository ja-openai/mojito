package com.box.l10n.mojito.service.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Execute production seek/directory HQL with no template, content, or review-project tables. */
public class RepositoryContentCursorQueriesTest {
  private static JdbcTemplate jdbc;
  private static LocalContainerEntityManagerFactoryBean factory;
  private EntityManager entityManager;
  private RepositoryContentQueries queries;

  @BeforeClass
  public static void database() {
    var source =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:cursor-" + UUID.randomUUID() + ";sql.syntax_mys=true", "sa", "");
    jdbc = new JdbcTemplate(source);
    jdbc.execute("create table locale (id bigint primary key, bcp47_tag varchar(35))");
    jdbc.execute("create table repository (id bigint primary key, source_locale_id bigint)");
    jdbc.execute(
        "create table asset (id bigint primary key, path varchar(255), repository_id bigint, deleted bit)");
    jdbc.execute("create index asset_repo_path on asset(repository_id, path)");
    jdbc.execute(
        "create table branch (id bigint primary key, name varchar(255), repository_id bigint, deleted bit)");
    jdbc.execute(
        "create table asset_extraction (id bigint primary key, asset_id bigint, content_md5 varchar(32), filter_options_md5 varchar(32))");
    jdbc.execute(
        "create table asset_extraction_by_branch (id bigint primary key, asset_id bigint, branch_id bigint, asset_extraction_id bigint, deleted bit)");
    jdbc.update("insert into locale values (1, 'en')");
    jdbc.update("insert into repository values (10,1),(20,1)");
    jdbc.update(
        "insert into branch values (1,null,10,false),(2,'feature',10,false),(3,null,20,false),(4,'deleted',10,true)");
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(source);
    factory.setPackagesToScan("com.box.l10n.mojito.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(
        Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.generate_statistics", "true"));
    factory.afterPropertiesSet();
  }

  @AfterClass
  public static void closeDatabase() {
    factory.destroy();
    jdbc.execute("shutdown");
  }

  @Before
  public void setup() {
    jdbc.update("delete from asset_extraction_by_branch");
    jdbc.update("delete from asset_extraction");
    jdbc.update("delete from asset");
    entityManager = factory.getObject().createEntityManager();
    queries = new RepositoryContentQueries(entityManager);
  }

  @After
  public void closeSession() {
    entityManager.close();
  }

  @Test
  public void seekDoesNotSkipAfterInsertsOrDeletesBeforeTheCursorAndCanReturnBackwards() {
    asset(10, "b.mdx", 10, 1);
    asset(20, "d.mdx", 10, 1);
    asset(30, "f.mdx", 10, 1);
    asset(40, "h.mdx", 10, 1);
    var filter = filter("", "prefix", "");
    assertPaths(queries.seek(10L, 1L, filter, null, false, 2), "b.mdx", "d.mdx");
    var after = new RepositoryContentCursor("d.mdx", 20L);
    asset(5, "a.mdx", 10, 1);
    jdbc.update("delete from asset_extraction_by_branch where asset_id in (10,20)");
    // Even deleting the cursor asset itself does not invalidate a value-based seek position.
    assertPaths(queries.seek(10L, 1L, filter, after, false, 2), "f.mdx", "h.mdx");
    assertPaths(
        queries.seek(10L, 1L, filter, new RepositoryContentCursor("h.mdx", 40), true, 2),
        "f.mdx",
        "a.mdx");
    assertThat(queries.seek(10L, 1L, filter, new RepositoryContentCursor("h.mdx", 40), false, 2))
        .isEmpty();
  }

  @Test
  public void tieBreakerAndScopedMembershipAreEnforced() {
    asset(10, "same.mdx", 10, 1);
    asset(20, "same.mdx", 10, 1);
    asset(30, "same.mdx", 10, 2);
    asset(40, "same.mdx", 20, 3);
    asset(50, "deleted.mdx", 10, 1);
    asset(60, "deleted-map.mdx", 10, 1);
    asset(70, "deleted-branch.mdx", 10, 4);
    asset(80, "wrong-repo.mdx", 20, 1);
    asset(90, "wrong-extraction.mdx", 10, 1);
    asset(100, "catalog.json", 10, 1);
    jdbc.update("update asset set deleted=true where id=50");
    jdbc.update("update asset_extraction_by_branch set deleted=true where id=60");
    jdbc.update("update asset_extraction_by_branch set asset_extraction_id=40 where id=90");
    assertThat(
            queries.seek(
                10L,
                1L,
                filter("", "prefix", ""),
                new RepositoryContentCursor("same.mdx", 10),
                false,
                100))
        .extracting(ReviewProjectDocumentTemplate::assetId)
        .containsExactly(20L);
    assertThat(queries.seek(10L, 4L, filter("", "prefix", ""), null, false, 100)).isEmpty();
    assertThat(queries.seek(20L, 1L, filter("", "prefix", ""), null, false, 100)).isEmpty();
    assertThat(queries.branch(10L, 3L)).isNull();
    assertThat(queries.branch(10L, 4L)).isNull();
    assertThat(queries.branches(10L, 1))
        .extracting(RepositoryContentIndex.Branch::id)
        .containsExactly(1L);
    assertThat(queries.defaultBranches(10L))
        .extracting(RepositoryContentIndex.Branch::id)
        .containsExactly(1L);
  }

  @Test
  public void prefixAndDirectoryFiltersTreatSqlWildcardsLiterallyAndKeepSlashBoundaries() {
    asset(1, "docs_%!/Guide.mdx", 10, 1);
    asset(2, "docsAAx/Guide.mdx", 10, 1);
    asset(3, "docs_%!extra/Guide.mdx", 10, 1);
    asset(4, "docs_%!/Sub/intro.mdx", 10, 1);
    asset(5, "other/guide.mdx", 10, 1);
    asset(6, " leading /page.mdx", 10, 1);
    assertPaths(
        queries.seek(10L, 1L, filter(" leading /page.mdx", "exact", ""), null, false, 10),
        " leading /page.mdx");
    assertPaths(
        queries.seek(10L, 1L, filter(" leading ", "prefix", ""), null, false, 10),
        " leading /page.mdx");
    assertPaths(
        queries.seek(10L, 1L, filter("docs_%!", "prefix", ""), null, false, 10),
        "docs_%!/Guide.mdx",
        "docs_%!/Sub/intro.mdx",
        "docs_%!extra/Guide.mdx");
    assertPaths(
        queries.seek(10L, 1L, filter("", "prefix", "docs_%!/"), null, false, 10),
        "docs_%!/Guide.mdx",
        "docs_%!/Sub/intro.mdx");
    assertPaths(
        queries.seek(10L, 1L, filter("docs_%!/Guide.mdx", "exact", ""), null, false, 10),
        "docs_%!/Guide.mdx");
    assertThat(queries.seek(10L, 1L, filter("docs_%!/guide.mdx", "exact", ""), null, false, 10))
        .isEmpty();
    assertPaths(
        queries.seek(10L, 1L, filter("guide", "contains", "docs_%!/"), null, false, 10),
        "docs_%!/Guide.mdx");
    assertThat(queries.directories(10L, 1L, "docs_%!/", null, 10)).containsExactly("docs_%!/Sub/");
  }

  @Test
  public void directRootFilesAreFilteredBeforeOffsetAndSeekPagination() {
    asset(1, "a.mdx", 10, 1);
    asset(2, "b/nested.mdx", 10, 1);
    asset(3, "c.mdx", 10, 1);
    asset(4, "d/deep/nested.mdx", 10, 1);
    asset(5, "e.mdx", 10, 1);
    asset(6, "f.json", 10, 1);
    var direct = new RepositoryContentQueries.Filter("", "prefix", "", false);

    assertPaths(queries.list(10L, 1L, direct, 1, 2), "c.mdx", "e.mdx");
    assertPaths(queries.seek(10L, 1L, direct, null, false, 2), "a.mdx", "c.mdx");
    var cursor = new RepositoryContentCursor("c.mdx", 3);
    assertPaths(queries.seek(10L, 1L, direct, cursor, false, 2), "e.mdx");
    assertPaths(queries.seek(10L, 1L, direct, cursor, true, 2), "a.mdx");
    // Existing callers retain recursive listing semantics.
    assertPaths(queries.list(10L, 1L, filter("", "prefix", ""), 0, 2), "a.mdx", "b/nested.mdx");
  }

  @Test
  public void directNestedFilesRespectLiteralDirectoryNamesAndSearch() {
    asset(1, "docs_%!/a.mdx", 10, 1);
    asset(2, "docs_%!/b/nested.mdx", 10, 1);
    asset(3, "docs_%!/c_%!.mdx", 10, 1);
    asset(4, "docs_%!/cZZZ.mdx", 10, 1);
    asset(5, "docs_%!extra/c_%!.mdx", 10, 1);
    asset(6, "docsAAx/c_%!.mdx", 10, 1);
    asset(7, "docs_%!/feature.mdx", 10, 2);
    var direct = new RepositoryContentQueries.Filter("", "prefix", "docs_%!/", false);

    assertPaths(queries.list(10L, 1L, direct, 0, 2), "docs_%!/a.mdx", "docs_%!/cZZZ.mdx");
    assertPaths(queries.list(10L, 1L, direct, 2, 2), "docs_%!/c_%!.mdx");
    var cursor = new RepositoryContentCursor("docs_%!/cZZZ.mdx", 4);
    assertPaths(queries.seek(10L, 1L, direct, cursor, false, 2), "docs_%!/c_%!.mdx");
    assertPaths(queries.seek(10L, 1L, direct, cursor, true, 2), "docs_%!/a.mdx");
    assertPaths(
        queries.seek(
            10L,
            1L,
            new RepositoryContentQueries.Filter("docs_%!/c_%!", "prefix", "docs_%!/", false),
            null,
            false,
            10),
        "docs_%!/c_%!.mdx");
    assertThat(
            queries.list(
                10L,
                1L,
                new RepositoryContentQueries.Filter("nested", "contains", "docs_%!/", false),
                0,
                10))
        .isEmpty();
  }

  @Test
  public void directChildDepthUsesDatabaseCharacterLengthForUnicodeDirectories() {
    asset(1, "📚📚/a.mdx", 10, 1);
    asset(2, "📚📚/b/nested.mdx", 10, 1);
    assertPaths(
        queries.seek(
            10L,
            1L,
            new RepositoryContentQueries.Filter("", "prefix", "📚📚/", false),
            null,
            false,
            10),
        "📚📚/a.mdx");
  }

  @Test
  public void directoryDiscoveryIsImmediateDistinctScopedAndSeekPaged() {
    asset(1, "docs/a/deep/page.mdx", 10, 1);
    asset(2, "docs/a/another.mdx", 10, 1);
    asset(3, "docs/b/page.mdx", 10, 1);
    asset(4, "docs/c/page.mdx", 10, 1);
    asset(5, "docs/direct.mdx", 10, 1);
    asset(6, "docsSibling/other/page.mdx", 10, 1);
    asset(7, "root.mdx", 10, 1);
    asset(8, "foreign/page.mdx", 20, 3);
    asset(9, "feature/page.mdx", 10, 2);
    asset(10, "deleted/page.mdx", 10, 1);
    jdbc.update("update asset set deleted=true where id=10");
    assertThat(queries.directories(10L, 1L, "", null, 50)).containsExactly("docs/", "docsSibling/");
    assertThat(queries.directories(10L, 1L, "docs/", null, 2))
        .containsExactly("docs/a/", "docs/b/");
    jdbc.update("delete from asset_extraction_by_branch where asset_id=3");
    assertThat(queries.directories(10L, 1L, "docs/", "docs/b/", 2)).containsExactly("docs/c/");
    assertThat(queries.directories(10L, 1L, "docs/a/", null, 50)).containsExactly("docs/a/deep/");
    assertThat(queries.directories(10L, 1L, "docs/a/deep/", null, 50)).isEmpty();
    asset(11, "📚📚/a/page.mdx", 10, 1);
    assertThat(queries.directories(10L, 1L, "📚📚/", null, 50)).containsExactly("📚📚/a/");
  }

  @Test
  public void largeListingLoadsOnlyRequestedScalarRowsAndNoEntities() {
    for (int i = 1; i <= 2000; i++)
      asset(i, "site/folder-" + String.format("%04d", i) + "/page.mdx", 10, 1);
    var statistics = factory.getObject().unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    assertThat(queries.directories(10L, 1L, "site/", null, 51)).hasSize(51);
    assertThat(queries.seek(10L, 1L, filter("site/", "prefix", ""), null, false, 101)).hasSize(101);
    assertThat(statistics.getEntityLoadCount()).isZero();
    assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
    assertThat(List.of(statistics.getQueries()))
        .allSatisfy(
            query ->
                assertThat(statistics.getQueryStatistics(query).getExecutionRowCount())
                    .isLessThanOrEqualTo(101));
  }

  private static RepositoryContentQueries.Filter filter(String q, String mode, String directory) {
    return new RepositoryContentQueries.Filter(q, mode, directory);
  }

  private static void assertPaths(List<ReviewProjectDocumentTemplate> results, String... expected) {
    assertThat(results)
        .extracting(ReviewProjectDocumentTemplate::assetPath)
        .containsExactly(expected);
  }

  private static void asset(long id, String path, long repositoryId, long branchId) {
    jdbc.update("insert into asset values (?,?,?,false)", id, path, repositoryId);
    jdbc.update("insert into asset_extraction values (?,?,'hash','filter')", id, id);
    jdbc.update(
        "insert into asset_extraction_by_branch values (?,?,?,?,false)", id, id, branchId, id);
  }
}
