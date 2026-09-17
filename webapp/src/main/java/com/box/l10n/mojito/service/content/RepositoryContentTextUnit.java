package com.box.l10n.mojito.service.content;

import com.box.l10n.mojito.entity.TMTextUnitVariant;

public record RepositoryContentTextUnit(
    Long assetId,
    String name,
    String source,
    Long tmTextUnitId,
    Long tmTextUnitVariantId,
    String targetContent,
    TMTextUnitVariant.Status targetStatus) {}
