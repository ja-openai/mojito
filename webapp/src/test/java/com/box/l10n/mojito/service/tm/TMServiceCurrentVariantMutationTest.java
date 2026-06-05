package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TM;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TMServiceCurrentVariantMutationTest {

  @Mock TMTextUnitCurrentVariantMutationLockService mutationLockService;

  @Mock TMTextUnitCurrentVariantRepository currentVariantRepository;

  @Mock TMTextUnitVariantRepository variantRepository;

  @Mock AuditorAwareImpl auditorAwareImpl;

  @Mock EntityManager entityManager;

  @InjectMocks TMService service;

  @Test
  public void prefetchedCurrentVariantIsReloadedAfterMutationLock() {
    TMTextUnitCurrentVariant staleCurrentVariant = currentVariant("Stale");
    TMTextUnitCurrentVariant lockedCurrentVariant = currentVariant("Bonjour");
    when(currentVariantRepository.findByLocale_IdAndTmTextUnit_Id(7L, 11L))
        .thenReturn(lockedCurrentVariant);

    AddTMTextUnitCurrentVariantResult result =
        service.addTMTextUnitCurrentVariantWithResult(
            staleCurrentVariant,
            1L,
            2L,
            11L,
            7L,
            "Bonjour",
            null,
            TMTextUnitVariant.Status.APPROVED,
            true,
            null,
            null);

    InOrder inOrder = inOrder(mutationLockService, currentVariantRepository);
    inOrder.verify(mutationLockService).lockTextUnit(11L);
    inOrder.verify(currentVariantRepository).findByLocale_IdAndTmTextUnit_Id(7L, 11L);
    assertThat(result.isTmTextUnitCurrentVariantUpdated()).isFalse();
    assertThat(result.getTmTextUnitCurrentVariant()).isSameAs(lockedCurrentVariant);
    verify(variantRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  public void uncheckedCurrentVariantCreationTakesMutationLock() {
    TMTextUnitVariant savedVariant = new TMTextUnitVariant();
    savedVariant.setId(13L);
    when(auditorAwareImpl.getCurrentAuditor()).thenReturn(Optional.empty());
    when(entityManager.getReference(TMTextUnit.class, 11L)).thenReturn(new TMTextUnit());
    when(entityManager.getReference(Locale.class, 7L)).thenReturn(new Locale());
    when(entityManager.getReference(TM.class, 1L)).thenReturn(new TM());
    when(entityManager.getReference(Asset.class, 2L)).thenReturn(new Asset());
    when(entityManager.getReference(TMTextUnitVariant.class, 13L)).thenReturn(savedVariant);
    when(variantRepository.save(any(TMTextUnitVariant.class))).thenReturn(savedVariant);
    when(currentVariantRepository.save(any(TMTextUnitCurrentVariant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TMTextUnitVariant result =
        service.addTMTextUnitVariantAndMakeCurrent(
            1L, 2L, 11L, 7L, "Bonjour", null, TMTextUnitVariant.Status.APPROVED, true, null);

    InOrder inOrder = inOrder(mutationLockService, variantRepository, currentVariantRepository);
    inOrder.verify(mutationLockService).lockTextUnit(11L);
    inOrder.verify(variantRepository).save(any(TMTextUnitVariant.class));
    inOrder.verify(currentVariantRepository).save(any(TMTextUnitCurrentVariant.class));
    assertThat(result).isSameAs(savedVariant);
  }

  private TMTextUnitCurrentVariant currentVariant(String content) {
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setContent(content);
    variant.setContentMD5(DigestUtils.md5Hex(content));
    variant.setStatus(TMTextUnitVariant.Status.APPROVED);
    variant.setIncludedInLocalizedFile(true);

    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setTmTextUnitVariant(variant);
    return currentVariant;
  }
}
