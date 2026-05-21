package com.box.l10n.mojito.service.assetintegritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class AssetIntegrityCheckerServiceTransactionTest {

  @Test
  public void addToRepositoryCommitsTransaction() {
    AssetIntegrityCheckerService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    Repository repository = new Repository();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);

    service.addToRepository(repository, "strings/messages.properties", IntegrityCheckerType.EMAIL);

    ArgumentCaptor<AssetIntegrityChecker> checkerCaptor =
        ArgumentCaptor.forClass(AssetIntegrityChecker.class);
    verify(service.assetIntegrityCheckerRepository).save(checkerCaptor.capture());
    assertSame(repository, checkerCaptor.getValue().getRepository());
    assertEquals("properties", checkerCaptor.getValue().getAssetExtension());
    assertEquals(IntegrityCheckerType.EMAIL, checkerCaptor.getValue().getIntegrityCheckerType());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void addToRepositoryRollsBackTransaction() {
    AssetIntegrityCheckerService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.assetIntegrityCheckerRepository.save(any()))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.addToRepository(new Repository(), "messages.properties", IntegrityCheckerType.EMAIL);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected addToRepository to rethrow the save failure");
  }

  private AssetIntegrityCheckerService service() {
    AssetIntegrityCheckerService service = new AssetIntegrityCheckerService();
    service.assetIntegrityCheckerRepository = mock(AssetIntegrityCheckerRepository.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }
}
