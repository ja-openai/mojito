package com.box.l10n.mojito.service.badtranslation;

import java.time.ZonedDateTime;
import java.util.List;

public record BadTranslationReviewProjectCandidate(
    Long reviewProjectId,
    String reviewProjectStatus,
    ZonedDateTime reviewProjectCreatedDate,
    ZonedDateTime reviewProjectDueDate,
    ZonedDateTime decisionLastModifiedDate,
    String reviewProjectLink,
    Long reviewProjectRequestId,
    String reviewProjectRequestName,
    String reviewProjectRequestLink,
    Long teamId,
    String teamName,
    BadTranslationPersonRef requestCreator,
    BadTranslationPersonRef assignedPm,
    BadTranslationPersonRef assignedTranslator,
    BadTranslationPersonRef reviewer,
    String confidence,
    int confidenceScore,
    List<String> confidenceReasons) {}
