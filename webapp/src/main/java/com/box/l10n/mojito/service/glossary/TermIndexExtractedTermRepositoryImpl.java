package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexOccurrence;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexReview;
import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Pageable;

public class TermIndexExtractedTermRepositoryImpl
    implements TermIndexExtractedTermRepositoryCustom {

  private static final String SORT_HITS = "HITS";
  private static final String SORT_REVIEW_CONFIDENCE_DESC = "REVIEW_CONFIDENCE_DESC";
  private static final String SORT_REVIEW_CONFIDENCE_ASC = "REVIEW_CONFIDENCE_ASC";

  private final EntityManager entityManager;

  public TermIndexExtractedTermRepositoryImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public List<TermIndexExtractedTermRepository.SearchRow> searchEntries(
      boolean repositoryIdsEmpty,
      Collection<Long> repositoryIds,
      String searchQuery,
      String extractionMethod,
      String reviewStatusFilter,
      String reviewAuthorityFilter,
      long minOccurrences,
      ZonedDateTime lastOccurrenceAfter,
      ZonedDateTime lastOccurrenceBefore,
      ZonedDateTime reviewChangedAfter,
      ZonedDateTime reviewChangedBefore,
      String sortBy,
      Pageable pageable) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaQuery<Tuple> query = cb.createTupleQuery();
    Root<TermIndexOccurrence> occurrence = query.from(TermIndexOccurrence.class);
    Join<TermIndexOccurrence, TermIndexExtractedTerm> entry =
        occurrence.join("termIndexExtractedTerm");
    Join<TermIndexExtractedTerm, User> reviewChangedByUser =
        entry.join("reviewChangedByUser", JoinType.LEFT);

    Path<Long> entryId = entry.get("id");
    Path<String> normalizedKey = entry.get("normalizedKey");
    Path<String> displayTerm = entry.get("displayTerm");
    Path<String> sourceLocaleTag = entry.get("sourceLocaleTag");
    Path<ZonedDateTime> createdDate = entry.get("createdDate");
    Path<ZonedDateTime> lastModifiedDate = entry.get("lastModifiedDate");
    Path<String> reviewStatus = entry.get("reviewStatus");
    Path<String> reviewAuthority = entry.get("reviewAuthority");
    Path<String> reviewReason = entry.get("reviewReason");
    Path<String> reviewRationale = entry.get("reviewRationale");
    Path<Integer> reviewConfidence = entry.get("reviewConfidence");
    Path<ZonedDateTime> reviewChangedAt = entry.get("reviewChangedAt");
    Path<Long> reviewChangedByUserId = reviewChangedByUser.get("id");
    Path<String> reviewChangedByUsername = reviewChangedByUser.get("username");
    Path<String> reviewChangedByCommonName = reviewChangedByUser.get("commonName");
    Expression<Long> occurrenceCount = cb.count(occurrence.get("id"));
    Expression<Long> repositoryCount = cb.countDistinct(occurrence.get("repository").get("id"));
    Expression<ZonedDateTime> lastOccurrenceAt =
        cb.function("max", ZonedDateTime.class, occurrence.get("createdDate"));

    query.multiselect(
        entryId.alias("id"),
        normalizedKey.alias("normalizedKey"),
        displayTerm.alias("displayTerm"),
        sourceLocaleTag.alias("sourceLocaleTag"),
        createdDate.alias("createdDate"),
        lastModifiedDate.alias("lastModifiedDate"),
        reviewStatus.alias("reviewStatus"),
        reviewAuthority.alias("reviewAuthority"),
        reviewReason.alias("reviewReason"),
        reviewRationale.alias("reviewRationale"),
        reviewConfidence.alias("reviewConfidence"),
        reviewChangedAt.alias("reviewChangedAt"),
        reviewChangedByUserId.alias("reviewChangedByUserId"),
        reviewChangedByUsername.alias("reviewChangedByUsername"),
        reviewChangedByCommonName.alias("reviewChangedByCommonName"),
        occurrenceCount.alias("occurrenceCount"),
        repositoryCount.alias("repositoryCount"),
        lastOccurrenceAt.alias("lastOccurrenceAt"));

    List<Predicate> predicates = new ArrayList<>();
    if (!repositoryIdsEmpty) {
      predicates.add(occurrence.get("repository").get("id").in(repositoryIds));
    }
    if (searchQuery != null) {
      String searchPattern = "%" + searchQuery.toLowerCase(Locale.ROOT) + "%";
      predicates.add(
          cb.or(
              cb.like(cb.lower(displayTerm), searchPattern),
              cb.like(cb.lower(normalizedKey), searchPattern)));
    }
    if (extractionMethod != null) {
      predicates.add(cb.equal(occurrence.get("extractionMethod"), extractionMethod));
    }
    addReviewStatusFilter(cb, predicates, reviewStatus, reviewStatusFilter);
    addReviewAuthorityFilter(cb, predicates, reviewAuthority, reviewAuthorityFilter);
    if (reviewChangedAfter != null) {
      predicates.add(cb.greaterThanOrEqualTo(reviewChangedAt, reviewChangedAfter));
    }
    if (reviewChangedBefore != null) {
      predicates.add(cb.lessThan(reviewChangedAt, reviewChangedBefore));
    }
    query.where(predicates.toArray(Predicate[]::new));

    List<Expression<?>> groupBy =
        List.of(
            entryId,
            normalizedKey,
            displayTerm,
            sourceLocaleTag,
            createdDate,
            lastModifiedDate,
            reviewStatus,
            reviewAuthority,
            reviewReason,
            reviewRationale,
            reviewConfidence,
            reviewChangedAt,
            reviewChangedByUserId,
            reviewChangedByUsername,
            reviewChangedByCommonName);
    query.groupBy(groupBy);

    List<Predicate> having = new ArrayList<>();
    having.add(cb.greaterThanOrEqualTo(occurrenceCount, minOccurrences));
    if (lastOccurrenceAfter != null) {
      having.add(cb.greaterThanOrEqualTo(lastOccurrenceAt, lastOccurrenceAfter));
    }
    if (lastOccurrenceBefore != null) {
      having.add(cb.lessThan(lastOccurrenceAt, lastOccurrenceBefore));
    }
    query.having(having.toArray(Predicate[]::new));

    query.orderBy(buildSearchOrder(cb, sortBy, reviewConfidence, occurrenceCount, displayTerm));

    TypedQuery<Tuple> typedQuery = entityManager.createQuery(query);
    if (pageable != null && pageable.isPaged()) {
      typedQuery.setFirstResult(Math.toIntExact(pageable.getOffset()));
      typedQuery.setMaxResults(pageable.getPageSize());
    }
    return typedQuery.getResultList().stream()
        .map(row -> (TermIndexExtractedTermRepository.SearchRow) new SearchRowProjection(row))
        .toList();
  }

  private void addReviewStatusFilter(
      CriteriaBuilder cb,
      List<Predicate> predicates,
      Path<String> reviewStatus,
      String reviewStatusFilter) {
    if (TermIndexReview.STATUS_FILTER_ALL.equals(reviewStatusFilter)) {
      return;
    }
    if (reviewStatusFilter == null
        || TermIndexReview.STATUS_FILTER_NON_REJECTED.equals(reviewStatusFilter)) {
      predicates.add(cb.notEqual(reviewStatus, TermIndexReview.STATUS_REJECTED));
      return;
    }
    predicates.add(cb.equal(reviewStatus, reviewStatusFilter));
  }

  private void addReviewAuthorityFilter(
      CriteriaBuilder cb,
      List<Predicate> predicates,
      Path<String> reviewAuthority,
      String reviewAuthorityFilter) {
    if (reviewAuthorityFilter == null
        || TermIndexReview.AUTHORITY_FILTER_ALL.equals(reviewAuthorityFilter)) {
      return;
    }
    predicates.add(cb.equal(reviewAuthority, reviewAuthorityFilter));
  }

  private List<Order> buildSearchOrder(
      CriteriaBuilder cb,
      String sortBy,
      Path<Integer> reviewConfidence,
      Expression<Long> occurrenceCount,
      Path<String> displayTerm) {
    List<Order> orders = new ArrayList<>();
    if (SORT_REVIEW_CONFIDENCE_DESC.equals(sortBy)) {
      orders.add(cb.asc(nullsLast(cb, reviewConfidence)));
      orders.add(cb.desc(reviewConfidence));
    } else if (SORT_REVIEW_CONFIDENCE_ASC.equals(sortBy)) {
      orders.add(cb.asc(nullsLast(cb, reviewConfidence)));
      orders.add(cb.asc(reviewConfidence));
    } else if (!SORT_HITS.equals(sortBy)) {
      orders.add(cb.asc(nullsLast(cb, reviewConfidence)));
      orders.add(cb.desc(reviewConfidence));
    }
    orders.add(cb.desc(occurrenceCount));
    orders.add(cb.asc(cb.lower(displayTerm)));
    return orders;
  }

  private Expression<Integer> nullsLast(CriteriaBuilder cb, Path<Integer> path) {
    return cb.<Integer>selectCase().when(cb.isNull(path), 1).otherwise(0);
  }

  private record SearchRowProjection(
      Long id,
      String normalizedKey,
      String displayTerm,
      String sourceLocaleTag,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      ZonedDateTime reviewChangedAt,
      Long reviewChangedByUserId,
      String reviewChangedByUsername,
      String reviewChangedByCommonName,
      Long occurrenceCount,
      Long repositoryCount,
      ZonedDateTime lastOccurrenceAt)
      implements TermIndexExtractedTermRepository.SearchRow {

    SearchRowProjection(Tuple row) {
      this(
          row.get("id", Long.class),
          row.get("normalizedKey", String.class),
          row.get("displayTerm", String.class),
          row.get("sourceLocaleTag", String.class),
          row.get("createdDate", ZonedDateTime.class),
          row.get("lastModifiedDate", ZonedDateTime.class),
          row.get("reviewStatus", String.class),
          row.get("reviewAuthority", String.class),
          row.get("reviewReason", String.class),
          row.get("reviewRationale", String.class),
          row.get("reviewConfidence", Integer.class),
          row.get("reviewChangedAt", ZonedDateTime.class),
          row.get("reviewChangedByUserId", Long.class),
          row.get("reviewChangedByUsername", String.class),
          row.get("reviewChangedByCommonName", String.class),
          row.get("occurrenceCount", Long.class),
          row.get("repositoryCount", Long.class),
          row.get("lastOccurrenceAt", ZonedDateTime.class));
    }

    @Override
    public Long getId() {
      return id;
    }

    @Override
    public String getNormalizedKey() {
      return normalizedKey;
    }

    @Override
    public String getDisplayTerm() {
      return displayTerm;
    }

    @Override
    public String getSourceLocaleTag() {
      return sourceLocaleTag;
    }

    @Override
    public ZonedDateTime getCreatedDate() {
      return createdDate;
    }

    @Override
    public ZonedDateTime getLastModifiedDate() {
      return lastModifiedDate;
    }

    @Override
    public String getReviewStatus() {
      return reviewStatus;
    }

    @Override
    public String getReviewAuthority() {
      return reviewAuthority;
    }

    @Override
    public String getReviewReason() {
      return reviewReason;
    }

    @Override
    public String getReviewRationale() {
      return reviewRationale;
    }

    @Override
    public Integer getReviewConfidence() {
      return reviewConfidence;
    }

    @Override
    public ZonedDateTime getReviewChangedAt() {
      return reviewChangedAt;
    }

    @Override
    public Long getReviewChangedByUserId() {
      return reviewChangedByUserId;
    }

    @Override
    public String getReviewChangedByUsername() {
      return reviewChangedByUsername;
    }

    @Override
    public String getReviewChangedByCommonName() {
      return reviewChangedByCommonName;
    }

    @Override
    public Long getOccurrenceCount() {
      return occurrenceCount;
    }

    @Override
    public Long getRepositoryCount() {
      return repositoryCount;
    }

    @Override
    public ZonedDateTime getLastOccurrenceAt() {
      return lastOccurrenceAt;
    }
  }
}
