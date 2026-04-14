package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;

public record CreateAutomatedReviewProjectRequestCommand(
    Long reviewFeatureId,
    String name,
    String notes,
    ZonedDateTime dueDate,
    Long teamId,
    Integer maxWordCountPerProject,
    Boolean assignTranslator,
    Long requestedByUserId) {}
