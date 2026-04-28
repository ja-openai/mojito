package com.box.l10n.mojito.service.oaitranslate;

import java.time.ZonedDateTime;

public record AiTranslateTextUnitAttemptLineageRow(
    Long id,
    ZonedDateTime createdDate,
    ZonedDateTime lastModifiedDate,
    Long tmTextUnitId,
    String tmTextUnitName,
    Long tmTextUnitVariantId,
    String localeBcp47Tag,
    Long repositoryId,
    String repositoryName,
    Long pollableTaskId,
    Long aiTranslateRunId,
    String requestGroupId,
    String translateType,
    String model,
    String status,
    String completionId,
    String requestPayloadBlobName,
    String responsePayloadBlobName,
    String errorMessage) {}
