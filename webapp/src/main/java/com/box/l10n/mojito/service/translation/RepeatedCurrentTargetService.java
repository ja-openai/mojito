package com.box.l10n.mojito.service.translation;

import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.AuditWatermark;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.CandidateMemberRow;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.CandidateStateFingerprint;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.ReviewProjectEvidenceRow;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetRepository.TextSummary;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Finds exact repeated targets among current non-source translations. */
@Service
public class RepeatedCurrentTargetService {

  public static final int DEFAULT_PAGE_SIZE = 500;
  public static final int MAX_PAGE_SIZE = 2000;
  public static final int SCAN_TRANSACTION_TIMEOUT_SECONDS = 180;

  private static final int MAX_SCAN_TOKEN_LENGTH = 256;
  private static final String SCAN_TOKEN_VERSION = "v1";
  private static final String RESTART_REASON =
      "In-scope candidate-membership state changed after this scan began. Discard every page "
          + "from this scan and restart without cursors, high-water marks, or scanToken.";
  private static final String DETECTION_RULE =
      "Current rows are candidates when another current row in the same target locale has an "
          + "exactly equal target and an exactly different source. Deleted assets/repositories, "
          + "source-locale rows, and unconfigured repository locales are excluded. Time, reviewer, "
          + "status, and inclusion flags are not discovery predicates.";

  private final RepeatedCurrentTargetRepository repository;

  public RepeatedCurrentTargetService(RepeatedCurrentTargetRepository repository) {
    this.repository = Objects.requireNonNull(repository);
  }

  @Transactional(
      readOnly = true,
      isolation = Isolation.READ_COMMITTED,
      timeout = SCAN_TRANSACTION_TIMEOUT_SECONDS)
  public ScanResult scan(
      Long reviewProjectId,
      Long afterCurrentPointerId,
      Long highWaterCurrentPointerId,
      Long highWaterReviewProjectTextUnitId,
      String requestedScanToken,
      Integer requestedPageSize) {
    validateReviewProjectId(reviewProjectId);
    if (reviewProjectId != null && !repository.reviewProjectExists(reviewProjectId)) {
      throw new IllegalArgumentException("Unknown reviewProjectId: " + reviewProjectId);
    }
    long after = normalizeAfter(afterCurrentPointerId);
    int pageSize = normalizePageSize(requestedPageSize);
    long highWater = resolveHighWaterCurrentPointerId(after, highWaterCurrentPointerId);
    Long reviewProjectTextUnitHighWater =
        resolveHighWaterReviewProjectTextUnitId(
            reviewProjectId, after, highWaterReviewProjectTextUnitId);
    boolean firstPage = after == 0;

    ScanToken scanToken;
    boolean contentHashIntegrityCheckedThisPage = false;
    if (firstPage) {
      if (requestedScanToken != null) {
        throw new IllegalArgumentException("scanToken must be omitted on the first page");
      }
      AuditWatermark auditBeforeHashPreflight =
          repository.readCurrentPointerAuditWatermark(
              highWater, reviewProjectId, reviewProjectTextUnitHighWater);
      CandidateStateFingerprint candidateStateBeforeHashPreflight =
          repository.readCandidateStateFingerprint(
              highWater, reviewProjectId, reviewProjectTextUnitHighWater);
      long invalidHashCount =
          repository.countInvalidCurrentTargetHashes(
              highWater, reviewProjectId, reviewProjectTextUnitHighWater);
      contentHashIntegrityCheckedThisPage = true;
      if (invalidHashCount != 0) {
        throw new RepeatedCurrentTargetHashIntegrityException(invalidHashCount);
      }
      scanToken =
          captureScanToken(
              highWater,
              reviewProjectId,
              reviewProjectTextUnitHighWater,
              auditBeforeHashPreflight,
              candidateStateBeforeHashPreflight);
      if (scanToken == null) {
        return restartResult(
            reviewProjectId,
            highWater,
            reviewProjectTextUnitHighWater,
            after,
            pageSize,
            contentHashIntegrityCheckedThisPage);
      }
    } else {
      scanToken =
          ScanToken.parseRequired(
              requestedScanToken, reviewProjectId, highWater, reviewProjectTextUnitHighWater);
      if (hasNewAuditRevision(scanToken)) {
        return restartResult(
            reviewProjectId, highWater, reviewProjectTextUnitHighWater, after, pageSize, false);
      }
    }

    List<CandidateMemberRow> fetched =
        repository.findCandidateMembers(
            after,
            highWater,
            reviewProjectId,
            reviewProjectTextUnitHighWater,
            Math.addExact(pageSize, 1));
    boolean hasMore = fetched.size() > pageSize;
    List<CandidateMemberRow> pageRows = hasMore ? fetched.subList(0, pageSize) : fetched;

    Map<Long, List<ReviewProjectEvidenceRow>> evidenceByTextUnit =
        loadEvidenceByTextUnit(reviewProjectId, reviewProjectTextUnitHighWater, pageRows);
    List<CandidateMember> members =
        pageRows.stream()
            .map(
                row ->
                    toCandidateMember(
                        row, evidenceByTextUnit.getOrDefault(row.tmTextUnitId(), List.of())))
            .toList();

    if (hasNewAuditRevision(scanToken) || (!hasMore && !scanSnapshotStillMatches(scanToken))) {
      return restartResult(
          reviewProjectId,
          highWater,
          reviewProjectTextUnitHighWater,
          after,
          pageSize,
          contentHashIntegrityCheckedThisPage);
    }

    Long nextAfterCurrentPointerId =
        hasMore ? members.get(members.size() - 1).currentPointerId() : null;
    String status = !members.isEmpty() ? "CANDIDATES_FOUND" : firstPage ? "PASS" : "SCAN_COMPLETE";
    return new ScanResult(
        status,
        Instant.now(),
        scope(reviewProjectId),
        false,
        DETECTION_RULE,
        highWater,
        reviewProjectTextUnitHighWater,
        scanToken.encode(),
        null,
        after,
        pageSize,
        members.size(),
        nextAfterCurrentPointerId,
        !hasMore,
        contentHashIntegrityCheckedThisPage,
        members);
  }

