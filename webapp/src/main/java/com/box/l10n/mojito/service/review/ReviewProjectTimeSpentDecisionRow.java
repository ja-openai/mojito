package com.box.l10n.mojito.service.review;

import java.time.ZonedDateTime;

public record ReviewProjectTimeSpentDecisionRow(
    Long decisionId, Long decisionUserId, ZonedDateTime decidedAt, Long wordCount) {}
