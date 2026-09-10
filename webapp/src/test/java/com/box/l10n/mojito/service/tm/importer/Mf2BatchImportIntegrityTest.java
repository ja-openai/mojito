package com.box.l10n.mojito.service.tm.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.Mf2TranslationIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.IntegrityChecksType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class Mf2BatchImportIntegrityTest {
  @Test
  public void configuredMf2FailureExcludesImportAndRecordsDiagnostic() {
    var service = new TextUnitBatchImporterService();
    service.integrityCheckerFactory = mock(IntegrityCheckerFactory.class);
    service.pluralIntegrityCheckerRelaxer = new PluralIntegrityCheckerRelaxer();
    var asset = new Asset();
    when(service.integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(Set.of(new Mf2TranslationIntegrityChecker()));
    var current = new TextUnitDTO();
    String source = ".input {$n :number}\n.match $n\none {{One}}\n* {{{ $n } items}}";
    current.setSource(source);
    // An inherited English target must not determine validation for an Arabic import.
    current.setTargetLocale("en");
    var candidate = new TextUnitForBatchMatcherImport();
    candidate.setCurrentTextUnit(current);
    candidate.setContent(source);
    service.applyIntegrityChecks(
        asset, List.of(candidate), IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS, "ar");
    assertFalse(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
    assertTrue(
        candidate.getTmTextUnitVariantComments().stream()
            .anyMatch(c -> c.getContent().contains("mf2-plural-category-missing")));

    candidate.setContent(
        ".input {$n :number}\n.match $n\nzero {{zero}}\none {{one}}\ntwo {{two}}\nfew {{{ $n } few}}\nmany {{{ $n } many}}\n* {{{ $n } other}}");
    candidate.setStatus(Status.APPROVED);
    service.applyIntegrityChecks(
        asset, List.of(candidate), IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS, "ar");
    assertTrue(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.APPROVED, candidate.getStatus());
  }
}
