package com.box.l10n.mojito.okapi;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.Mf2TranslationIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.TMTextUnitVariantCommentAnnotations;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import java.util.Set;
import net.sf.okapi.common.resource.TextContainer;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class ImportTranslationsFromLocalizedAssetStepTest {
  @Test
  public void configuredMf2ImportUsesRepositoryLocaleAndRetainsInvalidTranslationForRepair() {
    String source = ".input {$n :number}\n.match $n\none {{One}}\n* {{{ $n } items}}";
    for (String tag : new String[] {"en", "ar"}) {
      Locale locale = new Locale();
      locale.setId(12L);
      locale.setBcp47Tag(tag);
      RepositoryLocale repositoryLocale = new RepositoryLocale();
      repositoryLocale.setLocale(locale);
      ImportTranslationsFromLocalizedAssetStep step =
          new ImportTranslationsFromLocalizedAssetStep(
              new Asset(),
              repositoryLocale,
              ImportTranslationsFromLocalizedAssetStep.StatusForEqualTarget.APPROVED) {
            @Override
            Long getTargetLocaleId() {
              return locale.getId();
            }

            @Override
            boolean shouldImportAsCurrentTranslation(Long textUnitId) {
              return false;
            }
          };
      ReflectionTestUtils.setField(
          step, "textUnitIntegrityCheckers", Set.of(new Mf2TranslationIntegrityChecker()));
      step.tmService = mock(TMService.class);
      step.tmMTextUnitVariantCommentService = mock(TMTextUnitVariantCommentService.class);
      TMTextUnit textUnit = new TMTextUnit();
      textUnit.setId(321L);
      textUnit.setContent(source);
      TextContainer target = new TextContainer(source);
      boolean valid = tag.equals("en");
      TMTextUnitVariant.Status status =
          valid ? TMTextUnitVariant.Status.APPROVED : TMTextUnitVariant.Status.TRANSLATION_NEEDED;
      TMTextUnitVariant variant = new TMTextUnitVariant();
      when(step.tmService.addTMTextUnitVariant(321L, 12L, source, null, status, valid, null, null))
          .thenReturn(variant);

      assertEquals(
          variant, step.importTextUnit(textUnit, target, TMTextUnitVariant.Status.APPROVED, null));

      assertEquals(
          !valid, new TMTextUnitVariantCommentAnnotations(target).hasCommentWithErrorSeverity());
      assertEquals(!valid, step.documentReviewNeeded);
      verify(step.tmService)
          .addTMTextUnitVariant(321L, 12L, source, null, status, valid, null, null);
      if (!valid)
        verify(step.tmMTextUnitVariantCommentService)
            .addComment(any(TMTextUnitVariant.class), any(), any(), any());
    }
  }
}
