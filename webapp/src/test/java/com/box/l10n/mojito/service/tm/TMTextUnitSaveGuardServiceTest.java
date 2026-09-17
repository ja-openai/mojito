package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class TMTextUnitSaveGuardServiceTest {
  private ThreadBoundTransactionAdvice transactionAdvice;
  private final UserService users = mock(UserService.class);
  private final EntityManager entities = mock(EntityManager.class);
  private final TMTextUnitCurrentVariantRepository currents =
      mock(TMTextUnitCurrentVariantRepository.class);
  private final TMTextUnitSaveGuardService guard =
      new TMTextUnitSaveGuardService(users, entities, currents);
  private final TMTextUnit unit = new TMTextUnit();

  @SuppressWarnings("unchecked")
  private final Supplier<TextUnitDTO> write = mock(Supplier.class);

  @Before
  public void setup() {
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    transactionAdvice = new ThreadBoundTransactionAdvice(transactions);
    when(entities.find(TMTextUnit.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(unit);
  }

  @After
  public void restoreTransactions() {
    transactionAdvice.close();
  }

  @Test
  public void absentBaselineIsCheckedUnderParentLockBeforeWriting() {
    TextUnitDTO saved = new TextUnitDTO();
    when(write.get()).thenReturn(saved);
    assertThat(guard.save(1L, 2L, null, write)).isSameAs(saved);
    var order = inOrder(users, entities, currents, write);
    order.verify(users).checkUserCanEditLocale(2L);
    order.verify(entities).find(TMTextUnit.class, 1L, LockModeType.PESSIMISTIC_WRITE);
    order.verify(entities).refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    order.verify(currents).findForUpdateByLocaleIdAndTmTextUnitId(2L, 1L);
    order.verify(write).get();
  }

  @Test
  public void acceptsOnlyTheExactCurrentVariantAndRefreshesLockedEntity() {
    var current = current(9L);
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(2L, 1L)).thenReturn(current);
    guard.save(1L, 2L, 9L, write);
    verify(entities).refresh(current, LockModeType.PESSIMISTIC_WRITE);
    verify(write).get();
    reset(write);
    conflict(8L);
    conflict(null);
    verifyNoInteractions(write);
  }

  @Test
  public void removedTranslationConflictsWithItsOldBaselineButCanBeRecreated() {
    conflict(9L);
    var tombstone = new TMTextUnitCurrentVariant();
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(2L, 1L)).thenReturn(tombstone);
    conflict(9L);
    guard.save(1L, 2L, null, write);
    verify(write).get();
    reset(write);
    var recreated = new TMTextUnitVariant();
    recreated.setId(10L);
    tombstone.setTmTextUnitVariant(recreated);
    conflict(null);
    verifyNoInteractions(write);
  }

  @Test
  public void authorizationAndInvalidBaselineCannotInvokeWriter() {
    doThrow(new AccessDeniedException("denied")).when(users).checkUserCanEditLocale(2L);
    assertThatThrownBy(() -> guard.save(1L, 2L, null, write))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> guard.save(1L, 2L, -1L, write))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(entities, currents, write);
  }

  private void conflict(Long baseline) {
    assertThatThrownBy(() -> guard.save(1L, 2L, baseline, write))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  private TMTextUnitCurrentVariant current(Long variantId) {
    var variant = new TMTextUnitVariant();
    variant.setId(variantId);
    var current = new TMTextUnitCurrentVariant();
    current.setTmTextUnitVariant(variant);
    return current;
  }
}
