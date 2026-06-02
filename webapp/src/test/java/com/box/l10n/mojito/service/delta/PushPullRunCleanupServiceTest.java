package com.box.l10n.mojito.service.delta;

import static org.junit.Assert.assertFalse;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Commit;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PullRun;
import com.box.l10n.mojito.entity.PullRunAsset;
import com.box.l10n.mojito.entity.PullRunTextUnitVariant;
import com.box.l10n.mojito.entity.PushRun;
import com.box.l10n.mojito.entity.PushRunAsset;
import com.box.l10n.mojito.entity.PushRunAssetTmTextUnit;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TM;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.DBUtils;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.commit.CommitService;
import com.box.l10n.mojito.service.commit.CommitToPullRunRepository;
import com.box.l10n.mojito.service.commit.CommitToPushRunRepository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pullrun.PullRunAssetRepository;
import com.box.l10n.mojito.service.pullrun.PullRunAssetService;
import com.box.l10n.mojito.service.pullrun.PullRunRepository;
import com.box.l10n.mojito.service.pullrun.PullRunService;
import com.box.l10n.mojito.service.pullrun.PullRunTextUnitVariantRepository;
import com.box.l10n.mojito.service.pushrun.PushRunAssetRepository;
import com.box.l10n.mojito.service.pushrun.PushRunAssetTmTextUnitRepository;
import com.box.l10n.mojito.service.pushrun.PushRunRepository;
import com.box.l10n.mojito.service.pushrun.PushRunService;
import com.box.l10n.mojito.service.repository.RepositoryNameAlreadyUsedException;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMRepository;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.test.TestIdWatcher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class PushPullRunCleanupServiceTest extends ServiceTestBase {

  static Logger logger = LoggerFactory.getLogger(PushPullRunCleanupServiceTest.class);

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired EntityManager entityManager;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired PushPullRunCleanupService pushPullRunCleanupService;

  @Autowired AssetService assetService;

  @Autowired PushRunService pushRunService;

  @Autowired PushRunRepository pushRunRepository;

  @Autowired PushRunAssetRepository pushRunAssetRepository;

  @Autowired PushRunAssetTmTextUnitRepository pushRunAssetTmTextUnitRepository;

  @Autowired PullRunService pullRunService;

  @Autowired PullRunRepository pullRunRepository;

  @Autowired PullRunAssetService pullRunAssetService;

  @Autowired PullRunAssetRepository pullRunAssetRepository;

  @Autowired PullRunTextUnitVariantRepository pullRunTextUnitVariantRepository;

  @Autowired RepositoryService repositoryService;

  @Autowired LocaleService localeService;

  @Autowired DBUtils dbUtils;

  @Autowired TMService tmService;

  @Autowired CommitService commitService;

  @Autowired CommitToPushRunRepository commitToPushRunRepository;

  @Autowired CommitToPullRunRepository commitToPullRunRepository;

  TM tm;

  Repository repository;

  Asset asset;

  @Autowired TMRepository tmRepository;

  @Before
  public void before() throws RepositoryNameAlreadyUsedException {
    if (tm == null) {
      tm = new TM();
      tmRepository.save(tm);
    }

    if (repository == null) {
      repository = repositoryService.createRepository(testIdWatcher.getEntityName("repository"));
      asset =
          assetService.createAssetWithContent(
              repository.getId(), "path/to/asset", "test asset content");
    }
  }

  @Transactional
  @Test
  public void testCleanOldPushPullData() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("repository") + "testCleanOldPushPullData");

    PushRun pushRun = pushRunService.createPushRun(repository, "cleanOldPushPullData");
    Assert.assertTrue(pushRunAssetRepository.findByPushRun(pushRun).isEmpty());

    TMTextUnit tmTextUnit1 =
        tmService.addTMTextUnit(
            tm.getId(),
            asset.getId(),
            "hello_world 1",
            "Hello World!",
            "Comments about hello world");
    TMTextUnit tmTextUnit2 =
        tmService.addTMTextUnit(
            tm.getId(),
            asset.getId(),
            "hello_world 2",
            "Hello World!",
            "Comments about hello world");

    pushRunService.associatePushRunToTextUnitIds(
        pushRun, asset, Arrays.asList(tmTextUnit1.getId(), tmTextUnit2.getId()));
    List<TMTextUnit> textUnits =
        pushRunService.getPushRunTextUnits(pushRun, PageRequest.of(0, Integer.MAX_VALUE));
    Assert.assertEquals(2, textUnits.size());

    PullRun pullRun = pullRunService.getOrCreate("cleanOldPushPullData", repository);
    PullRunAsset pullRunAsset = pullRunAssetService.createPullRunAsset(pullRun, asset);

    Locale frFR = localeService.findByBcp47Tag("fr-FR");
    TMTextUnitVariant tuv1 =
        tmService.addCurrentTMTextUnitVariant(
            tmTextUnit1.getId(), frFR.getId(), "le hello_world 1");

    TMTextUnitVariant tuv2 =
        tmService.addCurrentTMTextUnitVariant(
            tmTextUnit1.getId(), frFR.getId(), "le hello_world 2");

    pullRunAssetService.replaceTextUnitVariants(
        pullRunAsset, frFR.getId(), Arrays.asList(tuv1.getId(), tuv2.getId()), "fr-FR");
    List<TMTextUnitVariant> recordedVariants =
        pullRunTextUnitVariantRepository.findByPullRun(pullRun, Pageable.unpaged());
    Assert.assertEquals(2, recordedVariants.size());

    // This should not delete anything
    pushPullRunCleanupService.cleanOldPushPullData(Duration.ofDays(1000));
    Assert.assertEquals(
        2,
        pushRunService.getPushRunTextUnits(pushRun, PageRequest.of(0, Integer.MAX_VALUE)).size());
    Assert.assertEquals(
        2, pullRunTextUnitVariantRepository.findByPullRun(pullRun, Pageable.unpaged()).size());

    // This should delete all of the old data
    pushPullRunCleanupService.cleanOldPushPullData(Duration.ofSeconds(-1));

    entityManager.flush();
    entityManager.clear();

    Assert.assertEquals(
        0,
        pushRunService.getPushRunTextUnits(pushRun, PageRequest.of(0, Integer.MAX_VALUE)).size());
    Assert.assertEquals(
        0, pullRunTextUnitVariantRepository.findByPullRun(pullRun, Pageable.unpaged()).size());

    assertFalse(pushRunRepository.findById(pushRun.getId()).isPresent());
    assertFalse(pullRunRepository.findById(pullRun.getId()).isPresent());
  }

  @Transactional
  @Test
  public void cleanupDeletesMatchLegacyNativeSql() throws Exception {
    Repository nativeRepository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("repository") + "nativeCleanup");
    Repository jpqlRepository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("repository") + "jpqlCleanup");

    ZonedDateTime now = ZonedDateTime.now();
    ZonedDateTime nativeDeleteDate = now.minusDays(20);
    ZonedDateTime jpqlDeleteDate = now.minusDays(10);
    ZonedDateTime keepDate = now.plusDays(1);

    createCleanupFixture(nativeRepository, "native-delete", nativeDeleteDate);
    createCleanupFixture(nativeRepository, "native-keep", keepDate);
    createCleanupFixture(jpqlRepository, "jpql-delete", jpqlDeleteDate);
    createCleanupFixture(jpqlRepository, "jpql-keep", keepDate);

    entityManager.flush();
    entityManager.clear();

    runLegacyNativeCleanup(now.minusDays(15));
    runJpaCleanup(now.minusDays(5));

    entityManager.flush();
    entityManager.clear();

    Assertions.assertThat(cleanupCounts(jpqlRepository))
        .usingRecursiveComparison()
        .isEqualTo(cleanupCounts(nativeRepository));
  }

  @Transactional
  @Test
  public void mysqlCleanupDeleteBenchmarkLegacyNativeSqlAgainstCriteria() throws Exception {
    Assume.assumeTrue(
        "MySQL cleanup benchmark is opt-in and requires a MySQL test database",
        dbUtils.isMysql() && Boolean.getBoolean("mojito.test.mysqlCleanupBenchmark"));

    int rowCount = Integer.getInteger("mojito.test.cleanupBenchmarkRows", 20000);
    Repository nativeRepository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("repository") + "nativeBenchmark");
    Repository criteriaRepository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("repository") + "criteriaBenchmark");
    Repository jpqlRepository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("repository") + "jpqlBenchmark");

    ZonedDateTime now = ZonedDateTime.now();
    createLargeCleanupFixture(nativeRepository, "bench-native", now.minusDays(30), rowCount);
    createLargeCleanupFixture(criteriaRepository, "bench-criteria", now.minusDays(20), rowCount);
    createLargeCleanupFixture(jpqlRepository, "bench-jpql", now.minusDays(10), rowCount);

    entityManager.flush();
    entityManager.clear();

    CleanupCounts nativeCountsBefore = cleanupCounts(nativeRepository);
    CleanupCounts criteriaCountsBefore = cleanupCounts(criteriaRepository);
    CleanupCounts jpqlCountsBefore = cleanupCounts(jpqlRepository);
    Assertions.assertThat(criteriaCountsBefore)
        .usingRecursiveComparison()
        .isEqualTo(nativeCountsBefore);
    Assertions.assertThat(jpqlCountsBefore)
        .usingRecursiveComparison()
        .isEqualTo(nativeCountsBefore);

    long nativeNanos = timeNanos(() -> runLegacyMysqlCleanup(now.minusDays(25)));

    entityManager.flush();
    entityManager.clear();

    long criteriaNanos = timeNanos(() -> runJpaCleanup(now.minusDays(15)));

    entityManager.flush();
    entityManager.clear();

    long jpqlNanos = timeNanos(() -> runJpqlInCleanup(now.minusDays(5)));

    entityManager.flush();
    entityManager.clear();

    Assertions.assertThat(cleanupCounts(criteriaRepository))
        .usingRecursiveComparison()
        .isEqualTo(cleanupCounts(nativeRepository));
    Assertions.assertThat(cleanupCounts(jpqlRepository))
        .usingRecursiveComparison()
        .isEqualTo(cleanupCounts(nativeRepository));

    double nativeMillis = nativeNanos / 1_000_000.0;
    double criteriaMillis = criteriaNanos / 1_000_000.0;
    double jpqlMillis = jpqlNanos / 1_000_000.0;
    double criteriaRatio = nativeNanos == 0 ? 0 : (double) criteriaNanos / nativeNanos;
    double jpqlRatio = nativeNanos == 0 ? 0 : (double) jpqlNanos / nativeNanos;
    logger.info(
        "MySQL cleanup delete benchmark rows={}, legacyNativeMs={}, criteriaMs={}, jpqlInMs={}, criteriaToNativeRatio={}, jpqlToNativeRatio={}",
        rowCount,
        nativeMillis,
        criteriaMillis,
        jpqlMillis,
        criteriaRatio,
        jpqlRatio);
    System.out.printf(
        "MySQL cleanup delete benchmark rows=%d legacyNativeMs=%.3f criteriaMs=%.3f jpqlInMs=%.3f criteriaToNativeRatio=%.2f jpqlToNativeRatio=%.2f%n",
        rowCount, nativeMillis, criteriaMillis, jpqlMillis, criteriaRatio, jpqlRatio);
  }

  private void createCleanupFixture(Repository repository, String prefix, ZonedDateTime createdDate)
      throws Exception {
    TMTextUnit tmTextUnit1 =
        tmService.addTMTextUnit(
            tm.getId(),
            asset.getId(),
            prefix + " hello_world 1",
            prefix + " Hello World 1!",
            "Comments about " + prefix);
    TMTextUnit tmTextUnit2 =
        tmService.addTMTextUnit(
            tm.getId(),
            asset.getId(),
            prefix + " hello_world 2",
            prefix + " Hello World 2!",
            "Comments about " + prefix);

    PushRun pushRun = pushRunService.createPushRun(repository, prefix + "-push");
    pushRun.setCreatedDate(createdDate);
    pushRunRepository.save(pushRun);
    pushRunService.associatePushRunToTextUnitIds(
        pushRun, asset, Arrays.asList(tmTextUnit1.getId(), tmTextUnit2.getId()));

    Commit pushCommit =
        commitService.getOrCreateCommit(
            repository, prefix + "-push-commit", "author@example.com", "Author", createdDate);
    commitService.associateCommitToPushRun(pushCommit, pushRun);

    PullRun pullRun = pullRunService.getOrCreate(prefix + "-pull", repository);
    pullRun.setCreatedDate(createdDate);
    pullRunRepository.save(pullRun);
    PullRunAsset pullRunAsset = pullRunAssetService.createPullRunAsset(pullRun, asset);

    Locale frFR = localeService.findByBcp47Tag("fr-FR");
    TMTextUnitVariant tuv1 =
        tmService.addCurrentTMTextUnitVariant(
            tmTextUnit1.getId(), frFR.getId(), prefix + " le hello_world 1");
    TMTextUnitVariant tuv2 =
        tmService.addCurrentTMTextUnitVariant(
            tmTextUnit2.getId(), frFR.getId(), prefix + " le hello_world 2");
    pullRunAssetService.replaceTextUnitVariants(
        pullRunAsset, frFR.getId(), Arrays.asList(tuv1.getId(), tuv2.getId()), "fr-FR");

    Commit pullCommit =
        commitService.getOrCreateCommit(
            repository, prefix + "-pull-commit", "author@example.com", "Author", createdDate);
    commitService.associateCommitToPullRun(pullCommit, pullRun);
  }

  private void createLargeCleanupFixture(
      Repository repository, String prefix, ZonedDateTime createdDate, int rowCount)
      throws Exception {
    List<Long> tmTextUnitIds = createBenchmarkTextUnits(prefix, rowCount);

    PushRun pushRun = pushRunService.createPushRun(repository, prefix + "-push");
    pushRun.setCreatedDate(createdDate);
    pushRunRepository.save(pushRun);
    Long pushRunAssetId = createPushRunAsset(pushRun);
    createBenchmarkPushRunAssetTextUnits(pushRunAssetId, tmTextUnitIds);

    Commit pushCommit =
        commitService.getOrCreateCommit(
            repository, prefix + "-push-commit", "author@example.com", "Author", createdDate);
    commitService.associateCommitToPushRun(pushCommit, pushRun);

    PullRun pullRun = pullRunService.getOrCreate(prefix + "-pull", repository);
    pullRun.setCreatedDate(createdDate);
    pullRunRepository.save(pullRun);
    Long pullRunAssetId = createPullRunAsset(pullRun);
    Locale frFR = localeService.findByBcp47Tag("fr-FR");
    List<Long> tmTextUnitVariantIds =
        createBenchmarkTextUnitVariants(prefix, tmTextUnitIds, frFR.getId());
    createBenchmarkPullRunTextUnitVariants(pullRunAssetId, tmTextUnitVariantIds, frFR.getId());

    Commit pullCommit =
        commitService.getOrCreateCommit(
            repository, prefix + "-pull-commit", "author@example.com", "Author", createdDate);
    commitService.associateCommitToPullRun(pullCommit, pullRun);
  }

  private List<Long> createBenchmarkTextUnits(String prefix, int rowCount) {
    String namePrefix = testIdWatcher.getEntityName(prefix) + "-tu-";
    Timestamp createdDate = Timestamp.from(java.time.Instant.now());
    jdbcTemplate.batchUpdate(
        """
        insert into tm_text_unit(created_date, comment, content, content_md5, md5, name, asset_id, tm_id)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            String name = namePrefix + i;
            String content = "content " + name;
            ps.setTimestamp(1, createdDate);
            ps.setString(2, "comment " + name);
            ps.setString(3, content);
            ps.setString(4, DigestUtils.md5Hex(content));
            ps.setString(5, DigestUtils.md5Hex(name + content));
            ps.setString(6, name);
            ps.setLong(7, asset.getId());
            ps.setLong(8, tm.getId());
          }

          @Override
          public int getBatchSize() {
            return rowCount;
          }
        });

    return jdbcTemplate.queryForList(
        "select id from tm_text_unit where name like ? order by id", Long.class, namePrefix + "%");
  }

  private List<Long> createBenchmarkTextUnitVariants(
      String prefix, List<Long> tmTextUnitIds, Long localeId) {
    String contentPrefix = testIdWatcher.getEntityName(prefix) + "-tuv-";
    Timestamp createdDate = Timestamp.from(java.time.Instant.now());
    jdbcTemplate.batchUpdate(
        """
        insert into tm_text_unit_variant(created_date, content, content_md5, included_in_localized_file, status, locale_id, tm_text_unit_id)
        values (?, ?, ?, ?, ?, ?, ?)
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            String content = contentPrefix + i;
            ps.setTimestamp(1, createdDate);
            ps.setString(2, content);
            ps.setString(3, DigestUtils.md5Hex(content));
            ps.setBoolean(4, true);
            ps.setString(5, TMTextUnitVariant.Status.APPROVED.name());
            ps.setLong(6, localeId);
            ps.setLong(7, tmTextUnitIds.get(i));
          }

          @Override
          public int getBatchSize() {
            return tmTextUnitIds.size();
          }
        });

    return jdbcTemplate.queryForList(
        "select id from tm_text_unit_variant where content like ? order by id",
        Long.class,
        contentPrefix + "%");
  }

  private Long createPushRunAsset(PushRun pushRun) {
    PushRunAsset pushRunAsset = new PushRunAsset();
    pushRunAsset.setPushRun(pushRun);
    pushRunAsset.setAsset(asset);
    return pushRunAssetRepository.save(pushRunAsset).getId();
  }

  private Long createPullRunAsset(PullRun pullRun) {
    return pullRunAssetService.createPullRunAsset(pullRun, asset).getId();
  }

  private void createBenchmarkPushRunAssetTextUnits(Long pushRunAssetId, List<Long> tmTextUnitIds) {
    Timestamp createdDate = Timestamp.from(java.time.Instant.now());
    jdbcTemplate.batchUpdate(
        """
        insert into push_run_asset_tm_text_unit(push_run_asset_id, tm_text_unit_id, created_date)
        values (?, ?, ?)
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            ps.setLong(1, pushRunAssetId);
            ps.setLong(2, tmTextUnitIds.get(i));
            ps.setTimestamp(3, createdDate);
          }

          @Override
          public int getBatchSize() {
            return tmTextUnitIds.size();
          }
        });
  }

  private void createBenchmarkPullRunTextUnitVariants(
      Long pullRunAssetId, List<Long> tmTextUnitVariantIds, Long localeId) {
    Timestamp createdDate = Timestamp.from(java.time.Instant.now());
    jdbcTemplate.batchUpdate(
        """
        insert into pull_run_text_unit_variant(pull_run_asset_id, locale_id, tm_text_unit_variant_id, created_date, output_bcp47_tag)
        values (?, ?, ?, ?, ?)
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            ps.setLong(1, pullRunAssetId);
            ps.setLong(2, localeId);
            ps.setLong(3, tmTextUnitVariantIds.get(i));
            ps.setTimestamp(4, createdDate);
            ps.setString(5, "fr-FR");
          }

          @Override
          public int getBatchSize() {
            return tmTextUnitVariantIds.size();
          }
        });
  }

  private void runJpaCleanup(ZonedDateTime beforeDate) {
    deleteJpaPushRows(beforeDate);
    deleteJpaPullRows(beforeDate);
  }

  private void runJpqlInCleanup(ZonedDateTime beforeDate) {
    deleteJpqlInPushRows(beforeDate);
    deleteJpqlInPullRows(beforeDate);
  }

  private void deleteJpaPushRows(ZonedDateTime beforeDate) {
    int deleteCount;
    do {
      List<Long> ids =
          pushRunAssetTmTextUnitRepository.findIdsByPushRunWithCreatedDateBefore(
              beforeDate, PageRequest.of(0, 100000));
      deleteCount = deletePushRunAssetTmTextUnitsByIds(ids);
    } while (deleteCount == 100000);

    pushRunAssetRepository.deleteAllByPushRunWithCreatedDateBefore(beforeDate);
    commitToPushRunRepository.deleteAllByPushRunWithCreatedDateBefore(beforeDate);
    pushRunRepository.deleteAllByCreatedDateBefore(beforeDate);
  }

  private void deleteJpaPullRows(ZonedDateTime beforeDate) {
    int deleteCount;
    do {
      List<Long> ids =
          pullRunTextUnitVariantRepository.findIdsByPullRunWithCreatedDateBefore(
              beforeDate, PageRequest.of(0, 100000));
      deleteCount = deletePullRunTextUnitVariantsByIds(ids);
    } while (deleteCount == 100000);

    pullRunAssetRepository.deleteAllByPullRunWithCreatedDateBefore(beforeDate);
    commitToPullRunRepository.deleteAllByPullRunWithCreatedDateBefore(beforeDate);
    pullRunRepository.deleteAllByCreatedDateBefore(beforeDate);
  }

  private void deleteJpqlInPushRows(ZonedDateTime beforeDate) {
    int deleteCount;
    do {
      List<Long> ids =
          pushRunAssetTmTextUnitRepository.findIdsByPushRunWithCreatedDateBefore(
              beforeDate, PageRequest.of(0, 100000));
      deleteCount = deleteJpqlInPushRunAssetTmTextUnitsByIds(ids);
    } while (deleteCount == 100000);

    pushRunAssetRepository.deleteAllByPushRunWithCreatedDateBefore(beforeDate);
    commitToPushRunRepository.deleteAllByPushRunWithCreatedDateBefore(beforeDate);
    pushRunRepository.deleteAllByCreatedDateBefore(beforeDate);
  }

  private void deleteJpqlInPullRows(ZonedDateTime beforeDate) {
    int deleteCount;
    do {
      List<Long> ids =
          pullRunTextUnitVariantRepository.findIdsByPullRunWithCreatedDateBefore(
              beforeDate, PageRequest.of(0, 100000));
      deleteCount = deleteJpqlInPullRunTextUnitVariantsByIds(ids);
    } while (deleteCount == 100000);

    pullRunAssetRepository.deleteAllByPullRunWithCreatedDateBefore(beforeDate);
    commitToPullRunRepository.deleteAllByPullRunWithCreatedDateBefore(beforeDate);
    pullRunRepository.deleteAllByCreatedDateBefore(beforeDate);
  }

  private int deletePushRunAssetTmTextUnitsByIds(List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }

    CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
    CriteriaDelete<PushRunAssetTmTextUnit> delete =
        criteriaBuilder.createCriteriaDelete(PushRunAssetTmTextUnit.class);
    Root<PushRunAssetTmTextUnit> root = delete.from(PushRunAssetTmTextUnit.class);
    delete.where(root.get("id").in(ids));
    return entityManager.createQuery(delete).executeUpdate();
  }

  private int deletePullRunTextUnitVariantsByIds(List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }

    CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
    CriteriaDelete<PullRunTextUnitVariant> delete =
        criteriaBuilder.createCriteriaDelete(PullRunTextUnitVariant.class);
    Root<PullRunTextUnitVariant> root = delete.from(PullRunTextUnitVariant.class);
    delete.where(root.get("id").in(ids));
    return entityManager.createQuery(delete).executeUpdate();
  }

  private int deleteJpqlInPushRunAssetTmTextUnitsByIds(List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }

    return entityManager
        .createQuery(
            """
            delete from PushRunAssetTmTextUnit prattu
            where prattu.id in :ids
            """)
        .setParameter("ids", ids)
        .executeUpdate();
  }

  private int deleteJpqlInPullRunTextUnitVariantsByIds(List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }

    return entityManager
        .createQuery(
            """
            delete from PullRunTextUnitVariant prtuv
            where prtuv.id in :ids
            """)
        .setParameter("ids", ids)
        .executeUpdate();
  }

  private void runLegacyNativeCleanup(ZonedDateTime beforeDate) {
    if (dbUtils.isMysql()) {
      runLegacyMysqlCleanup(beforeDate);
    } else {
      runPortableNativeCleanup(beforeDate);
    }
  }

  private void runLegacyMysqlCleanup(ZonedDateTime beforeDate) {
    executeNative(
        """
        delete push_run_asset_tm_text_unit
        from push_run_asset_tm_text_unit
          join (select prattu.id as id
            from push_run pr
              join push_run_asset pra on pra.push_run_id = pr.id
              join push_run_asset_tm_text_unit prattu on prattu.push_run_asset_id = pra.id
            where pr.created_date < :beforeDate
            limit 100000
          ) todelete on todelete.id = push_run_asset_tm_text_unit.id
        """,
        beforeDate);
    executeNative(
        """
        delete pra
        from push_run pr
        join push_run_asset pra on pra.push_run_id = pr.id
        where pr.created_date < :beforeDate
        """,
        beforeDate);
    executeNative(
        """
        delete ctpr
        from push_run pr
        join commit_to_push_run ctpr on ctpr.push_run_id = pr.id
        where pr.created_date < :beforeDate
        """,
        beforeDate);
    executeNative("delete pr from push_run pr where pr.created_date < :beforeDate", beforeDate);

    executeNative(
        """
        delete pull_run_text_unit_variant
        from pull_run_text_unit_variant
        join (select prtuv.id as id
          from pull_run pr
          join pull_run_asset pra on pra.pull_run_id = pr.id
          join pull_run_text_unit_variant prtuv on prtuv.pull_run_asset_id = pra.id
          where pr.created_date < :beforeDate
          limit 100000
        ) todelete on todelete.id = pull_run_text_unit_variant.id
        """,
        beforeDate);
    executeNative(
        """
        delete pra
        from pull_run pr
        join pull_run_asset pra on pra.pull_run_id = pr.id
        where pr.created_date < :beforeDate
        """,
        beforeDate);
    executeNative(
        """
        delete ctpr
        from pull_run pr
        join commit_to_pull_run ctpr on ctpr.pull_run_id = pr.id
        where pr.created_date < :beforeDate
        """,
        beforeDate);
    executeNative("delete pr from pull_run pr where pr.created_date < :beforeDate", beforeDate);
  }

  private void runPortableNativeCleanup(ZonedDateTime beforeDate) {
    executeNative(
        """
        delete from push_run_asset_tm_text_unit
        where id in (
          select prattu.id
          from push_run pr
          join push_run_asset pra on pra.push_run_id = pr.id
          join push_run_asset_tm_text_unit prattu on prattu.push_run_asset_id = pra.id
          where pr.created_date < :beforeDate
        )
        """,
        beforeDate);
    executeNative(
        """
        delete from push_run_asset
        where push_run_id in (select id from push_run where created_date < :beforeDate)
        """,
        beforeDate);
    executeNative(
        """
        delete from commit_to_push_run
        where push_run_id in (select id from push_run where created_date < :beforeDate)
        """,
        beforeDate);
    executeNative("delete from push_run where created_date < :beforeDate", beforeDate);

    executeNative(
        """
        delete from pull_run_text_unit_variant
        where id in (
          select prtuv.id
          from pull_run pr
          join pull_run_asset pra on pra.pull_run_id = pr.id
          join pull_run_text_unit_variant prtuv on prtuv.pull_run_asset_id = pra.id
          where pr.created_date < :beforeDate
        )
        """,
        beforeDate);
    executeNative(
        """
        delete from pull_run_asset
        where pull_run_id in (select id from pull_run where created_date < :beforeDate)
        """,
        beforeDate);
    executeNative(
        """
        delete from commit_to_pull_run
        where pull_run_id in (select id from pull_run where created_date < :beforeDate)
        """,
        beforeDate);
    executeNative("delete from pull_run where created_date < :beforeDate", beforeDate);
  }

  private void executeNative(String sql, ZonedDateTime beforeDate) {
    entityManager.createNativeQuery(sql).setParameter("beforeDate", beforeDate).executeUpdate();
  }

  private CleanupCounts cleanupCounts(Repository repository) {
    return new CleanupCounts(
        countRows("push_run", "repository_id", repository.getId()),
        countPushRunAssets(repository.getId()),
        countPushRunAssetTmTextUnits(repository.getId()),
        countCommitToPushRuns(repository.getId()),
        countRows("pull_run", "repository_id", repository.getId()),
        countPullRunAssets(repository.getId()),
        countPullRunTextUnitVariants(repository.getId()),
        countCommitToPullRuns(repository.getId()));
  }

  private long countRows(String table, String column, Long value) {
    return ((Number)
            entityManager
                .createNativeQuery("select count(*) from " + table + " where " + column + " = :id")
                .setParameter("id", value)
                .getSingleResult())
        .longValue();
  }

  private long countPushRunAssets(Long repositoryId) {
    return countJoinedRows(
        """
        select count(*)
        from push_run_asset pra
        join push_run pr on pr.id = pra.push_run_id
        where pr.repository_id = :repositoryId
        """,
        repositoryId);
  }

  private long countPushRunAssetTmTextUnits(Long repositoryId) {
    return countJoinedRows(
        """
        select count(*)
        from push_run_asset_tm_text_unit prattu
        join push_run_asset pra on pra.id = prattu.push_run_asset_id
        join push_run pr on pr.id = pra.push_run_id
        where pr.repository_id = :repositoryId
        """,
        repositoryId);
  }

  private long countCommitToPushRuns(Long repositoryId) {
    return countJoinedRows(
        """
        select count(*)
        from commit_to_push_run ctpr
        join push_run pr on pr.id = ctpr.push_run_id
        where pr.repository_id = :repositoryId
        """,
        repositoryId);
  }

  private long countPullRunAssets(Long repositoryId) {
    return countJoinedRows(
        """
        select count(*)
        from pull_run_asset pra
        join pull_run pr on pr.id = pra.pull_run_id
        where pr.repository_id = :repositoryId
        """,
        repositoryId);
  }

  private long countPullRunTextUnitVariants(Long repositoryId) {
    return countJoinedRows(
        """
        select count(*)
        from pull_run_text_unit_variant prtuv
        join pull_run_asset pra on pra.id = prtuv.pull_run_asset_id
        join pull_run pr on pr.id = pra.pull_run_id
        where pr.repository_id = :repositoryId
        """,
        repositoryId);
  }

  private long countCommitToPullRuns(Long repositoryId) {
    return countJoinedRows(
        """
        select count(*)
        from commit_to_pull_run ctpr
        join pull_run pr on pr.id = ctpr.pull_run_id
        where pr.repository_id = :repositoryId
        """,
        repositoryId);
  }

  private long countJoinedRows(String sql, Long repositoryId) {
    return ((Number)
            entityManager
                .createNativeQuery(sql)
                .setParameter("repositoryId", repositoryId)
                .getSingleResult())
        .longValue();
  }

  private long timeNanos(ThrowingRunnable runnable) throws Exception {
    long startNanos = System.nanoTime();
    runnable.run();
    return System.nanoTime() - startNanos;
  }

  interface ThrowingRunnable {
    void run() throws Exception;
  }

  record CleanupCounts(
      long pushRuns,
      long pushRunAssets,
      long pushRunAssetTmTextUnits,
      long commitToPushRuns,
      long pullRuns,
      long pullRunAssets,
      long pullRunTextUnitVariants,
      long commitToPullRuns) {}
}