  private ScanToken captureScanToken(
      long highWaterCurrentPointerId,
      Long reviewProjectId,
      Long highWaterReviewProjectTextUnitId,
      AuditWatermark auditBefore,
      CandidateStateFingerprint candidateStateBefore) {
    CandidateStateFingerprint candidateStateAfter =
        repository.readCandidateStateFingerprint(
            highWaterCurrentPointerId, reviewProjectId, highWaterReviewProjectTextUnitId);
    AuditWatermark auditAfter =
        repository.readCurrentPointerAuditWatermark(
            highWaterCurrentPointerId, reviewProjectId, highWaterReviewProjectTextUnitId);
    if (!auditBefore.equals(auditAfter) || !candidateStateBefore.equals(candidateStateAfter)) {
      return null;
    }
    return new ScanToken(
        reviewProjectId == null ? 0 : reviewProjectId,
        highWaterCurrentPointerId,
        highWaterReviewProjectTextUnitId == null ? 0 : highWaterReviewProjectTextUnitId,
        auditAfter.rowCount(),
        auditAfter.maxRevision(),
        candidateStateAfter.rowCount(),
        candidateStateAfter.sha256());
  }

  private boolean hasNewAuditRevision(ScanToken scanToken) {
    Long reviewProjectId =
        scanToken.reviewProjectIdOrZero() == 0 ? null : scanToken.reviewProjectIdOrZero();
    return repository.hasCurrentPointerAuditRevisionAfter(
        scanToken.highWaterCurrentPointerId(),
        reviewProjectId,
        reviewProjectId == null ? null : scanToken.highWaterReviewProjectTextUnitIdOrZero(),
        scanToken.maxAuditRevision());
  }

  private boolean scanSnapshotStillMatches(ScanToken scanToken) {
    Long reviewProjectId =
        scanToken.reviewProjectIdOrZero() == 0 ? null : scanToken.reviewProjectIdOrZero();
    Long reviewProjectTextUnitHighWater =
        reviewProjectId == null ? null : scanToken.highWaterReviewProjectTextUnitIdOrZero();
    AuditWatermark expectedAudit =
        new AuditWatermark(scanToken.auditRowCount(), scanToken.maxAuditRevision());
    CandidateStateFingerprint expectedState =
        new CandidateStateFingerprint(
            scanToken.candidateStateRowCount(), scanToken.candidateStateSha256());

    AuditWatermark before =
        repository.readCurrentPointerAuditWatermark(
            scanToken.highWaterCurrentPointerId(), reviewProjectId, reviewProjectTextUnitHighWater);
    if (!expectedAudit.equals(before)) {
      return false;
    }
    CandidateStateFingerprint actualState =
        repository.readCandidateStateFingerprint(
            scanToken.highWaterCurrentPointerId(), reviewProjectId, reviewProjectTextUnitHighWater);
    AuditWatermark after =
        repository.readCurrentPointerAuditWatermark(
            scanToken.highWaterCurrentPointerId(), reviewProjectId, reviewProjectTextUnitHighWater);
    return expectedAudit.equals(after) && expectedState.equals(actualState);
  }

