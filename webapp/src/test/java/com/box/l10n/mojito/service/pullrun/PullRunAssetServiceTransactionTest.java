package com.box.l10n.mojito.service.pullrun;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PullRunAsset;
import java.util.List;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class PullRunAssetServiceTransactionTest {

  @Test
  public void deleteExistingVariantsCommitsTransaction() {
    PullRunAssetService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    PullRunAsset pullRunAsset = new PullRunAsset();
    pullRunAsset.setId(10L);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.pullRunTextUnitVariantRepository.findByPullRunAssetIdAndLocaleIdAndOutputBcp47Tag(
            10L, 20L, "fr-FR"))
        .thenReturn(List.of());

    service.deleteExistingVariants(pullRunAsset, 20L, "fr-FR");

    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void deleteExistingVariantsRollsBackTransaction() {
    PullRunAssetService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    PullRunAsset pullRunAsset = new PullRunAsset();
    pullRunAsset.setId(10L);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.pullRunTextUnitVariantRepository.findByPullRunAssetIdAndLocaleIdAndOutputBcp47Tag(
            10L, 20L, "fr-FR"))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.deleteExistingVariants(pullRunAsset, 20L, "fr-FR");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected deleteExistingVariants to rethrow the repository failure");
  }

  @Test
  public void saveTextUnitVariantsMultiRowBatchCommitsTransaction() {
    PullRunAssetService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    PullRunAsset pullRunAsset = new PullRunAsset();
    pullRunAsset.setId(10L);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);

    service.saveTextUnitVariantsMultiRowBatch(pullRunAsset, 20L, List.of(1L, 2L), "fr-FR");

    verify(service.jdbcTemplate).update(anyString());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void saveTextUnitVariantsMultiRowBatchRollsBackTransaction() {
    PullRunAssetService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    PullRunAsset pullRunAsset = new PullRunAsset();
    pullRunAsset.setId(10L);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.jdbcTemplate.update(anyString())).thenThrow(new IllegalStateException("failed"));

    try {
      service.saveTextUnitVariantsMultiRowBatch(pullRunAsset, 20L, List.of(1L, 2L), "fr-FR");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError(
        "Expected saveTextUnitVariantsMultiRowBatch to rethrow the JDBC failure");
  }

  private PullRunAssetService service() {
    PullRunAssetService service = new PullRunAssetService();
    service.pullRunTextUnitVariantRepository = mock(PullRunTextUnitVariantRepository.class);
    service.jdbcTemplate = mock(JdbcTemplate.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }
}
