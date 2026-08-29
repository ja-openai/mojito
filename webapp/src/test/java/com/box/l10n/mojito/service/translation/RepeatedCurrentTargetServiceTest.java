package com.box.l10n.mojito.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.AuditWatermark;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.CandidateMemberRow;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.CandidateStateFingerprint;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.ReviewProjectEvidenceRow;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.TextSummary;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetService.ScanResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class RepeatedCurrentTargetServiceTest {

  private static final AuditWatermark AUDIT_WATERMARK = new AuditWatermark(90, 120);
  private static final CandidateStateFingerprint CANDIDATE_STATE =
      new CandidateStateFingerprint(50, "a".repeat(64));

  private RepeatedCurrentTargetRepository repository;
  private RepeatedCurrentTargetService service;

  @Before
  public void setUp() {
    repository = Mockito.mock(RepeatedCurrentTargetRepository.class);
    service = new RepeatedCurrentTargetService(repository);
    when(repository.readCurrentPointerAuditWatermark(
            anyLong(), nullable(Long.class), nullable(Long.class)))
        .thenReturn(AUDIT_WATERMARK);
    when(repository.readCandidateStateFingerprint(
            anyLong(), nullable(Long.class), nullable(Long.class)))
        .thenReturn(CANDIDATE_STATE);
  }

  @Test
  public void pinsSnapshotTokenAndBoundsLongTextEvidence() {
    String longTarget = "  " + "目标\n".repeat(300) + "  ";
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.findCandidateMembers(0, 999, null, null, 3))
        .thenReturn(
            List.of(
                row(101, 1001, 5001, 2, "Source A", longTarget),
                row(102, 1002, 5002, 2, "Source B", longTarget),
                row(103, 1003, 5003, 2, "Source C", "another")));

    ScanResult result = service.scan(null, null, null, null, null, 2);

    assertThat(result.status()).isEqualTo("CANDIDATES_FOUND");
    assertThat(result.highWaterCurrentPointerId()).isEqualTo(999L);
    assertThat(result.scanToken()).startsWith("v1.0.999.0.90.120.50.");
    assertThat(result.nextAfterCurrentPointerId()).isEqualTo(102L);
    assertThat(result.complete()).isFalse();
    assertThat(result.contentHashIntegrityCheckedThisPage()).isTrue();
    assertThat(result.candidateMembers().get(0).currentTarget().exactText()).isNull();
    assertThat(result.candidateMembers().get(0).currentTarget().preview())
        .hasSizeLessThanOrEqualTo(ExactTextEvidence.PREVIEW_CODE_POINT_LIMIT * 2);
    assertThat(result.candidateMembers().get(0).currentTarget().truncated()).isTrue();
    assertThat(result.candidateMembers().get(0).clusterKey())
        .isEqualTo(result.candidateMembers().get(1).clusterKey());
  }

  @Test
  public void returnsRestartRequiredWhenEarlierPointerChangesBetweenPages() {
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.findCandidateMembers(0, 999, null, null, 2))
        .thenReturn(List.of(row(101, 1001, 5001, 2, "A", "x"), row(102, 1002, 5002, 2, "B", "x")));
    ScanResult first = service.scan(null, null, null, null, null, 1);

    when(repository.hasCurrentPointerAuditRevisionAfter(999, null, null, 120)).thenReturn(true);
    ScanResult resumed = service.scan(null, 101L, 999L, null, first.scanToken(), 1);

    assertThat(resumed.status()).isEqualTo("RESTART_REQUIRED");
    assertThat(resumed.complete()).isFalse();
    assertThat(resumed.scanToken()).isNull();
    assertThat(resumed.nextAfterCurrentPointerId()).isNull();
    assertThat(resumed.candidateMembers()).isEmpty();
    assertThat(resumed.restartReason()).contains("Discard every page");
    verify(repository, never()).findCandidateMembers(101, 999, null, null, 2);
  }

  @Test
  public void restartsIfPointerChangesDuringInitialHashPreflight() {
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.readCurrentPointerAuditWatermark(999, null, null))
        .thenReturn(AUDIT_WATERMARK, new AuditWatermark(91, 121));

    ScanResult result = service.scan(null, null, null, null, null, 10);

    assertThat(result.status()).isEqualTo("RESTART_REQUIRED");
    assertThat(result.complete()).isFalse();
    verify(repository, never()).findCandidateMembers(0, 999, null, null, 11);
  }

  @Test
  public void restartsIfCandidateMembershipChangesDuringInitialHashPreflight() {
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.readCandidateStateFingerprint(999, null, null))
        .thenReturn(CANDIDATE_STATE, new CandidateStateFingerprint(50, "b".repeat(64)));

    ScanResult result = service.scan(null, null, null, null, null, 10);

    assertThat(result.status()).isEqualTo("RESTART_REQUIRED");
    assertThat(result.complete()).isFalse();
    verify(repository, never()).findCandidateMembers(0, 999, null, null, 11);
  }

  @Test
  public void finalAuditCountCatchesRevisionCommittedOutOfOrder() {
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.findCandidateMembers(0, 999, null, null, 2))
        .thenReturn(List.of(row(101, 1001, 5001, 2, "A", "x"), row(102, 1002, 5002, 2, "B", "x")));
    ScanResult first = service.scan(null, null, null, null, null, 1);

    when(repository.findCandidateMembers(101, 999, null, null, 2)).thenReturn(List.of());
    when(repository.readCurrentPointerAuditWatermark(999, null, null))
        .thenReturn(new AuditWatermark(91, 120));
    ScanResult resumed = service.scan(null, 101L, 999L, null, first.scanToken(), 1);

    assertThat(resumed.status()).isEqualTo("RESTART_REQUIRED");
    assertThat(resumed.complete()).isFalse();
  }

  @Test
  public void finalFingerprintCatchesDurableUnauditedCandidateStateDrift() {
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.findCandidateMembers(0, 999, null, null, 2))
        .thenReturn(List.of(row(101, 1001, 5001, 2, "A", "x"), row(102, 1002, 5002, 2, "B", "x")));
    ScanResult first = service.scan(null, null, null, null, null, 1);

    when(repository.findCandidateMembers(101, 999, null, null, 2)).thenReturn(List.of());
    when(repository.readCandidateStateFingerprint(999, null, null))
        .thenReturn(new CandidateStateFingerprint(50, "b".repeat(64)));
    ScanResult resumed = service.scan(null, 101L, 999L, null, first.scanToken(), 1);

    assertThat(resumed.status()).isEqualTo("RESTART_REQUIRED");
    assertThat(resumed.complete()).isFalse();
  }

  @Test
  public void failsClosedWithActionableHashIntegrityException() {
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.countInvalidCurrentTargetHashes(999, null, null)).thenReturn(2L);

    assertThatThrownBy(() -> service.scan(null, null, null, null, null, 10))
        .isInstanceOf(RepeatedCurrentTargetHashIntegrityException.class)
        .hasMessageContaining("2 current targets have a missing or stale content_md5");

    verify(repository, never()).findCandidateMembers(0, 999, null, null, 11);
  }

  @Test
  public void scopedScanBoundsLineageAndReturnsTotalCount() {
    CandidateMemberRow current = row(201, 2001, 6001, 2, "Scoped source", "current");
    ReviewProjectEvidenceRow evidence =
        new ReviewProjectEvidenceRow(
            2001,
            8001,
            Instant.parse("2026-08-28T10:00:00Z"),
            6101L,
            summary("baseline"),
            "APPROVED",
            true,
            9001L,
            "PENDING",
            4L,
            Instant.parse("2026-08-28T11:00:00Z"),
            Instant.parse("2026-08-28T12:00:00Z"),
            6102L,
            summary("reviewed"),
            6001L,
            summary("current"),
            9L,
            "reviewer@example.com",
            "Reviewer",
            70,
            "TRANSLATION",
            null,
            "OPEN",
            60L,
            "Review request",
            25);
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.findMaxReviewProjectTextUnitId(70)).thenReturn(8002L);
    when(repository.reviewProjectExists(70)).thenReturn(true);
    when(repository.findCandidateMembers(0, 999, 70L, 8002L, 11)).thenReturn(List.of(current));
    when(repository.findReviewProjectEvidence(70, 8002, List.of(2001L)))
        .thenReturn(List.of(evidence));

    ScanResult result = service.scan(70L, null, null, null, null, 10);

    var member = result.candidateMembers().get(0);
    var provenance = member.reviewProjectEvidence().get(0);
    assertThat(member.reviewProjectEvidenceTotalCount()).isEqualTo(25);
    assertThat(member.reviewProjectEvidenceTruncated()).isTrue();
    assertThat(provenance.baselineTarget().exactText()).isEqualTo("baseline");
    assertThat(provenance.reviewedTarget().exactText()).isEqualTo("reviewed");
    assertThat(provenance.decisionTarget().exactText()).isEqualTo("current");
    assertThat(provenance.decisionVariantMatchesCurrentVariant()).isTrue();
  }

  @Test
  public void validatesCursorTokenAndPageBounds() {
    assertThatThrownBy(() -> service.scan(null, 1L, null, null, null, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("highWaterCurrentPointerId is required");
    assertThatThrownBy(() -> service.scan(null, 11L, 10L, null, "bad", 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not exceed");
    assertThatThrownBy(() -> service.scan(null, 1L, 10L, null, null, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scanToken is required");
    assertThatThrownBy(() -> service.scan(null, 0L, null, null, null, 2001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pageSize must be between 1 and 2000");
    assertThatThrownBy(() -> service.scan(0L, 0L, null, null, null, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reviewProjectId must be positive");
  }

  @Test
  public void redactsSecretLikeBoundedEvidence() {
    String secretTarget = "Bearer abcdefghijklmnop";
    CandidateMemberRow current =
        row(
            301,
            3001,
            7001,
            2,
            "password=secret-value",
            "jdbc:mysql://user:password@example.invalid/db",
            secretTarget);
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.findCandidateMembers(0, 999, null, null, 11)).thenReturn(List.of(current));

    ScanResult result = service.scan(null, null, null, null, null, 10);

    var member = result.candidateMembers().get(0);
    assertThat(member.currentTarget().exactText()).isNull();
    assertThat(member.currentTarget().preview()).isNull();
    assertThat(member.currentTarget().redacted()).isTrue();
    assertThat(member.source().redacted()).isTrue();
    assertThat(member.sourceComment().redacted()).isTrue();
  }

  @Test
  public void boundsLongStringIdEvidence() {
    String longStringId = "identifier-".repeat(300);
    when(repository.findMaxCurrentPointerId()).thenReturn(999L);
    when(repository.findCandidateMembers(0, 999, null, null, 11))
        .thenReturn(
            List.of(row(401, 4001, 8001, 2, longStringId, "Source", "source comment", "target")));

    ScanResult result = service.scan(null, null, null, null, null, 10);

    ExactTextEvidence stringId = result.candidateMembers().get(0).stringId();
    assertThat(stringId.exactText()).isNull();
    assertThat(stringId.preview().codePointCount(0, stringId.preview().length()))
        .isEqualTo(ExactTextEvidence.PREVIEW_CODE_POINT_LIMIT);
    assertThat(stringId.truncated()).isTrue();
  }

  private static CandidateMemberRow row(
      long currentPointerId,
      long textUnitId,
      long variantId,
      long localeId,
      String source,
      String target) {
    return row(currentPointerId, textUnitId, variantId, localeId, source, "source comment", target);
  }

  private static CandidateMemberRow row(
      long currentPointerId,
      long textUnitId,
      long variantId,
      long localeId,
      String source,
      String sourceComment,
      String target) {
    return row(
        currentPointerId,
        textUnitId,
        variantId,
        localeId,
        "string." + textUnitId,
        source,
        sourceComment,
        target);
  }

  private static CandidateMemberRow row(
      long currentPointerId,
      long textUnitId,
      long variantId,
      long localeId,
      String stringId,
      String source,
      String sourceComment,
      String target) {
    return new CandidateMemberRow(
        currentPointerId,
        Instant.parse("2026-08-28T12:00:00Z"),
        variantId,
        summary(target),
        "REVIEW_NEEDED",
        true,
        Instant.parse("2026-08-28T11:00:00Z"),
        9L,
        "reviewer@example.com",
        "Reviewer",
        localeId,
        "fr",
        textUnitId,
        summary(stringId),
        summary(source),
        summary(sourceComment),
        10,
        "strings.json",
        20,
        "repository",
        1,
        "en");
  }

  private static TextSummary summary(String value) {
    if (value == null) {
      return null;
    }
    int codePoints = value.codePointCount(0, value.length());
    int end =
        value.offsetByCodePoints(
            0, Math.min(codePoints, ExactTextEvidence.PREVIEW_CODE_POINT_LIMIT));
    return new TextSummary(
        value.substring(0, end),
        DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8)),
        codePoints);
  }
}
