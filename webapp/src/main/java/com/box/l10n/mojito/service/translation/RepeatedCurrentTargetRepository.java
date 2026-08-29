package com.box.l10n.mojito.service.translation;

import static com.box.l10n.mojito.service.translation.ExactTextEvidence.PREVIEW_CODE_POINT_LIMIT;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to exact repeated current targets. */
@Repository
public class RepeatedCurrentTargetRepository {

  public static final int MAX_REVIEW_PROJECT_EVIDENCE_PER_TEXT_UNIT = 20;

  private static final String MYSQL_MEMBER_SELECT =
      """
      WITH eligible_current AS (
          SELECT %s
              current_pointer.id AS current_pointer_id,
              current_pointer.last_modified_date AS current_pointer_last_modified_at,
              current_pointer.tm_text_unit_id,
              current_pointer.locale_id,
              current_pointer.tm_text_unit_variant_id AS current_variant_id,
              current_variant.content_md5 AS target_content_md5
          %s
          JOIN tm_text_unit_variant current_variant
              ON current_variant.id = current_pointer.tm_text_unit_variant_id
              AND current_variant.tm_text_unit_id = current_pointer.tm_text_unit_id
              AND current_variant.locale_id = current_pointer.locale_id
          JOIN tm_text_unit source_text_unit
              ON source_text_unit.id = current_pointer.tm_text_unit_id
          JOIN asset source_asset
              ON source_asset.id = source_text_unit.asset_id
              AND source_asset.deleted = 0
          JOIN repository source_repository
              ON source_repository.id = source_asset.repository_id
              AND source_repository.deleted = 0
          JOIN repository_locale configured_locale
              ON configured_locale.repository_id = source_repository.id
              AND configured_locale.locale_id = current_pointer.locale_id
          WHERE current_pointer.id <= :highWaterCurrentPointerId
              AND current_pointer.locale_id <> source_repository.source_locale_id
              AND current_variant.content IS NOT NULL
              %s
      )
      SELECT %s
          c1.current_pointer_id,
          c1.current_pointer_last_modified_at,
          c1.current_variant_id,
          %s,
          v1.status AS current_status,
          v1.included_in_localized_file,
          v1.created_date AS current_variant_created_at,
          v1.created_by_user_id AS current_variant_created_by_user_id,
          variant_creator.username AS current_variant_created_by_username,
          variant_creator.common_name AS current_variant_created_by_common_name,
          c1.locale_id AS target_locale_id,
          target_locale.bcp47_tag AS target_locale,
          tu1.id AS tm_text_unit_id,
          %s,
          %s,
          %s,
          a1.id AS asset_id,
          a1.path AS asset_path,
          r1.id AS repository_id,
          r1.name AS repository_name,
          r1.source_locale_id,
          source_locale.bcp47_tag AS source_locale
      FROM eligible_current c1
      JOIN tm_text_unit_variant v1
          ON v1.id = c1.current_variant_id
      JOIN tm_text_unit tu1
          ON tu1.id = c1.tm_text_unit_id
      JOIN asset a1
          ON a1.id = tu1.asset_id
          AND a1.deleted = 0
      JOIN repository r1
          ON r1.id = a1.repository_id
          AND r1.deleted = 0
      JOIN repository_locale repository_locale1
          ON repository_locale1.repository_id = r1.id
          AND repository_locale1.locale_id = c1.locale_id
      JOIN locale target_locale
          ON target_locale.id = c1.locale_id
      JOIN locale source_locale
          ON source_locale.id = r1.source_locale_id
      LEFT JOIN %s variant_creator
          ON variant_creator.id = v1.created_by_user_id
      WHERE c1.current_pointer_id > :afterCurrentPointerId
          AND EXISTS (
              SELECT 1
              FROM eligible_current c2
              JOIN tm_text_unit_variant v2
                  ON v2.id = c2.current_variant_id
              JOIN tm_text_unit tu2
                  ON tu2.id = c2.tm_text_unit_id
              WHERE c2.current_pointer_id <> c1.current_pointer_id
                  AND c2.tm_text_unit_id <> c1.tm_text_unit_id
                  AND c2.locale_id = c1.locale_id
                  AND c2.target_content_md5 = c1.target_content_md5
                  AND %s
                  AND %s
          )
      ORDER BY c1.current_pointer_id
      LIMIT :fetchLimit
      """;

