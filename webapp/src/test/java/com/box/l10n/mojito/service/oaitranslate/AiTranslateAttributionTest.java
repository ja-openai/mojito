package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.tm.importer.BulkImportLineageService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import org.junit.Test;

public class AiTranslateAttributionTest {

  @Test
  public void preparedAutomatedOutputHasExplicitAttribution() {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(42L);
    textUnit.setSource("Hello");
    textUnit.setTranslatorIdentity("stale-translator@example.com");
    textUnit.setReviewerIdentity("stale-reviewer@example.com");

    AiTranslateService.TextUnitDTOWithVariantCommentOrError prepared =
        AiTranslateService.prepareForTextUnitDTOForImport(
            "completion-id",
            AiTranslateType.TARGET_ONLY,
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            textUnit,
            new AiTranslateType.SimpleCompletionOutput("Bonjour"),
            "request-group-id");

    TextUnitDTO preparedTextUnit = prepared.textUnitDTOWithVariantComment().textUnitDTO();
    assertThat(preparedTextUnit.getTarget()).isEqualTo("Bonjour");
    assertThat(preparedTextUnit.getTranslatorIdentity())
        .isEqualTo(BulkImportLineageService.AI_TRANSLATE_IDENTITY);
    assertThat(preparedTextUnit.getReviewerIdentity())
        .isEqualTo(BulkImportLineageService.NOT_REVIEWED_IDENTITY);
  }
}
