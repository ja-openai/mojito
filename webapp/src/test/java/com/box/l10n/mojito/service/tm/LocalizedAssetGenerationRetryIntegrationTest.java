package com.box.l10n.mojito.service.tm;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.TEXT_UNIT_DTOS_CACHE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.okapi.asset.AssetPathToFilterConfigMapper;
import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheBlobStorageJson;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real shared generation must tolerate replay after its lineage writes have committed.
 *
 * <p>Use the standard service-test context: legacy Quartz and Pollable singletons cannot safely
 * span application contexts backed by different databases in the same test JVM.
 */
public class LocalizedAssetGenerationRetryIntegrationTest extends ServiceTestBase {

  @Autowired LocalizedAssetGenerationService generationService;
  @Autowired RepositoryService repositoryService;
  @Autowired RepositoryLocaleRepository repositoryLocaleRepository;
  @Autowired AssetService assetService;
  @Autowired TMService tmService;
  @Autowired ObjectMapper objectMapper;

  @Autowired
  @Qualifier("fail_on_unknown_properties_false")
  ObjectMapper cacheObjectMapper;

  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired StructuredBlobStorage structuredBlobStorage;

  private Repository repository;
  private Asset asset;
  private Long localeId;
  private TMTextUnit textUnit;
  private TMTextUnitVariant originalVariant;
  private String sourceXliff;
  private String pullRunName;
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @After
  public void closeTestMeters() {
    meters.close();
  }

