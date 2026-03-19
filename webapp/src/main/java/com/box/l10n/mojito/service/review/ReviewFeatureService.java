package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewFeatureService {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  private final ReviewFeatureRepository reviewFeatureRepository;
  private final RepositoryRepository repositoryRepository;
  private final UserService userService;

  public ReviewFeatureService(
      ReviewFeatureRepository reviewFeatureRepository,
      RepositoryRepository repositoryRepository,
      UserService userService) {
    this.reviewFeatureRepository = reviewFeatureRepository;
    this.repositoryRepository = repositoryRepository;
    this.userService = userService;
  }

  @Transactional(readOnly = true)
  public SearchReviewFeaturesView searchReviewFeatures(
      String searchQuery, Boolean enabled, Integer limit) {
    requireAdmin();
    int resolvedLimit = normalizeLimit(limit);
    Page<ReviewFeatureSummaryRow> page =
        reviewFeatureRepository.searchSummaryRows(
            normalizeSearchQuery(searchQuery), enabled, PageRequest.of(0, resolvedLimit));
    List<SearchReviewFeaturesView.ReviewFeatureSummary> summaries =
        toSummaryViews(page.getContent(), loadRepositoriesByFeatureId(page.getContent()));
    return new SearchReviewFeaturesView(summaries, page.getTotalElements());
  }

  @Transactional(readOnly = true)
  public List<ReviewFeatureOption> getReviewFeatureOptions() {
    requireAdmin();
    return reviewFeatureRepository.findAllOptionRows().stream()
        .map(
            row ->
                new ReviewFeatureOption(row.id(), row.name(), Boolean.TRUE.equals(row.enabled())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ReviewFeatureBatchExportRow> getReviewFeatureBatchExportRows() {
    requireAdmin();
    List<ReviewFeatureOptionRow> optionRows = reviewFeatureRepository.findAllOptionRows();
    if (optionRows.isEmpty()) {
      return List.of();
    }

    List<Long> featureIds = optionRows.stream().map(ReviewFeatureOptionRow::id).toList();
    Map<Long, List<String>> repositoryNamesByFeatureId = new LinkedHashMap<>();
    for (ReviewFeatureRepositoryRow row :
        reviewFeatureRepository.findRepositoryRowsByFeatureIds(featureIds)) {
      repositoryNamesByFeatureId
          .computeIfAbsent(row.reviewFeatureId(), ignored -> new ArrayList<>())
          .add(row.repositoryName());
    }

    return optionRows.stream()
        .map(
            row ->
                new ReviewFeatureBatchExportRow(
                    row.id(),
                    row.name(),
                    Boolean.TRUE.equals(row.enabled()),
                    repositoryNamesByFeatureId.getOrDefault(row.id(), List.of())))
        .toList();
  }

  @Transactional(readOnly = true)
  public ReviewFeatureDetail getReviewFeature(Long featureId) {
    requireAdmin();
    ReviewFeature reviewFeature =
        reviewFeatureRepository
            .findByIdWithRepositories(featureId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review feature not found: " + featureId));
    List<ReviewFeatureDetail.RepositoryRef> repositories =
        reviewFeature.getRepositories().stream()
            .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
            .sorted(
                java.util.Comparator.comparing(Repository::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Repository::getId))
            .map(
                repository ->
                    new ReviewFeatureDetail.RepositoryRef(repository.getId(), repository.getName()))
            .toList();
    return new ReviewFeatureDetail(
        reviewFeature.getId(),
        reviewFeature.getCreatedDate(),
        reviewFeature.getLastModifiedDate(),
        reviewFeature.getName(),
        Boolean.TRUE.equals(reviewFeature.getEnabled()),
        repositories);
  }

  @Transactional
  public ReviewFeatureDetail createReviewFeature(
      String name, Boolean enabled, List<Long> repositoryIds) {
    requireAdmin();
    String normalizedName = normalizeName(name);
    ensureNameAvailable(normalizedName, null);

    ReviewFeature reviewFeature = new ReviewFeature();
    reviewFeature.setName(normalizedName);
    reviewFeature.setEnabled(enabled == null ? Boolean.TRUE : enabled);
    reviewFeature.setRepositories(resolveRepositories(repositoryIds));
    ReviewFeature saved = reviewFeatureRepository.save(reviewFeature);
    return getReviewFeature(saved.getId());
  }

  @Transactional
  public ReviewFeatureDetail updateReviewFeature(
      Long featureId, String name, Boolean enabled, List<Long> repositoryIds) {
    requireAdmin();
    ReviewFeature reviewFeature =
        reviewFeatureRepository
            .findByIdWithRepositories(featureId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review feature not found: " + featureId));

    String normalizedName = normalizeName(name);
    ensureNameAvailable(normalizedName, reviewFeature.getId());

    reviewFeature.setName(normalizedName);
    reviewFeature.setEnabled(enabled == null ? Boolean.TRUE : enabled);
    reviewFeature.setRepositories(resolveRepositories(repositoryIds));
    reviewFeatureRepository.save(reviewFeature);
    return getReviewFeature(reviewFeature.getId());
  }

  @Transactional
  public void deleteReviewFeature(Long featureId) {
    requireAdmin();
    ReviewFeature reviewFeature =
        reviewFeatureRepository
            .findById(featureId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review feature not found: " + featureId));
    reviewFeatureRepository.delete(reviewFeature);
  }

  @Transactional
  public BatchUpsertResult batchUpsert(List<BatchUpsertRow> rows) {
    requireAdmin();
    if (rows == null || rows.isEmpty()) {
      return new BatchUpsertResult(0, 0);
    }

    Set<String> seenNames = new LinkedHashSet<>();
    Set<Long> seenIds = new LinkedHashSet<>();
    int createdCount = 0;
    int updatedCount = 0;

    for (BatchUpsertRow row : rows) {
      if (row == null) {
        continue;
      }
      String normalizedName = normalizeName(row.name());
      String normalizedKey = normalizedName.toLowerCase();
      if (!seenNames.add(normalizedKey)) {
        throw new IllegalArgumentException(
            "Duplicate review feature name in batch: " + normalizedName);
      }
      if (row.id() != null && !seenIds.add(row.id())) {
        throw new IllegalArgumentException("Duplicate review feature id in batch: " + row.id());
      }

      ReviewFeature reviewFeature = null;
      if (row.id() != null) {
        reviewFeature =
            reviewFeatureRepository
                .findByIdWithRepositories(row.id())
                .orElseThrow(
                    () -> new IllegalArgumentException("Review feature not found: " + row.id()));
        ensureNameAvailable(normalizedName, reviewFeature.getId());
        updatedCount++;
      } else {
        reviewFeature = reviewFeatureRepository.findByNameIgnoreCase(normalizedName).orElse(null);
        if (reviewFeature == null) {
          reviewFeature = new ReviewFeature();
          createdCount++;
        } else {
          updatedCount++;
        }
        ensureNameAvailable(normalizedName, reviewFeature.getId());
      }

      reviewFeature.setName(normalizedName);
      reviewFeature.setEnabled(row.enabled() == null ? Boolean.TRUE : row.enabled());
      reviewFeature.setRepositories(resolveRepositories(row.repositoryIds()));
      reviewFeatureRepository.save(reviewFeature);
    }

    return new BatchUpsertResult(createdCount, updatedCount);
  }

  private Map<Long, List<SearchReviewFeaturesView.RepositorySummary>> loadRepositoriesByFeatureId(
      List<ReviewFeatureSummaryRow> rows) {
    Map<Long, List<SearchReviewFeaturesView.RepositorySummary>> repositoriesByFeatureId =
        new LinkedHashMap<>();
    if (rows == null || rows.isEmpty()) {
      return repositoriesByFeatureId;
    }

    List<Long> featureIds =
        rows.stream().map(ReviewFeatureSummaryRow::id).filter(Objects::nonNull).toList();
    for (ReviewFeatureRepositoryRow row :
        reviewFeatureRepository.findRepositoryRowsByFeatureIds(featureIds)) {
      repositoriesByFeatureId
          .computeIfAbsent(row.reviewFeatureId(), ignored -> new ArrayList<>())
          .add(
              new SearchReviewFeaturesView.RepositorySummary(
                  row.repositoryId(), row.repositoryName()));
    }
    return repositoriesByFeatureId;
  }

  private List<SearchReviewFeaturesView.ReviewFeatureSummary> toSummaryViews(
      List<ReviewFeatureSummaryRow> rows,
      Map<Long, List<SearchReviewFeaturesView.RepositorySummary>> repositoriesByFeatureId) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(
            row ->
                new SearchReviewFeaturesView.ReviewFeatureSummary(
                    row.id(),
                    row.createdDate(),
                    row.lastModifiedDate(),
                    row.name(),
                    Boolean.TRUE.equals(row.enabled()),
                    row.repositoryCount(),
                    repositoriesByFeatureId.getOrDefault(row.id(), List.of())))
        .toList();
  }

  private Set<Repository> resolveRepositories(List<Long> repositoryIds) {
    List<Long> normalizedIds =
        (repositoryIds == null ? List.<Long>of() : repositoryIds)
            .stream().filter(id -> id != null && id > 0).distinct().toList();
    if (normalizedIds.isEmpty()) {
      return new LinkedHashSet<>();
    }

    List<Repository> repositories =
        repositoryRepository.findByIdInAndDeletedFalseOrderByNameAsc(normalizedIds);
    if (repositories.size() != normalizedIds.size()) {
      Set<Long> foundIds =
          repositories.stream().map(Repository::getId).collect(java.util.stream.Collectors.toSet());
      List<Long> missingIds = normalizedIds.stream().filter(id -> !foundIds.contains(id)).toList();
      throw new IllegalArgumentException("Unknown repositories: " + missingIds);
    }
    return new LinkedHashSet<>(repositories);
  }

  private void ensureNameAvailable(String normalizedName, Long currentId) {
    reviewFeatureRepository
        .findByNameIgnoreCase(normalizedName)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException(
                  "Review feature already exists: " + normalizedName);
            });
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }

  private String normalizeSearchQuery(String searchQuery) {
    if (searchQuery == null) {
      return null;
    }
    String trimmed = searchQuery.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String normalizeName(String name) {
    String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Review feature name is required");
    }
    if (normalized.length() > ReviewFeature.NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Review feature name must be at most " + ReviewFeature.NAME_MAX_LENGTH + " characters");
    }
    return normalized;
  }

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  public record ReviewFeatureDetail(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      boolean enabled,
      List<RepositoryRef> repositories) {
    public record RepositoryRef(Long id, String name) {}
  }

  public record ReviewFeatureOption(Long id, String name, boolean enabled) {}

  public record ReviewFeatureBatchExportRow(
      Long id, String name, boolean enabled, List<String> repositoryNames) {}

  public record BatchUpsertRow(Long id, String name, Boolean enabled, List<Long> repositoryIds) {}

  public record BatchUpsertResult(int createdCount, int updatedCount) {}
}
