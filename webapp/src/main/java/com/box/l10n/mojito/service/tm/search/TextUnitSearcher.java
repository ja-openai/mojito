package com.box.l10n.mojito.service.tm.search;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetTextUnit;
import com.box.l10n.mojito.entity.AssetTextUnitToTMTextUnit;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PluralForm;
import com.box.l10n.mojito.entity.PluralFormForLocale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.service.NormalizationUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Selection;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.hibernate.Session;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCrossJoin;
import org.hibernate.query.criteria.JpaEntityJoin;
import org.hibernate.query.criteria.JpaJoin;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.criteria.JpaSetJoin;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Text unit searcher implemented with Hibernate's Criteria extension API.
 *
 * <p>It uses Hibernate 6.6 entity joins to preserve the current native searcher's left joins
 * against entities that are not directly mapped as collections from {@link TMTextUnit}.
 */
@Service
public class TextUnitSearcher {

  private static final String TM_TEXT_UNIT_ID = "tmTextUnitId";
  private static final String TM_TEXT_UNIT_VARIANT_ID = "tmTextUnitVariantId";
  private static final String LOCALE_ID = "localeId";
  private static final String TARGET_LOCALE = "targetLocale";
  private static final String NAME = "name";
  private static final String SOURCE = "source";
  private static final String COMMENT = "comment";
  private static final String TARGET = "target";
  private static final String TARGET_COMMENT = "targetComment";
  private static final String ASSET_ID = "assetId";
  private static final String LAST_SUCCESSFUL_ASSET_EXTRACTION_ID =
      "lastSuccessfulAssetExtractionId";
  private static final String ASSET_EXTRACTION_ID = "assetExtractionId";
  private static final String TM_TEXT_UNIT_CURRENT_VARIANT_ID = "tmTextUnitCurrentVariantId";
  private static final String STATUS = "status";
  private static final String INCLUDED_IN_LOCALIZED_FILE = "includedInLocalizedFile";
  private static final String CREATED_DATE = "createdDate";
  private static final String ASSET_DELETED = "assetDeleted";
  private static final String PLURAL_FORM = "pluralForm";
  private static final String PLURAL_FORM_OTHER = "pluralFormOther";
  private static final String REPOSITORY_NAME = "repositoryName";
  private static final String ASSET_PATH = "assetPath";
  private static final String ASSET_TEXT_UNIT_ID = "assetTextUnitId";
  private static final String TM_TEXT_UNIT_CREATED_DATE = "tmTextUnitCreatedDate";
  private static final String DO_NOT_TRANSLATE = "doNotTranslate";
  private static final String BRANCH_ID = "branchId";
  private static final String USAGES = "usages";
  private static final String TEXT_UNIT_COUNT = "textUnitCount";
  private static final String TEXT_UNIT_WORD_COUNT = "textUnitWordCount";

  private final EntityManager entityManager;