  @Before
  public void createCommittedFixture() throws Exception {
    // A test transaction would hide the independently committed first-attempt lineage.
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    repository = repositoryService.createRepository("generation-retry-" + UUID.randomUUID());
    repositoryService.addRepositoryLocale(repository, "fr-FR");
    RepositoryLocale repositoryLocale =
        repositoryLocaleRepository.findByRepositoryAndLocale_Bcp47Tag(repository, "fr-FR");
    localeId = repositoryLocale.getLocale().getId();
    asset =
        assetService.createAssetWithContent(
            repository.getId(), "generation-retry.xliff", "fixture content");
    textUnit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), asset.getId(), "home", "Home", "Navigation label");
    originalVariant = tmService.addCurrentTMTextUnitVariant(textUnit.getId(), localeId, "Accueil");
    sourceXliff =
        xliffDataFactory.generateSourceXliff(
            List.of(
                xliffDataFactory.createTextUnit(
                    textUnit.getId(),
                    textUnit.getName(),
                    textUnit.getContent(),
                    textUnit.getComment())));
    pullRunName = "generation-retry-" + UUID.randomUUID();
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  @Test
  public void replayWithOmittedOutputTagKeepsOneAssociation() throws Exception {
    assertReplay(null, false);
  }

  @Test
  public void replayWithExplicitOutputTagKeepsOneAssociation() throws Exception {
    assertReplay("fr-FR", false);
  }

  @Test
  public void replayWithOmittedOutputTagReplacesPreviouslySelectedVariant() throws Exception {
    assertReplay(null, true);
  }

  @Test
  public void untrackedOkapiGenerationRefreshesSharedTranslationCache() throws Exception {
    assertUntrackedCacheRefresh(
        asset, textUnit, originalVariant, sourceXliff, LocalizationConverterSelection.OKAPI_OPTION);
  }

  @Test
  public void untrackedPortableGenerationRefreshesSharedTranslationCache() throws Exception {
    assertUntrackedPortableCacheRefresh("cache-effects.properties", "home=Home\n", null);
  }

  @Test
  public void untrackedPortableFormatJsGenerationRefreshesSharedTranslationCache()
      throws Exception {
    assertUntrackedPortableCacheRefresh(
        "cache-effects.json",
        "{\"home\":{\"defaultMessage\":\"Home\",\"description\":\"Navigation label\"}}",
        "Navigation label");
  }

  @Test
  public void generationTimerRetainsNameTagAndCount() throws Exception {
    assertTrue(generateWithTimer(meters, null).contains("Accueil"));
    assertEquals(
        1,
        meters
            .get("TMService.generateLocalizedBase")
            .tag("repositoryId", repository.getId().toString())
            .timer()
            .count());
  }

  @Test
  public void generationTimerCollisionPreservesRealOutput() throws Exception {
    Gauge.builder("TMService.generateLocalizedBase", () -> 0)
        .tag("repositoryId", repository.getId().toString())
        .register(meters);
    assertTrue(generateWithTimer(meters, null).contains("Accueil"));
  }

  @Test
  public void generationTimerProviderErrorPreservesRealOutput() throws Exception {
    AtomicInteger attempts = failGenerationTimer(meters, new AssertionError("provider failed"));
    assertTrue(generateWithTimer(meters, null).contains("Accueil"));
    assertEquals(1, attempts.get());
  }

  @Test
  public void generationFailureSurvivesNonfatalTimerError() throws Exception {
    AtomicInteger attempts = failGenerationTimer(meters, new AssertionError("provider failed"));
    UnsupportedAssetFilterTypeException failure =
        new UnsupportedAssetFilterTypeException("unsupported test filter");
    assertSame(
        failure,
        assertThrows(
            UnsupportedAssetFilterTypeException.class, () -> generateWithTimer(meters, failure)));
    assertEquals(1, attempts.get());
    assertEquals(0, failure.getSuppressed().length);
  }

  @Test
  public void generationTimerFatalErrorPropagatesAfterSuccessfulGeneration() throws Exception {
    VirtualMachineError fatal = new VirtualMachineError("fatal provider") {};
    failGenerationTimer(meters, fatal);
    assertSame(
        fatal, assertThrows(VirtualMachineError.class, () -> generateWithTimer(meters, null)));
  }

  @Test
  public void generationTimerThreadDeathIsNotHiddenByBusinessFailure() throws Exception {
    ThreadDeath fatal = new ThreadDeath();
    failGenerationTimer(meters, fatal);
    IllegalStateException failure = new IllegalStateException("business failure");
    assertSame(fatal, assertThrows(ThreadDeath.class, () -> generateWithTimer(meters, failure)));
  }

  @Test
  public void fatalGenerationSkipsTelemetryAndPropagatesOriginalFailure() throws Exception {
    AtomicInteger attempts = failGenerationTimer(meters, new ThreadDeath());
    VirtualMachineError fatal = new VirtualMachineError("fatal generation") {};
    assertSame(
        fatal, assertThrows(VirtualMachineError.class, () -> generateWithTimer(meters, fatal)));
    assertEquals(0, attempts.get());
    assertEquals(0, fatal.getSuppressed().length);
  }

  private AtomicInteger failGenerationTimer(SimpleMeterRegistry meters, Error failure) {
    AtomicInteger attempts = new AtomicInteger();
    meters
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("TMService.generateLocalizedBase")) {
                attempts.incrementAndGet();
                throw failure;
              }
            });
    return attempts;
  }

  private String generateWithTimer(SimpleMeterRegistry meters, Throwable filterFailure)
      throws Exception {
    // Copy injected dependencies; never replace metrics on the shared Spring singleton.
    TMService privateService = spy(tmService);
    privateService.meterRegistry = meters;
    if (filterFailure != null) {
      privateService.assetPathToFilterConfigMapper = mock(AssetPathToFilterConfigMapper.class);
      when(privateService.assetPathToFilterConfigMapper.getFilterConfigIdFromPath(asset.getPath()))
          .thenThrow(filterFailure);
    }
    RepositoryLocale repositoryLocale =
        repositoryLocaleRepository.findByRepositoryAndLocale_Bcp47Tag(repository, "fr-FR");
    return privateService.generateLocalized(
        asset,
        sourceXliff,
        repositoryLocale,
        null,
        null,
        List.of(LocalizationConverterSelection.OKAPI_OPTION),
        Status.ALL,
        InheritanceMode.USE_PARENT,
        null);
  }

  private void assertUntrackedPortableCacheRefresh(String path, String source, String comment)
      throws Exception {
    Asset portableAsset = assetService.createAssetWithContent(repository.getId(), path, source);
    TMTextUnit portableUnit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), portableAsset.getId(), "home", "Home", comment);
    TMTextUnitVariant portableVariant =
        tmService.addCurrentTMTextUnitVariant(portableUnit.getId(), localeId, "Accueil");
    assertUntrackedCacheRefresh(
        portableAsset,
        portableUnit,
        portableVariant,
        source,
        LocalizationConverterSelection.PORTABLE_OPTION);
  }

  private void assertUntrackedCacheRefresh(
      Asset inputAsset,
      TMTextUnit inputUnit,
      TMTextUnitVariant initialVariant,
      String source,
      String converterOption)
      throws Exception {
    String cacheKey = "asset/" + inputAsset.getId() + "/locale/" + localeId;
    assertFalse(structuredBlobStorage.getString(TEXT_UNIT_DTOS_CACHE, cacheKey).isPresent());
    LocalizedAssetBody input = new LocalizedAssetBody();
    input.setAssetId(inputAsset.getId());
    input.setLocaleId(localeId);
    input.setContent(source);
    input.setFilterOptions(List.of(converterOption));
    input.setStatus(Status.ALL);
    input.setInheritanceMode(InheritanceMode.USE_PARENT);
    assertNull(input.getPullRunName());
    String serializedInput = objectMapper.writeValueAsString(input);
    List<Long> firstVariantIds = variantIds(inputUnit.getId());

    LocalizedAssetBody first =
        generationService.generate(
            objectMapper.readValue(serializedInput, LocalizedAssetBody.class));
    assertTrue(first.getContent().contains(initialVariant.getContent()));
    assertEquals(List.of(initialVariant.getId()), cachedVariantIds(cacheKey));
    assertEquals(firstVariantIds, variantIds(inputUnit.getId()));

    TMTextUnitVariant nextVariant =
        tmService.addCurrentTMTextUnitVariant(inputUnit.getId(), localeId, "Page principale");
    List<Long> nextVariantIds = variantIds(inputUnit.getId());
    // Read storage directly: a cache-service read could itself refresh the entry under test.
    assertEquals(List.of(initialVariant.getId()), cachedVariantIds(cacheKey));
    LocalizedAssetBody second =
        generationService.generate(
            objectMapper.readValue(serializedInput, LocalizedAssetBody.class));
    assertTrue(second.getContent().contains(nextVariant.getContent()));
    assertEquals(List.of(nextVariant.getId()), cachedVariantIds(cacheKey));
    assertEquals(
        Long.valueOf(0),
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pull_run WHERE repository_id = ?",
            Long.class,
            repository.getId()));
    assertEquals(nextVariantIds, variantIds(inputUnit.getId()));
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  private List<Long> variantIds(long inputUnitId) {
    return jdbcTemplate.queryForList(
        "SELECT id FROM tm_text_unit_variant WHERE tm_text_unit_id = ? ORDER BY id",
        Long.class,
        inputUnitId);
  }

  private List<Long> cachedVariantIds(String cacheKey) throws Exception {
    return cacheObjectMapper
        .readValue(
            structuredBlobStorage.getString(TEXT_UNIT_DTOS_CACHE, cacheKey).orElseThrow(),
            TextUnitDTOsCacheBlobStorageJson.class)
        .getTextUnitDTOs()
        .stream()
        .map(TextUnitDTO::getTmTextUnitVariantId)
        .toList();
  }

  private void assertReplay(String outputTag, boolean changeTranslation) throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    input.setAssetId(asset.getId());
    input.setLocaleId(localeId);
    input.setContent(sourceXliff);
    input.setOutputBcp47tag(outputTag);
    input.setPullRunName(pullRunName);
    input.setStatus(Status.ALL);
    input.setInheritanceMode(InheritanceMode.USE_PARENT);
    String serializedInput = objectMapper.writeValueAsString(input);

    LocalizedAssetBody first = generateFromOriginalInput(serializedInput);
    assertTrue(first.getContent().contains("Accueil"));
    assertEquals("fr-FR", first.getBcp47Tag());
    long pullRunId = assertSinglePullRun();
    long pullRunAssetId = assertSinglePullRunAsset(pullRunId);
    assertEquals(List.of(originalVariant.getId()), selectedVariantIds(pullRunAssetId));

    TMTextUnitVariant expectedVariant = originalVariant;
    if (changeTranslation) {
      expectedVariant =
          tmService.addCurrentTMTextUnitVariant(textUnit.getId(), localeId, "Page principale");
      assertNotEquals(originalVariant.getId(), expectedVariant.getId());
    }

    // The service mutates the body. A retry must deserialize the original durable input again.
    LocalizedAssetBody second = generateFromOriginalInput(serializedInput);
    assertEquals("fr-FR", second.getBcp47Tag());
    assertTrue(second.getContent().contains(expectedVariant.getContent()));
    if (!changeTranslation) {
      assertEquals(first.getContent(), second.getContent());
    }
    assertEquals(pullRunId, assertSinglePullRun());
    assertEquals(pullRunAssetId, assertSinglePullRunAsset(pullRunId));
    assertEquals(List.of(expectedVariant.getId()), selectedVariantIds(pullRunAssetId));
    assertEquals(
        List.of(new Association(localeId, expectedVariant.getId(), outputTag)),
        jdbcTemplate.query(
            "SELECT locale_id, tm_text_unit_variant_id, output_bcp47_tag"
                + " FROM pull_run_text_unit_variant WHERE pull_run_asset_id = ?",
            (row, index) ->
                new Association(
                    row.getLong("locale_id"),
                    row.getLong("tm_text_unit_variant_id"),
                    row.getString("output_bcp47_tag")),
            pullRunAssetId));
  }

  private LocalizedAssetBody generateFromOriginalInput(String serializedInput) throws Exception {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    LocalizedAssetBody attempt = objectMapper.readValue(serializedInput, LocalizedAssetBody.class);
    assertEquals(sourceXliff, attempt.getContent());
    assertNull(attempt.getBcp47Tag());
    LocalizedAssetBody result = generationService.generate(attempt);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    return result;
  }

  private long assertSinglePullRun() {
    List<Long> ids =
        jdbcTemplate.queryForList(
            "SELECT id FROM pull_run WHERE name = ? AND repository_id = ?",
            Long.class,
            pullRunName,
            repository.getId());
    assertEquals(1, ids.size());
    return ids.get(0);
  }

  private long assertSinglePullRunAsset(long pullRunId) {
    List<Long> assetIds =
        jdbcTemplate.queryForList(
            "SELECT asset_id FROM pull_run_asset WHERE pull_run_id = ?", Long.class, pullRunId);
    assertEquals(List.of(asset.getId()), assetIds);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM pull_run_asset WHERE pull_run_id = ?", Long.class, pullRunId);
  }

  private List<Long> selectedVariantIds(long pullRunAssetId) {
    return jdbcTemplate.queryForList(
        "SELECT tm_text_unit_variant_id FROM pull_run_text_unit_variant"
            + " WHERE pull_run_asset_id = ? ORDER BY tm_text_unit_variant_id",
        Long.class,
        pullRunAssetId);
  }

  private record Association(long localeId, long variantId, String outputTag) {}
}
