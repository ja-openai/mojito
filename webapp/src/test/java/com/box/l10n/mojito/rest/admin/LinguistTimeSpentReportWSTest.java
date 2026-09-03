package com.box.l10n.mojito.rest.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.review.ReviewProjectTimeSpentStatRepository;
import com.box.l10n.mojito.service.review.ReviewProjectTimeSpentStatService;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class LinguistTimeSpentReportWSTest {

  @Test
  public void reportMapsIndependentPagesAndReturnsNextPageFlags() throws Exception {
    ReviewProjectTimeSpentStatService service = mock(ReviewProjectTimeSpentStatService.class);
    when(service.getReport(any()))
        .thenReturn(
            new ReviewProjectTimeSpentStatService.TimeSpentReport(
                null, List.of(), List.of(), List.of(), true, false, true));

    reportMockMvc(service)
        .perform(
            get("/api/admin/linguist-time-spent")
                .param("scorecardPage", "2")
                .param("linguistPage", "3")
                .param("detailPage", "4")
                .param("summaryLimit", "25")
                .param("detailLimit", "50")
                .param("translatorUserId", "7")
                .param("localeBcp47Tag", "fr-FR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.translatorScorecardsHasNext").value(true))
        .andExpect(jsonPath("$.results.linguistsHasNext").value(false))
        .andExpect(jsonPath("$.results.windowsHasNext").value(true));

    ArgumentCaptor<ReviewProjectTimeSpentStatService.TimeSpentReportCriteria> criteria =
        ArgumentCaptor.forClass(ReviewProjectTimeSpentStatService.TimeSpentReportCriteria.class);
    verify(service).getReport(criteria.capture());
    assertEquals(2, criteria.getValue().scorecardPage());
    assertEquals(3, criteria.getValue().linguistPage());
    assertEquals(4, criteria.getValue().detailPage());
    assertEquals(25, criteria.getValue().summaryLimit());
    assertEquals(50, criteria.getValue().detailLimit());
    assertEquals(Long.valueOf(7), criteria.getValue().translatorUserId());
    assertEquals("fr-FR", criteria.getValue().localeBcp47Tag());
  }

  @Test
  public void reportWithoutPaginationParametersKeepsFirstPageDefaults() throws Exception {
    ReviewProjectTimeSpentStatService service = mock(ReviewProjectTimeSpentStatService.class);
    when(service.getReport(any()))
        .thenReturn(
            new ReviewProjectTimeSpentStatService.TimeSpentReport(
                null, List.of(), List.of(), List.of(), false, false, false));

    reportMockMvc(service)
        .perform(get("/api/admin/linguist-time-spent"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.translatorScorecardsHasNext").value(false));

    verify(service).getReport(ReviewProjectTimeSpentStatService.TimeSpentReportCriteria.defaults());
  }

  private MockMvc reportMockMvc(ReviewProjectTimeSpentStatService service) {
    return MockMvcBuilders.standaloneSetup(
            new LinguistTimeSpentReportWS(
                service,
                mock(StructuredBlobStorage.class),
                mock(ObjectMapper.class),
                new LinguistTimeSpentHybridProperties(
                    Duration.ofMinutes(1), Duration.ofMinutes(2), null),
                new TaskExecutorAdapter(Runnable::run)))
        .build();
  }

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
