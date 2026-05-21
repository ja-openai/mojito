package com.box.l10n.mojito.service.asset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitDTO;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class VirtualAssetServiceTransactionTest {

  @Test
  public void createOrUpdateVirtualAssetCommitsTransaction() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualAssetService service = service(transactionManager);
    VirtualAsset virtualAsset = new VirtualAsset();

    VirtualAsset result = service.createOrUpdateVirtualAsset(virtualAsset);

    assertSame(virtualAsset, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createOrUpdateVirtualAssetRollsBackCheckedException() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualAssetService service = service(transactionManager);
    service.virtualAssetFailure = new VirtualAssetBadRequestException("failed");

    try {
      service.createOrUpdateVirtualAsset(new VirtualAsset());
    } catch (VirtualAssetBadRequestException e) {
      assertSame(service.virtualAssetFailure, e);
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected createOrUpdateVirtualAsset to rethrow the checked failure");
  }

  @Test
  public void getLocalizedTextUnitsRollsBackCheckedException() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualAssetService service = service(transactionManager);
    service.requiredFailure = new VirtualAssetRequiredException("failed");

    try {
      service.getLocalizedTextUnits(1L, 2L, InheritanceMode.USE_PARENT);
    } catch (VirtualAssetRequiredException e) {
      assertSame(service.requiredFailure, e);
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected getLocalizedTextUnits to rethrow the checked failure");
  }

  @Test
  public void findByAssetExtractionIdAndDoNotTranslateFilterCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualAssetService service = service(transactionManager);

    List<AssetTextUnitDTO> result =
        service.findByAssetExtractionIdAndDoNotTranslateFilter(1L, true);

    assertSame(service.assetTextUnitDTOs, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void addTextUnitRollsBackCheckedException() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualAssetService service = service(transactionManager);
    service.requiredFailure = new VirtualAssetRequiredException("failed");

    try {
      service.addTextUnit(1L, "name", "content", "comment", null, null, false);
    } catch (VirtualAssetRequiredException e) {
      assertSame(service.requiredFailure, e);
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected addTextUnit to rethrow the checked failure");
  }

  @Test
  public void deleteTextUnitRollsBackRuntimeException() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestVirtualAssetService service = service(transactionManager);
    service.runtimeFailure = new IllegalStateException("failed");

    try {
      service.deleteTextUnit(1L, "name");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected deleteTextUnit to rethrow the failure");
  }

  private TestVirtualAssetService service(PlatformTransactionManager transactionManager) {
    TestVirtualAssetService service = new TestVirtualAssetService();
    service.transactionManager = transactionManager;
    return service;
  }

  private static class TestVirtualAssetService extends VirtualAssetService {
    List<AssetTextUnitDTO> assetTextUnitDTOs =
        List.of(new AssetTextUnitDTO("name", "content", "comment", null, null, "md5", false));
    VirtualAssetBadRequestException virtualAssetFailure;
    VirtualAssetRequiredException requiredFailure;
    RuntimeException runtimeFailure;

    @Override
    VirtualAsset createOrUpdateVirtualAssetNoTx(VirtualAsset virtualAsset)
        throws VirtualAssetBadRequestException {
      if (virtualAssetFailure != null) {
        throw virtualAssetFailure;
      }
      return virtualAsset;
    }

    @Override
    List<VirtualAssetTextUnit> getLocalizedTextUnitsNoTx(
        long assetId, long localeId, InheritanceMode inheritanceMode)
        throws VirtualAssetRequiredException {
      if (requiredFailure != null) {
        throw requiredFailure;
      }
      return List.of();
    }

    @Override
    List<AssetTextUnitDTO> findByAssetExtractionIdAndDoNotTranslateFilterNoTx(
        Long assetExtractionId, Boolean doNotTranslateFilter) {
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      return assetTextUnitDTOs;
    }

    @Override
    void addTextUnitNoTx(
        long assetId,
        String name,
        String content,
        String comment,
        String pluralForm,
        String pluralFormOther,
        boolean doNotTranslate)
        throws VirtualAssetRequiredException {
      if (requiredFailure != null) {
        throw requiredFailure;
      }
    }

    @Override
    void deleteTextUnitNoTx(Long assetId, String name) {
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
    }
  }
}
