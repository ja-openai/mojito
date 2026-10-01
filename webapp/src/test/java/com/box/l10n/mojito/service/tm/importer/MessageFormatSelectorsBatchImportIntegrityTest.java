package com.box.l10n.mojito.service.tm.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.MessageFormatIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.IntegrityChecksType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class MessageFormatSelectorsBatchImportIntegrityTest {

  private static final String SOURCE = "{count, plural, one {One item} other {Several items}}";
  private static final String TARGET =
      "{count, plural, one {Один элемент} few {Несколько элементов} many {Много элементов} other {Элементы}}";

  @Test
  public void duplicateCategoryExcludesCandidateAndRecordsDiagnostic() {
    String target = TARGET.replace("few {", "one {Несколько элементов} few {");
    TextUnitForBatchMatcherImport candidate = checkImport(target);

    assertFalse(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
    assertEquals(target, candidate.getContent());
    assertEquals(1, candidate.getTmTextUnitVariantComments().size());
    assertEquals(
        "Duplicate selector 'one' in target argument 'count'",
        candidate.getTmTextUnitVariantComments().get(0).getContent());
  }

  @Test
  public void unknownPluralKeywordExcludesCandidateAndRecordsDiagnostic() {
    TextUnitForBatchMatcherImport candidate = checkImport(SOURCE.replace("one {", "uno {"));
    assertFalse(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
    assertEquals(1, candidate.getTmTextUnitVariantComments().size());
    assertEquals(
        "Invalid plural keyword 'uno' in target argument 'count'",
        candidate.getTmTextUnitVariantComments().get(0).getContent());
  }

  @Test
  public void validRussianCategoriesRemainIncludedWithRequestedStatus() {
    TextUnitForBatchMatcherImport candidate = checkImport(TARGET);

    assertTrue(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.REVIEW_NEEDED, candidate.getStatus());
    assertEquals(TARGET, candidate.getContent());
    assertTrue(candidate.getTmTextUnitVariantComments().isEmpty());
  }

  private TextUnitForBatchMatcherImport checkImport(String target) {
    var importer = new TextUnitBatchImporterService();
    importer.integrityCheckerFactory = mock(IntegrityCheckerFactory.class);
    importer.pluralIntegrityCheckerRelaxer = new PluralIntegrityCheckerRelaxer();
    var asset = new Asset();
    when(importer.integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(Set.of(new MessageFormatIntegrityChecker()));
    var current = new TextUnitDTO();
    current.setSource(SOURCE);
    current.setTargetLocale("ru");
    var candidate = new TextUnitForBatchMatcherImport();
    candidate.setCurrentTextUnit(current);
    candidate.setContent(target);
    candidate.setStatus(Status.REVIEW_NEEDED);

    importer.applyIntegrityChecks(
        asset,
        List.of(candidate),
        IntegrityChecksType.KEEP_STATUS_IF_SAME_TARGET_AND_NOT_INCLUDED,
        "ru");
    return candidate;
  }
}
