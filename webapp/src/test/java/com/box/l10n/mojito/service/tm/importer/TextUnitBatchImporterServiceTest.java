package com.box.l10n.mojito.service.tm.importer;

import static com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.IntegrityChecksType.fromLegacy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.AssetTextUnit;
import com.box.l10n.mojito.entity.BulkImportRun;
import com.box.l10n.mojito.entity.BulkImportRunItem;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.service.asset.VirtualAsset;
import com.box.l10n.mojito.service.asset.VirtualAssetBadRequestException;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualAssetTextUnit;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionRepository;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionService;
import com.box.l10n.mojito.service.assetExtraction.AssetMappingService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleCreationException;
import com.box.l10n.mojito.service.repository.RepositoryNameAlreadyUsedException;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTestData;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParametersForTesting;
import com.box.l10n.mojito.test.TestIdWatcher;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * @author jaurambault
 */
public class TextUnitBatchImporterServiceTest extends ServiceTestBase {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(TextUnitBatchImporterServiceTest.class);

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired TextUnitBatchImporterService textUnitBatchImporterService;

  @Autowired PollableTaskService pollableTaskService;

  @Autowired TextUnitSearcher textUnitSearcher;

  @Autowired RepositoryService repositoryService;

  @Autowired VirtualAssetService virtualAssetService;

  @Autowired TMService tmService;

  @Autowired AssetExtractionRepository assetExtractionRepository;

  @Autowired AssetExtractionService assetExtractionService;

  @Autowired AssetMappingService assetMappingService;

  @Autowired BulkImportRunRepository bulkImportRunRepository;

  @Autowired BulkImportRunItemRepository bulkImportRunItemRepository;

  @Autowired BulkImportLineageService bulkImportLineageService;

  @Autowired StructuredBlobStorage structuredBlobStorage;

  @Autowired TMTextUnitVariantRepository tmTextUnitVariantRepository;

