package com.box.l10n.mojito.service.tm.importer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class TextUnitBatchImporterServiceTransactionTest {

  @Test
  public void importTextUnitsOfLocaleAndAssetCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTextUnitBatchImporterService service = service(transactionManager);

    List<TextUnitBatchImporterService.ImportResult> result =
        service.importTextUnitsOfLocaleAndAsset(
            locale(),
            new Asset(),
            List.of(),
            TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT);

    assertEquals(List.of(), result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void importTextUnitsOfLocaleAndAssetRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTextUnitBatchImporterService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.importTextUnitsOfLocaleAndAsset(
          locale(), new Asset(), List.of(), TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected importTextUnitsOfLocaleAndAsset to rethrow the failure");
  }

  private TestTextUnitBatchImporterService service(PlatformTransactionManager transactionManager) {
    TestTextUnitBatchImporterService service = new TestTextUnitBatchImporterService();
    service.transactionManager = transactionManager;
    return service;
  }

  private Locale locale() {
    Locale locale = new Locale();
    locale.setId(10L);
    locale.setBcp47Tag("fr-FR");
    return locale;
  }

  private static class TestTextUnitBatchImporterService extends TextUnitBatchImporterService {
    RuntimeException failure;

    @Override
    List<ImportResult> importTextUnitsOfLocaleAndAssetNoTx(
        Locale locale,
        Asset asset,
        List<TextUnitForBatchMatcherImport> textUnitsToImport,
        ImportMode importMode) {
      if (failure != null) {
        throw failure;
      }
      return List.of();
    }
  }
}
