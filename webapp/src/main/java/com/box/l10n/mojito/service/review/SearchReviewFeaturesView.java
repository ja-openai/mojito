package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;
import java.util.List;

public record SearchReviewFeaturesView(List<ReviewFeatureSummary> reviewFeatures, long totalCount) {

  public record ReviewFeatureSummary(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      Boolean enabled,
      long repositoryCount,
      List<RepositorySummary> repositories) {}

  public record RepositorySummary(Long id, String name) {}
}