  @Test
  public void testAsyncImportTextUnitsNameOnly() throws InterruptedException {
    TMTestData tmTestData = new TMTestData(testIdWatcher);

    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setRepositoryName(tmTestData.repository.getName());
    textUnitDTO.setTargetLocale(tmTestData.frFR.getBcp47Tag());
    textUnitDTO.setAssetPath(tmTestData.asset.getPath());
    textUnitDTO.setName("TEST2");
    textUnitDTO.setTarget("TEST2 translation for fr");
    textUnitDTO.setComment("Comment2");
    textUnitDTO.setTranslatorIdentity("translator@example.com");
    textUnitDTO.setReviewerIdentity("reviewer@example.com");

    TextUnitDTO textUnitDTO2 = new TextUnitDTO();
    textUnitDTO2.setRepositoryName(tmTestData.repository.getName());
    textUnitDTO2.setTargetLocale(tmTestData.frFR.getBcp47Tag());
    textUnitDTO2.setAssetPath(tmTestData.asset.getPath());
    textUnitDTO2.setName("TEST3");
    textUnitDTO2.setTarget("TEST3 translation for fr");
    textUnitDTO2.setTargetComment("TEST3 target comment");

    TextUnitDTO textUnitDTO3 = new TextUnitDTO();
    textUnitDTO3.setRepositoryName(tmTestData.repository.getName());
    textUnitDTO3.setTargetLocale(tmTestData.frFR.getBcp47Tag());
    textUnitDTO3.setAssetPath(tmTestData.asset.getPath());
    textUnitDTO3.setName("zuora_error_message_verify_state_province");
    textUnitDTO3.setTarget("zuora_error_message_verify_state_province translation for fr");

    List<TextUnitDTO> textUnitDTOsForImport =
        Arrays.asList(textUnitDTO, textUnitDTO2, textUnitDTO3);

    PollableFuture<Void> asyncImportTextUnits =
        textUnitBatchImporterService.asyncImportTextUnits(
            textUnitDTOsForImport, fromLegacy(false, false));

    pollableTaskService.waitForPollableTask(asyncImportTextUnits.getPollableTask().getId());

    TextUnitSearcherParameters textUnitSearcherParameters =
        new TextUnitSearcherParametersForTesting();
    textUnitSearcherParameters.setRepositoryNames(Arrays.asList(tmTestData.repository.getName()));
    textUnitSearcherParameters.setAssetPath(tmTestData.asset.getPath());
    textUnitSearcherParameters.setLocaleTags(Arrays.asList("fr-FR"));

    List<TextUnitDTO> textUnitDTOsFromSearch = textUnitSearcher.search(textUnitSearcherParameters);

    int i = 0;

    assertEquals(
        "zuora_error_message_verify_state_province", textUnitDTOsFromSearch.get(i).getName());
    assertEquals(
        "zuora_error_message_verify_state_province translation for fr",
        textUnitDTOsFromSearch.get(i).getTarget());
    i++;
    assertEquals("TEST2", textUnitDTOsFromSearch.get(i).getName());
    assertEquals("TEST2 translation for fr", textUnitDTOsFromSearch.get(i).getTarget());
    assertNull(textUnitDTOsFromSearch.get(i).getTargetComment());
    i++;
    assertEquals("TEST3", textUnitDTOsFromSearch.get(i).getName());
    assertEquals("TEST3 translation for fr", textUnitDTOsFromSearch.get(i).getTarget());
    assertEquals("TEST3 target comment", textUnitDTOsFromSearch.get(i).getTargetComment());
    i++;

    List<BulkImportRun> importRuns =
        bulkImportRunRepository.findByPollableTask_IdOrderByCreatedDateDesc(
            asyncImportTextUnits.getPollableTask().getId());
    assertEquals(1, importRuns.size());
    BulkImportRun importRun = importRuns.getFirst();
    assertEquals(BulkImportRun.Status.COMPLETED, importRun.getStatus());
    assertEquals(BulkImportRun.ActorType.HUMAN, importRun.getActorType());
    assertNotNull(importRun.getInitiatingUser());
    assertEquals(3, importRun.getRequestedCount());
    assertEquals(3, importRun.getImportedCount());
    assertEquals(0, importRun.getSkippedCount());
    assertNotNull(importRun.getCompletedDate());

    List<BulkImportRunItem> runItems =
        bulkImportRunItemRepository.findByRun_IdOrderByIdAsc(importRun.getId());
    assertEquals(3, runItems.size());
    for (BulkImportRunItem item : runItems) {
      assertEquals(BulkImportRunItem.Status.IMPORTED, item.getStatus());
      assertNotNull(item.getResultingTmTextUnitVariant());
      if (item.getTmTextUnit().getId().equals(tmTestData.addTMTextUnit2.getId())) {
        assertEquals("translator@example.com", item.getTranslatorIdentity());
        assertEquals("reviewer@example.com", item.getReviewerIdentity());
        assertFalse(importRun.getActorIdentity().equals(item.getTranslatorIdentity()));
      } else {
        assertEquals(BulkImportLineageService.UNKNOWN_IDENTITY, item.getTranslatorIdentity());
        assertEquals(BulkImportLineageService.UNKNOWN_IDENTITY, item.getReviewerIdentity());
      }
      assertEquals(
          importRun.getInitiatingUser().getId(),
          tmTextUnitVariantRepository
              .findById(item.getResultingTmTextUnitVariant().getId())
              .orElseThrow()
              .getCreatedByUser()
              .getId());
    }

    String input =
        structuredBlobStorage
            .getString(
                StructuredBlobStorage.Prefix.BULK_IMPORT_LINEAGE,
                importRun.getInputPayloadBlobName())
            .orElseThrow();
    String output =
        structuredBlobStorage
            .getString(
                StructuredBlobStorage.Prefix.BULK_IMPORT_LINEAGE,
                importRun.getOutputPayloadBlobName())
            .orElseThrow();
    assertTrue(input.contains("TEST2 translation for fr"));
    assertTrue(input.contains("\"translatorIdentity\":\"translator@example.com\""));
    assertTrue(input.contains("\"reviewerIdentity\":\"reviewer@example.com\""));
    assertTrue(input.contains("\"translatorIdentity\":\"UNKNOWN\""));
    assertTrue(output.contains("\"status\":\"COMPLETED\""));
    assertTrue(output.contains("\"resultingTmTextUnitVariantId\":"));
    assertEquals(
        importRun.getRunId(),
        bulkImportLineageService
            .findRunsForTextUnit(
                runItems.getFirst().getTmTextUnit().getId(), tmTestData.frFR.getId())
            .getFirst()
            .runId());
  }

