package com.box.l10n.mojito.service.tm.textunitdtocache;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.TEXT_UNIT_DTOS_CACHE;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.google.common.collect.ImmutableList;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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

  static Logger logger = LoggerFactory.getLogger(TextUnitDTOsCacheBlobStorage.class);

  @Autowired StructuredBlobStorage structuredBlobStorage;

  @Autowired
  @Qualifier("fail_on_unknown_properties_false")
  ObjectMapper objectMapper;

  @Autowired MeterRegistry meterRegistry;

  /**
   * For a given an asset and a locale, read the list of TextUnitDTOs. If there are no reccord for
   * that asset and locale, it returns an empty list. If the content in the StructuredBlobStorage
   * can't be convert it will also return an empty list.
   *
   * @param assetId
   * @param localeId
   * @return
   */
  public Optional<ImmutableList<TextUnitDTO>> getTextUnitDTOs(Long assetId, Long localeId) {
    Timer.Sample sample = Timer.start(meterRegistry);
    String exceptionClass = DEFAULT_EXCEPTION_TAG_VALUE;

    try {
      logger.debug(
          "Get TextUnitDTOs from Blob Storage for assetId: {}, localeId: {}", assetId, localeId);
      return getTextUnitsFromCache(assetId, localeId);
    } catch (RuntimeException e) {
      exceptionClass = e.getClass().getSimpleName();
      throw e;
    } finally {
      recordTimer(
          "TextUnitDTOsCacheBlobStorage.getTextUnitDTOs",
          "getTextUnitDTOs",
          sample,
          exceptionClass);
    }
  }

  public void putTextUnitDTOs(
      Long assetId, Long localeId, ImmutableList<TextUnitDTO> textUnitDTOs) {
    Timer.Sample sample = Timer.start(meterRegistry);
    String exceptionClass = DEFAULT_EXCEPTION_TAG_VALUE;

    try {
      logger.debug(
          "Put TextUnitDTOs to Blob Storage for assetId: {}, localeId: {}, count: {}",
          assetId,
          localeId,
          textUnitDTOs.size());
      TextUnitDTOsCacheBlobStorageJson textUnitDTOsCacheBlobStorageJson =
          new TextUnitDTOsCacheBlobStorageJson();
      textUnitDTOsCacheBlobStorageJson.setTextUnitDTOs(textUnitDTOs);
      writeTextUnitDTOsToCache(assetId, localeId, textUnitDTOsCacheBlobStorageJson);
    } catch (RuntimeException e) {
      exceptionClass = e.getClass().getSimpleName();
      throw e;
    } finally {
      recordTimer(
          "TextUnitDTOsCacheBlobStorage.putTextUnitDTOs",
          "putTextUnitDTOs",
          sample,
          exceptionClass);
    }
  }

  String getName(Long assetId, Long localeId) {
    return "asset/" + assetId + "/locale/" + localeId;
  }

  ImmutableList<TextUnitDTO> convertToListOrEmptyList(String s) {
    try {
      return ImmutableList.copyOf(
          objectMapper
              .readValueUnchecked(s, TextUnitDTOsCacheBlobStorageJson.class)
              .getTextUnitDTOs());
    } catch (Exception e) {
      logger.error("Convert: %s".formatted(s));
      logger.error(
          "Can't convert the content into TextUnitDTOsCacheBlobStorageJson, return an empty list instead",
          e);
      return ImmutableList.of();
    }
  }

  Optional<ImmutableList<TextUnitDTO>> getTextUnitsFromCache(Long assetId, Long localeId) {
    Optional<String> asString =
        structuredBlobStorage.getString(TEXT_UNIT_DTOS_CACHE, getName(assetId, localeId));
    return asString.map(this::convertToListOrEmptyList);
  }

  void writeTextUnitDTOsToCache(
      Long assetId,
      Long localeId,
      TextUnitDTOsCacheBlobStorageJson textUnitDTOsCacheBlobStorageJson) {
    String asString = objectMapper.writeValueAsStringUnchecked(textUnitDTOsCacheBlobStorageJson);
    structuredBlobStorage.put(
        TEXT_UNIT_DTOS_CACHE, getName(assetId, localeId), asString, Retention.PERMANENT);
  }

  private void recordTimer(
      String metricName, String methodName, Timer.Sample sample, String exceptionClass) {
    sample.stop(
        Timer.builder(metricName)
            .tag(EXCEPTION_TAG, exceptionClass)
            .tag("class", TextUnitDTOsCacheBlobStorage.class.getName())
            .tag("method", methodName)
            .register(meterRegistry));
  }

  static final String DEFAULT_EXCEPTION_TAG_VALUE = "none";
  static final String EXCEPTION_TAG = "exception";
}