  private static ScanResult restartResult(
      Long reviewProjectId,
      long highWaterCurrentPointerId,
      Long highWaterReviewProjectTextUnitId,
      long afterCurrentPointerId,
      int pageSize,
      boolean contentHashIntegrityCheckedThisPage) {
    return new ScanResult(
        "RESTART_REQUIRED",
        Instant.now(),
        scope(reviewProjectId),
        false,
        DETECTION_RULE,
        highWaterCurrentPointerId,
        highWaterReviewProjectTextUnitId,
        null,
        RESTART_REASON,
        afterCurrentPointerId,
        pageSize,
        0,
        null,
        false,
        contentHashIntegrityCheckedThisPage,
        List.of());
  }

  private long resolveHighWaterCurrentPointerId(long after, Long requestedHighWater) {
    if (after == 0) {
      if (requestedHighWater != null) {
        throw new IllegalArgumentException(
            "highWaterCurrentPointerId must be omitted on the first page");
      }
      return repository.findMaxCurrentPointerId();
    }
    if (requestedHighWater == null) {
      throw new IllegalArgumentException(
          "highWaterCurrentPointerId is required when afterCurrentPointerId is non-zero");
    }
    if (requestedHighWater < 0) {
      throw new IllegalArgumentException("highWaterCurrentPointerId must be >= 0");
    }
    if (after > requestedHighWater) {
      throw new IllegalArgumentException(
          "afterCurrentPointerId must not exceed highWaterCurrentPointerId");
    }
    return requestedHighWater;
  }

  private Long resolveHighWaterReviewProjectTextUnitId(
      Long reviewProjectId, long after, Long requestedHighWater) {
    if (reviewProjectId == null) {
      if (requestedHighWater != null) {
        throw new IllegalArgumentException(
            "highWaterReviewProjectTextUnitId is only valid with reviewProjectId");
      }
      return null;
    }
    if (after == 0) {
      if (requestedHighWater != null) {
        throw new IllegalArgumentException(
            "highWaterReviewProjectTextUnitId must be omitted on the first page");
      }
      return repository.findMaxReviewProjectTextUnitId(reviewProjectId);
    }
    if (requestedHighWater == null) {
      throw new IllegalArgumentException(
          "highWaterReviewProjectTextUnitId is required when resuming a Review Project scan");
    }
    if (requestedHighWater < 0) {
      throw new IllegalArgumentException("highWaterReviewProjectTextUnitId must be >= 0");
    }
    return requestedHighWater;
  }

  private Map<Long, List<ReviewProjectEvidenceRow>> loadEvidenceByTextUnit(
      Long reviewProjectId,
      Long highWaterReviewProjectTextUnitId,
      List<CandidateMemberRow> pageRows) {
    if (reviewProjectId == null || pageRows.isEmpty()) {
      return Map.of();
    }
    List<Long> textUnitIds =
        pageRows.stream().map(CandidateMemberRow::tmTextUnitId).distinct().toList();
    return repository
        .findReviewProjectEvidence(reviewProjectId, highWaterReviewProjectTextUnitId, textUnitIds)
        .stream()
        .collect(
            Collectors.groupingBy(
                ReviewProjectEvidenceRow::tmTextUnitId, LinkedHashMap::new, Collectors.toList()));
  }

