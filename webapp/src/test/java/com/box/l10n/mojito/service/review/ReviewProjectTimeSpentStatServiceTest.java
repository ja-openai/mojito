package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindow;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindowEndReason;
import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentAttributionConfidence;
import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentReviewFlag;
import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentStat;
import com.box.l10n.mojito.entity.security.user.User;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class ReviewProjectTimeSpentStatServiceTest {

  private final ReviewProjectAssignmentWindowRepository assignmentWindowRepository =
      Mockito.mock(ReviewProjectAssignmentWindowRepository.class);
  private final ReviewProjectRepository reviewProjectRepository =
      Mockito.mock(ReviewProjectRepository.class);
  private final ReviewProjectTextUnitDecisionRepository decisionRepository =
      Mockito.mock(ReviewProjectTextUnitDecisionRepository.class);
  private final ReviewProjectTimeSpentStatRepository statRepository =
      Mockito.mock(ReviewProjectTimeSpentStatRepository.class);

  private ReviewProjectTimeSpentStatService service;

  @Before
  public void setup() {
    service =
        new ReviewProjectTimeSpentStatService(
            assignmentWindowRepository,
            reviewProjectRepository,
            decisionRepository,
            statRepository);
    when(statRepository.save(any(ReviewProjectTimeSpentStat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  public void computeWindowStatCapsLongDecisionGapsAndFlagsHighReport() {
    ZonedDateTime base = ZonedDateTime.parse("2026-06-01T10:00:00Z");
    User translator = new User();
    translator.setId(5L);
    translator.setUsername("linguist@example.com");
    Locale locale = new Locale();
    locale.setId(7L);
    locale.setBcp47Tag("fr-FR");
    ReviewProject reviewProject = new ReviewProject();
    reviewProject.setId(11L);
    reviewProject.setStatus(ReviewProjectStatus.CLOSED);
    reviewProject.setLocale(locale);
    reviewProject.setAssignedTranslatorUser(translator);
    reviewProject.setCreatedDate(base.minusHours(1));
    reviewProject.setTextUnitCount(3);
    reviewProject.setWordCount(30);
    reviewProject.setDecidedCount(3L);
    reviewProject.setDecidedWordCount(30L);

    ReviewProjectAssignmentWindow window = new ReviewProjectAssignmentWindow();
    window.setId(13L);
    window.setReviewProject(reviewProject);
    window.setAssignedTranslatorUser(translator);
    window.setAssignedAt(base.minusMinutes(15));
    window.setAcceptedAt(base.minusMinutes(5));
    window.setEndedAt(base.plusMinutes(4));
    window.setEndReason(ReviewProjectAssignmentWindowEndReason.PROJECT_CLOSED);
    window.setSelfReportedMinutes(4);

    when(statRepository.findByAssignmentWindow_Id(13L)).thenReturn(Optional.empty());
    when(decisionRepository.findTimeSpentDecisionRowsByReviewProjectId(11L))
        .thenReturn(
            List.of(
                new ReviewProjectTimeSpentDecisionRow(101L, 5L, base, 10),
                new ReviewProjectTimeSpentDecisionRow(102L, 5L, base.plusSeconds(30), 10),
                new ReviewProjectTimeSpentDecisionRow(103L, 5L, base.plusSeconds(180), 10)));

    ReviewProjectTimeSpentStat stat =
        service.computeWindowStat(reviewProject, window, base.plusMinutes(4));

    assertEquals(3L, stat.getDecidedCount());
    assertEquals(30L, stat.getDecidedWordCount());
    assertEquals(200L, stat.getRawDecisionSpanSeconds());
    assertEquals(90L, stat.getEstimatedActiveSeconds());
    assertEquals(110L, stat.getPauseSeconds());
    assertEquals(1L, stat.getPauseCount());
    assertEquals(Long.valueOf(240L), stat.getSelfReportedSeconds());
    assertEquals(Long.valueOf(150L), stat.getReportedComputedDeltaSeconds());
    assertEquals(2.666d, stat.getReportedComputedRatio(), 0.001d);
    assertEquals(ReviewProjectTimeSpentReviewFlag.CHECK_HIGH, stat.getReviewFlag());
    assertEquals(
        ReviewProjectTimeSpentAttributionConfidence.ACTOR, stat.getAttributionConfidence());
  }

  @Test
  public void computeWindowStatUsesFirstDecisionEstimateInRawSpan() {
    ZonedDateTime base = ZonedDateTime.parse("2026-06-01T10:00:00Z");
    User translator = new User();
    translator.setId(5L);
    ReviewProject reviewProject = new ReviewProject();
    reviewProject.setId(11L);
    reviewProject.setStatus(ReviewProjectStatus.CLOSED);
    reviewProject.setAssignedTranslatorUser(translator);
    reviewProject.setCreatedDate(base.minusHours(1));
    reviewProject.setTextUnitCount(1);
    reviewProject.setWordCount(10);
    reviewProject.setDecidedCount(1L);
    reviewProject.setDecidedWordCount(10L);

    ReviewProjectAssignmentWindow window = new ReviewProjectAssignmentWindow();
    window.setId(13L);
    window.setReviewProject(reviewProject);
    window.setAssignedTranslatorUser(translator);
    window.setAssignedAt(base.minusMinutes(15));
    window.setAcceptedAt(base.minusMinutes(5));
    window.setEndedAt(base.plusMinutes(4));
    window.setEndReason(ReviewProjectAssignmentWindowEndReason.PROJECT_CLOSED);
    window.setSelfReportedMinutes(1);

    when(statRepository.findByAssignmentWindow_Id(13L)).thenReturn(Optional.empty());
    when(decisionRepository.findTimeSpentDecisionRowsByReviewProjectId(11L))
        .thenReturn(List.of(new ReviewProjectTimeSpentDecisionRow(101L, 5L, base, 10)));

    ReviewProjectTimeSpentStat stat =
        service.computeWindowStat(reviewProject, window, base.plusMinutes(4));

    assertEquals(20L, stat.getRawDecisionSpanSeconds());
    assertEquals(20L, stat.getEstimatedActiveSeconds());
    assertEquals(0L, stat.getPauseSeconds());
  }

  @Test
  public void recomputeProjectStatsBackfillsMissingAssignmentWindowAndReportsComputedWindows() {
    ZonedDateTime base = ZonedDateTime.parse("2026-06-01T10:00:00Z");
    User translator = new User();
    translator.setId(5L);
    translator.setUsername("linguist@example.com");
    ReviewProject reviewProject = new ReviewProject();
    reviewProject.setId(11L);
    reviewProject.setStatus(ReviewProjectStatus.CLOSED);
    reviewProject.setAssignedTranslatorUser(translator);
    reviewProject.setCreatedDate(base.minusHours(1));
    reviewProject.setTextUnitCount(1);
    reviewProject.setWordCount(10);
    reviewProject.setDecidedCount(1L);
    reviewProject.setDecidedWordCount(10L);
    ArgumentCaptor<ReviewProjectAssignmentWindow> assignmentWindowCaptor =
        ArgumentCaptor.forClass(ReviewProjectAssignmentWindow.class);

    when(reviewProjectRepository.findForTimeSpentRecompute(
            any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(reviewProject));
    when(assignmentWindowRepository.findByReviewProjectIdOrderByAssignedAt(11L))
        .thenReturn(List.of());
    when(assignmentWindowRepository.save(assignmentWindowCaptor.capture()))
        .thenAnswer(
            invocation -> {
              ReviewProjectAssignmentWindow window = invocation.getArgument(0);
              window.setId(13L);
              return window;
            });
    when(statRepository.findByAssignmentWindow_Id(13L)).thenReturn(Optional.empty());
    when(decisionRepository.findTimeSpentDecisionRowsByReviewProjectId(11L))
        .thenReturn(List.of(new ReviewProjectTimeSpentDecisionRow(101L, 5L, base, 10)));

    ReviewProjectTimeSpentStatService.TimeSpentRecomputeResult result =
        service.recomputeProjectStats(
            ReviewProjectTimeSpentStatService.TimeSpentRecomputeRequest.defaults());

    assertEquals(1, result.matchedProjectCount());
    assertEquals(1, result.computedWindowCount());
    assertEquals(1, result.backfilledWindowCount());
    assertEquals(base.minusHours(1), assignmentWindowCaptor.getValue().getAssignedAt());
    assertEquals(
        ReviewProjectAssignmentWindowEndReason.PROJECT_CLOSED,
        assignmentWindowCaptor.getValue().getEndReason());
  }

  @Test
  public void computeWindowStatDoesNotAttributeSameActorDecisionOutsideWindow() {
    ZonedDateTime base = ZonedDateTime.parse("2026-06-01T10:00:00Z");
    User translator = new User();
    translator.setId(5L);
    translator.setUsername("linguist@example.com");
    ReviewProject reviewProject = new ReviewProject();
    reviewProject.setId(11L);
    reviewProject.setStatus(ReviewProjectStatus.CLOSED);
    reviewProject.setAssignedTranslatorUser(translator);
    reviewProject.setCreatedDate(base.minusHours(1));
    reviewProject.setTextUnitCount(1);
    reviewProject.setWordCount(2);
    reviewProject.setDecidedCount(1L);
    reviewProject.setDecidedWordCount(2L);

    ReviewProjectAssignmentWindow reopenedWindow = new ReviewProjectAssignmentWindow();
    reopenedWindow.setId(14L);
    reopenedWindow.setReviewProject(reviewProject);
    reopenedWindow.setAssignedTranslatorUser(translator);
    reopenedWindow.setAssignedAt(base.plusMinutes(5));
    reopenedWindow.setEndedAt(base.plusMinutes(10));
    reopenedWindow.setEndReason(ReviewProjectAssignmentWindowEndReason.PROJECT_CLOSED);
    reopenedWindow.setSelfReportedMinutes(5);

    when(statRepository.findByAssignmentWindow_Id(14L)).thenReturn(Optional.empty());
    when(decisionRepository.findTimeSpentDecisionRowsByReviewProjectId(11L))
        .thenReturn(List.of(new ReviewProjectTimeSpentDecisionRow(101L, 5L, base, 2)));

    ReviewProjectTimeSpentStat stat =
        service.computeWindowStat(reviewProject, reopenedWindow, base.plusMinutes(10));

    assertEquals(0L, stat.getDecidedCount());
    assertEquals(0L, stat.getDecidedWordCount());
    assertEquals(0L, stat.getEstimatedActiveSeconds());
    assertEquals(0L, stat.getRawDecisionSpanSeconds());
    assertEquals(ReviewProjectTimeSpentReviewFlag.OK, stat.getReviewFlag());
  }
}
