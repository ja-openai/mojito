package com.box.l10n.mojito.service.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReviewProjectDecisionIntegrityAuditRepository {

  static final long PREDECESSOR_LOOKBACK_SECONDS = 30;

  static final String MYSQL_DECISIONS_SQL =
      """
      WITH bounded_decisions AS (
          SELECT
              decision.id AS decision_id,
              decision.review_project_text_unit_id,
              decision.variant_id AS decision_variant_id,
              decision.version AS decision_version,
              decision.last_modified_date AS decided_at,
              COALESCE(
                  decision.last_modified_by_user_id,
                  decision.created_by_user_id
              ) AS effective_reviewer_id,
              review_text_unit.review_project_id,
              review_text_unit.tm_text_unit_id,
              LAG(decision.id) OVER (
                  PARTITION BY
                      review_text_unit.review_project_id,
                      COALESCE(
                          decision.last_modified_by_user_id,
                          decision.created_by_user_id
                      )
                  ORDER BY decision.last_modified_date, decision.id
              ) AS previous_decision_id
          FROM review_project_text_unit_decision decision
          JOIN review_project_text_unit review_text_unit
              ON review_text_unit.id = decision.review_project_text_unit_id
          WHERE decision.decision_state = 'DECIDED'
              AND decision.last_modified_date >= ?
              AND decision.last_modified_date < ?
      )
      SELECT
          bounded.decision_id,
          bounded.review_project_text_unit_id,
          bounded.review_project_id,
          review_project.review_project_request_id,
          review_request.name AS review_project_name,
          review_project.type AS review_project_type,
          review_project.terminology_phase,
          review_project.locale_id AS project_locale_id,
          project_locale.bcp47_tag AS project_locale,
          bounded.effective_reviewer_id,
          reviewer.username AS effective_reviewer_username,
          reviewer.common_name AS effective_reviewer_common_name,
          bounded.decided_at,
          bounded.decision_version,
          bounded.decision_variant_id,
          decision_variant.locale_id AS decision_variant_locale_id,
          bounded.tm_text_unit_id,
          source_text_unit.name AS tm_text_unit_name,
          source_text_unit.content AS source_text,
          decision_variant.content AS decision_target_text,
          current_pointer.tm_text_unit_variant_id AS current_variant_id,
          current_variant.locale_id AS current_variant_locale_id,
          current_variant.content AS current_target_text,
          source_text_unit.asset_id,
          asset.path AS asset_path,
          asset.repository_id,
          source_repository.name AS repository_name,
          source_repository.source_locale_id,
          source_locale.bcp47_tag AS source_locale,
          bounded.previous_decision_id,
          previous_decision.review_project_text_unit_id
              AS previous_review_project_text_unit_id,
          previous_review_text_unit.tm_text_unit_id AS previous_tm_text_unit_id,
          previous_decision.last_modified_date AS previous_decided_at,
          previous_source_text_unit.content AS previous_source_text,
          previous_variant.content AS previous_decision_target_text
      FROM bounded_decisions bounded
      JOIN review_project
          ON review_project.id = bounded.review_project_id
      LEFT JOIN review_project_request review_request
          ON review_request.id = review_project.review_project_request_id
      JOIN locale project_locale
          ON project_locale.id = review_project.locale_id
      LEFT JOIN `user` reviewer
          ON reviewer.id = bounded.effective_reviewer_id
      LEFT JOIN tm_text_unit source_text_unit
          ON source_text_unit.id = bounded.tm_text_unit_id
      LEFT JOIN asset
          ON asset.id = source_text_unit.asset_id
      LEFT JOIN repository source_repository
          ON source_repository.id = asset.repository_id
      LEFT JOIN locale source_locale
          ON source_locale.id = source_repository.source_locale_id
      LEFT JOIN tm_text_unit_variant decision_variant
          ON decision_variant.id = bounded.decision_variant_id
      LEFT JOIN tm_text_unit_current_variant current_pointer
          ON current_pointer.tm_text_unit_id = bounded.tm_text_unit_id
          AND current_pointer.locale_id = review_project.locale_id
      LEFT JOIN tm_text_unit_variant current_variant
          ON current_variant.id = current_pointer.tm_text_unit_variant_id
      LEFT JOIN review_project_text_unit_decision previous_decision
          ON previous_decision.id = bounded.previous_decision_id
      LEFT JOIN review_project_text_unit previous_review_text_unit
          ON previous_review_text_unit.id = previous_decision.review_project_text_unit_id
      LEFT JOIN tm_text_unit previous_source_text_unit
          ON previous_source_text_unit.id = previous_review_text_unit.tm_text_unit_id
      LEFT JOIN tm_text_unit_variant previous_variant
          ON previous_variant.id = previous_decision.variant_id
      WHERE bounded.decided_at >= ?
          AND bounded.decided_at < ?
      ORDER BY bounded.decided_at, bounded.decision_id
      """;

  private static final String HSQL_ORDERED_DECISIONS_SQL =
      """
      SELECT
          decision.id AS decision_id,
          decision.review_project_text_unit_id,
          review_text_unit.review_project_id,
          review_project.review_project_request_id,
          review_request.name AS review_project_name,
          review_project.type AS review_project_type,
          review_project.terminology_phase,
          review_project.locale_id AS project_locale_id,
          project_locale.bcp47_tag AS project_locale,
          COALESCE(
              decision.last_modified_by_user_id,
              decision.created_by_user_id
          ) AS effective_reviewer_id,
          reviewer.username AS effective_reviewer_username,
          reviewer.common_name AS effective_reviewer_common_name,
          decision.last_modified_date AS decided_at,
          decision.version AS decision_version,
          decision.variant_id AS decision_variant_id,
          decision_variant.locale_id AS decision_variant_locale_id,
          review_text_unit.tm_text_unit_id,
          source_text_unit.name AS tm_text_unit_name,
          source_text_unit.content AS source_text,
          decision_variant.content AS decision_target_text,
          current_pointer.tm_text_unit_variant_id AS current_variant_id,
          current_variant.locale_id AS current_variant_locale_id,
          current_variant.content AS current_target_text,
          source_text_unit.asset_id,
          asset.path AS asset_path,
          asset.repository_id,
          source_repository.name AS repository_name,
          source_repository.source_locale_id,
          source_locale.bcp47_tag AS source_locale
      FROM review_project_text_unit_decision decision
      JOIN review_project_text_unit review_text_unit
          ON review_text_unit.id = decision.review_project_text_unit_id
      JOIN review_project
          ON review_project.id = review_text_unit.review_project_id
      LEFT JOIN review_project_request review_request
          ON review_request.id = review_project.review_project_request_id
      JOIN locale project_locale
          ON project_locale.id = review_project.locale_id
      LEFT JOIN "USER" reviewer
          ON reviewer.id = COALESCE(
              decision.last_modified_by_user_id,
              decision.created_by_user_id
          )
      LEFT JOIN tm_text_unit source_text_unit
          ON source_text_unit.id = review_text_unit.tm_text_unit_id
      LEFT JOIN asset
          ON asset.id = source_text_unit.asset_id
      LEFT JOIN repository source_repository
          ON source_repository.id = asset.repository_id
      LEFT JOIN locale source_locale
          ON source_locale.id = source_repository.source_locale_id
      LEFT JOIN tm_text_unit_variant decision_variant
          ON decision_variant.id = decision.variant_id
      LEFT JOIN tm_text_unit_current_variant current_pointer
          ON current_pointer.tm_text_unit_id = review_text_unit.tm_text_unit_id
          AND current_pointer.locale_id = review_project.locale_id
      LEFT JOIN tm_text_unit_variant current_variant
          ON current_variant.id = current_pointer.tm_text_unit_variant_id
      WHERE decision.decision_state = 'DECIDED'
          AND decision.last_modified_date >= ?
          AND decision.last_modified_date < ?
      ORDER BY decision.last_modified_date, decision.id
      """;

  private final JdbcTemplate jdbcTemplate;
  private final boolean hsql;

  public ReviewProjectDecisionIntegrityAuditRepository(
      JdbcTemplate jdbcTemplate, @Value("${spring.datasource.url:}") String datasourceUrl) {
    this.jdbcTemplate = jdbcTemplate;
    this.hsql = datasourceUrl != null && datasourceUrl.startsWith("jdbc:hsqldb:");
  }

  @Transactional(readOnly = true)
  public List<DecisionRow> findDecisionRows(Instant fromInclusive, Instant toExclusive) {
    Instant scanFromInclusive = fromInclusive.minusSeconds(PREDECESSOR_LOOKBACK_SECONDS);
    if (hsql) {
      return findDecisionRowsForHsql(scanFromInclusive, fromInclusive, toExclusive);
    }

    return jdbcTemplate.query(
        MYSQL_DECISIONS_SQL,
        (resultSet, rowNumber) -> mapDecisionRow(resultSet),
        Timestamp.from(scanFromInclusive),
        Timestamp.from(toExclusive),
        Timestamp.from(fromInclusive),
        Timestamp.from(toExclusive));
  }

  private List<DecisionRow> findDecisionRowsForHsql(
      Instant scanFromInclusive, Instant fromInclusive, Instant toExclusive) {
    List<DecisionRow> boundedRows =
        jdbcTemplate.query(
            HSQL_ORDERED_DECISIONS_SQL,
            (resultSet, rowNumber) -> mapDecisionRowWithoutPrevious(resultSet),
            Timestamp.from(scanFromInclusive),
            Timestamp.from(toExclusive));

    Map<ProjectReviewer, DecisionRow> previousByProjectAndReviewer = new HashMap<>();
    List<DecisionRow> result = new ArrayList<>();
    for (DecisionRow current : boundedRows) {
      ProjectReviewer partition =
          new ProjectReviewer(current.reviewProjectId(), current.effectiveReviewerId());
      DecisionRow previous = previousByProjectAndReviewer.put(partition, current);
      if (!current.decidedAt().isBefore(fromInclusive)) {
        result.add(withPrevious(current, previous));
      }
    }
    return result;
  }

  private static DecisionRow mapDecisionRow(ResultSet resultSet) throws SQLException {
    return new DecisionRow(
        resultSet.getLong("decision_id"),
        resultSet.getLong("review_project_text_unit_id"),
        resultSet.getLong("review_project_id"),
        nullableLong(resultSet, "review_project_request_id"),
        resultSet.getString("review_project_name"),
        resultSet.getString("review_project_type"),
        resultSet.getString("terminology_phase"),
        resultSet.getLong("project_locale_id"),
        resultSet.getString("project_locale"),
        nullableLong(resultSet, "effective_reviewer_id"),
        resultSet.getString("effective_reviewer_username"),
        resultSet.getString("effective_reviewer_common_name"),
        resultSet.getTimestamp("decided_at").toInstant(),
        nullableLong(resultSet, "decision_version"),
        nullableLong(resultSet, "decision_variant_id"),
        nullableLong(resultSet, "decision_variant_locale_id"),
        resultSet.getLong("tm_text_unit_id"),
        resultSet.getString("tm_text_unit_name"),
        resultSet.getString("source_text"),
        resultSet.getString("decision_target_text"),
        nullableLong(resultSet, "current_variant_id"),
        nullableLong(resultSet, "current_variant_locale_id"),
        resultSet.getString("current_target_text"),
        nullableLong(resultSet, "asset_id"),
        resultSet.getString("asset_path"),
        nullableLong(resultSet, "repository_id"),
        resultSet.getString("repository_name"),
        nullableLong(resultSet, "source_locale_id"),
        resultSet.getString("source_locale"),
        nullableLong(resultSet, "previous_decision_id"),
        nullableLong(resultSet, "previous_review_project_text_unit_id"),
        nullableLong(resultSet, "previous_tm_text_unit_id"),
        nullableInstant(resultSet, "previous_decided_at"),
        resultSet.getString("previous_source_text"),
        resultSet.getString("previous_decision_target_text"));
  }

  private static DecisionRow mapDecisionRowWithoutPrevious(ResultSet resultSet)
      throws SQLException {
    return new DecisionRow(
        resultSet.getLong("decision_id"),
        resultSet.getLong("review_project_text_unit_id"),
        resultSet.getLong("review_project_id"),
        nullableLong(resultSet, "review_project_request_id"),
        resultSet.getString("review_project_name"),
        resultSet.getString("review_project_type"),
        resultSet.getString("terminology_phase"),
        resultSet.getLong("project_locale_id"),
        resultSet.getString("project_locale"),
        nullableLong(resultSet, "effective_reviewer_id"),
        resultSet.getString("effective_reviewer_username"),
        resultSet.getString("effective_reviewer_common_name"),
        resultSet.getTimestamp("decided_at").toInstant(),
        nullableLong(resultSet, "decision_version"),
        nullableLong(resultSet, "decision_variant_id"),
        nullableLong(resultSet, "decision_variant_locale_id"),
        resultSet.getLong("tm_text_unit_id"),
        resultSet.getString("tm_text_unit_name"),
        resultSet.getString("source_text"),
        resultSet.getString("decision_target_text"),
        nullableLong(resultSet, "current_variant_id"),
        nullableLong(resultSet, "current_variant_locale_id"),
        resultSet.getString("current_target_text"),
        nullableLong(resultSet, "asset_id"),
        resultSet.getString("asset_path"),
        nullableLong(resultSet, "repository_id"),
        resultSet.getString("repository_name"),
        nullableLong(resultSet, "source_locale_id"),
        resultSet.getString("source_locale"),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static DecisionRow withPrevious(DecisionRow current, DecisionRow previous) {
    if (previous == null) {
      return current;
    }
    return new DecisionRow(
        current.decisionId(),
        current.reviewProjectTextUnitId(),
        current.reviewProjectId(),
        current.reviewProjectRequestId(),
        current.reviewProjectName(),
        current.reviewProjectType(),
        current.terminologyPhase(),
        current.projectLocaleId(),
        current.projectLocale(),
        current.effectiveReviewerId(),
        current.effectiveReviewerUsername(),
        current.effectiveReviewerCommonName(),
        current.decidedAt(),
        current.decisionVersion(),
        current.decisionVariantId(),
        current.decisionVariantLocaleId(),
        current.tmTextUnitId(),
        current.tmTextUnitName(),
        current.sourceText(),
        current.decisionTargetText(),
        current.currentVariantId(),
        current.currentVariantLocaleId(),
        current.currentTargetText(),
        current.assetId(),
        current.assetPath(),
        current.repositoryId(),
        current.repositoryName(),
        current.sourceLocaleId(),
        current.sourceLocale(),
        previous.decisionId(),
        previous.reviewProjectTextUnitId(),
        previous.tmTextUnitId(),
        previous.decidedAt(),
        previous.sourceText(),
        previous.decisionTargetText());
  }

  private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private record ProjectReviewer(Long reviewProjectId, Long effectiveReviewerId) {}

  public record DecisionRow(
      Long decisionId,
      Long reviewProjectTextUnitId,
      Long reviewProjectId,
      Long reviewProjectRequestId,
      String reviewProjectName,
      String reviewProjectType,
      String terminologyPhase,
      Long projectLocaleId,
      String projectLocale,
      Long effectiveReviewerId,
      String effectiveReviewerUsername,
      String effectiveReviewerCommonName,
      Instant decidedAt,
      Long decisionVersion,
      Long decisionVariantId,
      Long decisionVariantLocaleId,
      Long tmTextUnitId,
      String tmTextUnitName,
      String sourceText,
      String decisionTargetText,
      Long currentVariantId,
      Long currentVariantLocaleId,
      String currentTargetText,
      Long assetId,
      String assetPath,
      Long repositoryId,
      String repositoryName,
      Long sourceLocaleId,
      String sourceLocale,
      Long previousDecisionId,
      Long previousReviewProjectTextUnitId,
      Long previousTmTextUnitId,
      Instant previousDecidedAt,
      String previousSourceText,
      String previousDecisionTargetText) {}
}
