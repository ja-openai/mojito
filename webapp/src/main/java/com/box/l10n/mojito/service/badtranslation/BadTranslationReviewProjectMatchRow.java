package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.review.ReviewProjectStatus;
import java.time.ZonedDateTime;

public record BadTranslationReviewProjectMatchRow(
    Long reviewProjectId,
    ReviewProjectStatus reviewProjectStatus,
    ZonedDateTime reviewProjectCreatedDate,
    ZonedDateTime reviewProjectDueDate,
    Long reviewProjectRequestId,
    String reviewProjectRequestName,
    Long reviewProjectRequestCreatedByUserId,
    String reviewProjectRequestCreatedByUsername,
    Long teamId,
    String teamName,
    Long assignedPmUserId,
    String assignedPmUsername,
    Long assignedTranslatorUserId,
    String assignedTranslatorUsername,
    Long reviewProjectCreatedByUserId,
    String reviewProjectCreatedByUsername,
    Long baselineVariantId,
    String baselineVariantContent,
    Long currentVariantId,
    String currentVariantContent,
    Long decisionVariantId,
    String decisionVariantContent,
    Long reviewedVariantId,
    ZonedDateTime decisionLastModifiedDate,
    Long decisionLastModifiedByUserId,
    String decisionLastModifiedByUsername) {}
