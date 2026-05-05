package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;
import java.util.List;

public record CreateGlossaryTerminologyReviewProjectCommand(
    String name,
    String notes,
    ZonedDateTime dueDate,
    Long teamId,
    Boolean assignTranslator,
    List<Long> tmTextUnitIds,
    List<Long> specialistUserIds,
    Long pmUserId,
    ZonedDateTime specialistDueDate,
    ZonedDateTime pmDueDate) {}
