package com.box.l10n.mojito.service.content;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** An exact, absent-target snapshot; this endpoint never updates an existing translation. */
public record ContentTranslationCandidate(
    Long branchId,
    Long assetId,
    Long tmTextUnitId,
    Long localeId,
    String name,
    String expectedSource,
    Long expectedAssetExtractionId,
    String expectedAssetContentMd5,
    @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonProperty(value = "expectedVariantId", required = true)
        Long expectedVariantId,
    String target) {
  public record Source(
      Long repositoryId,
      Long branchId,
      Long assetId,
      String assetPath,
      Long assetExtractionId,
      String assetContentMd5) {}

  public record Result(
      Long tmTextUnitId,
      Long tmTextUnitVariantId,
      TMTextUnitVariant.Status status,
      String target) {}
}
