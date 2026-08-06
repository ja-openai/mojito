package com.box.l10n.mojito.service.searchindex;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import java.time.ZonedDateTime;

public record SearchIndexVariantRow(
    Long tmTextUnitVariantId,
    Long tmTextUnitId,
    Long repositoryId,
    String repositoryName,
    String sourceLocaleTag,
    Long assetId,
    String assetPath,
    Long localeId,
    String localeTag,
    String name,
    String source,
    String target,
    String comment,
    String targetComment,
    TMTextUnitVariant.Status status,
    boolean includedInLocalizedFile,
    boolean current,
    boolean assetDeleted,
    ZonedDateTime createdDate,
    Long createdByUserId,
    Long sourceTmTextUnitId,
    Long sourceTmTextUnitVariantId,
    String leveragingType,
    boolean uniqueMatch) {}
