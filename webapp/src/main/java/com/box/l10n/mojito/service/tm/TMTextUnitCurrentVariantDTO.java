package com.box.l10n.mojito.service.tm;

import java.time.ZonedDateTime;

public class TMTextUnitCurrentVariantDTO {
  Long tmTextUnitId;
  Long tmTextUnitVariantId;
  Long localeId;
  Long assetId;
  ZonedDateTime lastModifiedDate;
  Long currentVariantId;

  public TMTextUnitCurrentVariantDTO(Long tmTextUnitId, Long tmTextUnitVariantId) {
    this.tmTextUnitId = tmTextUnitId;
    this.tmTextUnitVariantId = tmTextUnitVariantId;
  }

  public TMTextUnitCurrentVariantDTO(
      Long tmTextUnitId,
      Long tmTextUnitVariantId,
      Long localeId,
      Long assetId,
      ZonedDateTime lastModifiedDate,
      Long currentVariantId) {
    this.tmTextUnitId = tmTextUnitId;
    this.tmTextUnitVariantId = tmTextUnitVariantId;
    this.localeId = localeId;
    this.assetId = assetId;
    this.lastModifiedDate = lastModifiedDate;
    this.currentVariantId = currentVariantId;
  }

  public Long getTmTextUnitId() {
    return tmTextUnitId;
  }

  public void setTmTextUnitId(Long tmTextUnitId) {
    this.tmTextUnitId = tmTextUnitId;
  }

  public Long getTmTextUnitVariantId() {
    return tmTextUnitVariantId;
  }

  public void setTmTextUnitVariantId(Long tmTextUnitVariantId) {
    this.tmTextUnitVariantId = tmTextUnitVariantId;
  }

  public Long getLocaleId() {
    return localeId;
  }

  public Long getAssetId() {
    return assetId;
  }

  public ZonedDateTime getLastModifiedDate() {
    return lastModifiedDate;
  }

  public Long getCurrentVariantId() {
    return currentVariantId;
  }
}
