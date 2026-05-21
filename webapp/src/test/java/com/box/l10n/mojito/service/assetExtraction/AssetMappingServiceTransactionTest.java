package com.box.l10n.mojito.service.assetExtraction;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.security.user.User;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class AssetMappingServiceTransactionTest {

  @Test
  public void createTmTextUnitForUnmappedAssetTextUnitsCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestAssetMappingService service = service(transactionManager);

    List<TMTextUnit> result =
        service.createTMTextUnitForUnmappedAssetTextUnits(new User(), 1L, 2L, 3L);

    assertEquals(List.of(), result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createTmTextUnitForUnmappedAssetTextUnitsRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestAssetMappingService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.createTMTextUnitForUnmappedAssetTextUnits(new User(), 1L, 2L, 3L);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError(
        "Expected createTMTextUnitForUnmappedAssetTextUnits to rethrow the failure");
  }

  @Test
  public void saveExactMatchesCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestAssetMappingService service = service(transactionManager);

    int result = service.saveExactMatches(List.of());

    assertEquals(0, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  private TestAssetMappingService service(PlatformTransactionManager transactionManager) {
    TestAssetMappingService service = new TestAssetMappingService();
    service.transactionManager = transactionManager;
    return service;
  }

  private static class TestAssetMappingService extends AssetMappingService {
    RuntimeException failure;

    @Override
    protected List<TMTextUnit> createTMTextUnitForUnmappedAssetTextUnitsNoTx(
        User createdByUser, Long assetExtractionId, Long tmId, Long assetId) {
      if (failure != null) {
        throw failure;
      }
      return List.of();
    }

    @Override
    protected int saveExactMatchesNoTx(List<AssetMappingDTO> exactMatches) {
      return exactMatches.size();
    }
  }
}
