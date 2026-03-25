package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;

public record ReviewAutomationTriggerStatusView(
    String status,
    String quartzState,
    ZonedDateTime nextRunAt,
    ZonedDateTime previousRunAt,
    ZonedDateTime lastRunAt,
    ZonedDateTime lastSuccessfulRunAt,
    boolean repairRecommended) {}
