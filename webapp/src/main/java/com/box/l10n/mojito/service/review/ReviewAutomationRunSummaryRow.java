package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomationRun;
import java.time.ZonedDateTime;

public record ReviewAutomationRunSummaryRow(
    Long id,
    Long automationId,
    String automationName,
    ReviewAutomationRun.TriggerSource triggerSource,
    Long requestedByUserId,
    String requestedByUsername,
    ReviewAutomationRun.Status status,
    ZonedDateTime createdDate,
    ZonedDateTime startedAt,
    ZonedDateTime finishedAt,
    int featureCount,
    int createdProjectRequestCount,
    int createdProjectCount,
    int createdLocaleCount,
    int skippedLocaleCount,
    int erroredLocaleCount,
    String errorMessage) {}