  public TextUnitSearcher(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Retryable(backoff = @Backoff(delay = 500, multiplier = 2))
  @Transactional(readOnly = true)
  public List<TextUnitDTO> search(TextUnitSearcherParameters searchParameters) {
    HibernateCriteriaBuilder cb = criteriaBuilder();
    JpaCriteriaQuery<Tuple> query = cb.createTupleQuery();
    SearchContext context = buildSearchContext(cb, query, searchParameters);
    boolean usesAssetTextUnitUsages = usesAssetTextUnitUsages(searchParameters);

    addSearchProjection(query, cb, context, usesAssetTextUnitUsages);
    addOrdering(query, cb, context, searchParameters);

    TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
    if (searchParameters.getOffset() != null) {
      typedQuery.setFirstResult(searchParameters.getOffset());
    }
    if (searchParameters.getLimit() != null) {
      typedQuery.setMaxResults(searchParameters.getLimit());
    }

    return typedQuery.getResultList().stream()
        .map(tuple -> toTextUnitDTO(tuple, searchParameters.getAssetTextUnitUsages() != null))
        .collect(Collectors.toList());
  }

  @Retryable(backoff = @Backoff(delay = 500, multiplier = 2))
  @Transactional(readOnly = true)
  public TextUnitAndWordCount countTextUnitAndWordCount(
      TextUnitSearcherParameters searchParameters) {
    HibernateCriteriaBuilder cb = criteriaBuilder();
    JpaCriteriaQuery<Tuple> query = cb.createTupleQuery();
    SearchContext context = buildSearchContext(cb, query, searchParameters);

    query.multiselect(
        cb.count(context.textUnit.get("id")).alias(TEXT_UNIT_COUNT),
        cb.coalesce(cb.sumAsLong(context.textUnit.get("wordCount")), 0L)
            .alias(TEXT_UNIT_WORD_COUNT));

    Tuple tuple = entityManager.createQuery(query).getSingleResult();
    Long textUnitCount = tuple.get(TEXT_UNIT_COUNT, Long.class);
    Long textUnitWordCount = tuple.get(TEXT_UNIT_WORD_COUNT, Long.class);
    return new TextUnitAndWordCount(
        textUnitCount == null ? 0 : textUnitCount,
        textUnitWordCount == null ? 0 : textUnitWordCount);
  }

  private HibernateCriteriaBuilder criteriaBuilder() {
    return entityManager.unwrap(Session.class).getCriteriaBuilder();
  }

  private SearchContext buildSearchContext(
      CriteriaBuilder cb,
      JpaCriteriaQuery<Tuple> query,
      TextUnitSearcherParameters searchParameters) {
    SearchContext context = new SearchContext();
    context.textUnit = query.from(TMTextUnit.class);
    context.locale = context.textUnit.crossJoin(Locale.class);
    context.asset = context.textUnit.join("asset");
    context.repository = context.asset.join("repository");

    context.repositoryLocale = context.repository.join(RepositoryLocale.class, SqmJoinType.INNER);
    context.repositoryLocale.on(
        cb.equal(context.repositoryLocale.get("locale"), context.locale),
        cb.equal(context.repositoryLocale.get("repository"), context.repository),
        repositoryLocaleParentPredicate(cb, context, searchParameters));

    context.currentVariant =
        context.textUnit.join(TMTextUnitCurrentVariant.class, SqmJoinType.LEFT);
    context.currentVariant.on(
        cb.equal(context.currentVariant.get("tmTextUnit"), context.textUnit),
        cb.equal(context.currentVariant.get("locale"), context.locale));
    context.variant = context.currentVariant.join("tmTextUnitVariant", JoinType.LEFT);

    context.mapping = context.textUnit.join(AssetTextUnitToTMTextUnit.class, SqmJoinType.LEFT);
    context.mapping.on(
        cb.equal(context.mapping.get("tmTextUnit"), context.textUnit),
        cb.equal(
            context.mapping.get("assetExtraction"),
            context.asset.get("lastSuccessfulAssetExtraction")));
    context.assetTextUnit = context.mapping.join("assetTextUnit", JoinType.LEFT);

    context.pluralForm = context.textUnit.join("pluralForm", JoinType.LEFT);
    context.pluralFormForLocale =
        context.textUnit.join(PluralFormForLocale.class, SqmJoinType.LEFT);
    context.pluralFormForLocale.on(
        cb.equal(context.pluralFormForLocale.get("pluralForm"), context.textUnit.get("pluralForm")),
        cb.equal(context.pluralFormForLocale.get("locale"), context.locale));

    if (usesGlossaryTermMetadata(searchParameters)) {
      context.glossaryTermMetadata =
          context.textUnit.join(GlossaryTermMetadata.class, SqmJoinType.LEFT);
      context.glossaryTermMetadata.on(
          cb.equal(context.glossaryTermMetadata.get("tmTextUnit"), context.textUnit));
    }

    if (usesAssetTextUnitUsages(searchParameters)) {
      context.usage = context.assetTextUnit.joinSet("usages", JoinType.LEFT);
    }

    List<Predicate> predicates = buildPredicates(cb, context, searchParameters);
    if (!predicates.isEmpty()) {
      query.where(predicates.toArray(Predicate[]::new));
    }
    return context;
  }

  private Predicate repositoryLocaleParentPredicate(
      CriteriaBuilder cb, SearchContext context, TextUnitSearcherParameters searchParameters) {
    if (searchParameters.isForRootLocale()) {
      return cb.isNull(context.repositoryLocale.get("parentLocale"));
    }
    if (searchParameters.isRootLocaleExcluded()) {
      return cb.isNotNull(context.repositoryLocale.get("parentLocale"));
    }
    return cb.conjunction();
  }

  private void addSearchProjection(
      JpaCriteriaQuery<Tuple> query,
      HibernateCriteriaBuilder cb,
      SearchContext context,
      boolean usesAssetTextUnitUsages) {
    List<Selection<?>> selections = new ArrayList<>();
    List<Expression<?>> groupBy = new ArrayList<>();

    add(selections, groupBy, context.textUnit.get("id"), TM_TEXT_UNIT_ID);
    add(selections, groupBy, context.variant.get("id"), TM_TEXT_UNIT_VARIANT_ID);
    add(selections, groupBy, context.locale.get("id"), LOCALE_ID);
    add(selections, groupBy, context.locale.get("bcp47Tag"), TARGET_LOCALE);
    add(selections, groupBy, context.textUnit.get("name"), NAME);
    add(selections, groupBy, context.textUnit.get("content"), SOURCE);
    add(selections, groupBy, context.textUnit.get("comment"), COMMENT);
    add(selections, groupBy, context.variant.get("content"), TARGET);
    add(selections, groupBy, context.variant.get("comment"), TARGET_COMMENT);
    add(selections, groupBy, context.asset.get("id"), ASSET_ID);
    add(
        selections,
        groupBy,
        context.asset.get("lastSuccessfulAssetExtraction").get("id"),
        LAST_SUCCESSFUL_ASSET_EXTRACTION_ID);
    add(
        selections,
        groupBy,
        context.assetTextUnit.get("assetExtraction").get("id"),
        ASSET_EXTRACTION_ID);
    add(selections, groupBy, context.currentVariant.get("id"), TM_TEXT_UNIT_CURRENT_VARIANT_ID);
    add(selections, groupBy, context.variant.get("status"), STATUS);
    add(
        selections,
        groupBy,
        context.variant.get("includedInLocalizedFile"),
        INCLUDED_IN_LOCALIZED_FILE);
    add(selections, groupBy, context.variant.get("createdDate"), CREATED_DATE);
    add(selections, groupBy, context.asset.get("deleted"), ASSET_DELETED);
    add(selections, groupBy, context.pluralForm.get("name"), PLURAL_FORM);
    add(selections, groupBy, context.textUnit.get("pluralFormOther"), PLURAL_FORM_OTHER);
    add(selections, groupBy, context.repository.get("name"), REPOSITORY_NAME);
    add(selections, groupBy, context.asset.get("path"), ASSET_PATH);
    add(selections, groupBy, context.assetTextUnit.get("id"), ASSET_TEXT_UNIT_ID);
    add(selections, groupBy, context.textUnit.get("createdDate"), TM_TEXT_UNIT_CREATED_DATE);
    add(selections, groupBy, context.assetTextUnit.get("doNotTranslate"), DO_NOT_TRANSLATE);
    add(selections, groupBy, context.assetTextUnit.get("branch").get("id"), BRANCH_ID);

    if (usesAssetTextUnitUsages) {
      selections.add(cb.listagg(cb.asc(context.usage), context.usage, ",").alias(USAGES));
      query.groupBy(groupBy);
    }

    query.multiselect(selections);
  }

  private void add(
      List<Selection<?>> selections,
      List<Expression<?>> groupBy,
      Expression<?> expression,
      String alias) {
    selections.add(expression.alias(alias));
    groupBy.add(expression);
  }

  private List<Predicate> buildPredicates(
      CriteriaBuilder cb, SearchContext context, TextUnitSearcherParameters searchParameters) {
    List<Predicate> predicates = new ArrayList<>();

    if (searchParameters.isPluralFormsFiltered()) {
      predicates.add(
          cb.or(
              cb.isNotNull(context.pluralFormForLocale.get("pluralForm")),
              cb.isNull(context.textUnit.get("pluralForm"))));
    }

    if (searchParameters.getRepositoryIds() != null
        && !searchParameters.getRepositoryIds().isEmpty()) {
      predicates.add(context.repository.get("id").in(searchParameters.getRepositoryIds()));
    }
    if (searchParameters.getRepositoryNames() != null
        && !searchParameters.getRepositoryNames().isEmpty()) {
      predicates.add(context.repository.get("name").in(searchParameters.getRepositoryNames()));
    }

    Predicate textSearchPredicate = textSearchPredicate(cb, context, searchParameters);
    if (textSearchPredicate != null) {
      predicates.add(textSearchPredicate);
    }

    if (searchParameters.getMd5() != null) {
      predicates.add(cb.equal(context.textUnit.get("md5"), searchParameters.getMd5()));
    }
    if (searchParameters.getAssetTextUnitUsages() != null) {
      predicates.add(
          searchTypePredicate(
              cb,
              context.usage,
              null,
              searchParameters.getSearchType(),
              searchParameters.getAssetTextUnitUsages()));
    }
    if (searchParameters.getAssetPath() != null) {
      predicates.add(
          searchTypePredicate(
              cb,
              context.asset.get("path"),
              null,
              searchParameters.getSearchType(),
              searchParameters.getAssetPath()));
    }
    if (searchParameters.getAssetId() != null) {
      predicates.add(
          cb.equal(context.textUnit.get("asset").get("id"), searchParameters.getAssetId()));
    }
    if (searchParameters.getLocaleTags() != null && !searchParameters.getLocaleTags().isEmpty()) {
      predicates.add(context.locale.get("bcp47Tag").in(searchParameters.getLocaleTags()));
    }
    if (searchParameters.getLocaleId() != null) {
      predicates.add(cb.equal(context.locale.get("id"), searchParameters.getLocaleId()));
    }
    if (searchParameters.getToBeFullyTranslatedFilter() != null) {
      predicates.add(
          cb.equal(
              context.repositoryLocale.get("toBeFullyTranslated"),
              searchParameters.getToBeFullyTranslatedFilter()));
    }
    if (searchParameters.getTmTextUnitIds() != null) {
      predicates.add(context.textUnit.get("id").in(searchParameters.getTmTextUnitIds()));
    }
    if (searchParameters.getTmId() != null) {
      predicates.add(cb.equal(context.textUnit.get("tm").get("id"), searchParameters.getTmId()));
    }
    if (searchParameters.getPluralFormId() != null) {
      predicates.add(
          cb.equal(
              context.textUnit.get("pluralForm").get("id"), searchParameters.getPluralFormId()));
    }
    if (searchParameters.isPluralFormsExcluded()) {
      predicates.add(cb.isNull(context.textUnit.get("pluralForm")));
    }
    if (searchParameters.getDoNotTranslateFilter() != null) {
      predicates.add(
          cb.equal(
              context.assetTextUnit.get("doNotTranslate"),
              searchParameters.getDoNotTranslateFilter()));
    }
    if (searchParameters.getBranchId() != null) {
      predicates.add(
          cb.equal(context.assetTextUnit.get("branch").get("id"), searchParameters.getBranchId()));
    }
    if (searchParameters.getSkipTextUnitWithPattern() != null) {
      predicates.add(
          cb.not(
              cb.like(
                  cb.lower(context.textUnit.get("name")),
                  searchParameters.getSkipTextUnitWithPattern().toLowerCase())));
    }
    if (searchParameters.getIncludeTextUnitsWithPattern() != null) {
      predicates.add(
          cb.like(
              cb.lower(context.textUnit.get("name")),
              searchParameters.getIncludeTextUnitsWithPattern().toLowerCase()));
    }
    if (searchParameters.getSkipAssetPathWithPattern() != null) {
      predicates.add(
          cb.not(
              cb.like(
                  cb.lower(context.asset.get("path")),
                  searchParameters.getSkipAssetPathWithPattern().toLowerCase())));
    }

    addStatusFilter(cb, context, predicates, searchParameters.getStatusFilter());
    addGlossaryStatusFilter(cb, context, predicates, searchParameters.getGlossaryStatusFilter());
    addUsedFilter(cb, context, predicates, searchParameters.getUsedFilter());

    if (searchParameters.getTmTextUnitCreatedBefore() != null) {
      predicates.add(
          cb.lessThanOrEqualTo(
              context.textUnit.get("createdDate"), searchParameters.getTmTextUnitCreatedBefore()));
    }
    if (searchParameters.getTmTextUnitCreatedAfter() != null) {
      predicates.add(
          cb.greaterThanOrEqualTo(
              context.textUnit.get("createdDate"), searchParameters.getTmTextUnitCreatedAfter()));
    }
    if (searchParameters.getTmTextUnitVariantId() != null) {
      predicates.add(
          cb.equal(context.variant.get("id"), searchParameters.getTmTextUnitVariantId()));
    }
    if (searchParameters.getTmTextUnitVariantCreatedBefore() != null) {
      predicates.add(
          cb.lessThanOrEqualTo(
              context.variant.get("createdDate"),
              searchParameters.getTmTextUnitVariantCreatedBefore()));
    }
    if (searchParameters.getTmTextUnitVariantCreatedAfter() != null) {
      predicates.add(
          cb.greaterThanOrEqualTo(
              context.variant.get("createdDate"),
              searchParameters.getTmTextUnitVariantCreatedAfter()));
    }

    return predicates.stream().filter(Objects::nonNull).toList();
  }

  private Predicate textSearchPredicate(
      CriteriaBuilder cb, SearchContext context, TextUnitSearcherParameters searchParameters) {
    TextUnitTextSearch textSearch = effectiveTextSearch(searchParameters);
    List<Predicate> predicates =
        predicates(textSearch).stream()
            .map(predicate -> textSearchPredicate(cb, context, predicate))
            .filter(Objects::nonNull)
            .toList();

    if (predicates.isEmpty()) {
      return null;
    }
    return isOr(textSearch)
        ? cb.or(predicates.toArray(Predicate[]::new))
        : cb.and(predicates.toArray(Predicate[]::new));
  }

  private Predicate textSearchPredicate(
      CriteriaBuilder cb, SearchContext context, TextUnitTextSearchPredicate predicate) {
    if (predicate == null || predicate.getField() == null || predicate.getValue() == null) {
      return null;
    }

    SearchType searchType =
        predicate.getSearchType() == null ? SearchType.EXACT : predicate.getSearchType();
    String value = normalizeTextSearchValue(predicate);

    return switch (predicate.getField()) {
      case STRING_ID ->
          searchTypePredicate(cb, context.textUnit.get("name"), null, searchType, value);
      case SOURCE ->
          searchTypePredicate(
              cb,
              context.textUnit.get("content"),
              context.textUnit.get("contentMd5"),
              searchType,
              value);
      case TARGET ->
          searchTypePredicate(
              cb,
              context.variant.get("content"),
              context.variant.get("contentMD5"),
              searchType,
              value);
      case COMMENT ->
          searchTypePredicate(cb, context.textUnit.get("comment"), null, searchType, value);
      case ASSET -> searchTypePredicate(cb, context.asset.get("path"), null, searchType, value);
      case LOCATION -> searchTypePredicate(cb, context.usage, null, searchType, value);
      case PLURAL_FORM_OTHER ->
          searchTypePredicate(cb, context.textUnit.get("pluralFormOther"), null, searchType, value);
      case TM_TEXT_UNIT_IDS -> context.textUnit.get("id").in(parseTextUnitIds(value));
    };
  }

  private Predicate searchTypePredicate(
      CriteriaBuilder cb,
      Expression<String> column,
      Expression<String> md5Column,
      SearchType searchType,
      String value) {
    SearchType effectiveSearchType = searchType == null ? SearchType.EXACT : searchType;
    return switch (effectiveSearchType) {
      case EXACT ->
          md5Column == null
              ? cb.equal(column, value)
              : cb.equal(md5Column, DigestUtils.md5Hex(value));
      case CONTAINS -> cb.like(column, containsPattern(value), '\\');
      case ILIKE -> cb.like(cb.lower(column), value.toLowerCase(), '\\');
      case REGEX -> cb.isTrue(cb.function("REGEXP_LIKE", Boolean.class, column, cb.literal(value)));
    };
  }

  private void addStatusFilter(
      CriteriaBuilder cb,
      SearchContext context,
      List<Predicate> predicates,
      StatusFilter statusFilter) {
    if (statusFilter == null) {
      return;
    }

    switch (statusFilter) {
      case ALL:
        break;
      case NOT_REJECTED:
        predicates.add(cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.TRUE));
        break;
      case REJECTED:
        predicates.add(cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.FALSE));
        break;
      case REVIEW_NEEDED:
        predicates.add(
            cb.equal(context.variant.get("status"), TMTextUnitVariant.Status.REVIEW_NEEDED));
        break;
      case REVIEW_NEEDED_OR_REJECTED:
        predicates.add(
            cb.or(
                cb.equal(context.variant.get("status"), TMTextUnitVariant.Status.REVIEW_NEEDED),
                cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.FALSE)));
        break;
      case REVIEW_NOT_NEEDED:
        predicates.add(
            cb.notEqual(context.variant.get("status"), TMTextUnitVariant.Status.REVIEW_NEEDED));
        break;
      case TRANSLATION_NEEDED:
        predicates.add(
            cb.equal(context.variant.get("status"), TMTextUnitVariant.Status.TRANSLATION_NEEDED));
        break;
      case NOT_ACCEPTED:
        predicates.add(
            cb.or(
                cb.isNull(context.variant.get("id")),
                cb.notEqual(context.variant.get("status"), TMTextUnitVariant.Status.APPROVED),
                cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.FALSE)));
        break;
      case TRANSLATED:
        predicates.add(cb.isNotNull(context.variant.get("id")));
        break;
      case APPROVED_AND_NOT_REJECTED:
        predicates.add(cb.equal(context.variant.get("status"), TMTextUnitVariant.Status.APPROVED));
        predicates.add(cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.TRUE));
        break;
      case APPROVED_OR_NEEDS_REVIEW_AND_NOT_REJECTED:
        predicates.add(
            context
                .variant
                .get("status")
                .in(
                    Arrays.asList(
                        TMTextUnitVariant.Status.APPROVED,
                        TMTextUnitVariant.Status.REVIEW_NEEDED)));
        predicates.add(cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.TRUE));
        break;
      case TRANSLATED_AND_NOT_REJECTED:
        predicates.add(cb.isNotNull(context.variant.get("id")));
        predicates.add(cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.TRUE));
        break;
      case UNTRANSLATED:
        predicates.add(cb.isNull(context.variant.get("id")));
        break;
      case FOR_TRANSLATION:
        predicates.add(
            cb.or(
                cb.isNull(context.variant.get("id")),
                cb.equal(
                    context.variant.get("status"), TMTextUnitVariant.Status.TRANSLATION_NEEDED),
                cb.equal(context.variant.get("includedInLocalizedFile"), Boolean.FALSE)));
        break;
    }
  }

  private void addUsedFilter(
      CriteriaBuilder cb,
      SearchContext context,
      List<Predicate> predicates,
      UsedFilter usedFilter) {
    if (usedFilter == null) {
      return;
    }

    if (UsedFilter.USED.equals(usedFilter)) {
      predicates.add(cb.isNotNull(context.assetTextUnit.get("id")));
      predicates.add(cb.equal(context.asset.get("deleted"), Boolean.FALSE));
    } else {
      predicates.add(
          cb.or(
              cb.isNull(context.assetTextUnit.get("id")),
              cb.equal(context.asset.get("deleted"), Boolean.TRUE)));
    }
  }

  private void addGlossaryStatusFilter(
      CriteriaBuilder cb,
      SearchContext context,
      List<Predicate> predicates,
      GlossaryStatusFilter glossaryStatusFilter) {
    if (glossaryStatusFilter == null || GlossaryStatusFilter.ALL.equals(glossaryStatusFilter)) {
      return;
    }

    predicates.add(
        cb.or(
            cb.isNull(context.glossaryTermMetadata.get("id")),
            cb.equal(context.glossaryTermMetadata.get("status"), glossaryStatusFilter.name())));
  }

  private void addOrdering(
      JpaCriteriaQuery<Tuple> query,
      CriteriaBuilder cb,
      SearchContext context,
      TextUnitSearcherParameters searchParameters) {
    List<Order> orders = new ArrayList<>();
    if (searchParameters.isOrderedByTextUnitID()) {
      orders.add(cb.asc(context.textUnit.get("id")));
    }
    if (searchParameters instanceof TextUnitSearcherParametersForTesting testingParameters
        && testingParameters.isOrdered()) {
      orders.clear();
      orders.add(cb.asc(context.textUnit.get("id")));
      orders.add(cb.asc(context.locale.get("id")));
    }
    if (!orders.isEmpty()) {
      query.orderBy(orders);
    }
  }

  private static boolean usesAssetTextUnitUsages(TextUnitSearcherParameters searchParameters) {
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

  private static boolean usesGlossaryTermMetadata(TextUnitSearcherParameters searchParameters) {
    GlossaryStatusFilter glossaryStatusFilter = searchParameters.getGlossaryStatusFilter();
    return glossaryStatusFilter != null && !GlossaryStatusFilter.ALL.equals(glossaryStatusFilter);
  }

  private static TextUnitTextSearch effectiveTextSearch(
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

  private static List<TextUnitTextSearchPredicate> predicates(TextUnitTextSearch textSearch) {
    return textSearch == null || textSearch.getPredicates() == null
        ? Collections.emptyList()
        : textSearch.getPredicates();
  }

  private static boolean isOr(TextUnitTextSearch textSearch) {
    return textSearch != null
        && TextUnitTextSearchBooleanOperator.OR.equals(textSearch.getOperator());
  }

  private static String normalizeTextSearchValue(TextUnitTextSearchPredicate predicate) {
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

  private static List<Long> parseTextUnitIds(String value) {
    List<Long> ids =
        Arrays.stream(value.split("[,\\s]+"))
            .map(String::trim)
            .filter(token -> !token.isEmpty())
            .map(TextUnitSearcher::parseLongOrNull)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return ids.isEmpty() ? Collections.singletonList(0L) : ids;
  }

  private static String containsPattern(String value) {
    String escaped = value.replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static TextUnitDTO toTextUnitDTO(Tuple tuple, boolean mapAssetTextUnitUsages) {
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

  private static TMTextUnitVariant.Status toStatus(Object status) {
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

  private static class SearchContext {
    JpaRoot<TMTextUnit> textUnit;
    JpaCrossJoin<Locale> locale;
    JpaJoin<TMTextUnit, Asset> asset;
    JpaJoin<Asset, Repository> repository;
    JpaEntityJoin<RepositoryLocale> repositoryLocale;
    JpaEntityJoin<TMTextUnitCurrentVariant> currentVariant;
    JpaJoin<TMTextUnitCurrentVariant, TMTextUnitVariant> variant;
    JpaEntityJoin<AssetTextUnitToTMTextUnit> mapping;
    JpaJoin<AssetTextUnitToTMTextUnit, AssetTextUnit> assetTextUnit;
    JpaJoin<TMTextUnit, PluralForm> pluralForm;
    JpaEntityJoin<PluralFormForLocale> pluralFormForLocale;
    JpaEntityJoin<GlossaryTermMetadata> glossaryTermMetadata;
    JpaSetJoin<AssetTextUnit, String> usage;
  }
}
