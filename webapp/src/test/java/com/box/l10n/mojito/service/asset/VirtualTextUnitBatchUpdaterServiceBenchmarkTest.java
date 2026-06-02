package com.box.l10n.mojito.service.asset;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetTextUnitToTMTextUnit;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TM;
import com.box.l10n.mojito.service.DBUtils;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMRepository;
import com.box.l10n.mojito.test.TestIdWatcher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.assertj.core.api.Assertions;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class VirtualTextUnitBatchUpdaterServiceBenchmarkTest extends ServiceTestBase {

  static Logger logger =
      LoggerFactory.getLogger(VirtualTextUnitBatchUpdaterServiceBenchmarkTest.class);

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired EntityManager entityManager;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired DBUtils dbUtils;

  @Autowired RepositoryService repositoryService;

  @Autowired AssetService assetService;

  @Autowired TMRepository tmRepository;

  TM tm;

  @Before
  public void before() {
    if (tm == null) {
      tm = tmRepository.save(new TM());
    }
  }

  @Transactional
  @Test
  public void virtualMappingDeleteMatchesLegacyNativeOrSql() throws Exception {
    BenchmarkFixture nativeFixture = createVirtualDeleteFixture("equivalence-native", 6);
    BenchmarkFixture jpqlFixture = createVirtualDeleteFixture("equivalence-jpql", 6);
    List<Integer> indexesToDelete = List.of(0, 2, 5);

    entityManager.flush();
    entityManager.clear();

    int nativeDeleted =
        deleteNativeOrByAssetIds(assetTextUnitIdsAt(nativeFixture, indexesToDelete));
    int jpqlDeleted = deleteJpqlInByAssetIds(assetTextUnitIdsAt(jpqlFixture, indexesToDelete));

    entityManager.flush();
    entityManager.clear();

    Assertions.assertThat(jpqlDeleted).isEqualTo(nativeDeleted);
    Assertions.assertThat(remainingMappedNameSuffixes(jpqlFixture))
        .isEqualTo(remainingMappedNameSuffixes(nativeFixture))
        .containsExactly("1", "3", "4");
  }

  @Transactional
  @Test
  public void mysqlVirtualMappingDeleteBenchmarkNativeOrAgainstCriteriaAndJpql() throws Exception {
    Assume.assumeTrue(
        "MySQL virtual mapping delete benchmark is opt-in and requires a MySQL test database",
        dbUtils.isMysql() && Boolean.getBoolean("mojito.test.mysqlVirtualDeleteBenchmark"));

    int rowCount = Integer.getInteger("mojito.test.virtualDeleteBenchmarkRows", 20000);
    int batchSize = Integer.getInteger("mojito.test.virtualDeleteBenchmarkBatchSize", 100);
    BenchmarkFixture nativeFixture = createVirtualDeleteFixture("bench-native", rowCount);
    BenchmarkFixture criteriaFixture = createVirtualDeleteFixture("bench-criteria", rowCount);
    BenchmarkFixture jpqlFixture = createVirtualDeleteFixture("bench-jpql", rowCount);

    entityManager.flush();
    entityManager.clear();

    Assertions.assertThat(countMappings(criteriaFixture.assetExtractionId()))
        .isEqualTo(countMappings(nativeFixture.assetExtractionId()));
    Assertions.assertThat(countMappings(jpqlFixture.assetExtractionId()))
        .isEqualTo(countMappings(nativeFixture.assetExtractionId()));

    long nativeNanos =
        timeNanos(
            () ->
                deleteInBatches(
                    nativeFixture.assetTextUnitIds(), batchSize, this::deleteNativeOrByAssetIds));

    entityManager.flush();
    entityManager.clear();

    long criteriaNanos =
        timeNanos(
            () ->
                deleteInBatches(
                    criteriaFixture.assetTextUnitIds(),
                    batchSize,
                    this::deleteCriteriaOrByAssetIds));

    entityManager.flush();
    entityManager.clear();

    long jpqlNanos =
        timeNanos(
            () ->
                deleteInBatches(
                    jpqlFixture.assetTextUnitIds(), batchSize, this::deleteJpqlInByAssetIds));

    entityManager.flush();
    entityManager.clear();

    Assertions.assertThat(countMappings(nativeFixture.assetExtractionId())).isZero();
    Assertions.assertThat(countMappings(criteriaFixture.assetExtractionId())).isZero();
    Assertions.assertThat(countMappings(jpqlFixture.assetExtractionId())).isZero();

    double nativeMillis = nativeNanos / 1_000_000.0;
    double criteriaMillis = criteriaNanos / 1_000_000.0;
    double jpqlMillis = jpqlNanos / 1_000_000.0;
    double criteriaRatio = nativeNanos == 0 ? 0 : (double) criteriaNanos / nativeNanos;
    double jpqlRatio = nativeNanos == 0 ? 0 : (double) jpqlNanos / nativeNanos;
    logger.info(
        "MySQL virtual mapping delete benchmark rows={}, batchSize={}, legacyNativeOrMs={}, criteriaOrMs={}, jpqlInMs={}, criteriaToNativeRatio={}, jpqlToNativeRatio={}",
        rowCount,
        batchSize,
        nativeMillis,
        criteriaMillis,
        jpqlMillis,
        criteriaRatio,
        jpqlRatio);
    System.out.printf(
        "MySQL virtual mapping delete benchmark rows=%d batchSize=%d legacyNativeOrMs=%.3f criteriaOrMs=%.3f jpqlInMs=%.3f criteriaToNativeRatio=%.2f jpqlToNativeRatio=%.2f%n",
        rowCount, batchSize, nativeMillis, criteriaMillis, jpqlMillis, criteriaRatio, jpqlRatio);
  }

  private BenchmarkFixture createVirtualDeleteFixture(String prefix, int rowCount)
      throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName(prefix) + "-repository");
    Asset asset =
        assetService.createAssetWithContent(
            repository.getId(), testIdWatcher.getEntityName(prefix) + "-asset", "content");
    Long assetExtractionId = asset.getLastSuccessfulAssetExtraction().getId();

    List<Long> assetTextUnitIds =
        createBenchmarkAssetTextUnits(prefix, assetExtractionId, rowCount);
    List<Long> tmTextUnitIds = createBenchmarkTextUnits(prefix, asset.getId(), rowCount);
    createBenchmarkMappings(assetExtractionId, assetTextUnitIds, tmTextUnitIds);

    return new BenchmarkFixture(
        assetExtractionId, assetTextUnitIds, testIdWatcher.getEntityName(prefix) + "-atu-");
  }

  private List<Long> createBenchmarkAssetTextUnits(
      String prefix, Long assetExtractionId, int rowCount) {
    String namePrefix = testIdWatcher.getEntityName(prefix) + "-atu-";
    Timestamp createdDate = Timestamp.from(java.time.Instant.now());
    jdbcTemplate.batchUpdate(
        """
        insert into asset_text_unit(created_date, last_modified_date, comment, content, content_md5, md5, name, asset_extraction_id, do_not_translate)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            String name = namePrefix + i;
            String content = "content " + name;
            String comment = "comment " + name;
            ps.setTimestamp(1, createdDate);
            ps.setTimestamp(2, createdDate);
            ps.setString(3, comment);
            ps.setString(4, content);
            ps.setString(5, DigestUtils.md5Hex(content));
            ps.setString(6, DigestUtils.md5Hex(name + content + comment));
            ps.setString(7, name);
            ps.setLong(8, assetExtractionId);
            ps.setBoolean(9, false);
          }

          @Override
          public int getBatchSize() {
            return rowCount;
          }
        });

    return jdbcTemplate.queryForList(
        "select id from asset_text_unit where name like ? order by id",
        Long.class,
        namePrefix + "%");
  }

  private List<Long> createBenchmarkTextUnits(String prefix, Long assetId, int rowCount) {
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
            String comment = "comment " + name;
            ps.setTimestamp(1, createdDate);
            ps.setString(2, comment);
            ps.setString(3, content);
            ps.setString(4, DigestUtils.md5Hex(content));
            ps.setString(5, DigestUtils.md5Hex(name + content + comment));
            ps.setString(6, name);
            ps.setLong(7, assetId);
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

  private void createBenchmarkMappings(
      Long assetExtractionId, List<Long> assetTextUnitIds, List<Long> tmTextUnitIds) {
    jdbcTemplate.batchUpdate(
        """
        insert into asset_text_unit_to_tm_text_unit(asset_extraction_id, asset_text_unit_id, tm_text_unit_id)
        values (?, ?, ?)
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            ps.setLong(1, assetExtractionId);
            ps.setLong(2, assetTextUnitIds.get(i));
            ps.setLong(3, tmTextUnitIds.get(i));
          }

          @Override
          public int getBatchSize() {
            return assetTextUnitIds.size();
          }
        });
  }

  private int deleteInBatches(List<Long> ids, int batchSize, DeleteBatch deleteBatch) {
    int deleted = 0;
    for (int start = 0; start < ids.size(); start += batchSize) {
      deleted += deleteBatch.delete(ids.subList(start, Math.min(start + batchSize, ids.size())));
    }
    return deleted;
  }

  private List<Long> assetTextUnitIdsAt(BenchmarkFixture fixture, List<Integer> indexes) {
    return indexes.stream().map(index -> fixture.assetTextUnitIds().get(index)).toList();
  }

  private List<String> remainingMappedNameSuffixes(BenchmarkFixture fixture) {
    return jdbcTemplate
        .queryForList(
            """
            select atu.name
            from asset_text_unit atu
            join asset_text_unit_to_tm_text_unit map on map.asset_text_unit_id = atu.id
            where map.asset_extraction_id = ?
            order by atu.id
            """,
            String.class,
            fixture.assetExtractionId())
        .stream()
        .map(name -> name.substring(fixture.assetTextUnitNamePrefix().length()))
        .toList();
  }

  private int deleteNativeOrByAssetIds(List<Long> assetTextUnitIds) {
    if (assetTextUnitIds.isEmpty()) {
      return 0;
    }

    StringBuilder builder = new StringBuilder("delete from asset_text_unit_to_tm_text_unit where ");
    for (int i = 0; i < assetTextUnitIds.size(); i++) {
      builder.append("asset_text_unit_id = :p").append(i);
      if (i != assetTextUnitIds.size() - 1) {
        builder.append(" or ");
      }
    }

    Query query = entityManager.createNativeQuery(builder.toString());
    for (int i = 0; i < assetTextUnitIds.size(); i++) {
      query.setParameter("p" + i, assetTextUnitIds.get(i));
    }
    return query.executeUpdate();
  }

  private int deleteCriteriaOrByAssetIds(List<Long> assetTextUnitIds) {
    if (assetTextUnitIds.isEmpty()) {
      return 0;
    }

    CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
    CriteriaDelete<AssetTextUnitToTMTextUnit> delete =
        criteriaBuilder.createCriteriaDelete(AssetTextUnitToTMTextUnit.class);
    Root<AssetTextUnitToTMTextUnit> root = delete.from(AssetTextUnitToTMTextUnit.class);
    List<Predicate> predicates =
        assetTextUnitIds.stream()
            .map(id -> criteriaBuilder.equal(root.get("assetTextUnit").get("id"), id))
            .toList();
    delete.where(criteriaBuilder.or(predicates.toArray(new Predicate[0])));
    return entityManager.createQuery(delete).executeUpdate();
  }

  private int deleteJpqlInByAssetIds(List<Long> assetTextUnitIds) {
    if (assetTextUnitIds.isEmpty()) {
      return 0;
    }

    return entityManager
        .createQuery(
            """
            delete from AssetTextUnitToTMTextUnit map
            where map.assetTextUnit.id in :assetTextUnitIds
            """)
        .setParameter("assetTextUnitIds", assetTextUnitIds)
        .executeUpdate();
  }

  private long countMappings(Long assetExtractionId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from asset_text_unit_to_tm_text_unit where asset_extraction_id = ?",
        Long.class,
        assetExtractionId);
  }

  private long timeNanos(Runnable runnable) {
    long start = System.nanoTime();
    runnable.run();
    return System.nanoTime() - start;
  }

  private interface DeleteBatch {
    int delete(List<Long> ids);
  }

  private record BenchmarkFixture(
      Long assetExtractionId, List<Long> assetTextUnitIds, String assetTextUnitNamePrefix) {}
}