  @Test
  public void testImportWithoutPrincipalUsesExplicitUnknownActor() {
    TMTestData tmTestData = new TMTestData(testIdWatcher);
    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setRepositoryName(tmTestData.repository.getName());
    textUnitDTO.setTargetLocale(tmTestData.frFR.getBcp47Tag());
    textUnitDTO.setAssetPath(tmTestData.asset.getPath());
    textUnitDTO.setName("TEST2");
    textUnitDTO.setTarget("Unknown actor translation");

    SecurityContext originalContext = SecurityContextHolder.getContext();
    try {
      SecurityContextHolder.clearContext();
      textUnitBatchImporterService.importTextUnits(
          List.of(textUnitDTO),
          TextUnitBatchImporterService.IntegrityChecksType.SKIP,
          TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT);
    } finally {
      SecurityContextHolder.setContext(originalContext);
    }

    BulkImportRunItem item =
        bulkImportRunItemRepository
            .findByTmTextUnit_IdAndLocale_IdOrderByRun_CreatedDateDescIdDesc(
                tmTestData.addTMTextUnit2.getId(), tmTestData.frFR.getId())
            .getFirst();
    BulkImportRun run = bulkImportRunRepository.findById(item.getRun().getId()).orElseThrow();
    assertEquals(BulkImportRun.ActorType.UNKNOWN, run.getActorType());
    assertNull(run.getActorIdentity());
    assertNull(run.getInitiatingUser());
    assertEquals(BulkImportLineageService.UNKNOWN_IDENTITY, item.getTranslatorIdentity());
    assertEquals(BulkImportLineageService.UNKNOWN_IDENTITY, item.getReviewerIdentity());
    assertNull(
        tmTextUnitVariantRepository
            .findById(item.getResultingTmTextUnitVariant().getId())
            .orElseThrow()
            .getCreatedByUser());
  }

  @Test
  public void testLineageRecordsPreviousAndResultingVariantsForRollback() {
    TMTestData tmTestData = new TMTestData(testIdWatcher);
    TextUnitDTO firstImport = new TextUnitDTO();
    firstImport.setRepositoryName(tmTestData.repository.getName());
    firstImport.setTargetLocale(tmTestData.frFR.getBcp47Tag());
    firstImport.setAssetPath(tmTestData.asset.getPath());
    firstImport.setName("TEST2");
    firstImport.setTarget("First rollback checkpoint");

    textUnitBatchImporterService.importTextUnits(
        List.of(firstImport),
        TextUnitBatchImporterService.IntegrityChecksType.SKIP,
        TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT);
    BulkImportRunItem firstItem =
        bulkImportRunItemRepository
            .findByTmTextUnit_IdAndLocale_IdOrderByRun_CreatedDateDescIdDesc(
                tmTestData.addTMTextUnit2.getId(), tmTestData.frFR.getId())
            .getFirst();

    TextUnitDTO secondImport = new TextUnitDTO();
    secondImport.setRepositoryName(tmTestData.repository.getName());
    secondImport.setTargetLocale(tmTestData.frFR.getBcp47Tag());
    secondImport.setAssetPath(tmTestData.asset.getPath());
    secondImport.setName("TEST2");
    secondImport.setTarget("Second rollback checkpoint");

    textUnitBatchImporterService.importTextUnits(
        List.of(secondImport),
        TextUnitBatchImporterService.IntegrityChecksType.SKIP,
        TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT);
    BulkImportRunItem secondItem =
        bulkImportRunItemRepository
            .findByTmTextUnit_IdAndLocale_IdOrderByRun_CreatedDateDescIdDesc(
                tmTestData.addTMTextUnit2.getId(), tmTestData.frFR.getId())
            .getFirst();

    assertEquals(
        firstItem.getResultingTmTextUnitVariant().getId(),
        secondItem.getPreviousTmTextUnitVariantId());
    assertFalse(
        secondItem
            .getPreviousTmTextUnitVariantId()
            .equals(secondItem.getResultingTmTextUnitVariant().getId()));
  }

