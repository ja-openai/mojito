package com.box.l10n.mojito.service.tm.search.replacement;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.AssetTextUnit;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTestData;
import com.box.l10n.mojito.service.tm.search.SearchType;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitAndWordCount;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParametersForTesting;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import com.box.l10n.mojito.service.tm.search.replacement.hibernatecriteria.HibernateCriteriaTextUnitSearcher;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

public class HibernateCriteriaTextUnitSearcherParityTest extends ServiceTestBase {

  @Autowired TextUnitSearcher textUnitSearcher;

  @Autowired HibernateCriteriaTextUnitSearcher hibernateCriteriaTextUnitSearcher;

  @Autowired AssetTextUnitRepository assetTextUnitRepository;

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Transactional
  @Test
  public void criteriaMatchesCurrentSearcherForCoreMultiLocaleSearch() {
    TMTestData testData = new TMTestData(testIdWatcher);
    TextUnitSearcherParametersForTesting parameters = orderedParameters();
    parameters.setRepositoryIds(testData.repository.getId());
    parameters.setLocaleTags(Arrays.asList("ko-KR", "fr-FR", "fr-CA"));
    parameters.setName("zuora_error_message_verify_state_province");
    parameters.setSource("Please enter a valid state, region or province");

    assertSearchMatchesCurrentSearcher(parameters);
    assertCountMatchesCurrentSearcher(parameters);
  }

  @Transactional
  @Test
  public void criteriaMatchesCurrentSearcherForStatusAndUsedFilters() {
    TMTestData testData = new TMTestData(testIdWatcher);
    TextUnitSearcherParametersForTesting parameters = orderedParameters();
    parameters.setRepositoryIds(testData.repository.getId());
    parameters.setLocaleTags(List.of("ko-KR"));
    parameters.setTarget("올바른 국가, 지역 또는 시/도를 입력하십시오.");
    parameters.setUsedFilter(UsedFilter.USED);
    parameters.setStatusFilter(StatusFilter.TRANSLATED);

    assertSearchMatchesCurrentSearcher(parameters);
    assertCountMatchesCurrentSearcher(parameters);
  }

  @Transactional
  @Test
  public void criteriaMatchesCurrentSearcherForLocationTextSearch() {
    TMTestData testData = new TMTestData(testIdWatcher);
    AssetTextUnit assetTextUnit = testData.createAssetTextUnit1;
    assetTextUnit.setUsages(
        new HashSet<>(Set.of("src/messages.properties:10", "src/messages.properties:20")));
    assetTextUnitRepository.saveAndFlush(assetTextUnit);

    TextUnitTextSearchPredicate sourcePredicate =
        predicate(
            TextUnitTextSearchField.SOURCE,
            SearchType.EXACT,
            "Please enter a valid state, region or province");
    TextUnitTextSearchPredicate locationPredicate =
        predicate(TextUnitTextSearchField.LOCATION, SearchType.CONTAINS, "messages.properties");
    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setPredicates(List.of(sourcePredicate, locationPredicate));

    TextUnitSearcherParametersForTesting parameters = orderedParameters();
    parameters.setOrdered(false);
    parameters.setRepositoryIds(testData.repository.getId());
    parameters.setLocaleTags(List.of("ko-KR"));
    parameters.setTextSearch(textSearch);

    assertSearchMatchesCurrentSearcher(parameters);
  }

  private void assertSearchMatchesCurrentSearcher(TextUnitSearcherParameters parameters) {
    List<TextUnitDTO> currentRows = textUnitSearcher.search(parameters);
    List<TextUnitDTO> criteriaRows = hibernateCriteriaTextUnitSearcher.search(parameters);
    TextUnitSearchResultComparator.Comparison comparison =
        TextUnitSearchResultComparator.compareSearch(
            currentRows,
            criteriaRows,
            TextUnitSearchResultComparator.shouldCompareOrder(parameters));

    assertThat(comparison.matches()).as(comparison.message()).isTrue();
  }

  private void assertCountMatchesCurrentSearcher(TextUnitSearcherParameters parameters) {
    if (parameters instanceof TextUnitSearcherParametersForTesting testingParameters) {
      testingParameters.setOrdered(false);
    }

    TextUnitAndWordCount currentCount = textUnitSearcher.countTextUnitAndWordCount(parameters);
    TextUnitAndWordCount criteriaCount =
        hibernateCriteriaTextUnitSearcher.countTextUnitAndWordCount(parameters);
    TextUnitSearchResultComparator.Comparison comparison =
        TextUnitSearchResultComparator.compareCount(currentCount, criteriaCount);

    assertThat(comparison.matches()).as(comparison.message()).isTrue();
  }

  private TextUnitSearcherParametersForTesting orderedParameters() {
    TextUnitSearcherParametersForTesting parameters = new TextUnitSearcherParametersForTesting();
    parameters.setOrdered(true);
    return parameters;
  }

  private TextUnitTextSearchPredicate predicate(
      TextUnitTextSearchField field, SearchType searchType, String value) {
    TextUnitTextSearchPredicate predicate = new TextUnitTextSearchPredicate();
    predicate.setField(field);
    predicate.setSearchType(searchType);
    predicate.setValue(value);
    return predicate;
  }
}
