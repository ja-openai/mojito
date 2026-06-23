package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TMTextUnitCurrentVariantServiceTest {

  @Mock TMTextUnitCurrentVariantRepository currentVariantRepository;

  @Mock TMTextUnitCurrentVariantMutationLockService mutationLockService;

  @Mock TMService tmService;

  @InjectMocks TMTextUnitCurrentVariantService service;

  @Test
  public void removeCurrentVariantReloadsAfterMutationLock() {
    TMTextUnitCurrentVariant currentVariant = currentVariant(11L);
    when(currentVariantRepository.findById(7L))
        .thenReturn(Optional.of(currentVariant), Optional.of(currentVariant));

    assertThat(service.removeCurrentVariant(7L)).isTrue();

    InOrder inOrder = inOrder(currentVariantRepository, mutationLockService);
    inOrder.verify(currentVariantRepository).findById(7L);
    inOrder.verify(mutationLockService).lockTextUnit(11L);
    inOrder.verify(currentVariantRepository).findById(7L);
    verify(currentVariantRepository).save(currentVariant);
    assertThat(currentVariant.getTmTextUnitVariant()).isNull();
  }

  @Test
  public void updateCurrentVariantStatusesReloadsContentAfterMutationLock() {
    TMTextUnitCurrentVariant staleCurrentVariant =
        currentVariant(11L, 17L, "stale content", "stale comment");
    TMTextUnitCurrentVariant currentVariant =
        currentVariant(11L, 17L, "fresh content", "fresh comment");
    when(currentVariantRepository.findById(7L))
        .thenReturn(Optional.of(staleCurrentVariant), Optional.of(currentVariant));
    when(tmService.addTMTextUnitCurrentVariantWithResult(
            11L,
            17L,
            "fresh content",
            "fresh comment",
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            false,
            null))
        .thenReturn(new AddTMTextUnitCurrentVariantResult(true, currentVariant));

    assertThat(
            service.updateCurrentVariantStatuses(
                List.of(7L), TMTextUnitVariant.Status.REVIEW_NEEDED, false))
        .isEqualTo(1);

    InOrder inOrder = inOrder(currentVariantRepository, mutationLockService, tmService);
    inOrder.verify(currentVariantRepository).findById(7L);
    inOrder.verify(mutationLockService).lockTextUnit(11L);
    inOrder.verify(currentVariantRepository).findById(7L);
    inOrder
        .verify(tmService)
        .addTMTextUnitCurrentVariantWithResult(
            11L,
            17L,
            "fresh content",
            "fresh comment",
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            false,
            null);
    verify(tmService, never())
        .addTMTextUnitCurrentVariantWithResult(
            11L,
            17L,
            "stale content",
            "stale comment",
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            false,
            null);
  }

  private TMTextUnitCurrentVariant currentVariant(Long tmTextUnitId) {
    return currentVariant(tmTextUnitId, null, null, null);
  }

  private TMTextUnitCurrentVariant currentVariant(
      Long tmTextUnitId, Long localeId, String content, String comment) {
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(tmTextUnitId);

    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setTmTextUnit(tmTextUnit);
    if (localeId != null) {
      Locale locale = new Locale();
      locale.setId(localeId);
      currentVariant.setLocale(locale);

      TMTextUnitVariant variant = new TMTextUnitVariant();
      variant.setContent(content);
      variant.setComment(comment);
      currentVariant.setTmTextUnitVariant(variant);
    }
    return currentVariant;
  }
}