  @Test
  public void testBatchApiPreservesInitiatorTranslatorReviewerAndAdminAuditLookup()
      throws InterruptedException {
    TMTestData tmTestData = new TMTestData(testIdWatcher);
    Map<String, Object> textUnit =
        Map.of(
            "repositoryName", tmTestData.repository.getName(),
            "targetLocale", tmTestData.frFR.getBcp47Tag(),
            "assetPath", tmTestData.asset.getPath(),
            "name", "TEST2",
            "target", "Translation with explicit provenance",
            "translatedBy", "actual-translator@example.com",
            "approvedBy", "actual-reviewer@example.com");

    PollableTask task =
        authenticatedRestTemplate.postForObject(
            "/api/textunitsBatch", Map.of("textUnits", List.of(textUnit)), PollableTask.class);
    pollableTaskService.waitForPollableTask(task.getId());

    BulkImportRun run =
        bulkImportRunRepository
            .findByPollableTask_IdOrderByCreatedDateDesc(task.getId())
            .getFirst();
    BulkImportRunItem item =
        bulkImportRunItemRepository.findByRun_IdOrderByIdAsc(run.getId()).getFirst();
    assertEquals(BulkImportLineageService.SOURCE_BATCH_API, run.getSource());
    assertEquals(BulkImportRun.ActorType.HUMAN, run.getActorType());
    assertNotNull(run.getInitiatingUser());
    assertEquals("actual-translator@example.com", item.getTranslatorIdentity());
    assertEquals("actual-reviewer@example.com", item.getReviewerIdentity());
    assertFalse(run.getActorIdentity().equals(item.getTranslatorIdentity()));

    String runAudit =
        authenticatedRestTemplate.getForObject(
            "/api/monitoring/import-lineage/" + run.getRunId(), String.class);
    String affectedTextUnitAudit =
        authenticatedRestTemplate.getForObject(
            "/api/monitoring/import-lineage/text-units/"
                + tmTestData.addTMTextUnit2.getId()
                + "?bcp47Tag=fr-FR",
            String.class);
    String inputPayload =
        authenticatedRestTemplate.getForObject(
            "/api/monitoring/import-lineage/" + run.getRunId() + "/input", String.class);
    String outputPayload =
        authenticatedRestTemplate.getForObject(
            "/api/monitoring/import-lineage/" + run.getRunId() + "/output", String.class);

    assertTrue(runAudit.contains("actual-translator@example.com"));
    assertTrue(affectedTextUnitAudit.contains(run.getRunId()));
    assertTrue(inputPayload.contains("actual-reviewer@example.com"));
    assertTrue(outputPayload.contains(item.getResultingTmTextUnitVariant().getId().toString()));
  }

  @Test
  public void testAsyncImportTextUnitsFromSearch() throws InterruptedException {
    TMTestData tmTestData = new TMTestData(testIdWatcher);

    TextUnitSearcherParameters textUnitSearcherParameters =
        new TextUnitSearcherParametersForTesting();
    textUnitSearcherParameters.setRepositoryNames(Arrays.asList(tmTestData.repository.getName()));
    textUnitSearcherParameters.setAssetPath(tmTestData.asset.getPath());
    textUnitSearcherParameters.setLocaleTags(Arrays.asList("fr-FR"));

    List<TextUnitDTO> textUnitDTOsForImport = textUnitSearcher.search(textUnitSearcherParameters);
    for (TextUnitDTO textUnitDTO : textUnitDTOsForImport) {
      textUnitDTO.setTarget(textUnitDTO.getName() + " from import");
      textUnitDTO.setName(null); // make sure we import by id
    }

    PollableFuture<Void> asyncImportTextUnits =
        textUnitBatchImporterService.asyncImportTextUnits(
            textUnitDTOsForImport, fromLegacy(false, false));
    pollableTaskService.waitForPollableTask(asyncImportTextUnits.getPollableTask().getId());

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertFalse(textUnitDTOs.isEmpty());
    for (TextUnitDTO textUnitDTO : textUnitDTOs) {
      assertEquals(textUnitDTO.getName() + " from import", textUnitDTO.getTarget());
    }
  }

