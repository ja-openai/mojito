package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;
import java.util.List;

public record CreateReviewProjectRequestResult(
    Long requestId,
    String requestName,
    List<String> localeTags,
    ZonedDateTime dueDate,
    List<Long> projectIds,
    int requestedLocaleCount,
    int createdLocaleCount,
    int skippedLocaleCount,
    int erroredLocaleCount,
    List<LocaleResult> localeResults) {

  public enum LocaleResultStatus {
    CREATED,
    SKIPPED_NO_TEXT_UNITS,
    ERROR
  }

  public record LocaleResult(
      String localeTag,
      LocaleResultStatus status,
      int textUnitCount,
      int projectCount,
      String message) {}
}
