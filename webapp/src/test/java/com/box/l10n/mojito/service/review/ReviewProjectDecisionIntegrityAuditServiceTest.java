package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditRepository.DecisionRow;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService.AuditResult;
import com.box.l10n.mojito.utils.ServerConfig;
import java.time.Instant;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ReviewProjectDecisionIntegrityAuditServiceTest {

  private static final Instant FROM = Instant.parse("2026-08-19T20:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-20T22:00:00Z");

  private ReviewProjectDecisionIntegrityAuditRepository repository;
  private ReviewProjectDecisionIntegrityAuditService service;

  @Before
  public void setUp() {
    repository = mock(ReviewProjectDecisionIntegrityAuditRepository.class);
    ServerConfig serverConfig = mock(ServerConfig.class);
    when(serverConfig.getUrl()).thenReturn("https://mojito.example/");
    service = new ReviewProjectDecisionIntegrityAuditService(repository, serverConfig);
  }

  @Test
  public void detectsAndGroupsExactCarryoverIncludingOneCharacterTarget() {
    Instant firstTime = FROM.plusSeconds(10);
    DecisionRow second =
        row(
            2,
            firstTime.plusSeconds(30),
            "Color",
            "o",
            "o",
            1L,
            firstTime,
            "Model",
            "o",
            "NORMAL",
            20L,
            2L,
            1L);
    DecisionRow third =
        row(
            3,
            firstTime.plusSeconds(30),
            "Action",
            "o",
            "o",
            2L,
            second.decidedAt(),
            second.sourceText(),
            second.decisionTargetText(),
            "NORMAL",
            30L,
            2L,
            1L);
    DecisionRow staleCurrent =
        row(
            4,
            firstTime.plusSeconds(31),
            "Changed",
            "o",
            "new current",
            3L,
            third.decidedAt(),
            third.sourceText(),
            third.decisionTargetText(),
            "NORMAL",
            40L,
            2L,
            1L);
    when(repository.findDecisionRows(FROM, TO)).thenReturn(List.of(second, third, staleCurrent));

    AuditResult result = service.audit(FROM, TO, 50, 50);

    assertThat(result.status()).isEqualTo("CANDIDATES_FOUND");
    assertThat(result.window().fromInclusiveUtc()).isEqualTo("2026-08-19T20:00:00Z");
    assertThat(result.window().toExclusiveUtc()).isEqualTo("2026-08-20T22:00:00Z");
    assertThat(result.window().durationSeconds()).isEqualTo(93_600.0);
    assertThat(result.coverage().totalDecisions()).isEqualTo(3);
    assertThat(result.carryover().candidatePairCount()).isEqualTo(2);
    assertThat(result.carryover().runCount()).isEqualTo(1);
    assertThat(result.carryover().runs()).hasSize(1);
    assertThat(result.carryover().runs().get(0).decisionCount()).isEqualTo(3);
    assertThat(result.carryover().runs().get(0).repeatedTargetPreview().text()).isEqualTo("o");
    assertThat(result.carryover().runs().get(0).decisions())
        .extracting(decision -> decision.decisionId())
        .containsExactly(1L, 2L, 3L);
    assertThat(result.carryover().runs().get(0).decisions().get(1).deltaSeconds()).isEqualTo(30.0);
    assertThat(result.carryover().runs().get(0).decisions().get(1).decidedAtUtc()).endsWith("Z");
    assertThat(result.carryover().runs().get(0).decisions().get(1).mojitoLink())
        .isEqualTo("https://mojito.example/review-projects/100?tu=1002");
  }

  @Test
  public void reportsStructuralRisksAndKeepsSemanticConcernSeparate() {
    DecisionRow missing =
        row(
            10,
            FROM.plusSeconds(1),
            "Hello",
            null,
            null,
            null,
            null,
            null,
            null,
            "NORMAL",
            10L,
            2L,
            1L);
    DecisionRow expectedTerminologyNull =
        row(
            11,
            FROM.plusSeconds(2),
            "Term",
            null,
            null,
            null,
            null,
            null,
            null,
            "TERMINOLOGY",
            null,
            2L,
            1L);
    DecisionRow specialistTerminologyNull =
        withTerminologyPhase(
            row(
                17,
                FROM.plusSeconds(2),
                "Specialist term",
                null,
                null,
                null,
                null,
                null,
                null,
                "TERMINOLOGY",
                null,
                2L,
                1L),
            "SPECIALIST_INPUT");
    DecisionRow blank =
        row(
            12,
            FROM.plusSeconds(3),
            "Hello",
            "\u00a0",
            "\u00a0",
            null,
            null,
            null,
            null,
            "NORMAL",
            12L,
            2L,
            1L);
    DecisionRow placeholders =
        row(
            13,
            FROM.plusSeconds(4),
            "Hello %s <b>${name}</b>",
            "client_secret=clientvalue Authorization: Basic dXNlcjpwYXNz AWS_SECRET_ACCESS_KEY=Abcdef123456 mysql://user:pass@db/name",
            "client_secret=clientvalue Authorization: Basic dXNlcjpwYXNz AWS_SECRET_ACCESS_KEY=Abcdef123456 mysql://user:pass@db/name",
            null,
            null,
            null,
            null,
            "NORMAL",
            13L,
            2L,
            1L);
    DecisionRow icuBranchTextIsNotAnArgument =
        row(
            14,
            FROM.plusSeconds(5),
            "{count, plural, one {One file} other {# files}}",
            "{count, plural, one {Un fichier} other {# fichiers}}",
            "{count, plural, one {Un fichier} other {# fichiers}}",
            null,
            null,
            null,
            null,
            "NORMAL",
            14L,
            2L,
            1L);
    DecisionRow icuMismatch =
        row(
            15,
            FROM.plusSeconds(6),
            "Hello {name}",
            "Bonjour {user}",
            "Bonjour {user}",
            null,
            null,
            null,
            null,
            "NORMAL",
            15L,
            2L,
            1L);
    DecisionRow sourceEqualsTarget =
        row(
            16,
            FROM.plusSeconds(7),
            "GPT-5",
            "GPT-5",
            "GPT-5",
            null,
            null,
            null,
            null,
            "NORMAL",
            16L,
            2L,
            1L);
    DecisionRow icuSimpleTypeMismatch =
        row(
            18,
            FROM.plusSeconds(8),
            "Updated {value, number}",
            "Mis à jour {value, date}",
            "Mis à jour {value, date}",
            null,
            null,
            null,
            null,
            "NORMAL",
            18L,
            2L,
            1L);
    DecisionRow numericMarkupMismatch =
        row(
            19,
            FROM.plusSeconds(9),
            "Click <0>here</0>",
            "Cliquez ici",
            "Cliquez ici",
            null,
            null,
            null,
            null,
            "NORMAL",
            19L,
            2L,
            1L);
    when(repository.findDecisionRows(FROM, TO))
        .thenReturn(
            List.of(
                missing,
                expectedTerminologyNull,
                specialistTerminologyNull,
                blank,
                placeholders,
                icuBranchTextIsNotAnArgument,
                icuMismatch,
                sourceEqualsTarget,
                icuSimpleTypeMismatch,
                numericMarkupMismatch));

    AuditResult result = service.audit(FROM, TO, 50, 50);

    assertThat(result.coverage().expectedTargetlessTerminologyDecisions()).isEqualTo(1);
    assertThat(result.broaderReview().countsByKind())
        .containsEntry("MISSING_CURRENT_TARGET", 2L)
        .containsEntry("WHITESPACE_ONLY_CURRENT_TARGET", 1L)
        .containsEntry("PRINTF_PLACEHOLDER_MISMATCH", 1L)
        .containsEntry("DOLLAR_PLACEHOLDER_MISMATCH", 1L)
        .containsEntry("MARKUP_TAG_MISMATCH", 2L)
        .containsEntry("ICU_MESSAGE_MISMATCH", 2L)
        .containsEntry("SOURCE_EQUALS_TARGET_NON_SOURCE_LOCALE", 1L);
    assertThat(result.broaderReview().reviewNeededFindingCount()).isEqualTo(1);
    assertThat(result.broaderReview().findings())
        .noneMatch(
            finding ->
                finding.decisionId().equals(14L) && finding.kind().equals("ICU_MESSAGE_MISMATCH"));
    assertThat(result.broaderReview().findings())
        .filteredOn(finding -> finding.decisionId().equals(13L))
        .allMatch(
            finding ->
                finding.currentTargetPreview().redacted()
                    && !finding.currentTargetPreview().text().contains("clientvalue")
                    && !finding.currentTargetPreview().text().contains("dXNlcjpwYXNz")
                    && !finding.currentTargetPreview().text().contains("Abcdef123456")
                    && !finding.currentTargetPreview().text().contains("user:pass@db"));
  }

  @Test
  public void groupsChainsEvenWhenAnotherPartitionIsInterleaved() {
    DecisionRow firstPartitionStart =
        row(
            31,
            FROM.plusSeconds(10),
            "A2",
            "same",
            "same",
            30L,
            FROM.plusSeconds(9),
            "A1",
            "same",
            "NORMAL",
            31L,
            2L,
            1L);
    DecisionRow otherPartition =
        withPartition(
            row(
                41,
                FROM.plusSeconds(11),
                "B2",
                "other",
                "other",
                40L,
                FROM.plusSeconds(10),
                "B1",
                "other",
                "NORMAL",
                41L,
                2L,
                1L),
            101L,
            201L);
    DecisionRow firstPartitionContinuation =
        row(
            32,
            FROM.plusSeconds(12),
            "A3",
            "same",
            "same",
            31L,
            firstPartitionStart.decidedAt(),
            firstPartitionStart.sourceText(),
            firstPartitionStart.decisionTargetText(),
            "NORMAL",
            32L,
            2L,
            1L);
    when(repository.findDecisionRows(FROM, TO))
        .thenReturn(List.of(firstPartitionStart, otherPartition, firstPartitionContinuation));

    AuditResult result = service.audit(FROM, TO, 50, 50);

    assertThat(result.carryover().candidatePairCount()).isEqualTo(3);
    assertThat(result.carryover().runCount()).isEqualTo(2);
    assertThat(result.carryover().runs())
        .extracting(run -> run.candidatePairCount())
        .containsExactly(2, 1);
  }

  @Test
  public void capsDetailsWithoutCappingTotals() {
    DecisionRow first =
        row(
            20,
            FROM.plusSeconds(2),
            "Second",
            "same",
            "same",
            19L,
            FROM.plusSeconds(1),
            "First",
            "same",
            "NORMAL",
            20L,
            2L,
            1L);
    DecisionRow second =
        row(
            22,
            FROM.plusSeconds(4),
            "Fourth",
            "again",
            "again",
            21L,
            FROM.plusSeconds(3),
            "Third",
            "again",
            "NORMAL",
            22L,
            2L,
            1L);
    when(repository.findDecisionRows(FROM, TO)).thenReturn(List.of(first, second));

    AuditResult result = service.audit(FROM, TO, 1, 0);

    assertThat(result.carryover().candidatePairCount()).isEqualTo(2);
    assertThat(result.carryover().runCount()).isEqualTo(2);
    assertThat(result.carryover().runs()).hasSize(1);
    assertThat(result.carryover().detailsTruncated()).isTrue();
  }

  @Test
  public void validatesWindowAndLimitsBeforeQuerying() {
    assertThatThrownBy(() -> service.audit(TO, FROM, 50, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("fromInclusive must be before toExclusive");
    assertThatThrownBy(() -> service.audit(FROM, FROM.plusSeconds(48 * 3600L + 1), 50, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("audit window must not exceed 48 hours");
    assertThatThrownBy(() -> service.audit(FROM, TO, 51, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("carryoverDetailLimit must be between 0 and 50");
    verify(repository, never()).findDecisionRows(FROM, TO);
  }

  private static DecisionRow row(
      long decisionId,
      Instant decidedAt,
      String source,
      String decisionTarget,
      String currentTarget,
      Long previousDecisionId,
      Instant previousDecidedAt,
      String previousSource,
      String previousTarget,
      String projectType,
      Long decisionVariantId,
      Long projectLocaleId,
      Long sourceLocaleId) {
    return new DecisionRow(
        decisionId,
        decisionId + 100,
        100L,
        90L,
        "Review request",
        projectType,
        "TERMINOLOGY".equals(projectType) ? "PM_RESOLUTION" : null,
        projectLocaleId,
        "fr-FR",
        200L,
        "reviewer@example.com",
        "Reviewer",
        decidedAt,
        1L,
        decisionVariantId,
        decisionVariantId == null ? null : projectLocaleId,
        decisionId + 1000,
        "string." + decisionId,
        source,
        decisionTarget,
        currentTarget == null ? null : decisionId + 2000,
        currentTarget == null ? null : projectLocaleId,
        currentTarget,
        300L,
        "strings.json",
        400L,
        "repo",
        sourceLocaleId,
        "en",
        previousDecisionId,
        previousDecisionId == null ? null : previousDecisionId + 100,
        previousDecisionId == null ? null : previousDecisionId + 1000,
        previousDecidedAt,
        previousSource,
        previousTarget);
  }

  private static DecisionRow withPartition(DecisionRow row, Long reviewProjectId, Long reviewerId) {
    return copyRow(row, reviewProjectId, reviewerId, row.terminologyPhase());
  }

  private static DecisionRow withTerminologyPhase(DecisionRow row, String terminologyPhase) {
    return copyRow(row, row.reviewProjectId(), row.effectiveReviewerId(), terminologyPhase);
  }

  private static DecisionRow copyRow(
      DecisionRow row, Long reviewProjectId, Long reviewerId, String terminologyPhase) {
    return new DecisionRow(
        row.decisionId(),
        row.reviewProjectTextUnitId(),
        reviewProjectId,
        row.reviewProjectRequestId(),
        row.reviewProjectName(),
        row.reviewProjectType(),
        terminologyPhase,
        row.projectLocaleId(),
        row.projectLocale(),
        reviewerId,
        row.effectiveReviewerUsername(),
        row.effectiveReviewerCommonName(),
        row.decidedAt(),
        row.decisionVersion(),
        row.decisionVariantId(),
        row.decisionVariantLocaleId(),
        row.tmTextUnitId(),
        row.tmTextUnitName(),
        row.sourceText(),
        row.decisionTargetText(),
        row.currentVariantId(),
        row.currentVariantLocaleId(),
        row.currentTargetText(),
        row.assetId(),
        row.assetPath(),
        row.repositoryId(),
        row.repositoryName(),
        row.sourceLocaleId(),
        row.sourceLocale(),
        row.previousDecisionId(),
        row.previousReviewProjectTextUnitId(),
        row.previousTmTextUnitId(),
        row.previousDecidedAt(),
        row.previousSourceText(),
        row.previousDecisionTargetText());
  }
}
