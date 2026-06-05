package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.TMTextUnitVariant;

public record CmsCurrentVariantRow(
    Long tmTextUnitId,
    String localeTag,
    String target,
    TMTextUnitVariant.Status status,
    Boolean includedInLocalizedFile) {}
