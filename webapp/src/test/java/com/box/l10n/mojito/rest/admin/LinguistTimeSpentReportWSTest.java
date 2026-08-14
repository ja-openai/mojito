package com.box.l10n.mojito.rest.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.review.ReviewProjectTimeSpentStatRepository;
import org.junit.Test;

public class LinguistTimeSpentReportWSTest {

  @Test
  public void summaryAggregatesRapidDecisionIntervalsWithoutAveragingPercentiles() {
    ReviewProjectTimeSpentStatRepository.SummaryProjection projection =
        mock(ReviewProjectTimeSpentStatRepository.SummaryProjection.class);
    when(projection.getWindowCount()).thenReturn(2L);
    when(projection.getDecisionIntervalCount()).thenReturn(100L);
    when(projection.getRapidDecisionIntervalCount()).thenReturn(75L);
    when(projection.getRawDecisionSpanSeconds()).thenReturn(240L);

    LinguistTimeSpentReportWS.SummaryResponse response =
        LinguistTimeSpentReportWS.SummaryResponse.from(projection);

    assertEquals(100L, response.decisionIntervalCount());
    assertEquals(75L, response.rapidDecisionIntervalCount());
    assertEquals(75.0d, response.rapidDecisionIntervalPercent(), 0.001d);
    assertEquals(240L, response.rawDecisionSpanSeconds());
  }

  @Test
  public void scorecardDoesNotCompareSelfReportedTimeWithAnInventedActiveEstimate() {
    ReviewProjectTimeSpentStatRepository.TranslatorScorecardProjection projection =
        mock(ReviewProjectTimeSpentStatRepository.TranslatorScorecardProjection.class);
    when(projection.getWindowCount()).thenReturn(1L);
    when(projection.getSelfReportedSeconds()).thenReturn(300L);
    when(projection.getEstimatedActiveSeconds()).thenReturn(180L);
    when(projection.getRawDecisionSpanSeconds()).thenReturn(180L);
    when(projection.getDecisionIntervalCount()).thenReturn(20L);
    when(projection.getRapidDecisionIntervalCount()).thenReturn(15L);

    LinguistTimeSpentReportWS.TranslatorScorecardResponse response =
        LinguistTimeSpentReportWS.TranslatorScorecardResponse.from(projection);

    assertEquals(180L, response.rawDecisionSpanSeconds());
    assertEquals(20L, response.decisionIntervalCount());
    assertEquals(15L, response.rapidDecisionIntervalCount());
    assertEquals(75.0d, response.rapidDecisionIntervalPercent(), 0.001d);
    assertNull(response.reportedComputedRatio());
  }

  @Test
  public void historicalRowsWithoutCadenceStayCompatible() {
    ReviewProjectTimeSpentStatRepository.SummaryProjection projection =
        mock(ReviewProjectTimeSpentStatRepository.SummaryProjection.class);

    LinguistTimeSpentReportWS.SummaryResponse response =
        LinguistTimeSpentReportWS.SummaryResponse.from(projection);

    assertEquals(0L, response.decisionIntervalCount());
    assertEquals(0L, response.rapidDecisionIntervalCount());
    assertEquals(0.0d, response.rapidDecisionIntervalPercent(), 0.001d);
  }
}
