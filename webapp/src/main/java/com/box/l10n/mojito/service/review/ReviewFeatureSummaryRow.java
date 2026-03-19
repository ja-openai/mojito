package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;

public record ReviewFeatureSummaryRow(
    Long id,
    ZonedDateTime createdDate,
    ZonedDateTime lastModifiedDate,
    String name,
    Boolean enabled,
    long repositoryCount) {}
