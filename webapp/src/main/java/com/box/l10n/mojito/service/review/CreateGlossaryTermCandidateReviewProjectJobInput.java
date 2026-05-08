package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;
import java.util.List;

public record CreateGlossaryTermCandidateReviewProjectJobInput(
    Long glossaryId,
    String name,
    String notes,
    ZonedDateTime dueDate,
    Long teamId,
    Boolean assignTranslator,
    List<Long> termIndexCandidateIds,
    List<Long> specialistUserIds,
    Long pmUserId,
    ZonedDateTime specialistDueDate,
    ZonedDateTime pmDueDate,
    Long requestedByUserId) {}