  private static final String HSQL_MEMBER_SELECT =
      """
      SELECT
          cv1.id AS current_pointer_id,
          cv1.last_modified_date AS current_pointer_last_modified_at,
          cv1.tm_text_unit_variant_id AS current_variant_id,
          %s,
          v1.status AS current_status,
          v1.included_in_localized_file,
          v1.created_date AS current_variant_created_at,
          v1.created_by_user_id AS current_variant_created_by_user_id,
          variant_creator.username AS current_variant_created_by_username,
          variant_creator.common_name AS current_variant_created_by_common_name,
          cv1.locale_id AS target_locale_id,
          target_locale.bcp47_tag AS target_locale,
          tu1.id AS tm_text_unit_id,
          %s,
          %s,
          %s,
          a1.id AS asset_id,
          a1.path AS asset_path,
          r1.id AS repository_id,
          r1.name AS repository_name,
          r1.source_locale_id,
          source_locale.bcp47_tag AS source_locale
      FROM tm_text_unit_current_variant cv1
      JOIN tm_text_unit_variant v1
          ON v1.id = cv1.tm_text_unit_variant_id
          AND v1.tm_text_unit_id = cv1.tm_text_unit_id
          AND v1.locale_id = cv1.locale_id
      JOIN tm_text_unit tu1
          ON tu1.id = cv1.tm_text_unit_id
      JOIN asset a1
          ON a1.id = tu1.asset_id
          AND a1.deleted = 0
      JOIN repository r1
          ON r1.id = a1.repository_id
          AND r1.deleted = 0
      JOIN repository_locale repository_locale1
          ON repository_locale1.repository_id = r1.id
          AND repository_locale1.locale_id = cv1.locale_id
      JOIN locale target_locale
          ON target_locale.id = cv1.locale_id
      JOIN locale source_locale
          ON source_locale.id = r1.source_locale_id
      LEFT JOIN "USER" variant_creator
          ON variant_creator.id = v1.created_by_user_id
      %s
      WHERE cv1.id > :afterCurrentPointerId
          AND cv1.id <= :highWaterCurrentPointerId
          AND cv1.locale_id <> r1.source_locale_id
          AND v1.content IS NOT NULL
          %s
          AND EXISTS (
              SELECT 1
              FROM tm_text_unit_variant v2
              JOIN tm_text_unit_current_variant cv2
                  ON cv2.tm_text_unit_variant_id = v2.id
                  AND cv2.tm_text_unit_id = v2.tm_text_unit_id
                  AND cv2.locale_id = v2.locale_id
              JOIN tm_text_unit tu2
                  ON tu2.id = cv2.tm_text_unit_id
              JOIN asset a2
                  ON a2.id = tu2.asset_id
                  AND a2.deleted = 0
              JOIN repository r2
                  ON r2.id = a2.repository_id
                  AND r2.deleted = 0
              JOIN repository_locale repository_locale2
                  ON repository_locale2.repository_id = r2.id
                  AND repository_locale2.locale_id = cv2.locale_id
              WHERE cv2.id <> cv1.id
                  AND cv2.id <= :highWaterCurrentPointerId
                  AND cv2.tm_text_unit_id <> cv1.tm_text_unit_id
                  AND cv2.locale_id = cv1.locale_id
                  AND cv2.locale_id <> r2.source_locale_id
                  AND v2.content_md5 = v1.content_md5
                  AND v2.content = v1.content
                  AND (
                      tu2.content <> tu1.content
                      OR (tu2.content IS NULL AND tu1.content IS NOT NULL)
                      OR (tu2.content IS NOT NULL AND tu1.content IS NULL)
                  )
                  %s
          )
      ORDER BY cv1.id
      LIMIT :fetchLimit
      """;

  static final String MYSQL_CANDIDATE_MEMBERS_SQL = buildMysqlCandidateMembersSql(false);
  static final String MYSQL_SCOPED_CANDIDATE_MEMBERS_SQL = buildMysqlCandidateMembersSql(true);
  static final String HSQL_CANDIDATE_MEMBERS_SQL = buildHsqlCandidateMembersSql(false);
  static final String HSQL_SCOPED_CANDIDATE_MEMBERS_SQL = buildHsqlCandidateMembersSql(true);

  static final String MYSQL_CANDIDATE_STATE_FINGERPRINT_SQL =
      buildCandidateStateFingerprintSql(false, false);
  static final String MYSQL_SCOPED_CANDIDATE_STATE_FINGERPRINT_SQL =
      buildMysqlScopedCandidateStateFingerprintSql();
  static final String HSQL_CANDIDATE_STATE_FINGERPRINT_SQL =
      buildCandidateStateFingerprintSql(true, false);
  static final String HSQL_SCOPED_CANDIDATE_STATE_FINGERPRINT_SQL =
      buildCandidateStateFingerprintSql(true, true);

  static final String MYSQL_REVIEW_PROJECT_EVIDENCE_SQL = buildReviewProjectEvidenceSql(false);
  static final String HSQL_REVIEW_PROJECT_EVIDENCE_SQL = buildReviewProjectEvidenceSql(true);
  static final String MYSQL_SCOPED_INVALID_CURRENT_TARGET_HASH_COUNT_SQL =
      buildMysqlScopedInvalidCurrentTargetHashCountSql();
  static final String MYSQL_SCOPED_CURRENT_POINTER_AUDIT_WATERMARK_SQL =
      buildMysqlScopedCurrentPointerAuditWatermarkSql();

  private final JdbcTemplate rawJdbcTemplate;
  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final boolean hsql;

  public RepeatedCurrentTargetRepository(
      JdbcTemplate jdbcTemplate, @Value("${spring.datasource.url:}") String datasourceUrl) {
    this.rawJdbcTemplate = jdbcTemplate;
    this.jdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    this.hsql = datasourceUrl != null && datasourceUrl.startsWith("jdbc:hsqldb:");
  }

  @Transactional(readOnly = true)
  public long findMaxCurrentPointerId() {
    Long value =
        rawJdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM tm_text_unit_current_variant", Long.class);
    return value == null ? 0 : value;
  }

