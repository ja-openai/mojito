package com.box.l10n.mojito.service.tm.textunitdtocache;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.TEXT_UNIT_DTOS_CACHE;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.google.common.collect.ImmutableList;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "l10n.cache.textunit.smile.enabled",
    havingValue = "false",
    matchIfMissing = true)
class TextUnitDTOsCacheBlobStorage {

  static final String CACHE_LOOKUP_METRIC = "TextUnitDTOsCacheBlobStorage.lookup";

  static Logger logger = LoggerFactory.getLogger(TextUnitDTOsCacheBlobStorage.class);

  @Autowired StructuredBlobStorage structuredBlobStorage;

  @Autowired MeterRegistry meterRegistry;

  @Autowired
  @Qualifier("fail_on_unknown_properties_false")
  ObjectMapper objectMapper;

  /**
   * For a given an asset and a locale, read the list of TextUnitDTOs. If there are no reccord for
   * that asset and locale, it returns an empty list. If the content in the StructuredBlobStorage
   * can't be convert it will also return an empty list.
   *
   * @param assetId
   * @param localeId
   * @return
   */
  @Timed("TextUnitDTOsCacheBlobStorage.getTextUnitDTOs")
  public Optional<ImmutableList<TextUnitDTO>> getTextUnitDTOs(Long assetId, Long localeId) {
    return getCacheEntry(assetId, localeId)
        .map(cacheEntry -> ImmutableList.copyOf(cacheEntry.getTextUnitDTOs()));
  }

  public Optional<TextUnitDTOsCacheBlobStorageJson> getCacheEntry(Long assetId, Long localeId) {
    logger.debug(
        "Get TextUnitDTOs from Blob Storage for assetId: {}, localeId: {}", assetId, localeId);
    Optional<TextUnitDTOsCacheBlobStorageJson> cacheEntry =
        getCacheEntryFromCache(assetId, localeId);
    meterRegistry
        .counter(
            CACHE_LOOKUP_METRIC,
            "format",
            getFormat(),
            "result",
            cacheEntry.isPresent() ? "hit" : "miss")
        .increment();
    return cacheEntry;
  }

  @Timed("TextUnitDTOsCacheBlobStorage.putTextUnitDTOs")
  public void putTextUnitDTOs(
      Long assetId, Long localeId, ImmutableList<TextUnitDTO> textUnitDTOs) {
    putTextUnitDTOs(assetId, localeId, textUnitDTOs, null);
  }

  void putTextUnitDTOs(
      Long assetId,
      Long localeId,
      ImmutableList<TextUnitDTO> textUnitDTOs,
      TextUnitDTOsCacheState cacheState) {
    logger.debug(
        "Put TextUnitDTOs to Blob Storage for assetId: {}, localeId: {}, count: {}",
        assetId,
        localeId,
        textUnitDTOs.size());
    TextUnitDTOsCacheBlobStorageJson textUnitDTOsCacheBlobStorageJson =
        new TextUnitDTOsCacheBlobStorageJson();
    textUnitDTOsCacheBlobStorageJson.setTextUnitDTOs(textUnitDTOs);
    textUnitDTOsCacheBlobStorageJson.setCacheState(cacheState);
    writeTextUnitDTOsToCache(assetId, localeId, textUnitDTOsCacheBlobStorageJson);
  }

  String getName(Long assetId, Long localeId) {
    return "asset/" + assetId + "/locale/" + localeId;
  }

  String getFormat() {
    return "json";
  }

  ImmutableList<TextUnitDTO> convertToListOrEmptyList(String s) {
    return ImmutableList.copyOf(convertToCacheEntryOrEmpty(s).getTextUnitDTOs());
  }

  TextUnitDTOsCacheBlobStorageJson convertToCacheEntryOrEmpty(String s) {
    try {
      return objectMapper.readValueUnchecked(s, TextUnitDTOsCacheBlobStorageJson.class);
    } catch (Exception e) {
      logger.error("Convert: %s".formatted(s));
      logger.error(
          "Can't convert the content into TextUnitDTOsCacheBlobStorageJson, return an empty list instead",
          e);
      TextUnitDTOsCacheBlobStorageJson emptyCacheEntry = new TextUnitDTOsCacheBlobStorageJson();
      emptyCacheEntry.setTextUnitDTOs(ImmutableList.of());
      return emptyCacheEntry;
    }
  }

  Optional<TextUnitDTOsCacheBlobStorageJson> getCacheEntryFromCache(Long assetId, Long localeId) {
    Optional<String> asString =
        structuredBlobStorage.getString(TEXT_UNIT_DTOS_CACHE, getName(assetId, localeId));
    return asString.map(this::convertToCacheEntryOrEmpty);
  }

  void writeTextUnitDTOsToCache(
      Long assetId,
      Long localeId,
      TextUnitDTOsCacheBlobStorageJson textUnitDTOsCacheBlobStorageJson) {
    String asString = objectMapper.writeValueAsStringUnchecked(textUnitDTOsCacheBlobStorageJson);
    structuredBlobStorage.put(
        TEXT_UNIT_DTOS_CACHE, getName(assetId, localeId), asString, Retention.PERMANENT);
  }
}
