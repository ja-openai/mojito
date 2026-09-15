package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomation.IncidentScope;
import com.box.l10n.mojito.entity.review.ReviewAutomation.ReviewSource;
import java.time.ZonedDateTime;

public record ReviewAutomationSummaryRow(
    Long id,
    ZonedDateTime createdDate,
    ZonedDateTime lastModifiedDate,
    String name,
    Boolean enabled,
    String cronExpression,
    String timeZone,
    Long teamId,
    String teamName,
    Integer dueDateOffsetDays,
    Integer maxWordCountPerProject,
    Boolean assignTranslator,
    long featureCount,
    ReviewSource reviewSource,
    String incidentReviewType,
    IncidentScope incidentScope) {}
