package com.box.l10n.mojito.rest.review;

import com.box.l10n.mojito.service.review.ReviewFeatureService;
import com.box.l10n.mojito.service.review.SearchReviewFeaturesView;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/review-features")
public class ReviewFeatureWS {

  private final ReviewFeatureService reviewFeatureService;

  public ReviewFeatureWS(ReviewFeatureService reviewFeatureService) {
    this.reviewFeatureService = reviewFeatureService;
  }

  public record SearchReviewFeaturesResponse(
      List<ReviewFeatureSummary> reviewFeatures, long totalCount) {
    public record ReviewFeatureSummary(
        Long id,
        ZonedDateTime createdDate,
        ZonedDateTime lastModifiedDate,
        String name,
        boolean enabled,
        long repositoryCount,
        List<RepositoryRef> repositories) {}
  }

  public record ReviewFeatureResponse(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      boolean enabled,
      List<RepositoryRef> repositories) {}

  public record RepositoryRef(Long id, String name) {}

  public record UpsertReviewFeatureRequest(
      String name, Boolean enabled, List<Long> repositoryIds) {}

  public record BatchUpsertReviewFeaturesRequest(List<BatchRow> rows) {
    public record BatchRow(Long id, String name, Boolean enabled, List<Long> repositoryIds) {}
  }

  public record ReviewFeatureOptionResponse(Long id, String name, boolean enabled) {}

  public record ReviewFeatureBatchExportResponse(
      Long id, String name, boolean enabled, List<String> repositoryNames) {}

  public record BatchUpsertReviewFeaturesResponse(int createdCount, int updatedCount) {}

  @GetMapping
  public SearchReviewFeaturesResponse searchReviewFeatures(
      @RequestParam(name = "search", required = false) String searchQuery,
      @RequestParam(name = "enabled", required = false) Boolean enabled,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      SearchReviewFeaturesView view =
          reviewFeatureService.searchReviewFeatures(searchQuery, enabled, limit);
      return new SearchReviewFeaturesResponse(
          view.reviewFeatures().stream().map(this::toSummaryResponse).toList(), view.totalCount());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @GetMapping("/{featureId}")
  public ReviewFeatureResponse getReviewFeature(@PathVariable Long featureId) {
    try {
      return toDetailResponse(reviewFeatureService.getReviewFeature(featureId));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
  }

  @GetMapping("/options")
  public List<ReviewFeatureOptionResponse> getReviewFeatureOptions() {
    return reviewFeatureService.getReviewFeatureOptions().stream()
        .map(
            option -> new ReviewFeatureOptionResponse(option.id(), option.name(), option.enabled()))
        .toList();
  }

  @GetMapping("/batch-export")
  public List<ReviewFeatureBatchExportResponse> getReviewFeatureBatchExport() {
    return reviewFeatureService.getReviewFeatureBatchExportRows().stream()
        .map(
            row ->
                new ReviewFeatureBatchExportResponse(
                    row.id(), row.name(), row.enabled(), row.repositoryNames()))
        .toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ReviewFeatureResponse createReviewFeature(
      @RequestBody UpsertReviewFeatureRequest request) {
    try {
      return toDetailResponse(
          reviewFeatureService.createReviewFeature(
              request != null ? request.name() : null,
              request != null ? request.enabled() : null,
              request != null ? request.repositoryIds() : List.of()));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @PutMapping("/{featureId}")
  public ReviewFeatureResponse updateReviewFeature(
      @PathVariable Long featureId, @RequestBody UpsertReviewFeatureRequest request) {
    try {
      return toDetailResponse(
          reviewFeatureService.updateReviewFeature(
              featureId,
              request != null ? request.name() : null,
              request != null ? request.enabled() : null,
              request != null ? request.repositoryIds() : List.of()));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Review feature not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @DeleteMapping("/{featureId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteReviewFeature(@PathVariable Long featureId) {
    try {
      reviewFeatureService.deleteReviewFeature(featureId);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
  }

  @PostMapping("/batch-upsert")
  public BatchUpsertReviewFeaturesResponse batchUpsertReviewFeatures(
      @RequestBody BatchUpsertReviewFeaturesRequest request) {
    try {
      ReviewFeatureService.BatchUpsertResult result =
          reviewFeatureService.batchUpsert(
              request == null || request.rows() == null
                  ? List.of()
                  : request.rows().stream()
                      .map(
                          row ->
                              new ReviewFeatureService.BatchUpsertRow(
                                  row.id(), row.name(), row.enabled(), row.repositoryIds()))
                      .toList());
      return new BatchUpsertReviewFeaturesResponse(result.createdCount(), result.updatedCount());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  private SearchReviewFeaturesResponse.ReviewFeatureSummary toSummaryResponse(
      SearchReviewFeaturesView.ReviewFeatureSummary view) {
    return new SearchReviewFeaturesResponse.ReviewFeatureSummary(
        view.id(),
        view.createdDate(),
        view.lastModifiedDate(),
        view.name(),
        Boolean.TRUE.equals(view.enabled()),
        view.repositoryCount(),
        view.repositories().stream()
            .map(repository -> new RepositoryRef(repository.id(), repository.name()))
            .toList());
  }

  private ReviewFeatureResponse toDetailResponse(ReviewFeatureService.ReviewFeatureDetail detail) {
    return new ReviewFeatureResponse(
        detail.id(),
        detail.createdDate(),
        detail.lastModifiedDate(),
        detail.name(),
        detail.enabled(),
        detail.repositories().stream()
            .map(repository -> new RepositoryRef(repository.id(), repository.name()))
            .toList());
  }
}
