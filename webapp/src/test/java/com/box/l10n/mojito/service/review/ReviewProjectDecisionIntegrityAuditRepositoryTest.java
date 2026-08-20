package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditRepository.DecisionRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

public class ReviewProjectDecisionIntegrityAuditRepositoryTest {

  private static final Instant FROM = Instant.parse("2026-08-20T00:00:00Z");
  private static final Instant TO = FROM.plusSeconds(26 * 3600L);

  private EmbeddedDatabase database;
  private JdbcTemplate jdbcTemplate;
  private ReviewProjectDecisionIntegrityAuditRepository repository;

  @Before
  public void setUp() {
    database =
        new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.HSQL)
            .build();
    jdbcTemplate = new JdbcTemplate(database);
    createSchema();
    insertReferenceData();
    repository =
        new ReviewProjectDecisionIntegrityAuditRepository(
            jdbcTemplate, "jdbc:hsqldb:mem:audit-test");
  }

  @After
  public void tearDown() {
    database.shutdown();
  }

  @Test
  public void keepsBoundaryPredecessorAndReturnsEveryInWindowDecision() {
    insertDecision(1, 101, 1001, 5001L, 9L, "First", "o", FROM.minusSeconds(30));
    insertDecision(2, 102, 1002, 5002L, 9L, "Second", "o", FROM);
    insertDecision(3, 103, 1003, null, null, "Term candidate", null, FROM.plusSeconds(1));

    List<DecisionRow> rows = repository.findDecisionRows(FROM, TO);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).decisionId()).isEqualTo(2L);
    assertThat(rows.get(0).previousDecisionId()).isEqualTo(1L);
    assertThat(rows.get(0).previousSourceText()).isEqualTo("First");
    assertThat(rows.get(0).previousDecisionTargetText()).isEqualTo("o");
    assertThat(rows.get(1).decisionId()).isEqualTo(3L);
    assertThat(rows.get(1).effectiveReviewerId()).isNull();
    assertThat(rows.get(1).decisionVariantId()).isNull();
    assertThat(rows.get(1).decisionTargetText()).isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM review_project_text_unit_decision", Long.class))
        .isEqualTo(3L);
  }

  @Test
  public void productionQueryIsBoundedAndUsesDeterministicPartitionedLag() {
    assertThat(ReviewProjectDecisionIntegrityAuditRepository.MYSQL_DECISIONS_SQL)
        .contains("LAG(decision.id) OVER")
        .contains("review_text_unit.review_project_id")
        .contains("COALESCE(")
        .contains("ORDER BY decision.last_modified_date, decision.id")
        .contains("decision.last_modified_date >= ?")
        .contains("decision.last_modified_date < ?")
        .contains("WHERE bounded.decided_at >= ?")
        .contains("bounded.decided_at < ?")
        .contains("LEFT JOIN tm_text_unit_variant decision_variant")
        .doesNotContain("SELECT *");
  }

  private void createSchema() {
    jdbcTemplate.execute(
        "CREATE TABLE review_project_text_unit_decision (id BIGINT PRIMARY KEY, review_project_text_unit_id BIGINT NOT NULL, variant_id BIGINT, version BIGINT, decision_state VARCHAR(16) NOT NULL, last_modified_date TIMESTAMP, created_by_user_id BIGINT, last_modified_by_user_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE review_project_text_unit (id BIGINT PRIMARY KEY, review_project_id BIGINT NOT NULL, tm_text_unit_id BIGINT NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE review_project (id BIGINT PRIMARY KEY, review_project_request_id BIGINT, type VARCHAR(32), terminology_phase VARCHAR(32), locale_id BIGINT NOT NULL)");
    jdbcTemplate.execute(
        "CREATE TABLE review_project_request (id BIGINT PRIMARY KEY, name VARCHAR(255))");
    jdbcTemplate.execute("CREATE TABLE locale (id BIGINT PRIMARY KEY, bcp47_tag VARCHAR(32))");
    jdbcTemplate.execute(
        "CREATE TABLE \"USER\" (id BIGINT PRIMARY KEY, username VARCHAR(255), common_name VARCHAR(255))");
    jdbcTemplate.execute(
        "CREATE TABLE tm_text_unit (id BIGINT PRIMARY KEY, name VARCHAR(255), content VARCHAR(1000), asset_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE tm_text_unit_variant (id BIGINT PRIMARY KEY, locale_id BIGINT, content VARCHAR(1000))");
    jdbcTemplate.execute(
        "CREATE TABLE tm_text_unit_current_variant (tm_text_unit_id BIGINT, locale_id BIGINT, tm_text_unit_variant_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE asset (id BIGINT PRIMARY KEY, path VARCHAR(255), repository_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE repository (id BIGINT PRIMARY KEY, name VARCHAR(255), source_locale_id BIGINT)");
  }

  private void insertReferenceData() {
    jdbcTemplate.update("INSERT INTO locale (id, bcp47_tag) VALUES (1, 'en')");
    jdbcTemplate.update("INSERT INTO locale (id, bcp47_tag) VALUES (2, 'fr-FR')");
    jdbcTemplate.update(
        "INSERT INTO \"USER\" (id, username, common_name) VALUES (9, 'reviewer@example.com', 'Reviewer')");
    jdbcTemplate.update(
        "INSERT INTO review_project_request (id, name) VALUES (80, 'Daily review')");
    jdbcTemplate.update(
        "INSERT INTO review_project (id, review_project_request_id, type, terminology_phase, locale_id) VALUES (70, 80, 'TERM_CANDIDATE', 'PM_RESOLUTION', 2)");
    jdbcTemplate.update(
        "INSERT INTO repository (id, name, source_locale_id) VALUES (60, 'repo', 1)");
    jdbcTemplate.update(
        "INSERT INTO asset (id, path, repository_id) VALUES (50, 'strings.json', 60)");
  }

  private void insertDecision(
      long decisionId,
      long reviewProjectTextUnitId,
      long tmTextUnitId,
      Long variantId,
      Long reviewerId,
      String source,
      String target,
      Instant decidedAt) {
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit (id, name, content, asset_id) VALUES (?, ?, ?, 50)",
        tmTextUnitId,
        "string." + tmTextUnitId,
        source);
    if (variantId != null) {
      jdbcTemplate.update(
          "INSERT INTO tm_text_unit_variant (id, locale_id, content) VALUES (?, 2, ?)",
          variantId,
          target);
      jdbcTemplate.update(
          "INSERT INTO tm_text_unit_current_variant (tm_text_unit_id, locale_id, tm_text_unit_variant_id) VALUES (?, 2, ?)",
          tmTextUnitId,
          variantId);
    }
    jdbcTemplate.update(
        "INSERT INTO review_project_text_unit (id, review_project_id, tm_text_unit_id) VALUES (?, 70, ?)",
        reviewProjectTextUnitId,
        tmTextUnitId);
    jdbcTemplate.update(
        "INSERT INTO review_project_text_unit_decision (id, review_project_text_unit_id, variant_id, version, decision_state, last_modified_date, created_by_user_id, last_modified_by_user_id) VALUES (?, ?, ?, 1, 'DECIDED', ?, ?, ?)",
        decisionId,
        reviewProjectTextUnitId,
        variantId,
        Timestamp.from(decidedAt),
        reviewerId,
        reviewerId);
  }
}
