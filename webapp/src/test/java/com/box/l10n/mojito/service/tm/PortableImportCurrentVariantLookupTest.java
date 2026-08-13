package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class PortableImportCurrentVariantLookupTest {

  @InjectMocks TMService tmService;

  @Mock TMTextUnitCurrentVariantRepository currentVariants;

  @Mock TMTextUnitCurrentVariant existing;

  @Test
  public void firstImportAvoidsEveryCurrentVariantLookup() {
    for (long textUnit = 1; textUnit <= 100; textUnit++) {
      assertNull(tmService.currentVariantForPortableImport(false, 7L, textUnit));
    }

    verifyNoInteractions(currentVariants);
  }

  @Test
  public void reimportPreservesExistingCurrentVariantLookup() {
    when(currentVariants.findByLocale_IdAndTmTextUnit_Id(7L, 42L)).thenReturn(existing);

    assertSame(existing, tmService.currentVariantForPortableImport(true, 7L, 42L));
    verify(currentVariants).findByLocale_IdAndTmTextUnit_Id(7L, 42L);
  }
}
