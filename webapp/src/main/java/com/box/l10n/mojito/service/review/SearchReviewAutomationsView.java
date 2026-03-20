package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;
import java.util.List;

public record SearchReviewAutomationsView(
    List<ReviewAutomationSummary> reviewAutomations, long totalCount) {

  public record ReviewAutomationSummary(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      Boolean enabled,
      String cronExpression,
      String timeZone,
      int maxWordCountPerProject,
      long featureCount,
      List<FeatureSummary> features) {}

  public record FeatureSummary(Long id, String name) {}
}