  @Transactional(readOnly = true)
  public boolean reviewProjectExists(long reviewProjectId) {
    Integer value =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM review_project WHERE id = :reviewProjectId",
            new MapSqlParameterSource("reviewProjectId", reviewProjectId),
            Integer.class);
    return value != null && value == 1;
  }

  @Transactional(readOnly = true)
  public long findMaxReviewProjectTextUnitId(long reviewProjectId) {
    Long value =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM review_project_text_unit WHERE review_project_id = :reviewProjectId",
            new MapSqlParameterSource("reviewProjectId", reviewProjectId),
            Long.class);
    return value == null ? 0 : value;
  }

  /**
   * Ensures the stored hash is safe as a candidate prefilter. Exact comparison is authoritative.
   */
  @Transactional(readOnly = true)
  public long countInvalidCurrentTargetHashes(
      long highWaterCurrentPointerId, Long reviewProjectId, Long highWaterReviewProjectTextUnitId) {
    if (!hsql && reviewProjectId != null) {
      Long value =
          jdbcTemplate.queryForObject(
              MYSQL_SCOPED_INVALID_CURRENT_TARGET_HASH_COUNT_SQL,
              scopeParameters(
                  highWaterCurrentPointerId, reviewProjectId, highWaterReviewProjectTextUnitId),
              Long.class);
      return value == null ? 0 : value;
    }
    String scopeJoin =
        reviewProjectId == null
            ? ""
            : """
              JOIN review_project hash_project
                  ON hash_project.id = :reviewProjectId
                  AND hash_project.locale_id = current_pointer.locale_id
              JOIN review_project_text_unit hash_rptu
                  ON hash_rptu.review_project_id = hash_project.id
                  AND hash_rptu.tm_text_unit_id = current_pointer.tm_text_unit_id
                  AND hash_rptu.id <= :highWaterReviewProjectTextUnitId
              """;
    String invalidPredicate =
        hsql
            ? "current_variant.content_md5 IS NULL"
            : """
              (
                  current_variant.content_md5 IS NULL
                  OR CAST(current_variant.content_md5 AS BINARY) <>
                      CAST(MD5(current_variant.content) AS BINARY)
              )
              """;
    String sql =
        """
        SELECT COUNT(DISTINCT current_pointer.id)
        FROM tm_text_unit_current_variant current_pointer
        JOIN tm_text_unit_variant current_variant
            ON current_variant.id = current_pointer.tm_text_unit_variant_id
            AND current_variant.tm_text_unit_id = current_pointer.tm_text_unit_id
            AND current_variant.locale_id = current_pointer.locale_id
        JOIN tm_text_unit source_text_unit
            ON source_text_unit.id = current_pointer.tm_text_unit_id
        JOIN asset source_asset
            ON source_asset.id = source_text_unit.asset_id
            AND source_asset.deleted = 0
        JOIN repository source_repository
            ON source_repository.id = source_asset.repository_id
            AND source_repository.deleted = 0
        JOIN repository_locale configured_locale
            ON configured_locale.repository_id = source_repository.id
            AND configured_locale.locale_id = current_pointer.locale_id
        %s
        WHERE current_pointer.id <= :highWaterCurrentPointerId
            AND current_pointer.locale_id <> source_repository.source_locale_id
            AND current_variant.content IS NOT NULL
            AND %s
        """
            .formatted(scopeJoin, invalidPredicate);
    MapSqlParameterSource parameters =
        scopeParameters(
            highWaterCurrentPointerId, reviewProjectId, highWaterReviewProjectTextUnitId);
    Long value = jdbcTemplate.queryForObject(sql, parameters, Long.class);
    return value == null ? 0 : value;
  }

  @Transactional(readOnly = true)
  public AuditWatermark readCurrentPointerAuditWatermark(
      long highWaterCurrentPointerId, Long reviewProjectId, Long highWaterReviewProjectTextUnitId) {
    String sql;
    if (!hsql && reviewProjectId != null) {
      sql = MYSQL_SCOPED_CURRENT_POINTER_AUDIT_WATERMARK_SQL;
    } else {
      sql =
          """
          SELECT COUNT(*) AS audit_row_count, COALESCE(MAX(audit_row.rev), 0) AS max_audit_revision
          FROM tm_text_unit_current_variant_aud audit_row
          WHERE audit_row.id <= :highWaterCurrentPointerId
          %s
          """
              .formatted(auditScopePredicate(reviewProjectId));
    }
    return jdbcTemplate.queryForObject(
        sql,
        scopeParameters(
            highWaterCurrentPointerId, reviewProjectId, highWaterReviewProjectTextUnitId),
        (resultSet, rowNumber) ->
            new AuditWatermark(
                resultSet.getLong("audit_row_count"), resultSet.getLong("max_audit_revision")));
  }

  @Transactional(readOnly = true)
  public boolean hasCurrentPointerAuditRevisionAfter(
      long highWaterCurrentPointerId,
      Long reviewProjectId,
      Long highWaterReviewProjectTextUnitId,
      long auditRevision) {
    String sql =
        """
        SELECT 1
        FROM tm_text_unit_current_variant_aud audit_row
        WHERE audit_row.rev > :auditRevision
            AND audit_row.id <= :highWaterCurrentPointerId
            %s
        LIMIT 1
        """
            .formatted(auditScopePredicate(reviewProjectId));
    MapSqlParameterSource parameters =
        scopeParameters(
                highWaterCurrentPointerId, reviewProjectId, highWaterReviewProjectTextUnitId)
            .addValue("auditRevision", auditRevision);
    return !jdbcTemplate.queryForList(sql, parameters, Integer.class).isEmpty();
  }

  /**
   * Streams the complete candidate-membership state into a constant-memory fingerprint. Production
   * text hashes are computed by MySQL so no LONGTEXT value crosses JDBC.
   */
  @Transactional(readOnly = true)
  public CandidateStateFingerprint readCandidateStateFingerprint(
      long highWaterCurrentPointerId, Long reviewProjectId, Long highWaterReviewProjectTextUnitId) {
    String sql;
    if (hsql) {
      sql =
          reviewProjectId == null
              ? HSQL_CANDIDATE_STATE_FINGERPRINT_SQL
              : HSQL_SCOPED_CANDIDATE_STATE_FINGERPRINT_SQL;
    } else {
      sql =
          reviewProjectId == null
              ? MYSQL_CANDIDATE_STATE_FINGERPRINT_SQL
              : MYSQL_SCOPED_CANDIDATE_STATE_FINGERPRINT_SQL;
    }
    MessageDigest digest = sha256Digest();
    long[] rowCount = {0};
    rawJdbcTemplate.query(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
          statement.setFetchSize(hsql ? 1000 : Integer.MIN_VALUE);
          if (!hsql && reviewProjectId != null) {
            statement.setLong(1, reviewProjectId);
            statement.setLong(2, highWaterReviewProjectTextUnitId);
            statement.setLong(3, highWaterCurrentPointerId);
          } else {
            statement.setLong(1, highWaterCurrentPointerId);
          }
          if (hsql && reviewProjectId != null) {
            statement.setLong(2, reviewProjectId);
            statement.setLong(3, reviewProjectId);
            statement.setLong(4, highWaterReviewProjectTextUnitId);
          }
          return statement;
        },
        resultSet -> {
          updateDigest(digest, resultSet.getLong("id"));
          updateDigest(digest, resultSet.getLong("tm_text_unit_id"));
          updateDigest(digest, resultSet.getLong("locale_id"));
          updateNullableLongDigest(digest, resultSet, "tm_text_unit_variant_id");
          updateNullableFixedWidthTextDigest(digest, resultSet, "current_target_digest");
          updateNullableFixedWidthTextDigest(digest, resultSet, "stored_target_hash_digest");
          updateNullableFixedWidthTextDigest(digest, resultSet, "source_text_digest");
          updateDigest(digest, resultSet.getLong("asset_id"));
          updateDigest(digest, resultSet.getLong("repository_id"));
          updateDigest(digest, resultSet.getLong("source_locale_id"));
          rowCount[0]++;
        });
    return new CandidateStateFingerprint(rowCount[0], HexFormat.of().formatHex(digest.digest()));
  }

  @Transactional(readOnly = true)
  public List<CandidateMemberRow> findCandidateMembers(
      long afterCurrentPointerId,
      long highWaterCurrentPointerId,
      Long reviewProjectId,
      Long highWaterReviewProjectTextUnitId,
      int fetchLimit) {
    String sql;
    if (hsql) {
      sql =
          reviewProjectId == null ? HSQL_CANDIDATE_MEMBERS_SQL : HSQL_SCOPED_CANDIDATE_MEMBERS_SQL;
    } else {
      sql =
          reviewProjectId == null
              ? MYSQL_CANDIDATE_MEMBERS_SQL
              : MYSQL_SCOPED_CANDIDATE_MEMBERS_SQL;
    }

    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("afterCurrentPointerId", afterCurrentPointerId)
            .addValue("highWaterCurrentPointerId", highWaterCurrentPointerId)
            .addValue("fetchLimit", fetchLimit)
            .addValue("evidencePreviewCodePointLimit", PREVIEW_CODE_POINT_LIMIT);
    if (reviewProjectId != null) {
      parameters
          .addValue("reviewProjectId", reviewProjectId)
          .addValue("highWaterReviewProjectTextUnitId", highWaterReviewProjectTextUnitId);
    }
    return jdbcTemplate.query(
        sql, parameters, (resultSet, rowNumber) -> mapCandidate(resultSet, hsql));
  }

  @Transactional(readOnly = true)
  public List<ReviewProjectEvidenceRow> findReviewProjectEvidence(
      long reviewProjectId, long highWaterReviewProjectTextUnitId, Collection<Long> tmTextUnitIds) {
    if (tmTextUnitIds.isEmpty()) {
      return List.of();
    }
    String sql = hsql ? HSQL_REVIEW_PROJECT_EVIDENCE_SQL : MYSQL_REVIEW_PROJECT_EVIDENCE_SQL;
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("reviewProjectId", reviewProjectId)
            .addValue("highWaterReviewProjectTextUnitId", highWaterReviewProjectTextUnitId)
            .addValue("tmTextUnitIds", tmTextUnitIds)
            .addValue("evidencePreviewCodePointLimit", PREVIEW_CODE_POINT_LIMIT)
            .addValue("evidencePerTextUnitLimit", MAX_REVIEW_PROJECT_EVIDENCE_PER_TEXT_UNIT);
    List<ReviewProjectEvidenceRow> rows =
        jdbcTemplate.query(
            sql, parameters, (resultSet, rowNumber) -> mapReviewProjectEvidence(resultSet, hsql));
    if (!hsql) {
      return rows;
    }

    Map<Long, Long> totals = new HashMap<>();
    rows.forEach(row -> totals.merge(row.tmTextUnitId(), 1L, Long::sum));
    Map<Long, Integer> returned = new HashMap<>();
    List<ReviewProjectEvidenceRow> bounded = new ArrayList<>();
    for (ReviewProjectEvidenceRow row : rows) {
      int count = returned.merge(row.tmTextUnitId(), 1, Integer::sum);
      if (count <= MAX_REVIEW_PROJECT_EVIDENCE_PER_TEXT_UNIT) {
        bounded.add(withEvidenceTotalCount(row, totals.get(row.tmTextUnitId())));
      }
    }
    return bounded;
  }

  private static String buildMysqlCandidateMembersSql(boolean scoped) {
    String eligibleCurrentDistinct = scoped ? "DISTINCT STRAIGHT_JOIN" : "DISTINCT";
    String eligibleCurrentFrom =
        scoped
            ? """
              FROM review_project scope_project
              JOIN review_project_text_unit scope_text_unit
                  ON scope_text_unit.review_project_id = scope_project.id
              JOIN tm_text_unit_current_variant current_pointer
                  ON current_pointer.tm_text_unit_id = scope_text_unit.tm_text_unit_id
                  AND current_pointer.locale_id = scope_project.locale_id
              """
            : "FROM tm_text_unit_current_variant current_pointer";
    String eligibleCurrentScope =
        scoped
            ? """
              AND scope_project.id = :reviewProjectId
              AND scope_text_unit.id <= :highWaterReviewProjectTextUnitId
              """
            : "";
    return MYSQL_MEMBER_SELECT.formatted(
        eligibleCurrentDistinct,
        eligibleCurrentFrom,
        eligibleCurrentScope,
        "",
        textProjection(false, "v1.content", "current_target"),
        textProjection(false, "tu1.name", "string_id"),
        textProjection(false, "tu1.content", "source_text"),
        textProjection(false, "tu1.comment", "source_comment"),
        "`user`",
        "CAST(v2.content AS BINARY) = CAST(v1.content AS BINARY)",
        "NOT (CAST(tu2.content AS BINARY) <=> CAST(tu1.content AS BINARY))");
  }

  private static String buildHsqlCandidateMembersSql(boolean scoped) {
    String scopeJoin =
        scoped
            ? """
              JOIN review_project scope_project
                  ON scope_project.id = :reviewProjectId
                  AND scope_project.locale_id = cv1.locale_id
              """
            : "";
    String outerScope =
        scoped
            ? """
              AND EXISTS (
                  SELECT 1
                  FROM review_project_text_unit scope_text_unit1
                  WHERE scope_text_unit1.review_project_id = :reviewProjectId
                      AND scope_text_unit1.tm_text_unit_id = cv1.tm_text_unit_id
                      AND scope_text_unit1.id <= :highWaterReviewProjectTextUnitId
              )
              """
            : "";
    String peerScope =
        scoped
            ? """
              AND EXISTS (
                  SELECT 1
                  FROM review_project_text_unit scope_text_unit2
                  WHERE scope_text_unit2.review_project_id = :reviewProjectId
                      AND scope_text_unit2.tm_text_unit_id = cv2.tm_text_unit_id
                      AND scope_text_unit2.id <= :highWaterReviewProjectTextUnitId
              )
              """
            : "";
    return HSQL_MEMBER_SELECT.formatted(
        textProjection(true, "v1.content", "current_target"),
        textProjection(true, "tu1.name", "string_id"),
        textProjection(true, "tu1.content", "source_text"),
        textProjection(true, "tu1.comment", "source_comment"),
        scopeJoin,
        outerScope,
        peerScope);
  }

  private static String buildCandidateStateFingerprintSql(boolean hsql, boolean scoped) {
    String currentTargetDigest =
        hsql ? "current_variant.content_md5" : "LOWER(SHA2(current_variant.content, 256))";
    String storedTargetHashDigest =
        hsql ? "current_variant.content_md5" : "LOWER(SHA2(current_variant.content_md5, 256))";
    String sourceTextDigest =
        hsql ? "source_text_unit.content_md5" : "LOWER(SHA2(source_text_unit.content, 256))";
    return """
        SELECT current_pointer.id,
               current_pointer.tm_text_unit_id,
               current_pointer.locale_id,
               current_pointer.tm_text_unit_variant_id,
               %s AS current_target_digest,
               %s AS stored_target_hash_digest,
               %s AS source_text_digest,
               source_text_unit.asset_id,
               source_asset.repository_id,
               source_repository.source_locale_id
        FROM tm_text_unit_current_variant current_pointer
        JOIN tm_text_unit_variant current_variant
            ON current_variant.id = current_pointer.tm_text_unit_variant_id
            AND current_variant.tm_text_unit_id = current_pointer.tm_text_unit_id
            AND current_variant.locale_id = current_pointer.locale_id
        JOIN tm_text_unit source_text_unit
            ON source_text_unit.id = current_pointer.tm_text_unit_id
        JOIN asset source_asset
            ON source_asset.id = source_text_unit.asset_id
            AND source_asset.deleted = 0
        JOIN repository source_repository
            ON source_repository.id = source_asset.repository_id
            AND source_repository.deleted = 0
        JOIN repository_locale configured_locale
            ON configured_locale.repository_id = source_repository.id
            AND configured_locale.locale_id = current_pointer.locale_id
        WHERE current_pointer.id <= ?
            AND current_pointer.locale_id <> source_repository.source_locale_id
            AND current_variant.content IS NOT NULL
            %s
        ORDER BY current_pointer.id
        """
        .formatted(
            currentTargetDigest,
            storedTargetHashDigest,
            sourceTextDigest,
            candidateScopePredicate(scoped));
  }

  private static String buildMysqlScopedCandidateStateFingerprintSql() {
    return mysqlScopedTextUnitsCte("?", "?")
        + """
        SELECT STRAIGHT_JOIN
               current_pointer.id,
               current_pointer.tm_text_unit_id,
               current_pointer.locale_id,
               current_pointer.tm_text_unit_variant_id,
               LOWER(SHA2(current_variant.content, 256)) AS current_target_digest,
               LOWER(SHA2(current_variant.content_md5, 256)) AS stored_target_hash_digest,
               LOWER(SHA2(source_text_unit.content, 256)) AS source_text_digest,
               source_text_unit.asset_id,
               source_asset.repository_id,
               source_repository.source_locale_id
        FROM scoped_text_units scope
        JOIN tm_text_unit_current_variant current_pointer
            ON current_pointer.tm_text_unit_id = scope.tm_text_unit_id
            AND current_pointer.locale_id = scope.locale_id
        JOIN tm_text_unit_variant current_variant
            ON current_variant.id = current_pointer.tm_text_unit_variant_id
            AND current_variant.tm_text_unit_id = current_pointer.tm_text_unit_id
            AND current_variant.locale_id = current_pointer.locale_id
        JOIN tm_text_unit source_text_unit
            ON source_text_unit.id = current_pointer.tm_text_unit_id
        JOIN asset source_asset
            ON source_asset.id = source_text_unit.asset_id
            AND source_asset.deleted = 0
        JOIN repository source_repository
            ON source_repository.id = source_asset.repository_id
            AND source_repository.deleted = 0
        JOIN repository_locale configured_locale
            ON configured_locale.repository_id = source_repository.id
            AND configured_locale.locale_id = current_pointer.locale_id
        WHERE current_pointer.id <= ?
            AND current_pointer.locale_id <> source_repository.source_locale_id
            AND current_variant.content IS NOT NULL
        ORDER BY current_pointer.id
        """;
  }

  private static String buildMysqlScopedInvalidCurrentTargetHashCountSql() {
    return mysqlScopedTextUnitsCte(":reviewProjectId", ":highWaterReviewProjectTextUnitId")
        + """
        SELECT STRAIGHT_JOIN COUNT(DISTINCT current_pointer.id)
        FROM scoped_text_units scope
        JOIN tm_text_unit_current_variant current_pointer
            ON current_pointer.tm_text_unit_id = scope.tm_text_unit_id
            AND current_pointer.locale_id = scope.locale_id
        JOIN tm_text_unit_variant current_variant
            ON current_variant.id = current_pointer.tm_text_unit_variant_id
            AND current_variant.tm_text_unit_id = current_pointer.tm_text_unit_id
            AND current_variant.locale_id = current_pointer.locale_id
        JOIN tm_text_unit source_text_unit
            ON source_text_unit.id = current_pointer.tm_text_unit_id
        JOIN asset source_asset
            ON source_asset.id = source_text_unit.asset_id
            AND source_asset.deleted = 0
        JOIN repository source_repository
            ON source_repository.id = source_asset.repository_id
            AND source_repository.deleted = 0
        JOIN repository_locale configured_locale
            ON configured_locale.repository_id = source_repository.id
            AND configured_locale.locale_id = current_pointer.locale_id
        WHERE current_pointer.id <= :highWaterCurrentPointerId
            AND current_pointer.locale_id <> source_repository.source_locale_id
            AND current_variant.content IS NOT NULL
            AND (
                current_variant.content_md5 IS NULL
                OR CAST(current_variant.content_md5 AS BINARY) <>
                    CAST(MD5(current_variant.content) AS BINARY)
            )
        """;
  }

  private static String buildMysqlScopedCurrentPointerAuditWatermarkSql() {
    return mysqlScopedTextUnitsCte(":reviewProjectId", ":highWaterReviewProjectTextUnitId")
        + """
        SELECT STRAIGHT_JOIN
               COUNT(*) AS audit_row_count,
               COALESCE(MAX(audit_row.rev), 0) AS max_audit_revision
        FROM tm_text_unit_current_variant_aud audit_row
        JOIN scoped_text_units scope
            ON scope.locale_id = audit_row.locale_id
            AND scope.tm_text_unit_id = audit_row.tm_text_unit_id
        WHERE audit_row.id <= :highWaterCurrentPointerId
        """;
  }

  private static String mysqlScopedTextUnitsCte(
      String reviewProjectIdParameter, String highWaterReviewProjectTextUnitIdParameter) {
    return """
        WITH scoped_text_units AS (
            SELECT DISTINCT
                   scope_project.locale_id,
                   scope_text_unit.tm_text_unit_id
            FROM review_project scope_project
            JOIN review_project_text_unit scope_text_unit
                ON scope_text_unit.review_project_id = scope_project.id
            WHERE scope_project.id = %s
                AND scope_text_unit.id <= %s
        )
        """
        .formatted(reviewProjectIdParameter, highWaterReviewProjectTextUnitIdParameter);
  }

  private static String buildReviewProjectEvidenceSql(boolean hsql) {
    String userTable = hsql ? "\"USER\"" : "`user`";
    String rankingProjection =
        hsql
            ? """
              0 AS evidence_rank,
              0 AS evidence_total_count
              """
            : """
              ROW_NUMBER() OVER (
                  PARTITION BY rptu.tm_text_unit_id
                  ORDER BY rptu.id, decision_row.id
              ) AS evidence_rank,
              COUNT(*) OVER (
                  PARTITION BY rptu.tm_text_unit_id
              ) AS evidence_total_count
              """;
    return """
        SELECT bounded_evidence.*
        FROM (
            SELECT
                rptu.tm_text_unit_id,
                rptu.id AS review_project_text_unit_id,
                rptu.created_date AS review_project_text_unit_created_at,
                rptu.tm_text_unit_variant_id AS baseline_variant_id,
                %s,
                baseline.status AS baseline_status,
                baseline.included_in_localized_file AS baseline_included_in_localized_file,
                decision_row.id AS decision_id,
                decision_row.decision_state,
                decision_row.version AS decision_version,
                decision_row.created_date AS decision_created_at,
                decision_row.last_modified_date AS decision_last_modified_at,
                decision_row.reviewed_variant_id,
                %s,
                decision_row.variant_id AS decision_variant_id,
                %s,
                COALESCE(
                    decision_row.last_modified_by_user_id,
                    decision_row.created_by_user_id
                ) AS effective_reviewer_id,
                effective_reviewer.username AS effective_reviewer_username,
                effective_reviewer.common_name AS effective_reviewer_common_name,
                project_row.id AS review_project_id,
                project_row.type AS review_project_type,
                project_row.terminology_phase,
                project_row.status AS review_project_status,
                project_row.review_project_request_id,
                request_row.name AS review_project_name,
                %s
            FROM review_project_text_unit rptu
            JOIN review_project project_row
                ON project_row.id = rptu.review_project_id
            LEFT JOIN review_project_request request_row
                ON request_row.id = project_row.review_project_request_id
            LEFT JOIN tm_text_unit_variant baseline
                ON baseline.id = rptu.tm_text_unit_variant_id
            LEFT JOIN review_project_text_unit_decision decision_row
                ON decision_row.review_project_text_unit_id = rptu.id
            LEFT JOIN tm_text_unit_variant reviewed_variant
                ON reviewed_variant.id = decision_row.reviewed_variant_id
            LEFT JOIN tm_text_unit_variant decision_variant
                ON decision_variant.id = decision_row.variant_id
            LEFT JOIN %s effective_reviewer
                ON effective_reviewer.id = COALESCE(
                    decision_row.last_modified_by_user_id,
                    decision_row.created_by_user_id
                )
            WHERE rptu.review_project_id = :reviewProjectId
                AND rptu.id <= :highWaterReviewProjectTextUnitId
                AND rptu.tm_text_unit_id IN (:tmTextUnitIds)
        ) bounded_evidence
        WHERE bounded_evidence.evidence_rank <= :evidencePerTextUnitLimit
        ORDER BY bounded_evidence.tm_text_unit_id,
                 bounded_evidence.review_project_text_unit_id,
                 bounded_evidence.decision_id
        """
        .formatted(
            textProjection(hsql, "baseline.content", "baseline_target"),
            textProjection(hsql, "reviewed_variant.content", "reviewed_target"),
            textProjection(hsql, "decision_variant.content", "decision_target"),
            rankingProjection.stripTrailing(),
            userTable);
  }

  private static String textProjection(boolean hsql, String expression, String alias) {
    if (hsql) {
      // HSQL is test-only here. MySQL production must never materialize the full LONGTEXT value.
      return """
          %s AS %s_preview,
          NULL AS %s_sha256,
          CHAR_LENGTH(%s) AS %s_code_point_length
          """
          .formatted(expression, alias, alias, expression, alias)
          .stripTrailing();
    }
    return """
        LEFT(%s, :evidencePreviewCodePointLimit) AS %s_preview,
        LOWER(SHA2(%s, 256)) AS %s_sha256,
        CHAR_LENGTH(%s) AS %s_code_point_length
        """
        .formatted(expression, alias, expression, alias, expression, alias)
        .stripTrailing();
  }

  private static CandidateMemberRow mapCandidate(ResultSet resultSet, boolean hsql)
      throws SQLException {
    return new CandidateMemberRow(
        resultSet.getLong("current_pointer_id"),
        nullableInstant(resultSet, "current_pointer_last_modified_at"),
        resultSet.getLong("current_variant_id"),
        mapTextSummary(resultSet, "current_target", hsql),
        resultSet.getString("current_status"),
        nullableBoolean(resultSet, "included_in_localized_file"),
        nullableInstant(resultSet, "current_variant_created_at"),
        nullableLong(resultSet, "current_variant_created_by_user_id"),
        resultSet.getString("current_variant_created_by_username"),
        resultSet.getString("current_variant_created_by_common_name"),
        resultSet.getLong("target_locale_id"),
        resultSet.getString("target_locale"),
        resultSet.getLong("tm_text_unit_id"),
        mapTextSummary(resultSet, "string_id", hsql),
        mapTextSummary(resultSet, "source_text", hsql),
        mapTextSummary(resultSet, "source_comment", hsql),
        resultSet.getLong("asset_id"),
        resultSet.getString("asset_path"),
        resultSet.getLong("repository_id"),
        resultSet.getString("repository_name"),
        resultSet.getLong("source_locale_id"),
        resultSet.getString("source_locale"));
  }

  private static ReviewProjectEvidenceRow mapReviewProjectEvidence(
      ResultSet resultSet, boolean hsql) throws SQLException {
    return new ReviewProjectEvidenceRow(
        resultSet.getLong("tm_text_unit_id"),
        resultSet.getLong("review_project_text_unit_id"),
        nullableInstant(resultSet, "review_project_text_unit_created_at"),
        nullableLong(resultSet, "baseline_variant_id"),
        mapTextSummary(resultSet, "baseline_target", hsql),
        resultSet.getString("baseline_status"),
        nullableBoolean(resultSet, "baseline_included_in_localized_file"),
        nullableLong(resultSet, "decision_id"),
        resultSet.getString("decision_state"),
        nullableLong(resultSet, "decision_version"),
        nullableInstant(resultSet, "decision_created_at"),
        nullableInstant(resultSet, "decision_last_modified_at"),
        nullableLong(resultSet, "reviewed_variant_id"),
        mapTextSummary(resultSet, "reviewed_target", hsql),
        nullableLong(resultSet, "decision_variant_id"),
        mapTextSummary(resultSet, "decision_target", hsql),
        nullableLong(resultSet, "effective_reviewer_id"),
        resultSet.getString("effective_reviewer_username"),
        resultSet.getString("effective_reviewer_common_name"),
        resultSet.getLong("review_project_id"),
        resultSet.getString("review_project_type"),
        resultSet.getString("terminology_phase"),
        resultSet.getString("review_project_status"),
        nullableLong(resultSet, "review_project_request_id"),
        resultSet.getString("review_project_name"),
        resultSet.getLong("evidence_total_count"));
  }

  private static ReviewProjectEvidenceRow withEvidenceTotalCount(
      ReviewProjectEvidenceRow row, long evidenceTotalCount) {
    return new ReviewProjectEvidenceRow(
        row.tmTextUnitId(),
        row.reviewProjectTextUnitId(),
        row.reviewProjectTextUnitCreatedAt(),
        row.baselineVariantId(),
        row.baselineTarget(),
        row.baselineStatus(),
        row.baselineIncludedInLocalizedFile(),
        row.decisionId(),
        row.decisionState(),
        row.decisionVersion(),
        row.decisionCreatedAt(),
        row.decisionLastModifiedAt(),
        row.reviewedVariantId(),
        row.reviewedTarget(),
        row.decisionVariantId(),
        row.decisionTarget(),
        row.effectiveReviewerId(),
        row.effectiveReviewerUsername(),
        row.effectiveReviewerCommonName(),
        row.reviewProjectId(),
        row.reviewProjectType(),
        row.terminologyPhase(),
        row.reviewProjectStatus(),
        row.reviewProjectRequestId(),
        row.reviewProjectName(),
        evidenceTotalCount);
  }

  private static TextSummary mapTextSummary(ResultSet resultSet, String prefix, boolean hsql)
      throws SQLException {
    String preview = resultSet.getString(prefix + "_preview");
    long length = resultSet.getLong(prefix + "_code_point_length");
    if (resultSet.wasNull()) {
      return null;
    }
    String sha256 = resultSet.getString(prefix + "_sha256");
    if (hsql) {
      sha256 = DigestUtils.sha256Hex(preview);
      preview = boundedPrefix(preview);
    }
    return new TextSummary(preview, sha256, length);
  }

  private static String boundedPrefix(String value) {
    int codePoints = value.codePointCount(0, value.length());
    int end = value.offsetByCodePoints(0, Math.min(codePoints, PREVIEW_CODE_POINT_LIMIT));
    return value.substring(0, end);
  }

  private static String auditScopePredicate(Long reviewProjectId) {
    if (reviewProjectId == null) {
      return "";
    }
    return """
        AND EXISTS (
            SELECT 1
            FROM review_project audit_project
            WHERE audit_project.id = :reviewProjectId
                AND audit_project.locale_id = audit_row.locale_id
                AND EXISTS (
                    SELECT 1
                    FROM review_project_text_unit audit_text_unit
                    WHERE audit_text_unit.review_project_id = :reviewProjectId
                        AND audit_text_unit.tm_text_unit_id = audit_row.tm_text_unit_id
                        AND audit_text_unit.id <= :highWaterReviewProjectTextUnitId
                )
        )
        """;
  }

  private static String candidateScopePredicate(boolean scoped) {
    if (!scoped) {
      return "";
    }
    return """
        AND EXISTS (
            SELECT 1
            FROM review_project state_project
            WHERE state_project.id = ?
                AND state_project.locale_id = current_pointer.locale_id
                AND EXISTS (
                    SELECT 1
                    FROM review_project_text_unit state_text_unit
                    WHERE state_text_unit.review_project_id = ?
                        AND state_text_unit.tm_text_unit_id = current_pointer.tm_text_unit_id
                        AND state_text_unit.id <= ?
                )
        )
        """;
  }

  private static MapSqlParameterSource scopeParameters(
      long highWaterCurrentPointerId, Long reviewProjectId, Long highWaterReviewProjectTextUnitId) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource("highWaterCurrentPointerId", highWaterCurrentPointerId);
    if (reviewProjectId != null) {
      parameters
          .addValue("reviewProjectId", reviewProjectId)
          .addValue("highWaterReviewProjectTextUnitId", highWaterReviewProjectTextUnitId);
    }
    return parameters;
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void updateDigest(MessageDigest digest, long value) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
  }

  private static void updateNullableLongDigest(
      MessageDigest digest, ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    boolean nullValue = resultSet.wasNull();
    digest.update((byte) (nullValue ? 0 : 1));
    if (!nullValue) {
      updateDigest(digest, value);
    }
  }

  private static void updateNullableFixedWidthTextDigest(
      MessageDigest digest, ResultSet resultSet, String column) throws SQLException {
    String value = resultSet.getString(column);
    digest.update((byte) (value == null ? 0 : 1));
    if (value == null) {
      return;
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 64) {
      throw new IllegalStateException(column + " exceeded the fixed-width digest bound");
    }
    updateDigest(digest, bytes.length);
    digest.update(bytes);
  }

  private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private static Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
    boolean value = resultSet.getBoolean(column);
    return resultSet.wasNull() ? null : value;
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  public record AuditWatermark(long rowCount, long maxRevision) {}

  public record CandidateStateFingerprint(long rowCount, String sha256) {}

  public record TextSummary(String preview, String sha256, long codePointLength) {}

  public record CandidateMemberRow(
      long currentPointerId,
      Instant currentPointerLastModifiedAt,
      long currentVariantId,
      TextSummary currentTarget,
      String currentStatus,
      Boolean includedInLocalizedFile,
      Instant currentVariantCreatedAt,
      Long currentVariantCreatedByUserId,
      String currentVariantCreatedByUsername,
      String currentVariantCreatedByCommonName,
      long targetLocaleId,
      String targetLocale,
      long tmTextUnitId,
      TextSummary stringId,
      TextSummary sourceText,
      TextSummary sourceComment,
      long assetId,
      String assetPath,
      long repositoryId,
      String repositoryName,
      long sourceLocaleId,
      String sourceLocale) {}

  public record ReviewProjectEvidenceRow(
      long tmTextUnitId,
      long reviewProjectTextUnitId,
      Instant reviewProjectTextUnitCreatedAt,
      Long baselineVariantId,
      TextSummary baselineTarget,
      String baselineStatus,
      Boolean baselineIncludedInLocalizedFile,
      Long decisionId,
      String decisionState,
      Long decisionVersion,
      Instant decisionCreatedAt,
      Instant decisionLastModifiedAt,
      Long reviewedVariantId,
      TextSummary reviewedTarget,
      Long decisionVariantId,
      TextSummary decisionTarget,
      Long effectiveReviewerId,
      String effectiveReviewerUsername,
      String effectiveReviewerCommonName,
      long reviewProjectId,
      String reviewProjectType,
      String terminologyPhase,
      String reviewProjectStatus,
      Long reviewProjectRequestId,
      String reviewProjectName,
      long evidenceTotalCount) {}
}