  private CandidateMember toCandidateMember(
      CandidateMemberRow row, List<ReviewProjectEvidenceRow> evidenceRows) {
    List<ReviewProjectEvidence> reviewProjectEvidence =
        evidenceRows.stream().map(evidence -> toEvidence(evidence, row)).toList();
    long evidenceTotalCount =
        evidenceRows.stream()
            .mapToLong(ReviewProjectEvidenceRow::evidenceTotalCount)
            .max()
            .orElse(0);
    return new CandidateMember(
        clusterKey(row.targetLocaleId(), row.currentTarget().sha256()),
        "REPEATED_CURRENT_TARGET_CANDIDATE",
        row.currentPointerId(),
        row.currentPointerLastModifiedAt(),
        row.currentVariantId(),
        toEvidence(row.currentTarget()),
        row.currentStatus(),
        row.includedInLocalizedFile(),
        row.currentVariantCreatedAt(),
        row.currentVariantCreatedByUserId(),
        row.currentVariantCreatedByUsername(),
        row.currentVariantCreatedByCommonName(),
        row.targetLocaleId(),
        row.targetLocale(),
        row.tmTextUnitId(),
        toEvidence(row.stringId()),
        toEvidence(row.sourceText()),
        toEvidence(row.sourceComment()),
        row.assetId(),
        row.assetPath(),
        row.repositoryId(),
        row.repositoryName(),
        row.sourceLocaleId(),
        row.sourceLocale(),
        evidenceTotalCount,
        evidenceTotalCount > reviewProjectEvidence.size(),
        reviewProjectEvidence);
  }

  private static ReviewProjectEvidence toEvidence(
      ReviewProjectEvidenceRow evidence, CandidateMemberRow current) {
    Boolean decisionVariantMatchesCurrentVariant =
        evidence.decisionId() == null
            ? null
            : Objects.equals(evidence.decisionVariantId(), current.currentVariantId());
    return new ReviewProjectEvidence(
        evidence.reviewProjectId(),
        evidence.reviewProjectRequestId(),
        evidence.reviewProjectName(),
        evidence.reviewProjectType(),
        evidence.terminologyPhase(),
        evidence.reviewProjectStatus(),
        evidence.reviewProjectTextUnitId(),
        evidence.reviewProjectTextUnitCreatedAt(),
        evidence.baselineVariantId(),
        toEvidence(evidence.baselineTarget()),
        evidence.baselineStatus(),
        evidence.baselineIncludedInLocalizedFile(),
        evidence.decisionId(),
        evidence.decisionState(),
        evidence.decisionVersion(),
        evidence.decisionCreatedAt(),
        evidence.decisionLastModifiedAt(),
        evidence.reviewedVariantId(),
        toEvidence(evidence.reviewedTarget()),
        evidence.decisionVariantId(),
        toEvidence(evidence.decisionTarget()),
        decisionVariantMatchesCurrentVariant,
        evidence.effectiveReviewerId(),
        evidence.effectiveReviewerUsername(),
        evidence.effectiveReviewerCommonName());
  }

  private static ExactTextEvidence toEvidence(TextSummary summary) {
    return summary == null
        ? null
        : ExactTextEvidence.fromSummary(
            summary.preview(), summary.sha256(), summary.codePointLength());
  }

  private static String clusterKey(long localeId, String exactTargetSha256) {
    byte[] bytes =
        (localeId + "\u0000sha256:" + exactTargetSha256).getBytes(StandardCharsets.UTF_8);
    return DigestUtils.sha256Hex(bytes);
  }

  private static ScanScope scope(Long reviewProjectId) {
    return new ScanScope(
        reviewProjectId == null ? "ALL_CURRENT_TRANSLATIONS" : "REVIEW_PROJECT", reviewProjectId);
  }

  private static long normalizeAfter(Long value) {
    long normalized = value == null ? 0 : value;
    if (normalized < 0) {
      throw new IllegalArgumentException("afterCurrentPointerId must be >= 0");
    }
    return normalized;
  }

  private static int normalizePageSize(Integer value) {
    int normalized = value == null ? DEFAULT_PAGE_SIZE : value;
    if (normalized < 1 || normalized > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and " + MAX_PAGE_SIZE + ": " + normalized);
    }
    return normalized;
  }

  private static void validateReviewProjectId(Long reviewProjectId) {
    if (reviewProjectId != null && reviewProjectId <= 0) {
      throw new IllegalArgumentException("reviewProjectId must be positive");
    }
  }

