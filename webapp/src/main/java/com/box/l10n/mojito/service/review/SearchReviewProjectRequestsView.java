package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;
import java.util.List;

public record SearchReviewProjectRequestsView(List<ReviewProjectRequestGroup> reviewProjectRequests) {

  public record ReviewProjectRequestGroup(
      Long requestId,
      String requestName,
      String requestCreatedByUsername,
      Integer openProjectCount,
      Integer closedProjectCount,
      Integer textUnitCount,
      Integer wordCount,
      Long acceptedCount,
      ZonedDateTime dueDate,
      List<SearchReviewProjectsView.ReviewProject> reviewProjects) {}
}

