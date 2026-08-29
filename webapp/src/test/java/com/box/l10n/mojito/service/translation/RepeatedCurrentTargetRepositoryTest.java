package com.box.l10n.mojito.service.translation;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.CandidateMemberRow;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.ReviewProjectEvidenceRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

public class RepeatedCurrentTargetRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

  private EmbeddedDatabase database;
  private JdbcTemplate jdbcTemplate;
  private RepeatedCurrentTargetRepository repository;

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
        new RepeatedCurrentTargetRepository(jdbcTemplate, "jdbc:hsqldb:mem:repeated-target-test");
  }

  @After
  public void tearDown() {
    database.shutdown();
  }

  @Test
  public void findsExactCurrentSameLocaleTargetsWithDifferentSources() {
    insertCurrent(101, 1001, 5001, 10, 2, "Source A", "x", "APPROVED", true);
    insertCurrent(102, 1002, 5002, 10, 2, "Source B", "x", "REVIEW_NEEDED", false);

    // Same source is not sufficient.
    insertCurrent(103, 1003, 5003, 10, 2, "Same source", "legitimate", "APPROVED", true);
    insertCurrent(104, 1004, 5004, 10, 2, "Same source", "legitimate", "APPROVED", true);

    // Target matching is exact, including case and whitespace.
    insertCurrent(105, 1005, 5005, 10, 2, "Case source 1", "Case", "APPROVED", true);
    insertCurrent(106, 1006, 5006, 10, 2, "Case source 2", "case", "APPROVED", true);
    insertCurrent(107, 1007, 5007, 10, 2, "Space source 1", "space", "APPROVED", true);
    insertCurrent(108, 1008, 5008, 10, 2, "Space source 2", "space ", "APPROVED", true);

    // Locale matching is required.
    insertCurrent(109, 1009, 5009, 10, 3, "German source", "x", "APPROVED", true);

    // Historical variants that are not current do not participate.
    insertCurrent(110, 1010, 5010, 10, 2, "Historical source", "current", "APPROVED", true);
    insertVariant(5999, 1010, 2, "x", "APPROVED", true);

    // Hash equality only narrows the lookup; unequal exact targets are not candidates.
    insertCurrent(114, 1014, 5014, 10, 2, "Collision A", "collision-a", "APPROVED", true);
    insertCurrent(115, 1015, 5015, 10, 2, "Collision B", "collision-b", "APPROVED", true);
    jdbcTemplate.update(
        "UPDATE tm_text_unit_variant SET content_md5 = '00000000000000000000000000000000' WHERE id IN (5014, 5015)");

    // Source-locale rows are excluded.
    insertCurrent(111, 1011, 5011, 10, 1, "Source-locale source", "x", "APPROVED", true);

    // Empty and one-character targets have no minimum-length exemption.
    insertCurrent(112, 1012, 5012, 10, 2, "Empty A", "", "APPROVED", true);
    insertCurrent(113, 1013, 5013, 10, 2, "Empty B", "", "APPROVED", true);

    List<CandidateMemberRow> rows = repository.findCandidateMembers(0, 115, null, null, 100);

    assertThat(rows)
        .extracting(CandidateMemberRow::currentPointerId)
        .containsExactly(101L, 102L, 112L, 113L);
    assertThat(rows.get(0).currentTarget().preview()).isEqualTo("x");
    assertThat(rows.get(0).sourceText().preview()).isEqualTo("Source A");
    assertThat(rows.get(0).currentStatus()).isEqualTo("APPROVED");
    assertThat(rows.get(1).includedInLocalizedFile()).isFalse();
    assertThat(rows.get(2).currentTarget().preview()).isEmpty();
  }

  @Test
  public void excludesDeletedAndUnconfiguredRowsAndPagesByCurrentPointer() {
    insertCurrent(201, 2001, 6001, 10, 2, "A", "repeat", "APPROVED", true);
    insertCurrent(202, 2002, 6002, 10, 2, "B", "repeat", "APPROVED", true);
    insertCurrent(203, 2003, 6003, 10, 2, "C", "repeat", "APPROVED", true);

    jdbcTemplate.update(
        "INSERT INTO asset (id, path, repository_id, deleted) VALUES (20, 'deleted.json', 10, 1)");
    insertCurrent(204, 2004, 6004, 20, 2, "Deleted asset", "repeat", "APPROVED", true);

    jdbcTemplate.update(
        "INSERT INTO repository (id, name, source_locale_id, deleted) VALUES (11, 'deleted-repo', 1, 1)");
    jdbcTemplate.update(
        "INSERT INTO repository_locale (id, repository_id, locale_id) VALUES (1102, 11, 2)");
    jdbcTemplate.update(
        "INSERT INTO asset (id, path, repository_id, deleted) VALUES (21, 'repo.json', 11, 0)");
    insertCurrent(205, 2005, 6005, 21, 2, "Deleted repo", "repeat", "APPROVED", true);

    jdbcTemplate.update(
        "INSERT INTO repository (id, name, source_locale_id, deleted) VALUES (12, 'unconfigured', 1, 0)");
    jdbcTemplate.update(
        "INSERT INTO asset (id, path, repository_id, deleted) VALUES (22, 'unconfigured.json', 12, 0)");
    insertCurrent(206, 2006, 6006, 22, 2, "No locale config", "repeat", "APPROVED", true);

    List<CandidateMemberRow> first = repository.findCandidateMembers(0, 206, null, null, 2);
    List<CandidateMemberRow> second = repository.findCandidateMembers(202, 206, null, null, 2);

    assertThat(first).extracting(CandidateMemberRow::currentPointerId).containsExactly(201L, 202L);
    assertThat(second).extracting(CandidateMemberRow::currentPointerId).containsExactly(203L);
  }

  @Test
  public void doesNotUsePeersAboveThePinnedCurrentPointerHighWaterMark() {
    insertCurrent(211, 2011, 6011, 10, 2, "Earlier source", "future", "APPROVED", true);
    insertCurrent(212, 2012, 6012, 10, 2, "Later source", "future", "APPROVED", true);

    List<CandidateMemberRow> beforePeer = repository.findCandidateMembers(0, 211, null, null, 100);
    List<CandidateMemberRow> afterPeer = repository.findCandidateMembers(0, 212, null, null, 100);

    assertThat(beforePeer).isEmpty();
    assertThat(afterPeer)
        .extracting(CandidateMemberRow::currentPointerId)
        .containsExactly(211L, 212L);
  }

  @Test
  public void reviewProjectScopeRequiresBothMembersAndReturnsLineage() {
    insertCurrent(301, 3001, 7001, 10, 2, "Scoped A", "scoped", "APPROVED", true);
    insertCurrent(302, 3002, 7002, 10, 2, "Scoped B", "scoped", "REVIEW_NEEDED", true);
    insertCurrent(303, 3003, 7003, 10, 2, "Outside", "outside", "APPROVED", true);
    insertCurrent(304, 3004, 7004, 10, 2, "Inside only", "outside", "APPROVED", true);

    insertVariant(7101, 3001, 2, "baseline", "APPROVED", true);
    insertVariant(7102, 3001, 2, "reviewed", "REVIEW_NEEDED", true);
    insertReviewProjectTextUnit(8001, 70, 3001, 7101L);
    insertReviewProjectTextUnit(8002, 70, 3002, 7002L);
    insertReviewProjectTextUnit(8003, 70, 3004, 7004L);
    insertReviewProjectTextUnit(8004, 70, 3001, 7001L);
    jdbcTemplate.update(
        "INSERT INTO review_project_text_unit_decision (id, review_project_text_unit_id, variant_id, reviewed_variant_id, decision_state, version, created_date, last_modified_date, created_by_user_id, last_modified_by_user_id) VALUES (9001, 8001, 7001, 7102, 'DECIDED', 3, ?, ?, 9, NULL)",
        Timestamp.from(NOW.minusSeconds(30)),
        Timestamp.from(NOW));

    List<CandidateMemberRow> rows = repository.findCandidateMembers(0, 304, 70L, 8004L, 100);
    List<ReviewProjectEvidenceRow> evidence =
        repository.findReviewProjectEvidence(70, 8003, List.of(3001L, 3002L));

    assertThat(rows).extracting(CandidateMemberRow::currentPointerId).containsExactly(301L, 302L);
    assertThat(evidence).hasSize(2);
    ReviewProjectEvidenceRow first = evidence.get(0);
    assertThat(first.reviewProjectTextUnitId()).isEqualTo(8001L);
    assertThat(first.baselineVariantId()).isEqualTo(7101L);
    assertThat(first.baselineTarget().preview()).isEqualTo("baseline");
    assertThat(first.reviewedVariantId()).isEqualTo(7102L);
    assertThat(first.reviewedTarget().preview()).isEqualTo("reviewed");
    assertThat(first.decisionVariantId()).isEqualTo(7001L);
    assertThat(first.decisionTarget().preview()).isEqualTo("scoped");
    assertThat(first.effectiveReviewerUsername()).isEqualTo("reviewer@example.com");
    assertThat(first.decisionLastModifiedAt()).isEqualTo(NOW);
  }

  @Test
  public void productionQueryMaterializesFixedWidthRowsForExactComparison() {
    assertThat(RepeatedCurrentTargetRepository.MYSQL_CANDIDATE_MEMBERS_SQL)
        .contains("WITH eligible_current AS")
        .contains("SELECT DISTINCT")
        .contains("current_variant.content_md5 AS target_content_md5")
        .contains("c2.target_content_md5 = c1.target_content_md5")
        .contains("CAST(v2.content AS BINARY) = CAST(v1.content AS BINARY)")
        .contains("NOT (CAST(tu2.content AS BINARY) <=> CAST(tu1.content AS BINARY))")
        .contains("LEFT(v1.content, :evidencePreviewCodePointLimit)")
        .contains("LOWER(SHA2(v1.content, 256))")
        .contains("CHAR_LENGTH(v1.content)")
        .contains("LEFT(tu1.name, :evidencePreviewCodePointLimit)")
        .contains("LOWER(SHA2(tu1.name, 256))")
        .doesNotContain("v1.content AS current_target")
        .doesNotContain("current_variant.content AS")
        .doesNotContain("tu1.name AS string_id")
        .doesNotContain("tu1.content AS source_text")
        .doesNotContain("FORCE INDEX")
        .doesNotContain("SET_VAR")
        .doesNotContain("I__TUV__LOCALE_CONTENT_MD5")
        .doesNotContain("I__RPTU__PROJECT_TEXT_UNIT")
        .doesNotContain("last_modified_date >=")
        .doesNotContain("LIMIT 50");
    assertThat(RepeatedCurrentTargetRepository.MYSQL_SCOPED_CANDIDATE_MEMBERS_SQL)
        .contains("SELECT DISTINCT STRAIGHT_JOIN")
        .contains("FROM review_project scope_project")
        .contains("JOIN review_project_text_unit scope_text_unit")
        .contains("JOIN tm_text_unit_current_variant current_pointer")
        .containsOnlyOnce(":afterCurrentPointerId")
        .doesNotContain("I__RPTU__PROJECT_TEXT_UNIT");
    assertThat(RepeatedCurrentTargetRepository.MYSQL_REVIEW_PROJECT_EVIDENCE_SQL)
        .contains("ROW_NUMBER() OVER")
        .contains("COUNT(*) OVER")
        .contains("evidence_rank <= :evidencePerTextUnitLimit")
        .doesNotContain("baseline.content AS baseline_target");
    assertThat(RepeatedCurrentTargetRepository.MYSQL_CANDIDATE_STATE_FINGERPRINT_SQL)
        .contains("LOWER(SHA2(current_variant.content, 256))")
        .contains("LOWER(SHA2(current_variant.content_md5, 256))")
        .contains("LOWER(SHA2(source_text_unit.content, 256))")
        .contains("source_asset.deleted = 0")
        .contains("source_repository.deleted = 0")
        .contains("JOIN repository_locale configured_locale")
        .contains("current_pointer.locale_id <> source_repository.source_locale_id")
        .doesNotContain("current_variant.content AS")
        .doesNotContain("source_text_unit.content AS");
    assertThat(RepeatedCurrentTargetRepository.MYSQL_SCOPED_CANDIDATE_STATE_FINGERPRINT_SQL)
        .contains("WITH scoped_text_units AS")
        .contains("SELECT STRAIGHT_JOIN")
        .contains("FROM scoped_text_units scope")
        .doesNotContain("state_text_unit");
    assertThat(RepeatedCurrentTargetRepository.MYSQL_SCOPED_INVALID_CURRENT_TARGET_HASH_COUNT_SQL)
        .contains("WITH scoped_text_units AS")
        .contains("SELECT STRAIGHT_JOIN COUNT(DISTINCT current_pointer.id)")
        .contains("FROM scoped_text_units scope");
    assertThat(RepeatedCurrentTargetRepository.MYSQL_SCOPED_CURRENT_POINTER_AUDIT_WATERMARK_SQL)
        .contains("WITH scoped_text_units AS")
        .contains("FROM tm_text_unit_current_variant_aud audit_row")
        .contains("JOIN scoped_text_units scope");
  }

  @Test
  public void scopedHashPreflightAndAuditWatermarkExcludeRowsOutsideTheProject() {
    insertCurrent(451, 4501, 7451, 10, 2, "Inside", "inside", "APPROVED", true);
    insertCurrent(452, 4502, 7452, 10, 2, "Outside", "outside", "APPROVED", true);
    insertReviewProjectTextUnit(8451, 70, 4501, 7451L);
    jdbcTemplate.update(
        "UPDATE tm_text_unit_variant SET content_md5 = NULL WHERE id IN (7451, 7452)");
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit_current_variant_aud (id, rev, locale_id, tm_text_unit_id, tm_text_unit_variant_id) VALUES (451, 20, 2, 4501, 7451)");
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit_current_variant_aud (id, rev, locale_id, tm_text_unit_id, tm_text_unit_variant_id) VALUES (452, 21, 2, 4502, 7452)");

    long scopedInvalid = repository.countInvalidCurrentTargetHashes(452, 70L, 8451L);
    long globalInvalid = repository.countInvalidCurrentTargetHashes(452, null, null);
    var scopedAudit = repository.readCurrentPointerAuditWatermark(452, 70L, 8451L);

    assertThat(scopedInvalid).isEqualTo(1);
    assertThat(globalInvalid).isEqualTo(2);
    assertThat(scopedAudit.rowCount()).isEqualTo(1);
    assertThat(scopedAudit.maxRevision()).isEqualTo(20);
  }

  @Test
  public void boundsReviewProjectLineageFanOutAndReportsTotal() {
    insertCurrent(401, 4001, 7401, 10, 2, "Scoped A", "scoped", "APPROVED", true);
    for (int index = 0; index < 25; index++) {
      insertReviewProjectTextUnit(8100 + index, 70, 4001, 7401L);
    }

    List<ReviewProjectEvidenceRow> evidence =
        repository.findReviewProjectEvidence(70, 9000, List.of(4001L));

    assertThat(evidence)
        .hasSize(RepeatedCurrentTargetRepository.MAX_REVIEW_PROJECT_EVIDENCE_PER_TEXT_UNIT)
        .allMatch(row -> row.evidenceTotalCount() == 25);
  }

  @Test
  public void fingerprintsCandidateStateAndReadsAuditWatermarkWithoutTextPayloads() {
    insertCurrent(501, 5001, 7501, 10, 2, "Source A", "target", "APPROVED", true);
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit_current_variant_aud (id, rev, locale_id, tm_text_unit_id, tm_text_unit_variant_id) VALUES (501, 10, 2, 5001, 7501)");
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit_current_variant_aud (id, rev, locale_id, tm_text_unit_id, tm_text_unit_variant_id) VALUES (501, 11, 2, 5001, 7502)");

    var before = repository.readCandidateStateFingerprint(501, null, null);
    var audit = repository.readCurrentPointerAuditWatermark(501, null, null);

    jdbcTemplate.update(
        "UPDATE tm_text_unit_current_variant SET tm_text_unit_variant_id = 7502 WHERE id = 501");
    var after = repository.readCandidateStateFingerprint(501, null, null);

    assertThat(before.rowCount()).isEqualTo(1);
    assertThat(before.sha256()).hasSize(64).isNotEqualTo(after.sha256());
    assertThat(audit.rowCount()).isEqualTo(2);
    assertThat(audit.maxRevision()).isEqualTo(11);
    assertThat(repository.hasCurrentPointerAuditRevisionAfter(501, null, null, 10)).isTrue();
  }

  @Test
  public void candidateFingerprintChangesForEveryMutableMembershipFamily() {
    insertCurrent(601, 6001, 7601, 10, 2, "Source A", "target", "APPROVED", true);
    var initial = repository.readCandidateStateFingerprint(601, null, null);

    jdbcTemplate.update(
        "UPDATE tm_text_unit_variant SET content = ?, content_md5 = ? WHERE id = 7601",
        "changed target",
        DigestUtils.md5Hex("changed target"));
    var targetChanged = repository.readCandidateStateFingerprint(601, null, null);
    assertThat(targetChanged).isNotEqualTo(initial);

    jdbcTemplate.update(
        "UPDATE tm_text_unit_variant SET content_md5 = '00000000000000000000000000000000' WHERE id = 7601");
    assertThat(repository.readCandidateStateFingerprint(601, null, null))
        .isNotEqualTo(targetChanged);
    jdbcTemplate.update(
        "UPDATE tm_text_unit_variant SET content_md5 = ? WHERE id = 7601",
        DigestUtils.md5Hex("changed target"));

    jdbcTemplate.update(
        "UPDATE tm_text_unit SET content = ?, content_md5 = ? WHERE id = 6001",
        "changed source",
        DigestUtils.md5Hex("changed source"));
    var sourceChanged = repository.readCandidateStateFingerprint(601, null, null);
    assertThat(sourceChanged).isNotEqualTo(targetChanged);

    jdbcTemplate.update(
        "INSERT INTO asset (id, path, repository_id, deleted) VALUES (11, 'other.json', 10, 0)");
    jdbcTemplate.update("UPDATE tm_text_unit SET asset_id = 11 WHERE id = 6001");
    assertThat(repository.readCandidateStateFingerprint(601, null, null))
        .isNotEqualTo(sourceChanged);
    jdbcTemplate.update("UPDATE tm_text_unit SET asset_id = 10 WHERE id = 6001");

    jdbcTemplate.update(
        "INSERT INTO repository (id, name, source_locale_id, deleted) VALUES (11, 'other-repo', 1, 0)");
    jdbcTemplate.update(
        "INSERT INTO repository_locale (id, repository_id, locale_id) VALUES (1102, 11, 2)");
    jdbcTemplate.update("UPDATE asset SET repository_id = 11 WHERE id = 10");
    assertThat(repository.readCandidateStateFingerprint(601, null, null))
        .isNotEqualTo(sourceChanged);
    jdbcTemplate.update("UPDATE asset SET repository_id = 10 WHERE id = 10");

    assertMembershipRemovalChangesFingerprint(
        sourceChanged, "UPDATE asset SET deleted = 1 WHERE id = 10");
    jdbcTemplate.update("UPDATE asset SET deleted = 0 WHERE id = 10");

    assertMembershipRemovalChangesFingerprint(
        sourceChanged, "UPDATE repository SET deleted = 1 WHERE id = 10");
    jdbcTemplate.update("UPDATE repository SET deleted = 0 WHERE id = 10");

    assertMembershipRemovalChangesFingerprint(
        sourceChanged, "DELETE FROM repository_locale WHERE repository_id = 10 AND locale_id = 2");
    jdbcTemplate.update(
        "INSERT INTO repository_locale (id, repository_id, locale_id) VALUES (1002, 10, 2)");

    assertMembershipRemovalChangesFingerprint(
        sourceChanged, "UPDATE repository SET source_locale_id = 2 WHERE id = 10");
    jdbcTemplate.update("UPDATE repository SET source_locale_id = 1 WHERE id = 10");

    insertReviewProjectTextUnit(8601, 70, 6001, 7601L);
    var scoped = repository.readCandidateStateFingerprint(601, 70L, 8601L);
    assertThat(scoped.rowCount()).isEqualTo(1);
    jdbcTemplate.update("UPDATE review_project SET locale_id = 3 WHERE id = 70");
    assertThat(repository.readCandidateStateFingerprint(601, 70L, 8601L))
        .isNotEqualTo(scoped)
        .extracting(state -> state.rowCount())
        .isEqualTo(0L);
    jdbcTemplate.update("UPDATE review_project SET locale_id = 2 WHERE id = 70");
    jdbcTemplate.update(
        "UPDATE review_project_text_unit SET tm_text_unit_id = 999999 WHERE id = 8601");
    assertThat(repository.readCandidateStateFingerprint(601, 70L, 8601L))
        .isNotEqualTo(scoped)
        .extracting(state -> state.rowCount())
        .isEqualTo(0L);
  }

  @Test
  public void candidateFingerprintTreatsMutableMetadataAndLineageAsPageTimeEvidence() {
    insertCurrent(651, 6501, 7651, 10, 2, "Source A", "target", "APPROVED", true);
    insertVariant(7652, 6501, 2, "baseline", "APPROVED", true);
    insertReviewProjectTextUnit(8651, 70, 6501, 7652L);
    jdbcTemplate.update(
        "INSERT INTO review_project_text_unit_decision (id, review_project_text_unit_id, variant_id, reviewed_variant_id, decision_state, version, created_date, last_modified_date, created_by_user_id, last_modified_by_user_id) VALUES (9651, 8651, 7651, 7652, 'DECIDED', 1, ?, ?, 9, NULL)",
        Timestamp.from(NOW.minusSeconds(30)),
        Timestamp.from(NOW));
    var before = repository.readCandidateStateFingerprint(651, 70L, 8651L);

    jdbcTemplate.update(
        "UPDATE tm_text_unit SET name = 'renamed', comment = 'changed comment' WHERE id = 6501");
    jdbcTemplate.update(
        "UPDATE tm_text_unit_variant SET status = 'REVIEW_NEEDED', included_in_localized_file = 0 WHERE id = 7651");
    jdbcTemplate.update("UPDATE asset SET path = 'renamed.json' WHERE id = 10");
    jdbcTemplate.update("UPDATE repository SET name = 'renamed-repo' WHERE id = 10");
    jdbcTemplate.update("UPDATE locale SET bcp47_tag = 'fr-FR' WHERE id = 2");
    jdbcTemplate.update(
        "UPDATE review_project SET status = 'CLOSED', terminology_phase = 'REVIEW' WHERE id = 70");
    jdbcTemplate.update(
        "UPDATE review_project_text_unit SET tm_text_unit_variant_id = 7651 WHERE id = 8651");
    jdbcTemplate.update(
        "UPDATE review_project_text_unit_decision SET decision_state = 'REJECTED', version = 2, reviewed_variant_id = 7651 WHERE id = 9651");

    assertThat(repository.readCandidateStateFingerprint(651, 70L, 8651L)).isEqualTo(before);
  }

  @Test
  public void boundsLongTextStringId() {
    insertCurrent(701, 7001, 7701, 10, 2, "Source A", "target", "APPROVED", true);
    insertCurrent(702, 7002, 7702, 10, 2, "Source B", "target", "APPROVED", true);
    String longStringId = "identifier-".repeat(500);
    jdbcTemplate.update("UPDATE tm_text_unit SET name = ? WHERE id = 7001", longStringId);

    CandidateMemberRow row = repository.findCandidateMembers(0, 702, null, null, 10).get(0);

    assertThat(row.stringId().preview().codePointCount(0, row.stringId().preview().length()))
        .isEqualTo(ExactTextEvidence.PREVIEW_CODE_POINT_LIMIT);
    assertThat(row.stringId().codePointLength())
        .isEqualTo(longStringId.codePointCount(0, longStringId.length()));
    assertThat(row.stringId().sha256()).hasSize(64);
  }

  private void assertMembershipRemovalChangesFingerprint(
      RepeatedCurrentTargetRepository.CandidateStateFingerprint expectedActiveState,
      String mutationSql) {
    jdbcTemplate.update(mutationSql);
    var removed = repository.readCandidateStateFingerprint(601, null, null);
    assertThat(removed).isNotEqualTo(expectedActiveState);
    assertThat(removed.rowCount()).isEqualTo(0);
  }

  private void createSchema() {
    jdbcTemplate.execute("CREATE TABLE locale (id BIGINT PRIMARY KEY, bcp47_tag VARCHAR(32))");
    jdbcTemplate.execute(
        "CREATE TABLE \"USER\" (id BIGINT PRIMARY KEY, username VARCHAR(255), common_name VARCHAR(255))");
    jdbcTemplate.execute(
        "CREATE TABLE repository (id BIGINT PRIMARY KEY, name VARCHAR(255), source_locale_id BIGINT, deleted TINYINT)");
    jdbcTemplate.execute(
        "CREATE TABLE repository_locale (id BIGINT PRIMARY KEY, repository_id BIGINT, locale_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE asset (id BIGINT PRIMARY KEY, path VARCHAR(255), repository_id BIGINT, deleted TINYINT)");
    jdbcTemplate.execute(
        "CREATE TABLE tm_text_unit (id BIGINT PRIMARY KEY, name VARCHAR(10000), content VARCHAR(10000), content_md5 VARCHAR(32), comment VARCHAR(10000), asset_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE tm_text_unit_variant (id BIGINT PRIMARY KEY, tm_text_unit_id BIGINT, locale_id BIGINT, content VARCHAR(10000), content_md5 VARCHAR(32), status VARCHAR(255), included_in_localized_file TINYINT, created_date TIMESTAMP, created_by_user_id BIGINT)");
    jdbcTemplate.execute(
        "CREATE TABLE tm_text_unit_current_variant (id BIGINT PRIMARY KEY, tm_text_unit_id BIGINT, locale_id BIGINT, tm_text_unit_variant_id BIGINT, last_modified_date TIMESTAMP)");
    jdbcTemplate.execute(
        "CREATE TABLE tm_text_unit_current_variant_aud (id BIGINT, rev BIGINT, locale_id BIGINT, tm_text_unit_id BIGINT, tm_text_unit_variant_id BIGINT, PRIMARY KEY (id, rev))");
    jdbcTemplate.execute(
        "CREATE TABLE review_project_request (id BIGINT PRIMARY KEY, name VARCHAR(255))");
    jdbcTemplate.execute(
        "CREATE TABLE review_project (id BIGINT PRIMARY KEY, locale_id BIGINT, review_project_request_id BIGINT, type VARCHAR(32), terminology_phase VARCHAR(32), status VARCHAR(32))");
    jdbcTemplate.execute(
        "CREATE TABLE review_project_text_unit (id BIGINT PRIMARY KEY, review_project_id BIGINT, tm_text_unit_id BIGINT, tm_text_unit_variant_id BIGINT, created_date TIMESTAMP)");
    jdbcTemplate.execute(
        "CREATE TABLE review_project_text_unit_decision (id BIGINT PRIMARY KEY, review_project_text_unit_id BIGINT, variant_id BIGINT, reviewed_variant_id BIGINT, decision_state VARCHAR(16), version BIGINT, created_date TIMESTAMP, last_modified_date TIMESTAMP, created_by_user_id BIGINT, last_modified_by_user_id BIGINT)");
  }

  private void insertReferenceData() {
    jdbcTemplate.update("INSERT INTO locale (id, bcp47_tag) VALUES (1, 'en')");
    jdbcTemplate.update("INSERT INTO locale (id, bcp47_tag) VALUES (2, 'fr')");
    jdbcTemplate.update("INSERT INTO locale (id, bcp47_tag) VALUES (3, 'de')");
    jdbcTemplate.update(
        "INSERT INTO \"USER\" (id, username, common_name) VALUES (9, 'reviewer@example.com', 'Reviewer')");
    jdbcTemplate.update(
        "INSERT INTO repository (id, name, source_locale_id, deleted) VALUES (10, 'repo', 1, 0)");
    jdbcTemplate.update(
        "INSERT INTO repository_locale (id, repository_id, locale_id) VALUES (1002, 10, 2)");
    jdbcTemplate.update(
        "INSERT INTO repository_locale (id, repository_id, locale_id) VALUES (1003, 10, 3)");
    jdbcTemplate.update(
        "INSERT INTO asset (id, path, repository_id, deleted) VALUES (10, 'strings.json', 10, 0)");
    jdbcTemplate.update(
        "INSERT INTO review_project_request (id, name) VALUES (60, 'Carryover review')");
    jdbcTemplate.update(
        "INSERT INTO review_project (id, locale_id, review_project_request_id, type, terminology_phase, status) VALUES (70, 2, 60, 'TRANSLATION', NULL, 'OPEN')");
  }

  private void insertCurrent(
      long currentPointerId,
      long textUnitId,
      long variantId,
      long assetId,
      long localeId,
      String source,
      String target,
      String status,
      boolean included) {
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit (id, name, content, content_md5, comment, asset_id) VALUES (?, ?, ?, ?, ?, ?)",
        textUnitId,
        "string." + textUnitId,
        source,
        DigestUtils.md5Hex(source),
        "source comment",
        assetId);
    insertVariant(variantId, textUnitId, localeId, target, status, included);
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit_current_variant (id, tm_text_unit_id, locale_id, tm_text_unit_variant_id, last_modified_date) VALUES (?, ?, ?, ?, ?)",
        currentPointerId,
        textUnitId,
        localeId,
        variantId,
        Timestamp.from(NOW));
  }

  private void insertVariant(
      long variantId,
      long textUnitId,
      long localeId,
      String target,
      String status,
      boolean included) {
    jdbcTemplate.update(
        "INSERT INTO tm_text_unit_variant (id, tm_text_unit_id, locale_id, content, content_md5, status, included_in_localized_file, created_date, created_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 9)",
        variantId,
        textUnitId,
        localeId,
        target,
        DigestUtils.md5Hex(target),
        status,
        included ? 1 : 0,
        Timestamp.from(NOW.minusSeconds(60)));
  }

  private void insertReviewProjectTextUnit(
      long id, long projectId, long textUnitId, Long baselineVariantId) {
    jdbcTemplate.update(
        "INSERT INTO review_project_text_unit (id, review_project_id, tm_text_unit_id, tm_text_unit_variant_id, created_date) VALUES (?, ?, ?, ?, ?)",
        id,
        projectId,
        textUnitId,
        baselineVariantId,
        Timestamp.from(NOW.minusSeconds(120)));
  }
}