  private record ScanToken(
      long reviewProjectIdOrZero,
      long highWaterCurrentPointerId,
      long highWaterReviewProjectTextUnitIdOrZero,
      long auditRowCount,
      long maxAuditRevision,
      long candidateStateRowCount,
      String candidateStateSha256) {

    private String encode() {
      return String.join(
          ".",
          SCAN_TOKEN_VERSION,
          Long.toString(reviewProjectIdOrZero),
          Long.toString(highWaterCurrentPointerId),
          Long.toString(highWaterReviewProjectTextUnitIdOrZero),
          Long.toString(auditRowCount),
          Long.toString(maxAuditRevision),
          Long.toString(candidateStateRowCount),
          candidateStateSha256);
    }

    private static ScanToken parseRequired(
        String encoded,
        Long reviewProjectId,
        long highWaterCurrentPointerId,
        Long highWaterReviewProjectTextUnitId) {
      if (encoded == null || encoded.isBlank()) {
        throw new IllegalArgumentException(
            "scanToken is required when afterCurrentPointerId is non-zero");
      }
      if (encoded.length() > MAX_SCAN_TOKEN_LENGTH) {
        throw new IllegalArgumentException("scanToken is invalid");
      }
      String[] parts = encoded.split("\\.", -1);
      if (parts.length != 8 || !SCAN_TOKEN_VERSION.equals(parts[0])) {
        throw new IllegalArgumentException("scanToken is invalid");
      }
      try {
        ScanToken token =
            new ScanToken(
                parseNonNegative(parts[1]),
                parseNonNegative(parts[2]),
                parseNonNegative(parts[3]),
                parseNonNegative(parts[4]),
                parseNonNegative(parts[5]),
                parseNonNegative(parts[6]),
                parts[7]);
        long expectedProjectId = reviewProjectId == null ? 0 : reviewProjectId;
        long expectedProjectHighWater =
            highWaterReviewProjectTextUnitId == null ? 0 : highWaterReviewProjectTextUnitId;
        if (token.reviewProjectIdOrZero != expectedProjectId
            || token.highWaterCurrentPointerId != highWaterCurrentPointerId
            || token.highWaterReviewProjectTextUnitIdOrZero != expectedProjectHighWater
            || !token.candidateStateSha256.matches("[0-9a-f]{64}")) {
          throw new IllegalArgumentException("scanToken does not match the requested scan");
        }
        return token;
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("scanToken is invalid", exception);
      }
    }

    private static long parseNonNegative(String value) {
      long parsed = Long.parseLong(value);
      if (parsed < 0) {
        throw new NumberFormatException("negative token value");
      }
      return parsed;
    }
  }

  public record ScanScope(String type, Long reviewProjectId) {}

  public record ScanResult(
      String status,
      Instant generatedAt,
      ScanScope scope,
      boolean timeWindowApplied,
      String detectionRule,
      long highWaterCurrentPointerId,
      Long highWaterReviewProjectTextUnitId,
      String scanToken,
      String restartReason,
      long afterCurrentPointerId,
      int pageSize,
      int returnedCandidateMemberCount,
      Long nextAfterCurrentPointerId,
      boolean complete,
      boolean contentHashIntegrityCheckedThisPage,
      List<CandidateMember> candidateMembers) {}

  public record CandidateMember(
      String clusterKey,
      String classification,
      long currentPointerId,
      Instant currentPointerLastModifiedAt,
      long currentVariantId,
      ExactTextEvidence currentTarget,
      String currentStatus,
      Boolean includedInLocalizedFile,
      Instant currentVariantCreatedAt,
      Long currentVariantCreatedByUserId,
      String currentVariantCreatedByUsername,
      String currentVariantCreatedByCommonName,
      long targetLocaleId,
      String targetLocale,
      long tmTextUnitId,
      ExactTextEvidence stringId,
      ExactTextEvidence source,
      ExactTextEvidence sourceComment,
      long assetId,
      String assetPath,
      long repositoryId,
      String repositoryName,
      long sourceLocaleId,
      String sourceLocale,
      long reviewProjectEvidenceTotalCount,
      boolean reviewProjectEvidenceTruncated,
      List<ReviewProjectEvidence> reviewProjectEvidence) {}

  public record ReviewProjectEvidence(
      long reviewProjectId,
      Long reviewProjectRequestId,
      String reviewProjectName,
      String reviewProjectType,
      String terminologyPhase,
      String reviewProjectStatus,
      long reviewProjectTextUnitId,
      Instant reviewProjectTextUnitCreatedAt,
      Long baselineVariantId,
      ExactTextEvidence baselineTarget,
      String baselineStatus,
      Boolean baselineIncludedInLocalizedFile,
      Long decisionId,
      String decisionState,
      Long decisionVersion,
      Instant decisionCreatedAt,
      Instant decisionLastModifiedAt,
      Long reviewedVariantId,
      ExactTextEvidence reviewedTarget,
      Long decisionVariantId,
      ExactTextEvidence decisionTarget,
      Boolean decisionVariantMatchesCurrentVariant,
      Long effectiveReviewerId,
      String effectiveReviewerUsername,
      String effectiveReviewerCommonName) {}
}