  @Test
  public void testAsyncImportTextUnitsDuplicatedNames() throws InterruptedException {
    TMTestData tmTestData = new TMTestData(testIdWatcher);

    AssetExtraction assetExtraction = new AssetExtraction();
    assetExtraction.setAsset(tmTestData.asset);
    assetExtraction = assetExtractionRepository.save(assetExtraction);

    AssetTextUnit createAssetTextUnit1 =
        assetExtractionService.createAssetTextUnit(
            assetExtraction, "TEST4", "Content4", "comment4");
    AssetTextUnit createAssetTextUnit2 =
        assetExtractionService.createAssetTextUnit(
            assetExtraction, "TEST4", "Content4b", "comment4");

    assetMappingService.mapAssetTextUnitAndCreateTMTextUnit(
        assetExtraction.getId(),
        tmTestData.tm.getId(),
        tmTestData.asset.getId(),
        null,
        PollableTask.INJECT_CURRENT_TASK);
    assetExtractionService.markAssetExtractionAsLastSuccessful(tmTestData.asset, assetExtraction);

    TextUnitSearcherParameters textUnitSearcherParameters =
        new TextUnitSearcherParametersForTesting();
    textUnitSearcherParameters.setRepositoryNames(Arrays.asList(tmTestData.repository.getName()));
    textUnitSearcherParameters.setAssetPath(tmTestData.asset.getPath());
    textUnitSearcherParameters.setLocaleTags(Arrays.asList("fr-FR"));

    List<TextUnitDTO> textUnitDTOsForImport = textUnitSearcher.search(textUnitSearcherParameters);
    for (TextUnitDTO textUnitDTO : textUnitDTOsForImport) {
      if ("Content4".equals(textUnitDTO.getSource())) {
        textUnitDTO.setTarget(textUnitDTO.getName() + " from import");
      } else {
        textUnitDTO.setTarget(textUnitDTO.getName() + " from import b");
      }
      textUnitDTO.setTmTextUnitId(null); // we're testing import by name
    }

    PollableFuture<Void> asyncImportTextUnits =
        textUnitBatchImporterService.asyncImportTextUnits(
            textUnitDTOsForImport, fromLegacy(false, false));
    pollableTaskService.waitForPollableTask(asyncImportTextUnits.getPollableTask().getId());

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertFalse(textUnitDTOs.isEmpty());
    for (TextUnitDTO textUnitDTO : textUnitDTOs) {
      if ("Content4".equals(textUnitDTO.getSource())) {
        assertEquals(textUnitDTO.getName() + " from import", textUnitDTO.getTarget());
      } else {
        assertEquals(textUnitDTO.getName() + " from import b", textUnitDTO.getTarget());
      }
    }
  }

