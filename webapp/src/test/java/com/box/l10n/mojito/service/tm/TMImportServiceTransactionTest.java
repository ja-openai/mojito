package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Repository;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class TMImportServiceTransactionTest {

  @Test
  public void importXliffCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTMImportService service = service(transactionManager);

    service.importXLIFF(repository(), "<xliff/>", true);

    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void importXliffRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTMImportService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.importXLIFF(repository(), "<xliff/>", true);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected importXLIFF to rethrow the import failure");
  }

  private TestTMImportService service(PlatformTransactionManager transactionManager) {
    TestTMImportService service = new TestTMImportService();
    service.transactionManager = transactionManager;
    return service;
  }

  private Repository repository() {
    Repository repository = new Repository();
    repository.setId(10L);
    repository.setName("repo");
    return repository;
  }

  private static class TestTMImportService extends TMImportService {
    RuntimeException failure;

    @Override
    void importXLIFFNoTx(ImportExportedXliffStep importExportedXliffStep, String xliffContent) {
      if (failure != null) {
        throw failure;
      }
    }
  }
}
