package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AssetTextUnit;
import com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.utils.FilePosition;
import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AiTranslateRelatedStringsProvider {

  static int CHARACTER_LIMIT = 10000;

  private static final Logger logger =
      LoggerFactory.getLogger(AiTranslateRelatedStringsProvider.class);

  private final AssetTextUnitRepository assetTextUnitRepository;
  private final Type type;

  private final ConcurrentHashMap<Long, List<AssetTextUnit>> assetExtractionMap =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, AssetTextUnit> assetTextUnitById =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Map<String, List<AssetTextUnitWithPosition>>>
      usageMapCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Map<String, List<AssetTextUnit>>> idPrefixMapCache =
      new ConcurrentHashMap<>();

  AiTranslateRelatedStringsProvider(AssetTextUnitRepository assetTextUnitRepository, Type type) {
    this.assetTextUnitRepository = assetTextUnitRepository;
    this.type = type;
  }

  enum Type {
    USAGES,
    ID_PREFIX,
    NONE;

    static Type fromString(String type) {
      Type result = NONE;
      if (type != null) {
        result = valueOf(type.toUpperCase());
      }
      return result;
    }
  }

  record RelatedString(String source, String description) {}

  record AssetTextUnitWithPosition(AssetTextUnit assetTextUnit, Long position) {}

  /**
   * Everything must be lazy in case we don't use the option. It is very heavy on memory usage but
   * relatively fast since all asset text units are load per asset, in most case we have one big
   * asset per repository.
   */
  List<RelatedString> getRelatedStrings(TextUnitDTO textUnitDTO) {

    if (Type.NONE.equals(type)) {
      return ImmutableList.of();
    }

    initCachesForAssetExtraction(textUnitDTO.getAssetExtractionId());

    AssetTextUnit assetTextUnit = assetTextUnitById.get(textUnitDTO.getAssetTextUnitId());

    if (assetTextUnit != null) {
      List<RelatedString> relatedStrings =
          switch (type) {
            case USAGES -> getRelatedStringsByUsages(assetTextUnit);
            case ID_PREFIX -> getRelatedStringsByIdPrefix(assetTextUnit);
            case NONE -> {
              logger.error("Must have exited earlier to avoid unnecessary computation");
              yield ImmutableList.of();
            }
          };
      List<RelatedString> filteredByCharLimit = filterByCharLimit(relatedStrings, CHARACTER_LIMIT);
      logger.debug(
          "Related strings (type: {}, count: {}, filtered: {}): {}",
          type,
          relatedStrings.size(),
          filteredByCharLimit.size(),
          relatedStrings);
      return filteredByCharLimit;
    } else {
      logger.warn(
          "The text unit dto does not have a matching asset text unit in the current asset extraction. This"
              + "is probably due to concurrent updates, return no related strings.");
      return ImmutableList.of();
    }
  }

  List<RelatedString> getRelatedStringsByIdPrefix(AssetTextUnit assetTextUnit) {
    Long id = assetTextUnit.getAssetExtraction().getId();
    initCachesForAssetExtraction(id);
    String prefix = getPrefix(assetTextUnit.getName());
    return idPrefixMapCache.get(id).get(prefix).stream()
        .map(atu -> new RelatedString(atu.getContent(), atu.getComment()))
        .toList();
  }

  static String getPrefix(String id) {
    int dot = id.indexOf('.');
    return (dot == -1) ? id : id.substring(0, dot);
  }

  List<RelatedString> getRelatedStringsByUsages(AssetTextUnit assetTextUnit) {

    return assetTextUnit.getUsages().stream()
        .flatMap(
            u -> {
              FilePosition filePosition = FilePosition.from(u);
              return usageMapCache
                  .get(assetTextUnit.getAssetExtraction().getId())
                  .getOrDefault(filePosition.path(), List.of())
                  .stream()
                  .sorted(Comparator.comparingLong(AssetTextUnitWithPosition::position))
                  .map(
                      atu ->
                          new RelatedString(
                              atu.assetTextUnit().getContent(), atu.assetTextUnit().getComment()));
            })
        .toList();
  }

  void initCachesForAssetExtraction(long assetExtractionId) {
    assetExtractionMap.computeIfAbsent(
        assetExtractionId,
        id -> {
          List<AssetTextUnit> byAssetExtractionId =
              assetTextUnitRepository.findByAssetExtractionId(id);

          for (AssetTextUnit atu : byAssetExtractionId) {
            assetTextUnitById.putIfAbsent(atu.getId(), atu);
          }

          if (Type.USAGES.equals(type)) {
            usageMapCache.computeIfAbsent(
                assetExtractionId,
                __ ->
                    byAssetExtractionId.stream()
                        .flatMap(
                            atu ->
                                atu.getUsages().stream()
                                    .map(
                                        usage -> {
                                          FilePosition filePosition = FilePosition.from(usage);
                                          return Map.entry(
                                              filePosition.path(),
                                              new AssetTextUnitWithPosition(
                                                  atu,
                                                  filePosition.line() == null
                                                      ? atu.getId()
                                                      : filePosition.line()));
                                        }))
                        .collect(
                            Collectors.groupingBy(
                                Map.Entry::getKey,
                                Collectors.mapping(Map.Entry::getValue, Collectors.toList()))));
          } else if (Type.ID_PREFIX.equals(type)) {
            idPrefixMapCache.computeIfAbsent(
                assetExtractionId,
                __ ->
                    byAssetExtractionId.stream()
                        .collect(Collectors.groupingBy(atu -> getPrefix(atu.getName()))));
          }

          return byAssetExtractionId;
        });
  }

  static List<RelatedString> filterByCharLimit(List<RelatedString> relatedStrings, int charLimit) {

    final int JSON_OVERHEAD = 30;
    int i = 0;
    int totalCharCount = 0;

    for (RelatedString rs : relatedStrings) {

      int charCount =
          (rs.source() == null ? 0 : rs.source().length())
              + (rs.description() == null ? 0 : rs.description().length())
              + JSON_OVERHEAD;

      if (totalCharCount + charCount > charLimit) break;

      totalCharCount += charCount;
      i++;
    }

    return List.copyOf(relatedStrings.subList(0, i));
  }
}