  @Test
  public void testAsyncImportTextUnitsDuplicatedEntries() throws InterruptedException {
    TMTestData tmTestData = new TMTestData(testIdWatcher);

    AssetExtraction assetExtraction = new AssetExtraction();
    assetExtraction.setAsset(tmTestData.asset);
    assetExtraction = assetExtractionRepository.save(assetExtraction);

    AssetTextUnit createAssetTextUnit1 =
        assetExtractionService.createAssetTextUnit(
            assetExtraction, "TEST4", "Content4", "comment4");

    assetMappingService.mapAssetTextUnitAndCreateTMTextUnit(
        assetExtraction.getId(),
        tmTestData.tm.getId(),
        tmTestData.asset.getId(),
        null,
        PollableTask.INJECT_CURRENT_TASK);
    assetExtractionService.markAssetExtractionAsLastSuccessful(tmTestData.asset, assetExtraction);

    TextUnitSearcherParameters textUnitSearcherParameters =
        new TextUnitSearcherParametersForTesting();
    textUnitSearcherParameters.setRepositoryNames(Arrays.asList(tmTestData.repository.getName()));
    textUnitSearcherParameters.setAssetPath(tmTestData.asset.getPath());
    textUnitSearcherParameters.setLocaleTags(Arrays.asList("fr-FR"));

    List<TextUnitDTO> textUnitDTOsForImport = textUnitSearcher.search(textUnitSearcherParameters);

    TextUnitDTO duplicatedEntry = null;

    for (TextUnitDTO textUnitDTO : textUnitDTOsForImport) {
      textUnitDTO.setTmTextUnitId(null); // we're testing import by n ame
      if ("Content4".equals(textUnitDTO.getSource())) {
        duplicatedEntry = textUnitDTO;
        duplicatedEntry.setTarget(duplicatedEntry.getSource() + "-duplicated");
      }
    }
    textUnitDTOsForImport.add(duplicatedEntry);

    PollableFuture<Void> asyncImportTextUnits =
        textUnitBatchImporterService.asyncImportTextUnits(
            textUnitDTOsForImport, fromLegacy(false, false));
    pollableTaskService.waitForPollableTask(asyncImportTextUnits.getPollableTask().getId());

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertFalse(textUnitDTOs.isEmpty());
    for (TextUnitDTO textUnitDTO : textUnitDTOs) {
      if ("Content4".equals(textUnitDTO.getSource())) {
        assertEquals(textUnitDTO.getSource() + "-duplicated", textUnitDTO.getTarget());
      }
    }
  }

