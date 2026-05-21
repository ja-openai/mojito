package com.box.l10n.mojito.service.rollback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class CurrentVariantRollbackServiceTransactionTest {

  @Test
  public void rollbackCurrentVariantsFromTMToDateCommitsTransaction() {
    TestCurrentVariantRollbackService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    ZonedDateTime rollbackDateTime = ZonedDateTime.now();
    CurrentVariantRollbackParameters parameters = new CurrentVariantRollbackParameters();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);

    service.rollbackCurrentVariantsFromTMToDate(rollbackDateTime, 10L, parameters);

    assertTrue(service.deletedExistingCurrentVariants);
    assertTrue(service.addedCurrentVariantsAsOfRollbackDate);
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void rollbackCurrentVariantsFromTMToDateRollsBackTransaction() {
    TestCurrentVariantRollbackService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    service.throwWhenAddingCurrentVariants = true;
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);

    try {
      service.rollbackCurrentVariantsFromTMToDate(
          ZonedDateTime.now(), 10L, new CurrentVariantRollbackParameters());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError(
        "Expected rollbackCurrentVariantsFromTMToDate to rethrow the add failure");
  }

  private TestCurrentVariantRollbackService service() {
    TestCurrentVariantRollbackService service = new TestCurrentVariantRollbackService();
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }

  static class TestCurrentVariantRollbackService extends CurrentVariantRollbackService {

    boolean deletedExistingCurrentVariants;
    boolean addedCurrentVariantsAsOfRollbackDate;
    boolean throwWhenAddingCurrentVariants;

    @Override
    protected void deleteExistingCurrentVariants(
        Long tmId, CurrentVariantRollbackParameters extraParameters) {
      deletedExistingCurrentVariants = true;
    }

    @Override
    protected void addCurrentVariantsAsOfRollbackDate(
        ZonedDateTime rollbackDateTime,
        Long tmId,
        CurrentVariantRollbackParameters extraParameters) {
      addedCurrentVariantsAsOfRollbackDate = true;
      if (throwWhenAddingCurrentVariants) {
        throw new IllegalStateException("failed");
      }
    }
  }
}
