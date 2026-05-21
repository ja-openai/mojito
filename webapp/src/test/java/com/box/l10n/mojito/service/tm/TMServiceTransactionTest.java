package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.sf.okapi.common.pipelinedriver.IPipelineDriver;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class TMServiceTransactionTest {

  @Test
  public void exportAssetAsXLIFFCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTMService service = service(transactionManager);

    String result = service.exportAssetAsXLIFF(1L, "fr-FR");

    assertEquals("xliff", result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void exportAssetAsXLIFFRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTMService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.exportAssetAsXLIFF(1L, "fr-FR");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected exportAssetAsXLIFF to rethrow the failure");
  }

  @Test
  public void processBatchInTransactionCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTMService service = service(transactionManager);

    service.processBatchInTransaction(mock(IPipelineDriver.class));

    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void processBatchInTransactionRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTMService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.processBatchInTransaction(mock(IPipelineDriver.class));
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected processBatchInTransaction to rethrow the failure");
  }

  private TestTMService service(PlatformTransactionManager transactionManager) {
    TestTMService service = new TestTMService();
    service.transactionManager = transactionManager;
    return service;
  }

  private static class TestTMService extends TMService {
    RuntimeException failure;

    @Override
    String exportAssetAsXLIFFNoTx(Long assetId, String bcp47Tag) {
      if (failure != null) {
        throw failure;
      }
      return "xliff";
    }

    @Override
    void processBatchNoTx(IPipelineDriver driver) {
      if (failure != null) {
        throw failure;
      }
    }
  }
}
