package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;

import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchBooleanOperator;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

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

    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setOperator(TextUnitTextSearchBooleanOperator.OR);
    textSearch.setPredicates(Arrays.asList(sourcePredicate, targetPredicate));
    body.setTextSearch(textSearch);

    TextUnitSearcherParameters parameters =
        textUnitWS.textUnitSearchBodyToTextUnitSearcherParameters(body);

    assertEquals(TextUnitTextSearchBooleanOperator.OR, parameters.getTextSearch().getOperator());
    assertEquals(2, parameters.getTextSearch().getPredicates().size());
    assertEquals(
        TextUnitTextSearchField.SOURCE,
        parameters.getTextSearch().getPredicates().get(0).getField());
    assertEquals("target text", parameters.getTextSearch().getPredicates().get(1).getValue());
  }
}
