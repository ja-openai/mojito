package com.box.l10n.mojito.service.machinetranslation;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.service.tm.importer.BulkImportLineageService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import org.junit.Test;

public class RepositoryMachineTranslationServiceTest {

  @Test
  public void preparedAutomatedOutputHasExplicitAttribution() {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTranslatorIdentity("stale-translator@example.com");
    textUnit.setReviewerIdentity("stale-reviewer@example.com");

    TextUnitDTO prepared =
        RepositoryMachineTranslationService.prepareForImport(textUnit, "Bonjour");

    assertThat(prepared.getTarget()).isEqualTo("Bonjour");
    assertThat(prepared.getTranslatorIdentity())
        .isEqualTo(BulkImportLineageService.MACHINE_TRANSLATION_IDENTITY);
    assertThat(prepared.getReviewerIdentity())
        .isEqualTo(BulkImportLineageService.NOT_REVIEWED_IDENTITY);
  }
}
