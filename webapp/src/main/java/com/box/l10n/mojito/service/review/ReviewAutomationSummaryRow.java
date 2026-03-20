package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;

public record ReviewAutomationSummaryRow(
    Long id,
    ZonedDateTime createdDate,
    ZonedDateTime lastModifiedDate,
    String name,
    Boolean enabled,
    String cronExpression,
    String timeZone,
    Integer maxWordCountPerProject,
    long featureCount) {}
