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
import com.box.l10n.mojito.service.translationkit.TranslationKitRepository;
import com.box.l10n.mojito.service.translationkit.TranslationKitService;
import java.time.ZonedDateTime;
import net.sf.okapi.common.resource.TextContainer;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * @author jaurambault
 */
public class ImportTranslationsStepTest {

  ImportTranslationsByIdStep newImportTranslationsByIdStep() {
    return newImportTranslationsByIdStep(mock(PlatformTransactionManager.class));
  }

  ImportTranslationsByIdStep newImportTranslationsByIdStep(
      PlatformTransactionManager transactionManager) {
    return new ImportTranslationsByIdStep(
        new TextUnitUtils(),
        mock(TMTextUnitRepository.class),
        mock(TMTextUnitCurrentVariantRepository.class),
        mock(LocaleService.class),
        mock(TMTextUnitVariantRepository.class),
        mock(TMTextUnitVariantCommentService.class),
        mock(UserRepository.class),
        mock(AuditorAwareImpl.class),
        mock(TMService.class),
        transactionManager);
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

  @Test
  public void importTextUnitCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    Mockito.when(transactionManager.getTransaction(Mockito.any())).thenReturn(transactionStatus);
    ImportTranslationsByIdStep importTranslationsStep =
        Mockito.spy(newImportTranslationsByIdStep(transactionManager));
    TMTextUnit tmTextUnit = new TMTextUnit();
    TextContainer target = mock(TextContainer.class);
    TMTextUnitVariant result = new TMTextUnitVariant();
    ZonedDateTime createdDate = ZonedDateTime.now();
    Mockito.doReturn(result)
        .when(importTranslationsStep)
        .importTextUnitNoTx(tmTextUnit, target, TMTextUnitVariant.Status.APPROVED, createdDate);

    assertEquals(
        result,
        importTranslationsStep.importTextUnit(
            tmTextUnit, target, TMTextUnitVariant.Status.APPROVED, createdDate));

    Mockito.verify(transactionManager).commit(transactionStatus);
    Mockito.verify(transactionManager, Mockito.never()).rollback(transactionStatus);
  }

  @Test
  public void importTextUnitRollsBackTransactionOnRuntimeException() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    Mockito.when(transactionManager.getTransaction(Mockito.any())).thenReturn(transactionStatus);
    ImportTranslationsByIdStep importTranslationsStep =
        Mockito.spy(newImportTranslationsByIdStep(transactionManager));
    TMTextUnit tmTextUnit = new TMTextUnit();
    TextContainer target = mock(TextContainer.class);
    ZonedDateTime createdDate = ZonedDateTime.now();
    RuntimeException failure = new RuntimeException("import failed");
    Mockito.doThrow(failure)
        .when(importTranslationsStep)
        .importTextUnitNoTx(tmTextUnit, target, TMTextUnitVariant.Status.APPROVED, createdDate);

    try {
      importTranslationsStep.importTextUnit(
          tmTextUnit, target, TMTextUnitVariant.Status.APPROVED, createdDate);
      fail("Expected importTextUnit to rethrow failure");
    } catch (RuntimeException e) {
      assertEquals(failure, e);
    }

    Mockito.verify(transactionManager).rollback(transactionStatus);
    Mockito.verify(transactionManager, Mockito.never()).commit(transactionStatus);
  }

  @Test
  public void translationKitImportTextUnitMarksImportedInsideTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    Mockito.when(transactionManager.getTransaction(Mockito.any())).thenReturn(transactionStatus);
    TranslationKitService translationKitService = mock(TranslationKitService.class);
    ImportTranslationsWithTranslationKitStep importTranslationsStep =
        Mockito.spy(
            new ImportTranslationsWithTranslationKitStep(
                new TextUnitUtils(),
                mock(TMTextUnitRepository.class),
                mock(TMTextUnitCurrentVariantRepository.class),
                mock(LocaleService.class),
                mock(TMTextUnitVariantRepository.class),
                mock(TMTextUnitVariantCommentService.class),
                mock(UserRepository.class),
                mock(AuditorAwareImpl.class),
                mock(TMService.class),
                transactionManager,
                translationKitService,
                mock(TranslationKitRepository.class)));
    TMTextUnit tmTextUnit = new TMTextUnit();
    TextContainer target = mock(TextContainer.class);
    TMTextUnitVariant result = new TMTextUnitVariant();
    ZonedDateTime createdDate = ZonedDateTime.now();
    Mockito.doReturn(result)
        .when(importTranslationsStep)
        .importTextUnitNoTx(tmTextUnit, target, TMTextUnitVariant.Status.APPROVED, createdDate);

    assertEquals(
        result,
        importTranslationsStep.importTextUnit(
            tmTextUnit, target, TMTextUnitVariant.Status.APPROVED, createdDate));

    Mockito.verify(translationKitService)
        .markTranslationKitTextUnitAsImported(importTranslationsStep.translationKit, result);
    Mockito.verify(transactionManager).commit(transactionStatus);
    Mockito.verify(transactionManager, Mockito.never()).rollback(transactionStatus);
  }
}
