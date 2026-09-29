package com.box.l10n.mojito.service.tm.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.MessageFormatDoubleBracesIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.IntegrityChecksType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class TemplateBracesBatchImportIntegrityTest {

  private static final String SOURCE = "Confirm {{name}} at {{{ confirmationUrl }}}.";
  private static final String TARGET = "Confirmez {{name}} via {{{ confirmationUrl }}}.";

  @Test
  public void validImportKeepsTemplateTextAndRequestedStatus() {
    TextUnitForBatchMatcherImport candidate = checkImport(TARGET);

    assertTrue(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.REVIEW_NEEDED, candidate.getStatus());
    assertTrue(candidate.getTmTextUnitVariantComments().isEmpty());
    assertEquals(TARGET, candidate.getContent());
  }

  @Test
  public void changedBraceWidthExcludesImportAndRecordsDiagnostic() {
    String target = TARGET.replace("{{{ confirmationUrl }}}", "{{ confirmationUrl }}");
    TextUnitForBatchMatcherImport candidate = checkImport(target);

    assertFalse(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
    assertEquals(1, candidate.getTmTextUnitVariantComments().size());
    assertTrue(
        candidate
            .getTmTextUnitVariantComments()
            .get(0)
            .getContent()
            .contains("triple-brace variables do not match source"));
    assertEquals(target, candidate.getContent());
  }

  private TextUnitForBatchMatcherImport checkImport(String target) {
    var importer = new TextUnitBatchImporterService();
    importer.integrityCheckerFactory = mock(IntegrityCheckerFactory.class);
    importer.pluralIntegrityCheckerRelaxer = new PluralIntegrityCheckerRelaxer();
    var asset = new Asset();
    when(importer.integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(Set.of(new MessageFormatDoubleBracesIntegrityChecker()));
    var current = new TextUnitDTO();
    current.setSource(SOURCE);
    current.setTargetLocale("fr");
    var candidate = new TextUnitForBatchMatcherImport();
    candidate.setCurrentTextUnit(current);
    candidate.setContent(target);
    candidate.setStatus(Status.REVIEW_NEEDED);

    importer.applyIntegrityChecks(
        asset, List.of(candidate), IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS, "fr");
    return candidate;
  }
}
