package com.box.l10n.mojito.service.tm.textunitdtocache;

import java.time.ZonedDateTime;

public record TextUnitDTOsCacheState(
    Long assetExtractionId,
    Long assetExtractionVersion,
    boolean assetDeleted,
    ZonedDateTime lastModifiedDate,
    Long currentVariantId) {}
