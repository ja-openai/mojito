package com.box.l10n.mojito.rest.glossary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.glossary.GlossaryImportExportService;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService;
import com.box.l10n.mojito.service.security.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryWSTest {

  @Mock GlossaryManagementService glossaryManagementService;
  @Mock GlossaryImportExportService glossaryImportExportService;
  @Mock GlossaryTermService glossaryTermService;
  @Mock GlossaryTermIndexCurationService glossaryTermIndexCurationService;
  @Mock GlossaryService glossaryService;
  @Mock StructuredBlobStorage structuredBlobStorage;
  @Mock ObjectMapper objectMapper;
  @Mock TermIndexEntriesHybridProperties termIndexEntriesHybridProperties;
  @Mock AsyncTaskExecutor termIndexEntriesHybridExecutor;
  @Mock UserService userService;

  private GlossaryWS glossaryWS;
  private SimpleMeterRegistry meterRegistry;

  @Before
  public void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    glossaryWS =
        new GlossaryWS(
            glossaryManagementService,
            glossaryImportExportService,
            glossaryTermService,
            glossaryTermIndexCurationService,
            glossaryService,
            structuredBlobStorage,
            objectMapper,
            termIndexEntriesHybridProperties,
            termIndexEntriesHybridExecutor,
            userService,
            meterRegistry);
  }

  @Test
  public void matchGlossaryTermsRecordsSuccessMetric() {
    when(glossaryService.findMatchesForRepositoryAndLocale(
            12L, null, null, "fr-FR", "Show mobile previews", null))
        .thenReturn(List.of());

    GlossaryWS.MatchGlossaryTermsResponse response =
        glossaryWS.matchGlossaryTerms(
            new GlossaryWS.MatchGlossaryTermsRequest(
                12L, null, null, "fr-FR", "Show mobile previews", null));

    assertEquals(0, response.matchedTerms().size());
    assertEquals(1.0, matchDurationCount("success", "repository_id"), 0.0);
  }

  @Test
  public void matchGlossaryTermsGroupsRangesByTermId() {
    GlossaryService.GlossaryTerm glossaryTerm = glossaryTerm(34L, "GPT");
    when(glossaryService.findMatchesForRepositoryAndLocale(
            12L, null, null, "fr-FR", "GPT GPT", null))
        .thenReturn(
            List.of(
                new GlossaryService.MatchedGlossaryTerm(
                    glossaryTerm, GlossaryService.MatchType.EXACT, 0, 3, "GPT"),
                new GlossaryService.MatchedGlossaryTerm(
                    glossaryTerm, GlossaryService.MatchType.EXACT, 4, 7, "GPT")));

    GlossaryWS.MatchGlossaryTermsResponse response =
        glossaryWS.matchGlossaryTerms(
            new GlossaryWS.MatchGlossaryTermsRequest(12L, null, null, "fr-FR", "GPT GPT", null));

    assertEquals(1, response.matchedTerms().size());
    GlossaryWS.MatchGlossaryTermsResponse.MatchedGlossaryTermResponse matchedTerm =
        response.matchedTerms().get(0);
    assertEquals(Long.valueOf(34L), matchedTerm.tmTextUnitId());
    assertEquals(0, matchedTerm.startIndex());
    assertEquals(3, matchedTerm.endIndex());
    assertEquals(2, matchedTerm.ranges().size());
    assertEquals(0, matchedTerm.ranges().get(0).startIndex());
    assertEquals(3, matchedTerm.ranges().get(0).endIndex());
    assertEquals(4, matchedTerm.ranges().get(1).startIndex());
    assertEquals(7, matchedTerm.ranges().get(1).endIndex());
  }

  @Test
  public void matchGlossaryTermsRecordsBadRequestMetric() {
    when(glossaryService.findMatchesForRepositoryAndLocale(null, null, null, null, null, null))
        .thenThrow(new IllegalArgumentException("Locale tag is required"));

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> glossaryWS.matchGlossaryTerms(null));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals(1.0, matchDurationCount("bad_request", "missing"), 0.0);
  }

  @Test
  public void matchGlossaryTermsRecordsErrorMetric() {
    when(glossaryService.findMatchesForRepositoryAndLocale(
            null, "mobile", null, "fr-FR", "Show mobile previews", null))
        .thenThrow(new IllegalStateException("Glossary matching failed"));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                glossaryWS.matchGlossaryTerms(
                    new GlossaryWS.MatchGlossaryTermsRequest(
                        null, "mobile", null, "fr-FR", "Show mobile previews", null)));

    assertEquals("Glossary matching failed", exception.getMessage());
    assertEquals(1.0, matchDurationCount("error", "repository_name"), 0.0);
  }

  private double matchDurationCount(String result, String scope) {
    return meterRegistry
        .find(GlossaryWS.MATCH_DURATION_METRIC)
        .tag("result", result)
        .tag("scope", scope)
        .timer()
        .count();
  }

  private GlossaryService.GlossaryTerm glossaryTerm(long tmTextUnitId, String source) {
    return new GlossaryService.GlossaryTerm(
        tmTextUnitId,
        5L,
        "Product",
        source.toLowerCase(),
        source,
        null,
        null,
        null,
        null,
        null,
        "APPROVED",
        null,
        null,
        null,
        false,
        false,
        List.of());
  }
}
