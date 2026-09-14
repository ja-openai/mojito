package com.box.l10n.mojito.service.assetExtraction;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.TEXT_UNIT_DTOS_CACHE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetcontent.AssetContentService;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.repository.statistics.RepositoryStatisticsJobScheduler;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheBlobStorageJson;
import com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheService;
import com.box.l10n.mojito.service.tm.textunitdtocache.UpdateType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

public class AssetExtractionServiceSourceCacheTest extends ServiceTestBase {

  @Autowired AssetExtractionService assetExtractionService;
  @Autowired AssetService assetService;
  @Autowired AssetContentService assetContentService;
  @Autowired RepositoryService repositoryService;
  @Autowired LocaleService localeService;
  @Autowired TMService tmService;
  @Autowired TMTextUnitRepository tmTextUnitRepository;
  @Autowired TMTextUnitCurrentVariantRepository currentVariantRepository;
  @Autowired TextUnitDTOsCacheService cacheService;
  @Autowired StructuredBlobStorage structuredBlobStorage;

  // Source leveraging must not depend on the asynchronous statistics job refreshing the cache.
  @MockitoBean RepositoryStatisticsJobScheduler statisticsJobScheduler;

  @Test
  public void sourceLeveragingRefreshesAnExistingEmptyRootCache() throws Exception {
    assertSourceLeveragingRefreshesRootCache(true);
  }

  @Test
  public void sourceLeveragingRefreshesStaleUsedFlagsInTheRootCache() throws Exception {
    assertSourceLeveragingRefreshesRootCache(false);
  }

  private void assertSourceLeveragingRefreshesRootCache(boolean emptyCache) throws Exception {
    Repository repository = repositoryService.createRepository("source-cache-" + UUID.randomUUID());
    repositoryService.addRepositoryLocale(repository, "fr-FR");
    repositoryService.addRepositoryLocale(repository, "fr-CA", "fr-FR", false);
    repositoryService.addRepositoryLocale(repository, "ja-JP");
    Asset asset = assetService.createAsset(repository.getId(), "messages.json", false);

    process(asset, "Old note");
    TMTextUnit original = findTextUnit(repository, "Old note");
    Map<String, String> translations =
        Map.of("fr-FR", "Le port est ouvert", "fr-CA", "Le havre est ouvert", "ja-JP", "港は開いています");
    for (var translation : translations.entrySet()) {
      tmService.addCurrentTMTextUnitVariant(
          original.getId(),
          localeService.findByBcp47Tag(translation.getKey()).getId(),
          translation.getValue(),
          TMTextUnitVariant.Status.APPROVED,
          true);
    }

    Long rootLocaleId = localeService.getDefaultLocale().getId();
    String cacheName = "asset/" + asset.getId() + "/locale/" + rootLocaleId;
    var originalSnapshot =
        new ObjectMapper()
            .readValue(
                structuredBlobStorage.getString(TEXT_UNIT_DTOS_CACHE, cacheName).orElseThrow(),
                TextUnitDTOsCacheBlobStorageJson.class);
    assertTrue(originalSnapshot.getTextUnitDTOs().isEmpty());
    assertNotNull(originalSnapshot.getCacheState());
    var cachedTextUnits =
        cacheService.getTextUnitDTOsForAssetAndLocale(
            asset.getId(), rootLocaleId, true, UpdateType.ALWAYS);
    assertEquals(1, cachedTextUnits.size());
    assertTrue(cachedTextUnits.getFirst().isUsed());
    cachedTextUnits.getFirst().setAssetExtractionId(null);
    var snapshot = new TextUnitDTOsCacheBlobStorageJson();
    snapshot.setTextUnitDTOs(emptyCache ? List.of() : cachedTextUnits);
    snapshot.setCacheState(originalSnapshot.getCacheState());
    // Model snapshots preceding identity creation or the extraction that made an identity used.
    structuredBlobStorage.put(
        TEXT_UNIT_DTOS_CACHE,
        cacheName,
        new ObjectMapper().writeValueAsString(snapshot),
        Retention.PERMANENT);
    assertTrue(structuredBlobStorage.getString(TEXT_UNIT_DTOS_CACHE, cacheName).isPresent());
    var staleCache =
        cacheService.getTextUnitDTOsForAssetAndLocale(
            asset.getId(), rootLocaleId, true, UpdateType.IF_MISSING);
    assertEquals(emptyCache ? 0 : 1, staleCache.size());
    assertTrue(staleCache.stream().noneMatch(textUnit -> textUnit.isUsed()));

    process(asset, "New note");
    TMTextUnit corrected = findTextUnit(repository, "New note");
    assertNotEquals(original.getId(), corrected.getId());
    for (var translation : translations.entrySet()) {
      var current =
          currentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
              localeService.findByBcp47Tag(translation.getKey()).getId(), corrected.getId());
      assertNotNull("Leveraged current variant for " + translation.getKey(), current);
      assertNotNull(
          "Leveraged translation for " + translation.getKey(), current.getTmTextUnitVariant());
      assertEquals(translation.getValue(), current.getTmTextUnitVariant().getContent());
      assertEquals(
          TMTextUnitVariant.Status.TRANSLATION_NEEDED, current.getTmTextUnitVariant().getStatus());
    }
  }

  private TMTextUnit findTextUnit(Repository repository, String comment) {
    return tmTextUnitRepository.findByTm_id(repository.getTm().getId()).stream()
        .filter(unit -> unit.getName().equals("label") && unit.getComment().equals(comment))
        .findFirst()
        .orElseThrow();
  }

  private void process(Asset asset, String comment) throws Exception {
    var content =
        assetContentService.createAssetContent(
            asset,
            "{\"label\":{\"defaultMessage\":\"The harbor is open\",\"description\":\""
                + comment
                + "\"}}");
    assetExtractionService
        .processAssetAsync(
            content.getId(),
            null,
            null,
            List.of(
                LocalizationConverterSelection.PORTABLE_OPTION,
                "noteKeyPattern=description",
                "extractAllPairs=false",
                "exceptions=defaultMessage",
                "removeKeySuffix=/defaultMessage"),
            null)
        .get(30, TimeUnit.SECONDS);
  }
}
