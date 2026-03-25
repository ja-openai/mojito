package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;

public record ReviewAutomationRunTimestampRow(Long automationId, ZonedDateTime timestamp) {}
