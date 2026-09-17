package com.box.l10n.mojito.service.review;

public record ReviewProjectDocumentTextUnit(
    Long reviewProjectTextUnitId,
    Long tmTextUnitId,
    String name,
    String source,
    Long assetId,
    String assetPath,
    Long repositoryId) {}
