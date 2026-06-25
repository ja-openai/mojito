package com.box.l10n.mojito.service.pushrun;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PushRun;
import com.box.l10n.mojito.entity.PushRunAsset;
import com.box.l10n.mojito.service.commit.CommitToPushRunRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

public class PushRunServiceTransactionTest {

  @Test
  public void clearPushRunLinkedDataCommitsTransaction() {
    PushRunService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    PushRun pushRun = new PushRun();
    PushRunAsset pushRunAsset = new PushRunAsset();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.pushRunAssetRepository.findByPushRun(pushRun)).thenReturn(List.of(pushRunAsset));

    service.clearPushRunLinkedData(pushRun);

    verify(service.pushRunAssetTmTextUnitRepository).deleteByPushRunAsset(pushRunAsset);
    verify(service.pushRunAssetRepository).deleteByPushRun(pushRun);
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void clearPushRunLinkedDataRollsBackTransaction() {
    PushRunService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    PushRun pushRun = new PushRun();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.pushRunAssetRepository.findByPushRun(pushRun))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.clearPushRunLinkedData(pushRun);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected clearPushRunLinkedData to rethrow the repository failure");
  }

  @Test
  public void associatePushRunToTextUnitIdsCommitsTransaction() {
    PushRunService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    PushRun pushRun = new PushRun();
    Asset asset = new Asset();
    PushRunAsset pushRunAsset = new PushRunAsset();
    pushRunAsset.setId(10L);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.pushRunAssetRepository.findByPushRunAndAsset(pushRun, asset))
        .thenReturn(Optional.of(pushRunAsset));

    service.associatePushRunToTextUnitIds(pushRun, asset, List.of(1L, 2L));

    verify(service.pushRunAssetTmTextUnitRepository).deleteByPushRunAsset(pushRunAsset);
    verify(service.jdbcTemplate).update(any(String.class));
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  private PushRunService service() {
    return new PushRunService(
        mock(EntityManager.class),
        mock(JdbcTemplate.class),
        mock(TransactionTemplate.class),
        mock(CommitToPushRunRepository.class),
        mock(PushRunRepository.class),
        mock(PushRunAssetRepository.class),
        mock(PushRunAssetTmTextUnitRepository.class),
        mock(PlatformTransactionManager.class));
  }
}
