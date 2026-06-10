package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindow;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindowEndReason;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentAttributionConfidence;
import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentReviewFlag;
import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentStat;
import com.box.l10n.mojito.entity.security.user.User;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewProjectTimeSpentStatService {

  private static final long SECONDS_PER_WORD = 2L;
  private static final long PAUSE_MULTIPLIER = 2L;

  private final ReviewProjectAssignmentWindowRepository assignmentWindowRepository;
  private final ReviewProjectRepository reviewProjectRepository;
  private final ReviewProjectTextUnitDecisionRepository decisionRepository;
  private final ReviewProjectTimeSpentStatRepository statRepository;

  public ReviewProjectTimeSpentStatService(
      ReviewProjectAssignmentWindowRepository assignmentWindowRepository,
      ReviewProjectRepository reviewProjectRepository,
      ReviewProjectTextUnitDecisionRepository decisionRepository,
      ReviewProjectTimeSpentStatRepository statRepository) {
    this.assignmentWindowRepository = Objects.requireNonNull(assignmentWindowRepository);
    this.reviewProjectRepository = Objects.requireNonNull(reviewProjectRepository);
    this.decisionRepository = Objects.requireNonNull(decisionRepository);
    this.statRepository = Objects.requireNonNull(statRepository);
  }

  @Transactional(readOnly = true)
  public TimeSpentReport getReport(TimeSpentReportCriteria criteria) {
    TimeSpentReportCriteria safeCriteria =
        criteria == null ? TimeSpentReportCriteria.defaults() : criteria;
    PageRequest detailLimit = PageRequest.of(0, safeCriteria.detailLimit());
    PageRequest summaryLimit = PageRequest.of(0, safeCriteria.summaryLimit());
    ReviewProjectTimeSpentStatRepository.SummaryProjection summary =
        statRepository.findReportSummary(
            safeCriteria.activityAfter(),
            safeCriteria.activityBefore(),
            safeCriteria.statusName(),
            safeCriteria.translatorUserId(),
            safeCriteria.localeBcp47Tag(),
            ReviewProjectTimeSpentReviewFlag.OK,
            ReviewProjectTimeSpentReviewFlag.MISSING_REPORT);
    List<ReviewProjectTimeSpentStatRepository.LinguistSummaryProjection> linguists =
        statRepository.findLinguistSummaries(
            safeCriteria.activityAfter(),
            safeCriteria.activityBefore(),
            safeCriteria.statusName(),
            safeCriteria.translatorUserId(),
            safeCriteria.localeBcp47Tag(),
            ReviewProjectTimeSpentReviewFlag.OK,
            ReviewProjectTimeSpentReviewFlag.MISSING_REPORT,
            summaryLimit);
    List<ReviewProjectTimeSpentStatRepository.TranslatorScorecardProjection> scorecards =
        statRepository.findTranslatorScorecards(
            safeCriteria.activityAfter(),
            safeCriteria.activityBefore(),
            safeCriteria.statusName(),
            safeCriteria.translatorUserId(),
            safeCriteria.localeBcp47Tag(),
            ReviewProjectTimeSpentReviewFlag.OK,
            ReviewProjectTimeSpentReviewFlag.MISSING_REPORT,
            summaryLimit);
    List<ReviewProjectTimeSpentStat> windows =
        statRepository.findReportRows(
            safeCriteria.activityAfter(),
            safeCriteria.activityBefore(),
            safeCriteria.statusName(),
            safeCriteria.translatorUserId(),
            safeCriteria.localeBcp47Tag(),
            detailLimit);
    return new TimeSpentReport(summary, linguists, scorecards, windows);
  }

  @Transactional
  public TimeSpentRecomputeResult recomputeProjectStats(TimeSpentRecomputeRequest request) {
    TimeSpentRecomputeRequest safeRequest =
        request == null ? TimeSpentRecomputeRequest.defaults() : request;
    List<ReviewProject> projects =
        reviewProjectRepository.findForTimeSpentRecompute(
            safeRequest.projectCreatedAfter(),
            safeRequest.projectCreatedBefore(),
            safeRequest.status(),
            safeRequest.translatorUserId(),
            safeRequest.localeBcp47Tag(),
            PageRequest.of(0, safeRequest.limit()));
    int computedWindowCount = 0;
    int backfilledWindowCount = 0;
    for (ReviewProject reviewProject : projects) {
      BackfilledAssignmentWindows assignmentWindows = getOrBackfillAssignmentWindows(reviewProject);
      ZonedDateTime finalizedAt =
          reviewProject.getStatus() == ReviewProjectStatus.CLOSED ? ZonedDateTime.now() : null;
      for (ReviewProjectAssignmentWindow assignmentWindow : assignmentWindows.assignmentWindows()) {
        computeWindowStat(reviewProject, assignmentWindow, finalizedAt);
        computedWindowCount++;
      }
      backfilledWindowCount += assignmentWindows.backfilledWindowCount();
    }
    return new TimeSpentRecomputeResult(
        projects.size(), computedWindowCount, backfilledWindowCount);
  }

  @Transactional
  public void computeProjectStats(ReviewProject reviewProject, ZonedDateTime finalizedAt) {
    assignmentWindowRepository
        .findByReviewProjectIdOrderByAssignedAt(reviewProject.getId())
        .forEach(window -> computeWindowStat(reviewProject, window, finalizedAt));
  }

  @Transactional
  public int deleteByReviewProjectIds(List<Long> projectIds) {
    if (projectIds == null || projectIds.isEmpty()) {
      return 0;
    }
    return statRepository.deleteByReviewProjectIds(projectIds);
  }

  private BackfilledAssignmentWindows getOrBackfillAssignmentWindows(ReviewProject reviewProject) {
    List<ReviewProjectAssignmentWindow> assignmentWindows =
        assignmentWindowRepository.findByReviewProjectIdOrderByAssignedAt(reviewProject.getId());
    if (!assignmentWindows.isEmpty() || reviewProject.getAssignedTranslatorUser() == null) {
      return new BackfilledAssignmentWindows(assignmentWindows, 0);
    }

    ReviewProjectAssignmentWindow assignmentWindow = new ReviewProjectAssignmentWindow();
    assignmentWindow.setReviewProject(reviewProject);
    assignmentWindow.setAssignedTranslatorUser(reviewProject.getAssignedTranslatorUser());
    assignmentWindow.setAssignedTranslatorUsernameSnapshot(
        reviewProject.getAssignedTranslatorUser().getUsername());
    assignmentWindow.setAssignedAt(
        reviewProject.getCreatedDate() == null
            ? ZonedDateTime.now()
            : reviewProject.getCreatedDate());
    if (reviewProject.getStatus() == ReviewProjectStatus.CLOSED) {
      assignmentWindow.setEndedAt(ZonedDateTime.now());
      assignmentWindow.setEndReason(ReviewProjectAssignmentWindowEndReason.PROJECT_CLOSED);
    }
    return new BackfilledAssignmentWindows(
        List.of(assignmentWindowRepository.save(assignmentWindow)), 1);
  }

  @Transactional
  public ReviewProjectTimeSpentStat computeWindowStat(
      ReviewProject reviewProject,
      ReviewProjectAssignmentWindow window,
      ZonedDateTime finalizedAt) {
    List<ReviewProjectTimeSpentDecisionRow> projectDecisions =
        decisionRepository.findTimeSpentDecisionRowsByReviewProjectId(reviewProject.getId());
    AttributedDecisions attributedDecisions = attributedDecisions(window, projectDecisions);
    PauseEstimate pauseEstimate = estimateActiveTime(attributedDecisions.decisions());

    ReviewProjectTimeSpentStat stat =
        statRepository
            .findByAssignmentWindow_Id(window.getId())
            .orElseGet(ReviewProjectTimeSpentStat::new);

    ZonedDateTime computedAt = ZonedDateTime.now();
    ZonedDateTime firstDecisionAt =
        attributedDecisions.decisions().isEmpty()
            ? null
            : attributedDecisions.decisions().get(0).decidedAt();
    ZonedDateTime lastDecisionAt =
        attributedDecisions.decisions().isEmpty()
            ? null
            : attributedDecisions
                .decisions()
                .get(attributedDecisions.decisions().size() - 1)
                .decidedAt();
    Long selfReportedSeconds =
        window.getSelfReportedMinutes() == null
            ? null
            : window.getSelfReportedMinutes().longValue() * 60L;
    Long reportedComputedDeltaSeconds =
        selfReportedSeconds == null ? null : selfReportedSeconds - pauseEstimate.activeSeconds();
    Double reportedComputedRatio =
        selfReportedSeconds == null || pauseEstimate.activeSeconds() <= 0
            ? null
            : selfReportedSeconds / (double) pauseEstimate.activeSeconds();

    stat.setAssignmentWindow(window);
    stat.setReviewProject(reviewProject);
    stat.setReviewProjectRequestId(
        reviewProject.getReviewProjectRequest() == null
            ? null
            : reviewProject.getReviewProjectRequest().getId());
    stat.setReviewProjectRequestName(
        reviewProject.getReviewProjectRequest() == null
            ? null
            : reviewProject.getReviewProjectRequest().getName());
    stat.setReviewProjectType(
        reviewProject.getType() == null ? null : reviewProject.getType().name());
    stat.setReviewProjectStatus(
        reviewProject.getStatus() == null ? null : reviewProject.getStatus().name());
    stat.setLocaleBcp47Tag(
        reviewProject.getLocale() == null ? null : reviewProject.getLocale().getBcp47Tag());
    stat.setAssignedTranslatorUser(window.getAssignedTranslatorUser());
    stat.setAssignedTranslatorUsername(translatorUsername(window));
    stat.setAssignmentWindowStartedAt(window.getAssignedAt());
    stat.setAssignmentAcceptedAt(window.getAcceptedAt());
    stat.setAssignmentWindowEndedAt(window.getEndedAt());
    stat.setAssignmentWindowEndReason(
        window.getEndReason() == null ? null : window.getEndReason().name());
    stat.setProjectCreatedDate(reviewProject.getCreatedDate());
    stat.setProjectDueDate(reviewProject.getDueDate());
    stat.setTextUnitCount(safeLong(reviewProject.getTextUnitCount()));
    stat.setWordCount(safeLong(reviewProject.getWordCount()));
    stat.setDecidedCount(attributedDecisions.decisions().size());
    stat.setDecidedWordCount(
        attributedDecisions.decisions().stream().mapToLong(row -> safeLong(row.wordCount())).sum());
    stat.setFirstDecisionAt(firstDecisionAt);
    stat.setLastDecisionAt(lastDecisionAt);
    stat.setRawDecisionSpanSeconds(pauseEstimate.rawSeconds());
    stat.setProjectSpanSeconds(secondsBetween(reviewProject.getCreatedDate(), lastDecisionAt));
    stat.setAcceptedToFirstDecisionSeconds(
        nullableSecondsBetween(window.getAcceptedAt(), firstDecisionAt));
    stat.setAssignedToAcceptedSeconds(
        nullableSecondsBetween(window.getAssignedAt(), window.getAcceptedAt()));
    stat.setEstimatedActiveSeconds(pauseEstimate.activeSeconds());
    stat.setPauseSeconds(pauseEstimate.pauseSeconds());
    stat.setPauseCount(pauseEstimate.pauseCount());
    stat.setPauseRatio(
        stat.getRawDecisionSpanSeconds() <= 0
            ? 0
            : pauseEstimate.pauseSeconds() / (double) stat.getRawDecisionSpanSeconds());
    stat.setSelfReportedSeconds(selfReportedSeconds);
    stat.setReportedComputedDeltaSeconds(reportedComputedDeltaSeconds);
    stat.setReportedComputedRatio(reportedComputedRatio);
    stat.setReportedMissing(selfReportedSeconds == null);
    stat.setReviewFlag(reviewFlag(selfReportedSeconds, reportedComputedRatio));
    long pendingCount =
        Math.max(
            0,
            safeLong(reviewProject.getTextUnitCount()) - safeLong(reviewProject.getDecidedCount()));
    long pendingWordCount =
        Math.max(
            0,
            safeLong(reviewProject.getWordCount()) - safeLong(reviewProject.getDecidedWordCount()));
    stat.setClosedWithPending(finalizedAt != null && (pendingCount > 0 || pendingWordCount > 0));
    stat.setPendingCountAtClose(finalizedAt == null ? null : pendingCount);
    stat.setPendingWordCountAtClose(finalizedAt == null ? null : pendingWordCount);
    stat.setCloseReason(reviewProject.getCloseReason());
    stat.setFinalizedAt(finalizedAt);
    stat.setComputedAt(computedAt);
    stat.setAttributionConfidence(attributedDecisions.confidence());
    return statRepository.save(stat);
  }

  private AttributedDecisions attributedDecisions(
      ReviewProjectAssignmentWindow window, List<ReviewProjectTimeSpentDecisionRow> decisions) {
    Long translatorUserId = userId(window.getAssignedTranslatorUser());
    List<ReviewProjectTimeSpentDecisionRow> actorAttributed =
        translatorUserId == null
            ? List.of()
            : decisions.stream()
                .filter(
                    row ->
                        Objects.equals(row.decisionUserId(), translatorUserId)
                            && isInWindow(row.decidedAt(), window))
                .toList();
    if (!actorAttributed.isEmpty()) {
      return new AttributedDecisions(
          actorAttributed, ReviewProjectTimeSpentAttributionConfidence.ACTOR);
    }

    List<ReviewProjectTimeSpentDecisionRow> windowAttributed =
        decisions.stream().filter(row -> isInWindow(row.decidedAt(), window)).toList();
    ReviewProjectTimeSpentAttributionConfidence confidence =
        windowAttributed.isEmpty()
            ? ReviewProjectTimeSpentAttributionConfidence.AMBIGUOUS
            : ReviewProjectTimeSpentAttributionConfidence.ASSIGNMENT_WINDOW;
    return new AttributedDecisions(windowAttributed, confidence);
  }

  private boolean isInWindow(ZonedDateTime decidedAt, ReviewProjectAssignmentWindow window) {
    if (decidedAt == null
        || window.getAssignedAt() == null
        || decidedAt.isBefore(window.getAssignedAt())) {
      return false;
    }
    return window.getEndedAt() == null || decidedAt.isBefore(window.getEndedAt());
  }

  private PauseEstimate estimateActiveTime(List<ReviewProjectTimeSpentDecisionRow> decisions) {
    long rawSeconds = 0;
    long activeSeconds = 0;
    long pauseSeconds = 0;
    long pauseCount = 0;
    ZonedDateTime previousDecisionAt = null;

    for (ReviewProjectTimeSpentDecisionRow decision : decisions) {
      long expectedSeconds = Math.max(1L, safeLong(decision.wordCount()) * SECONDS_PER_WORD);
      long allowedSeconds = expectedSeconds * PAUSE_MULTIPLIER;
      long measuredSeconds =
          previousDecisionAt == null
              ? expectedSeconds
              : Math.max(0, secondsBetween(previousDecisionAt, decision.decidedAt()));
      rawSeconds += measuredSeconds;
      activeSeconds += Math.min(measuredSeconds, allowedSeconds);
      if (measuredSeconds > allowedSeconds) {
        pauseCount++;
        pauseSeconds += measuredSeconds - allowedSeconds;
      }
      previousDecisionAt = decision.decidedAt();
    }

    return new PauseEstimate(rawSeconds, activeSeconds, pauseSeconds, pauseCount);
  }

  private ReviewProjectTimeSpentReviewFlag reviewFlag(
      Long selfReportedSeconds, Double reportedComputedRatio) {
    if (selfReportedSeconds == null) {
      return ReviewProjectTimeSpentReviewFlag.MISSING_REPORT;
    }
    if (reportedComputedRatio == null) {
      return ReviewProjectTimeSpentReviewFlag.OK;
    }
    if (reportedComputedRatio >= 2.0d) {
      return ReviewProjectTimeSpentReviewFlag.CHECK_HIGH;
    }
    if (reportedComputedRatio <= 0.5d) {
      return ReviewProjectTimeSpentReviewFlag.CHECK_LOW;
    }
    return ReviewProjectTimeSpentReviewFlag.OK;
  }

  private Long nullableSecondsBetween(ZonedDateTime start, ZonedDateTime end) {
    if (start == null || end == null || end.isBefore(start)) {
      return null;
    }
    return Duration.between(start, end).getSeconds();
  }

  private long secondsBetween(ZonedDateTime start, ZonedDateTime end) {
    if (start == null || end == null || end.isBefore(start)) {
      return 0L;
    }
    return Duration.between(start, end).getSeconds();
  }

  private long safeLong(Number value) {
    return value == null ? 0L : value.longValue();
  }

  private Long userId(User user) {
    return user == null ? null : user.getId();
  }

  private String translatorUsername(ReviewProjectAssignmentWindow window) {
    if (window.getAssignedTranslatorUser() != null) {
      return window.getAssignedTranslatorUser().getUsername();
    }
    return window.getAssignedTranslatorUsernameSnapshot();
  }

  private record AttributedDecisions(
      List<ReviewProjectTimeSpentDecisionRow> decisions,
      ReviewProjectTimeSpentAttributionConfidence confidence) {}

  private record PauseEstimate(
      long rawSeconds, long activeSeconds, long pauseSeconds, long pauseCount) {}

  private record BackfilledAssignmentWindows(
      List<ReviewProjectAssignmentWindow> assignmentWindows, int backfilledWindowCount) {}

  public record TimeSpentReport(
      ReviewProjectTimeSpentStatRepository.SummaryProjection summary,
      List<ReviewProjectTimeSpentStatRepository.LinguistSummaryProjection> linguistSummaries,
      List<ReviewProjectTimeSpentStatRepository.TranslatorScorecardProjection> translatorScorecards,
      List<ReviewProjectTimeSpentStat> windows) {}

  public record TimeSpentRecomputeResult(
      int matchedProjectCount, int computedWindowCount, int backfilledWindowCount) {}

  public record TimeSpentReportCriteria(
      ZonedDateTime activityAfter,
      ZonedDateTime activityBefore,
      ReviewProjectStatus status,
      Long translatorUserId,
      String localeBcp47Tag,
      int summaryLimit,
      int detailLimit) {

    public TimeSpentReportCriteria {
      summaryLimit = normalizeLimit(summaryLimit, 100, 500);
      detailLimit = normalizeLimit(detailLimit, 100, 500);
      localeBcp47Tag = blankToNull(localeBcp47Tag);
    }

    public String statusName() {
      return status == null ? null : status.name();
    }

    public static TimeSpentReportCriteria defaults() {
      return new TimeSpentReportCriteria(
          null, null, ReviewProjectStatus.CLOSED, null, null, 100, 100);
    }
  }

  public record TimeSpentRecomputeRequest(
      ZonedDateTime projectCreatedAfter,
      ZonedDateTime projectCreatedBefore,
      ReviewProjectStatus status,
      Long translatorUserId,
      String localeBcp47Tag,
      int limit) {

    public TimeSpentRecomputeRequest {
      limit = normalizeLimit(limit, 100, 1000);
      localeBcp47Tag = blankToNull(localeBcp47Tag);
    }

    public static TimeSpentRecomputeRequest defaults() {
      return new TimeSpentRecomputeRequest(null, null, ReviewProjectStatus.CLOSED, null, null, 100);
    }
  }

  private static int normalizeLimit(int limit, int defaultLimit, int maxLimit) {
    if (limit <= 0) {
      return defaultLimit;
    }
    return Math.min(limit, maxLimit);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
