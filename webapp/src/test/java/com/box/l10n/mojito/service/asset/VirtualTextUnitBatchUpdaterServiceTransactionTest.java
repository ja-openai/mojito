package com.box.l10n.mojito.service.asset;

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class VirtualTextUnitBatchUpdaterServiceTransactionTest {

  @Test
  public void updateTextUnitsCommitsTransaction() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualTextUnitBatchUpdaterService service = service(transactionManager);

    service.updateTextUnits(new Asset(), List.of(), false);

    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateTextUnitsRollsBackCheckedException() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualTextUnitBatchUpdaterService service = service(transactionManager);
    VirtualAssetRequiredException failure = new VirtualAssetRequiredException("virtual required");
    service.failure = failure;

    try {
      service.updateTextUnits(new Asset(), List.of(), false);
    } catch (VirtualAssetRequiredException e) {
      assertSame(failure, e);
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected updateTextUnits to rethrow the checked failure");
  }

  private TestVirtualTextUnitBatchUpdaterService service(
      PlatformTransactionManager transactionManager) {
    TestVirtualTextUnitBatchUpdaterService service = new TestVirtualTextUnitBatchUpdaterService();
    service.transactionManager = transactionManager;
    return service;
  }

  private static class TestVirtualTextUnitBatchUpdaterService
      extends VirtualTextUnitBatchUpdaterService {
    VirtualAssetRequiredException failure;

    @Override
    void updateTextUnitsNoTx(
        Asset asset, List<VirtualAssetTextUnit> virtualAssetTextUnits, boolean replace)
        throws VirtualAssetRequiredException {
      if (failure != null) {
        throw failure;
      }
    }
  }
}
