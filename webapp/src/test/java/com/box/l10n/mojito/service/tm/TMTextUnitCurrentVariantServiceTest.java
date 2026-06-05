package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
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

  private TMTextUnitCurrentVariant currentVariant(Long tmTextUnitId) {
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(tmTextUnitId);

    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setTmTextUnit(tmTextUnit);
    return currentVariant;
  }
}
