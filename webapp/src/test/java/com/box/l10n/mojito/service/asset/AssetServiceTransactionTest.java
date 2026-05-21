package com.box.l10n.mojito.service.asset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class AssetServiceTransactionTest {

  @Test
  public void createAssetCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestAssetService service = service(transactionManager);

    Asset result = service.createAsset(1L, "strings.properties", false);

    assertSame(service.createdAsset, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createAssetRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestAssetService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.createAsset(1L, "strings.properties", false);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected createAsset to rethrow the failure");
  }

  @Test
  public void deleteAssetsCommitsOneTransactionAndDeletesInsideIt() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestAssetService service = service(transactionManager);
    Asset asset = new Asset();
    when(service.assetRepository.findById(2L)).thenReturn(Optional.of(asset));

    service.deleteAssets(Set.of(2L));

    assertSame(asset, service.deletedAsset);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void deleteAssetRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestAssetService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.deleteAsset(new Asset());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected deleteAsset to rethrow the failure");
  }

  private TestAssetService service(PlatformTransactionManager transactionManager) {
    TestAssetService service = new TestAssetService();
    service.transactionManager = transactionManager;
    service.assetRepository = mock(AssetRepository.class);
    return service;
  }

  private static class TestAssetService extends AssetService {
    Asset createdAsset = new Asset();
    Asset deletedAsset;
    RuntimeException failure;

    @Override
    Asset createAssetNoTx(Long repositoryId, String assetPath, boolean virtualContent) {
      if (failure != null) {
        throw failure;
      }
      return createdAsset;
    }

    @Override
    void deleteAssetNoTx(Asset asset) {
      if (failure != null) {
        throw failure;
      }
      deletedAsset = asset;
    }
  }
}
