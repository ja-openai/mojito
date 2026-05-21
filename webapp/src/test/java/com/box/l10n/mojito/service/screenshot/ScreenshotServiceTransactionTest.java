package com.box.l10n.mojito.service.screenshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Screenshot;
import com.box.l10n.mojito.entity.ScreenshotRun;
import com.box.l10n.mojito.service.tm.search.SearchType;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class ScreenshotServiceTransactionTest {

  @Test
  public void createOrAddToScreenshotRunCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestScreenshotService service = service(transactionManager);

    ScreenshotRun result = service.createOrAddToScreenshotRun(service.screenshotRun, true);

    assertSame(service.screenshotRun, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createOrAddToScreenshotRunRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestScreenshotService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.createOrAddToScreenshotRun(service.screenshotRun, true);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected createOrAddToScreenshotRun to rethrow the failure");
  }

  @Test
  public void searchScreenshotsCommitsReadOnlyTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestScreenshotService service = service(transactionManager);

    List<Screenshot> result =
        service.searchScreenshots(
            List.of(1L),
            List.of("fr-FR"),
            "screen",
            Screenshot.Status.ACCEPTED,
            "name",
            "source",
            "target",
            SearchType.EXACT,
            ScreenshotRunType.LAST_SUCCESSFUL_RUN,
            0,
            10);

    assertSame(service.screenshots, result);
    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void deleteScreenshotRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestScreenshotService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.deleteScreenshot(1L);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected deleteScreenshot to rethrow the failure");
  }

  private TestScreenshotService service(PlatformTransactionManager transactionManager) {
    TestScreenshotService service = new TestScreenshotService();
    service.transactionManager = transactionManager;
    return service;
  }

  private static class TestScreenshotService extends ScreenshotService {
    ScreenshotRun screenshotRun = new ScreenshotRun();
    List<Screenshot> screenshots = List.of(new Screenshot());
    RuntimeException failure;

    @Override
    ScreenshotRun createOrAddToScreenshotRunNoTx(
        ScreenshotRun screenshotRun, boolean setLastSuccessfulScreenshotRun) {
      if (failure != null) {
        throw failure;
      }
      return screenshotRun;
    }

    @Override
    List<Screenshot> searchScreenshotsNoTx(
        List<Long> repositoryIds,
        List<String> bcp47Tags,
        String screenshotName,
        Screenshot.Status status,
        String name,
        String source,
        String target,
        SearchType searchType,
        ScreenshotRunType screenshotRunType,
        int offset,
        int limit) {
      if (failure != null) {
        throw failure;
      }
      return screenshots;
    }

    @Override
    void deleteScreenshotNoTx(Long id) {
      if (failure != null) {
        throw failure;
      }
    }
  }
}
