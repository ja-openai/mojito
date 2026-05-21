package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class TMTextUnitCurrentVariantServiceTransactionTest {

  @Test
  public void removeCurrentVariantsCommitsTransaction() {
    TMTextUnitCurrentVariantService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    TMTextUnitCurrentVariant currentVariant = currentVariant();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.tmTextUnitCurrentVariantRepository.findById(1L))
        .thenReturn(Optional.of(currentVariant));

    int removedCount = service.removeCurrentVariants(Arrays.asList(1L, 1L, null));

    assertEquals(1, removedCount);
    assertNull(currentVariant.getTmTextUnitVariant());
    verify(service.tmTextUnitCurrentVariantRepository).save(currentVariant);
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void removeCurrentVariantsRollsBackTransaction() {
    TMTextUnitCurrentVariantService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.tmTextUnitCurrentVariantRepository.findById(1L))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.removeCurrentVariants(List.of(1L));
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected removeCurrentVariants to rethrow the repository failure");
  }

  @Test
  public void updateCurrentVariantStatusesCommitsTransaction() {
    TMTextUnitCurrentVariantService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    TMTextUnitCurrentVariant currentVariant = currentVariant();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.tmTextUnitCurrentVariantRepository.findById(1L))
        .thenReturn(Optional.of(currentVariant));
    when(service.tmService.addTMTextUnitCurrentVariantWithResult(
            eq(10L),
            eq(20L),
            eq("content"),
            eq("comment"),
            eq(TMTextUnitVariant.Status.APPROVED),
            eq(true),
            eq(null)))
        .thenReturn(new AddTMTextUnitCurrentVariantResult(true, currentVariant));

    int updatedCount =
        service.updateCurrentVariantStatuses(List.of(1L), TMTextUnitVariant.Status.APPROVED, true);

    assertEquals(1, updatedCount);
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  private TMTextUnitCurrentVariantService service() {
    TMTextUnitCurrentVariantService service = new TMTextUnitCurrentVariantService();
    service.tmTextUnitCurrentVariantRepository = mock(TMTextUnitCurrentVariantRepository.class);
    service.tmService = mock(TMService.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }

  private TMTextUnitCurrentVariant currentVariant() {
    TMTextUnit textUnit = new TMTextUnit();
    textUnit.setId(10L);
    Locale locale = new Locale();
    locale.setId(20L);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setContent("content");
    variant.setComment("comment");

    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setId(1L);
    currentVariant.setTmTextUnit(textUnit);
    currentVariant.setLocale(locale);
    currentVariant.setTmTextUnitVariant(variant);
    return currentVariant;
  }
}
