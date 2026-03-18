package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateRun;
import java.math.BigDecimal;
import java.time.ZonedDateTime;

public record AiTranslateRunSummaryRow(
    Long id,
    AiTranslateRun.TriggerSource triggerSource,
    Long repositoryId,
    String repositoryName,
    Long requestedByUserId,
    Long pollableTaskId,
    String model,
    String translateType,
    String relatedStringsType,
    int sourceTextMaxCountPerLocale,
    AiTranslateRun.Status status,
    ZonedDateTime createdAt,
    ZonedDateTime startedAt,
    ZonedDateTime finishedAt,
    long inputTokens,
    long cachedInputTokens,
    long outputTokens,
    long reasoningTokens,
    BigDecimal estimatedCostUsd) {}
