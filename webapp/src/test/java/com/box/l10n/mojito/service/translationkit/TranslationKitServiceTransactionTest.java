package com.box.l10n.mojito.service.translationkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Drop;
import com.box.l10n.mojito.entity.TranslationKit;
import com.box.l10n.mojito.entity.TranslationKitTextUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class TranslationKitServiceTransactionTest {

  @Test
  public void addTranslationKitCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTranslationKitService service = service(transactionManager);

    TranslationKit result = service.addTranslationKit(1L, 2L, TranslationKit.Type.TRANSLATION);

    assertSame(service.translationKit, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateTranslationKitWithTmTextUnitsRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTranslationKitService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.updateTranslationKitWithTmTextUnits(1L, List.of(), 10L);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected updateTranslationKitWithTmTextUnits to rethrow the failure");
  }

  @Test
  public void updateStatisticsCommitsAndChecksPartiallyImportedInSameTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTranslationKitService service = service(transactionManager);
    Drop drop = new Drop();
    drop.setId(3L);
    TranslationKit translationKit = new TranslationKit();
    translationKit.setDrop(drop);
    translationKit.setNumTranslationKitUnits(1);
    when(service.translationKitRepository.findById(1L)).thenReturn(Optional.of(translationKit));

    service.updateStatistics(1L, Set.of("missing"));

    assertTrue(service.checkedPartiallyImported);
    verify(service.translationKitRepository).save(translationKit);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void generateTranslationKitAsXLIFFRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestTranslationKitService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.generateTranslationKitAsXLIFF(1L, 2L, 3L, TranslationKit.Type.TRANSLATION, false);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected generateTranslationKitAsXLIFF to rethrow the failure");
  }

  private TestTranslationKitService service(PlatformTransactionManager transactionManager) {
    TestTranslationKitService service = new TestTranslationKitService();
    service.transactionManager = transactionManager;
    service.translationKitRepository = mock(TranslationKitRepository.class);
    service.translationKitTextUnitRepository = mock(TranslationKitTextUnitRepository.class);
    return service;
  }

  private static class TestTranslationKitService extends TranslationKitService {
    TranslationKit translationKit = new TranslationKit();
    RuntimeException failure;
    boolean checkedPartiallyImported;

    @Override
    TranslationKit addTranslationKitNoTx(Long dropId, Long localeId, TranslationKit.Type type) {
      if (failure != null) {
        throw failure;
      }
      return translationKit;
    }

    @Override
    void updateTranslationKitWithTmTextUnitsNoTx(
        Long translationKitId,
        List<TranslationKitTextUnit> translationKitTextUnits,
        Long wordCount) {
      if (failure != null) {
        throw failure;
      }
    }

    @Override
    TranslationKitAsXliff generateTranslationKitAsXLIFFNoTx(
        Long dropId, Long tmId, Long localeId, TranslationKit.Type type, Boolean useInheritance) {
      if (failure != null) {
        throw failure;
      }
      return new TranslationKitAsXliff();
    }

    @Override
    void checkForPartiallyImportedNoTx(Long dropId) {
      checkedPartiallyImported = true;
    }
  }
}
