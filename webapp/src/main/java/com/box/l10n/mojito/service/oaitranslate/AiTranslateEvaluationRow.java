package com.box.l10n.mojito.service.oaitranslate;

import java.time.ZonedDateTime;

public record AiTranslateEvaluationRow(
    Long attemptId,
    Long decisionId,
    ZonedDateTime reviewedAt,
    Long reviewProjectId,
    Long repositoryId,
    String repositoryName,
    Long tmTextUnitId,
    String textUnitName,
    String source,
    String sourceDescription,
    String localeTag,
    String model,
    String promptFingerprint,
    String reasoningEffort,
    String textVerbosity,
    String aiTarget,
    String acceptedTarget,
    String decisionNotes) {}
