package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;
import java.util.List;

public record CreateAutomatedReviewProjectRequestCommand(
    Long reviewFeatureId,
    String name,
    String notes,
    ZonedDateTime dueDate,
    Long teamId,
    Integer maxWordCountPerProject,
    Boolean assignTranslator,
    Long requestedByUserId,
    List<String> excludedLocaleTags) {

  public CreateAutomatedReviewProjectRequestCommand {
    excludedLocaleTags = excludedLocaleTags == null ? List.of() : List.copyOf(excludedLocaleTags);
  }
}
