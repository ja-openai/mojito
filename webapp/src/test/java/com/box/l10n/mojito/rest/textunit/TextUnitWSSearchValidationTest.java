package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchBooleanOperator;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class TextUnitWSSearchValidationTest {

  TextUnitWS textUnitWS = new TextUnitWS();

  @Test
  public void mapsTranslationCreatedDates() throws Exception {
    TextUnitSearchBody body = new TextUnitSearchBody();
    body.setRepositoryIds(new ArrayList<>(Arrays.asList(1L)));
    body.setLocaleTags(new ArrayList<>(Arrays.asList("en")));
    body.setTmTextUnitVariantCreatedBefore(ZonedDateTime.parse("2024-01-01T00:00:00Z"));
    body.setTmTextUnitVariantCreatedAfter(ZonedDateTime.parse("2023-12-01T00:00:00Z"));

    TextUnitSearcherParameters parameters =
        textUnitWS.textUnitSearchBodyToTextUnitSearcherParameters(body);

    assertEquals(
        ZonedDateTime.parse("2024-01-01T00:00:00Z"),
        parameters.getTmTextUnitVariantCreatedBefore());
    assertEquals(
        ZonedDateTime.parse("2023-12-01T00:00:00Z"), parameters.getTmTextUnitVariantCreatedAfter());
  }

  @Test
  public void mapsCompoundTextSearch() throws Exception {
    TextUnitSearchBody body = new TextUnitSearchBody();
    body.setRepositoryIds(new ArrayList<>(Arrays.asList(1L)));
    body.setLocaleTags(new ArrayList<>(Arrays.asList("en")));

    TextUnitTextSearchPredicate sourcePredicate = new TextUnitTextSearchPredicate();
    sourcePredicate.setField(TextUnitTextSearchField.SOURCE);
    sourcePredicate.setValue("source text");

    TextUnitTextSearchPredicate targetPredicate = new TextUnitTextSearchPredicate();
    targetPredicate.setField(TextUnitTextSearchField.TARGET);
    targetPredicate.setValue("target text");

    TextUnitTextSearchPredicate commentPredicate = new TextUnitTextSearchPredicate();
    commentPredicate.setField(TextUnitTextSearchField.COMMENT);
    commentPredicate.setValue("comment text");

    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setOperator(TextUnitTextSearchBooleanOperator.OR);
    textSearch.setPredicates(Arrays.asList(sourcePredicate, targetPredicate, commentPredicate));
    body.setTextSearch(textSearch);

    TextUnitSearcherParameters parameters =
        textUnitWS.textUnitSearchBodyToTextUnitSearcherParameters(body);

    assertEquals(TextUnitTextSearchBooleanOperator.OR, parameters.getTextSearch().getOperator());
    assertEquals(3, parameters.getTextSearch().getPredicates().size());
    assertEquals(
        TextUnitTextSearchField.SOURCE,
        parameters.getTextSearch().getPredicates().get(0).getField());
    assertEquals("target text", parameters.getTextSearch().getPredicates().get(1).getValue());
    assertEquals(
        TextUnitTextSearchField.COMMENT,
        parameters.getTextSearch().getPredicates().get(2).getField());
  }

  @Test
  public void checkTMTextUnitRecordsSuccessMetric() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TMTextUnitIntegrityCheckService integrityCheckService =
        mock(TMTextUnitIntegrityCheckService.class);
    textUnitWS.meterRegistry = meterRegistry;
    textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
    TextUnitCheckBody body = new TextUnitCheckBody();
    body.setTmTextUnitId(321L);
    body.setContent("Bonjour");

    TMTextUnitIntegrityCheckResult result = textUnitWS.checkTMTextUnit(body);

    assertEquals(Boolean.TRUE, result.getCheckResult());
    assertEquals(1.0, integrityCheckDurationCount(meterRegistry, "success"), 0.0);
    verify(integrityCheckService).checkTMTextUnitIntegrity(321L, "Bonjour");
  }

  @Test
  public void checkTMTextUnitRecordsFailureMetric() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TMTextUnitIntegrityCheckService integrityCheckService =
        mock(TMTextUnitIntegrityCheckService.class);
    doThrow(new IntegrityCheckException("Missing placeholder"))
        .when(integrityCheckService)
        .checkTMTextUnitIntegrity(321L, "Bonjour");
    textUnitWS.meterRegistry = meterRegistry;
    textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
    TextUnitCheckBody body = new TextUnitCheckBody();
    body.setTmTextUnitId(321L);
    body.setContent("Bonjour");

    TMTextUnitIntegrityCheckResult result = textUnitWS.checkTMTextUnit(body);

    assertEquals(Boolean.FALSE, result.getCheckResult());
    assertEquals("Missing placeholder", result.getFailureDetail());
    assertEquals(1.0, integrityCheckDurationCount(meterRegistry, "failure"), 0.0);
    verify(integrityCheckService).checkTMTextUnitIntegrity(321L, "Bonjour");
  }

  private double integrityCheckDurationCount(SimpleMeterRegistry meterRegistry, String result) {
    return meterRegistry
        .find("TextUnitWS.integrityCheckDuration")
        .tag("result", result)
        .timer()
        .count();
  }

  @Test
  public void missingIntegrityTextUnitReturnsNotFound() throws Exception {
    TMTextUnitIntegrityCheckService integrityCheckService =
        Mockito.mock(TMTextUnitIntegrityCheckService.class);
    Mockito.doThrow(new TMTextUnitWithIdNotFoundException(123L))
        .when(integrityCheckService)
        .checkTMTextUnitIntegrity(123L, "Bonjour");
    textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(textUnitWS).build();

    mockMvc
        .perform(
            post("/api/textunits/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tmTextUnitId\":123,\"content\":\"Bonjour\"}"))
        .andExpect(status().isNotFound());
  }
}