  @Test
  public void testImportMulipleRepositoryAssetAndLocale() throws Exception {
    Repository repository1 =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("testImportMulipleRepositoryAssetAndLocale1"));
    Repository repository2 =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("testImportMulipleRepositoryAssetAndLocale2"));

    for (Repository repository : Arrays.asList(repository1, repository2)) {
      RepositoryLocale repositoryLocaleFrFR =
          repositoryService.addRepositoryLocale(repository, "fr-FR");
      RepositoryLocale repositoryLocaleKoKR =
          repositoryService.addRepositoryLocale(repository, "ko-KR");

      VirtualAsset virtualAsset1 = new VirtualAsset();
      virtualAsset1.setRepositoryId(repository.getId());
      virtualAsset1.setPath("default");
      virtualAsset1 = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset1);

      VirtualAsset virtualAsset2 = new VirtualAsset();
      virtualAsset2.setRepositoryId(repository.getId());
      virtualAsset2.setPath("default2");
      virtualAsset2 = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset2);

      List<VirtualAssetTextUnit> virtualAssetTextUnits = new ArrayList<>();

      VirtualAssetTextUnit virtualAssetTextUnit = new VirtualAssetTextUnit();
      virtualAssetTextUnit.setName("name1");
      virtualAssetTextUnit.setContent("content1");
      virtualAssetTextUnit.setComment("comment1");
      virtualAssetTextUnits.add(virtualAssetTextUnit);

      virtualAssetTextUnit = new VirtualAssetTextUnit();
      virtualAssetTextUnit.setName("name2");
      virtualAssetTextUnit.setContent("content2");
      virtualAssetTextUnit.setComment("comment2");
      virtualAssetTextUnits.add(virtualAssetTextUnit);

      virtualAssetService.addTextUnits(virtualAsset1.getId(), virtualAssetTextUnits).get();
      virtualAssetService.addTextUnits(virtualAsset2.getId(), virtualAssetTextUnits).get();
    }

    TextUnitSearcherParameters textUnitSearcherParameters =
        new TextUnitSearcherParametersForTesting();
    textUnitSearcherParameters.setRepositoryNames(
        Arrays.asList(repository1.getName(), repository2.getName()));

    List<TextUnitDTO> textUnitDTOsForImport = textUnitSearcher.search(textUnitSearcherParameters);
    for (TextUnitDTO textUnitDTO : textUnitDTOsForImport) {
      textUnitDTO.setTarget(
          textUnitDTO.getRepositoryName()
              + ":"
              + textUnitDTO.getAssetPath()
              + ":"
              + textUnitDTO.getTargetLocale()
              + ":"
              + textUnitDTO.getName());
    }

    textUnitBatchImporterService
        .asyncImportTextUnits(textUnitDTOsForImport, fromLegacy(false, false))
        .get();

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertFalse(textUnitDTOs.isEmpty());
    for (TextUnitDTO textUnitDTO : textUnitDTOs) {
      assertEquals(
          textUnitDTO.getRepositoryName()
              + ":"
              + textUnitDTO.getAssetPath()
              + ":"
              + textUnitDTO.getTargetLocale()
              + ":"
              + textUnitDTO.getName(),
          textUnitDTO.getTarget());
    }
  }

  @Test
  public void testUnused()
      throws InterruptedException,
          RepositoryNameAlreadyUsedException,
          RepositoryLocaleCreationException,
          VirtualAssetBadRequestException,
          ExecutionException {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("testUnused"));
    RepositoryLocale repositoryLocaleFrFR =
        repositoryService.addRepositoryLocale(repository, "fr-FR");

    VirtualAsset virtualAsset1 = new VirtualAsset();
    virtualAsset1.setRepositoryId(repository.getId());
    virtualAsset1.setPath("default");
    virtualAsset1 = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset1);

    VirtualAssetTextUnit virtualAssetTextUnit = new VirtualAssetTextUnit();
    virtualAssetTextUnit.setName("name1");
    virtualAssetTextUnit.setContent("content1");
    virtualAssetTextUnit.setComment("comment1");

    logger.debug("Create a first unused text unit for name1");
    virtualAssetService
        .addTextUnits(virtualAsset1.getId(), Arrays.asList(virtualAssetTextUnit))
        .get();
    virtualAssetService
        .replaceTextUnits(virtualAsset1.getId(), new ArrayList<VirtualAssetTextUnit>())
        .get();

    TextUnitSearcherParameters textUnitSearcherParameters =
        new TextUnitSearcherParametersForTesting();
    textUnitSearcherParameters.setRepositoryNames(Arrays.asList(repository.getName()));

    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setRepositoryName(repository.getName());
    textUnitDTO.setAssetPath("default");
    textUnitDTO.setName("name1");
    textUnitDTO.setTargetLocale("fr-FR");
    textUnitDTO.setTarget("v1");

    textUnitBatchImporterService
        .asyncImportTextUnits(Arrays.asList(textUnitDTO), fromLegacy(false, false))
        .get();

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertEquals(1, textUnitDTOs.size());
    assertEquals("content1", textUnitDTOs.get(0).getSource());
    assertEquals(
        "Should import since there is a single unused text unit for name1",
        "v1",
        textUnitDTOs.get(0).getTarget());

    virtualAssetTextUnit = new VirtualAssetTextUnit();
    virtualAssetTextUnit.setName("name1");
    virtualAssetTextUnit.setContent("content1 - v2");
    virtualAssetTextUnit.setComment("comment1");

    logger.debug("Create a second unused for text unit for name1");
    virtualAssetService
        .addTextUnits(virtualAsset1.getId(), Arrays.asList(virtualAssetTextUnit))
        .get();
    virtualAssetService
        .replaceTextUnits(virtualAsset1.getId(), new ArrayList<VirtualAssetTextUnit>())
        .get();

    textUnitDTO = new TextUnitDTO();
    textUnitDTO.setRepositoryName(repository.getName());
    textUnitDTO.setAssetPath("default");
    textUnitDTO.setName("name1");
    textUnitDTO.setTargetLocale("fr-FR");
    textUnitDTO.setTarget("v2");

    textUnitBatchImporterService
        .asyncImportTextUnits(Arrays.asList(textUnitDTO), fromLegacy(false, false))
        .get();

    textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertEquals(2, textUnitDTOs.size());
    assertEquals("content1", textUnitDTOs.get(0).getSource());
    assertEquals("v1", textUnitDTOs.get(0).getTarget());
    assertEquals("content1 - v2", textUnitDTOs.get(1).getSource());
    assertNull(
        "Should not import since there is 2 text unit for name1", textUnitDTOs.get(1).getTarget());

    virtualAssetService
        .addTextUnits(virtualAsset1.getId(), Arrays.asList(virtualAssetTextUnit))
        .get();
    textUnitBatchImporterService
        .asyncImportTextUnits(Arrays.asList(textUnitDTO), fromLegacy(false, false))
        .get();

    textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertEquals(2, textUnitDTOs.size());
    assertEquals("content1", textUnitDTOs.get(0).getSource());
    assertEquals("v1", textUnitDTOs.get(0).getTarget());
    assertEquals("content1 - v2", textUnitDTOs.get(1).getSource());
    assertEquals("v2", textUnitDTOs.get(1).getTarget());
  }

  @Test
  public void testIntegirtyChecker() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("testIntegirtyChecker"));
    RepositoryLocale repositoryLocaleFrFR =
        repositoryService.addRepositoryLocale(repository, "fr-FR");
    Locale frFR = repositoryLocaleFrFR.getLocale();

    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repository.getId());
    virtualAsset.setPath("default");
    virtualAsset = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset);

    VirtualAssetTextUnit virtualAssetTextUnit = new VirtualAssetTextUnit();
    virtualAssetTextUnit.setName("name1");
    virtualAssetTextUnit.setContent("with {placeholder}");
    virtualAssetService
        .addTextUnits(virtualAsset.getId(), Arrays.asList(virtualAssetTextUnit))
        .get();

    AssetIntegrityChecker assetIntegrityChecker = new AssetIntegrityChecker();
    assetIntegrityChecker.setAssetExtension("");
    assetIntegrityChecker.setIntegrityCheckerType(IntegrityCheckerType.MESSAGE_FORMAT);
    repositoryService.updateAssetIntegrityCheckers(
        repository, Sets.newHashSet(assetIntegrityChecker));

    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setRepositoryName(repository.getName());
    textUnitDTO.setTargetLocale(frFR.getBcp47Tag());
    textUnitDTO.setAssetPath(virtualAsset.getPath());
    textUnitDTO.setName("name1");
    textUnitDTO.setTarget("with some broken {placeholder");

    PollableFuture<Void> asyncImportTextUnits =
        textUnitBatchImporterService.asyncImportTextUnits(
            Arrays.asList(textUnitDTO), fromLegacy(false, false));
    pollableTaskService.waitForPollableTask(asyncImportTextUnits.getPollableTask().getId());

    TextUnitSearcherParameters textUnitSearcherParameters =
        new TextUnitSearcherParametersForTesting();
    textUnitSearcherParameters.setRepositoryNames(Arrays.asList(repository.getName()));
    textUnitSearcherParameters.setName("name1");

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertEquals(1, textUnitDTOs.size());
    assertEquals("name1", textUnitDTOs.get(0).getName());
    assertEquals("with some broken {placeholder", textUnitDTOs.get(0).getTarget());
    assertFalse(
        "Should be excluded with broken placeholder",
        textUnitDTOs.get(0).isIncludedInLocalizedFile());

    textUnitDTO.setTarget("with fixed {placeholder}");
    asyncImportTextUnits =
        textUnitBatchImporterService.asyncImportTextUnits(
            Arrays.asList(textUnitDTO), fromLegacy(false, false));
    pollableTaskService.waitForPollableTask(asyncImportTextUnits.getPollableTask().getId());

    textUnitDTOs = textUnitSearcher.search(textUnitSearcherParameters);
    assertEquals(1, textUnitDTOs.size());
    assertEquals("name1", textUnitDTOs.get(0).getName());
    assertEquals("with fixed {placeholder}", textUnitDTOs.get(0).getTarget());
    assertTrue(
        "should be included with proper placeholder",
        textUnitDTOs.get(0).isIncludedInLocalizedFile());
  }
}
