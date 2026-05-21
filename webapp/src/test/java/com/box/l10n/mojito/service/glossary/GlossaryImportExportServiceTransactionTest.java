package com.box.l10n.mojito.service.glossary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class GlossaryImportExportServiceTransactionTest {

  @Test
  public void exportGlossaryCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    Glossary glossary = glossary();
    Asset asset = asset(glossary.getBackingRepository());
    when(glossaryRepository.findByIdWithBindings(10L)).thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset);
    when(textUnitSearcher.search(any())).thenReturn(List.of());
    GlossaryImportExportService service =
        service(glossaryRepository, glossaryStorageService, textUnitSearcher, transactionManager);

    GlossaryImportExportService.ExportPayload payload = service.exportGlossary(10L, "json");

    assertEquals("json", payload.format());
    assertTrue(payload.content().contains("\"terms\""));
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void exportGlossaryRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    when(glossaryRepository.findByIdWithBindings(10L))
        .thenThrow(new IllegalStateException("failed"));
    GlossaryImportExportService service =
        service(
            glossaryRepository,
            mock(GlossaryStorageService.class),
            mock(TextUnitSearcher.class),
            transactionManager);

    try {
      service.exportGlossary(10L, "json");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected exportGlossary to rethrow the repository failure");
  }

  @Test
  public void importGlossaryCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    Glossary glossary = glossary();
    when(glossaryRepository.findByIdWithBindings(10L)).thenReturn(Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary))
        .thenReturn(asset(glossary.getBackingRepository()));
    GlossaryImportExportService service =
        service(
            glossaryRepository,
            glossaryStorageService,
            mock(TextUnitSearcher.class),
            transactionManager);

    GlossaryImportExportService.ImportResult result = service.importGlossary(10L, "json", "[]");

    assertEquals(0, result.createdTermCount());
    assertEquals(0, result.updatedTermCount());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void importGlossaryRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    when(glossaryRepository.findByIdWithBindings(10L))
        .thenThrow(new IllegalStateException("failed"));
    GlossaryImportExportService service =
        service(
            glossaryRepository,
            mock(GlossaryStorageService.class),
            mock(TextUnitSearcher.class),
            transactionManager);

    try {
      service.importGlossary(10L, "json", "[]");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected importGlossary to rethrow the repository failure");
  }

  private GlossaryImportExportService service(
      GlossaryRepository glossaryRepository,
      GlossaryStorageService glossaryStorageService,
      TextUnitSearcher textUnitSearcher,
      PlatformTransactionManager transactionManager) {
    return new GlossaryImportExportService(
        glossaryRepository,
        glossaryStorageService,
        textUnitSearcher,
        mock(GlossaryTermMetadataRepository.class),
        mock(VirtualTextUnitBatchUpdaterService.class),
        mock(TextUnitBatchImporterService.class),
        mock(TMTextUnitRepository.class),
        transactionManager);
  }

  private Glossary glossary() {
    Glossary glossary = new Glossary();
    glossary.setId(10L);
    glossary.setName("Test Glossary");
    glossary.setDescription("Test glossary description");
    glossary.setBackingRepository(repository());
    return glossary;
  }

  private Repository repository() {
    Repository repository = new Repository();
    repository.setId(20L);
    repository.setName("repo");
    return repository;
  }

  private Asset asset(Repository repository) {
    Asset asset = new Asset();
    asset.setId(30L);
    asset.setRepository(repository);
    asset.setPath("glossary");
    return asset;
  }
}
