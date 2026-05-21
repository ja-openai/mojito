package com.box.l10n.mojito.okapi;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import net.sf.okapi.common.resource.TextContainer;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * @author jaurambault
 */
public class ImportTranslationsStepTest {

  ImportTranslationsByIdStep newImportTranslationsByIdStep() {
    return new ImportTranslationsByIdStep(
        new TextUnitUtils(),
        mock(TMTextUnitRepository.class),
        mock(TMTextUnitCurrentVariantRepository.class),
        mock(LocaleService.class),
        mock(TMTextUnitVariantRepository.class),
        mock(TMTextUnitVariantCommentService.class),
        mock(UserRepository.class),
        mock(AuditorAwareImpl.class),
        mock(TMService.class));
  }

  @Test
  public void testGetStatusWithSpecificImportStatus() {

    TextContainer target = Mockito.mock(TextContainer.class);
    Mockito.when(target.getProperty(com.box.l10n.mojito.okapi.Property.STATE))
        .thenReturn(new net.sf.okapi.common.resource.Property("state", "doesnt matter"));

    ImportTranslationsByIdStep importTranslationsStep = newImportTranslationsByIdStep();
    importTranslationsStep.importWithStatus = TMTextUnitVariant.Status.APPROVED;

    TMTextUnitVariant.Status expResult = TMTextUnitVariant.Status.APPROVED;
    TMTextUnitVariant.Status result =
        importTranslationsStep.getStatusForImport(new TMTextUnit(), target);
    assertEquals(expResult, result);
  }

  @Test
  public void testGetStatusFromStateTranslated() {

    TextContainer target = Mockito.mock(TextContainer.class);
    Mockito.when(target.getProperty(com.box.l10n.mojito.okapi.Property.STATE))
        .thenReturn(new net.sf.okapi.common.resource.Property("state", "translated"));

    ImportTranslationsByIdStep importTranslationsStep = newImportTranslationsByIdStep();

    TMTextUnitVariant.Status expResult = TMTextUnitVariant.Status.REVIEW_NEEDED;
    TMTextUnitVariant.Status result =
        importTranslationsStep.getStatusForImport(new TMTextUnit(), target);
    assertEquals(expResult, result);
  }

  @Test
  public void testGetStatusFromStateSignedOff() {

    TextContainer target = Mockito.mock(TextContainer.class);
    Mockito.when(target.getProperty(com.box.l10n.mojito.okapi.Property.STATE))
        .thenReturn(new net.sf.okapi.common.resource.Property("state", "signed-off"));

    ImportTranslationsByIdStep importTranslationsStep = newImportTranslationsByIdStep();

    TMTextUnitVariant.Status expResult = TMTextUnitVariant.Status.APPROVED;
    TMTextUnitVariant.Status result =
        importTranslationsStep.getStatusForImport(new TMTextUnit(), target);
    assertEquals(expResult, result);
  }

  @Test
  public void testGetStatusFromStateUnsupported() {

    TextContainer target = Mockito.mock(TextContainer.class);
    Mockito.when(target.getProperty(com.box.l10n.mojito.okapi.Property.STATE))
        .thenReturn(new net.sf.okapi.common.resource.Property("state", "unsupported"));

    ImportTranslationsByIdStep importTranslationsStep = newImportTranslationsByIdStep();

    TMTextUnitVariant.Status expResult = null;
    TMTextUnitVariant.Status result =
        importTranslationsStep.getStatusForImport(new TMTextUnit(), target);
    assertEquals(expResult, result);
  }
}
