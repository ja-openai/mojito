package com.box.l10n.mojito.service.tm.search.replacement;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.tm.search.SearchType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchBooleanOperator;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import jakarta.persistence.Tuple;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TextUnitSearchCriteriaSupport {

  public static final String TM_TEXT_UNIT_ID = "tmTextUnitId";
  public static final String TM_TEXT_UNIT_VARIANT_ID = "tmTextUnitVariantId";
  public static final String LOCALE_ID = "localeId";
  public static final String TARGET_LOCALE = "targetLocale";
  public static final String NAME = "name";
  public static final String SOURCE = "source";
  public static final String COMMENT = "comment";
  public static final String TARGET = "target";
  public static final String TARGET_COMMENT = "targetComment";
  public static final String ASSET_ID = "assetId";
  public static final String LAST_SUCCESSFUL_ASSET_EXTRACTION_ID =
      "lastSuccessfulAssetExtractionId";
  public static final String ASSET_EXTRACTION_ID = "assetExtractionId";
  public static final String TM_TEXT_UNIT_CURRENT_VARIANT_ID = "tmTextUnitCurrentVariantId";
  public static final String STATUS = "status";
  public static final String INCLUDED_IN_LOCALIZED_FILE = "includedInLocalizedFile";
  public static final String CREATED_DATE = "createdDate";
  public static final String ASSET_DELETED = "assetDeleted";
  public static final String PLURAL_FORM = "pluralForm";
  public static final String PLURAL_FORM_OTHER = "pluralFormOther";
  public static final String REPOSITORY_NAME = "repositoryName";
  public static final String ASSET_PATH = "assetPath";
  public static final String ASSET_TEXT_UNIT_ID = "assetTextUnitId";
  public static final String TM_TEXT_UNIT_CREATED_DATE = "tmTextUnitCreatedDate";
  public static final String DO_NOT_TRANSLATE = "doNotTranslate";
  public static final String BRANCH_ID = "branchId";
  public static final String USAGES = "usages";
  public static final String TEXT_UNIT_COUNT = "textUnitCount";
  public static final String TEXT_UNIT_WORD_COUNT = "textUnitWordCount";

  private TextUnitSearchCriteriaSupport() {}

  public static boolean usesAssetTextUnitUsages(TextUnitSearcherParameters searchParameters) {
    if (searchParameters.getAssetTextUnitUsages() != null) {
      return true;
    }

    TextUnitTextSearch textSearch = searchParameters.getTextSearch();
    return textSearch != null
        && textSearch.getPredicates() != null
        && textSearch.getPredicates().stream()
            .anyMatch(
                predicate ->
                    predicate != null
                        && TextUnitTextSearchField.LOCATION.equals(predicate.getField()));
  }

  public static TextUnitTextSearch effectiveTextSearch(
      TextUnitSearcherParameters searchParameters) {
    if (searchParameters.getTextSearch() != null
        && searchParameters.getTextSearch().getPredicates() != null
        && !searchParameters.getTextSearch().getPredicates().isEmpty()) {
      return searchParameters.getTextSearch();
    }

    List<TextUnitTextSearchPredicate> predicates = new ArrayList<>();
    SearchType legacySearchType =
        searchParameters.getSearchType() == null
            ? SearchType.EXACT
            : searchParameters.getSearchType();

    addLegacyPredicate(
        predicates,
        TextUnitTextSearchField.STRING_ID,
        legacySearchType,
        searchParameters.getName());
    addLegacyPredicate(
        predicates, TextUnitTextSearchField.SOURCE, legacySearchType, searchParameters.getSource());
    addLegacyPredicate(
        predicates, TextUnitTextSearchField.TARGET, legacySearchType, searchParameters.getTarget());
    addLegacyPredicate(
        predicates,
        TextUnitTextSearchField.PLURAL_FORM_OTHER,
        legacySearchType,
        searchParameters.getPluralFormOther());

    if (predicates.isEmpty()) {
      return null;
    }

    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setPredicates(predicates);
    return textSearch;
  }

  public static List<TextUnitTextSearchPredicate> predicates(TextUnitTextSearch textSearch) {
    return textSearch == null || textSearch.getPredicates() == null
        ? Collections.emptyList()
        : textSearch.getPredicates();
  }

  public static boolean isOr(TextUnitTextSearch textSearch) {
    return textSearch != null
        && TextUnitTextSearchBooleanOperator.OR.equals(textSearch.getOperator());
  }

  public static String normalizeTextSearchValue(TextUnitTextSearchPredicate predicate) {
    String value = predicate.getValue();
    SearchType searchType =
        predicate.getSearchType() == null ? SearchType.EXACT : predicate.getSearchType();

    switch (predicate.getField()) {
      case SOURCE:
      case TARGET:
        return SearchType.EXACT.equals(searchType) && "\\u0000".equals(value)
            ? ""
            : NormalizationUtils.normalize(value);
      default:
        return value;
    }
  }

  public static List<Long> parseTextUnitIds(String value) {
    List<Long> ids =
        Arrays.stream(value.split("[,\\s]+"))
            .map(String::trim)
            .filter(token -> !token.isEmpty())
            .map(TextUnitSearchCriteriaSupport::parseLongOrNull)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return ids.isEmpty() ? Collections.singletonList(0L) : ids;
  }

  public static String containsPattern(String value) {
    String escaped = value.replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  public static TextUnitDTO toTextUnitDTO(Tuple tuple, boolean mapAssetTextUnitUsages) {
    TextUnitDTO dto = new TextUnitDTO();
    dto.setTmTextUnitId(tuple.get(TM_TEXT_UNIT_ID, Long.class));
    dto.setTmTextUnitVariantId(tuple.get(TM_TEXT_UNIT_VARIANT_ID, Long.class));
    dto.setLocaleId(tuple.get(LOCALE_ID, Long.class));
    dto.setTargetLocale(tuple.get(TARGET_LOCALE, String.class));
    dto.setName(tuple.get(NAME, String.class));
    dto.setSource(tuple.get(SOURCE, String.class));
    dto.setComment(tuple.get(COMMENT, String.class));
    dto.setTarget(tuple.get(TARGET, String.class));
    dto.setTargetComment(tuple.get(TARGET_COMMENT, String.class));
    dto.setAssetId(tuple.get(ASSET_ID, Long.class));
    dto.setLastSuccessfulAssetExtractionId(
        tuple.get(LAST_SUCCESSFUL_ASSET_EXTRACTION_ID, Long.class));
    dto.setAssetExtractionId(tuple.get(ASSET_EXTRACTION_ID, Long.class));
    dto.setTmTextUnitCurrentVariantId(tuple.get(TM_TEXT_UNIT_CURRENT_VARIANT_ID, Long.class));
    dto.setStatus(toStatus(tuple.get(STATUS)));
    dto.setIncludedInLocalizedFile(Boolean.TRUE.equals(tuple.get(INCLUDED_IN_LOCALIZED_FILE)));
    dto.setCreatedDate(tuple.get(CREATED_DATE, ZonedDateTime.class));
    dto.setAssetDeleted(Boolean.TRUE.equals(tuple.get(ASSET_DELETED)));
    dto.setPluralForm(tuple.get(PLURAL_FORM, String.class));
    dto.setPluralFormOther(tuple.get(PLURAL_FORM_OTHER, String.class));
    dto.setRepositoryName(tuple.get(REPOSITORY_NAME, String.class));
    dto.setAssetPath(tuple.get(ASSET_PATH, String.class));
    dto.setAssetTextUnitId(tuple.get(ASSET_TEXT_UNIT_ID, Long.class));
    dto.setTmTextUnitCreatedDate(tuple.get(TM_TEXT_UNIT_CREATED_DATE, ZonedDateTime.class));
    dto.setDoNotTranslate(Boolean.TRUE.equals(tuple.get(DO_NOT_TRANSLATE)));
    dto.setBranchId(tuple.get(BRANCH_ID, Long.class));

    if (mapAssetTextUnitUsages) {
      dto.setAssetTextUnitUsages(tuple.get(USAGES, String.class));
    }

    return dto;
  }

  public static TMTextUnitVariant.Status toStatus(Object status) {
    if (status == null) {
      return TMTextUnitVariant.Status.TRANSLATION_NEEDED;
    }
    if (status instanceof TMTextUnitVariant.Status variantStatus) {
      return variantStatus;
    }
    return TMTextUnitVariant.Status.valueOf(status.toString());
  }

  private static void addLegacyPredicate(
      List<TextUnitTextSearchPredicate> predicates,
      TextUnitTextSearchField field,
      SearchType searchType,
      String value) {
    if (value == null) {
      return;
    }

    TextUnitTextSearchPredicate predicate = new TextUnitTextSearchPredicate();
    predicate.setField(field);
    predicate.setSearchType(searchType);
    predicate.setValue(value);
    predicates.add(predicate);
  }

  private static Long parseLongOrNull(String token) {
    try {
      return Long.parseLong(token);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
