package com.box.l10n.mojito.service.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import org.junit.Test;
import org.mockito.Mockito;

public class BadTranslationMutationServiceTest {

  private final TMService tmService = Mockito.mock(TMService.class);
  private final TMTextUnitVariantCommentService tmTextUnitVariantCommentService =
      Mockito.mock(TMTextUnitVariantCommentService.class);

  private final BadTranslationMutationService service =
      new BadTranslationMutationService(tmService, tmTextUnitVariantCommentService);

  @Test
  public void rejectTranslationTransitionsVariantAndAddsAuditComment() {
    BadTranslationLookupService.TranslationCandidate candidate =
        new BadTranslationLookupService.TranslationCandidate(
            new BadTranslationLookupService.RepositoryRef(11L, "chatgpt-web"),
            41L,
            52L,
            63L,
            "string.id",
            "/src/a.ts",
            74L,
            "source",
            "{bad}",
            "comment",
            "APPROVED",
            true,
            null,
            true);
    BadTranslationLookupService.LocaleRef locale =
        new BadTranslationLookupService.LocaleRef(7L, "hr");

    TMTextUnitVariant currentVariant = new TMTextUnitVariant();
    currentVariant.setId(99L);
    currentVariant.setStatus(TMTextUnitVariant.Status.TRANSLATION_NEEDED);
    currentVariant.setIncludedInLocalizedFile(false);

    TMTextUnitCurrentVariant currentVariantRow = new TMTextUnitCurrentVariant();
    currentVariantRow.setId(88L);
    currentVariantRow.setTmTextUnitVariant(currentVariant);

    when(tmService.addTMTextUnitCurrentVariantWithResult(
            41L, 7L, "{bad}", "comment", TMTextUnitVariant.Status.TRANSLATION_NEEDED, false, null))
        .thenReturn(new AddTMTextUnitCurrentVariantResult(true, currentVariantRow));

    TMTextUnitVariantComment auditComment = new TMTextUnitVariantComment();
    auditComment.setId(1001L);
    when(tmTextUnitVariantCommentService.addComment(
            eq(99L),
            eq(TMTextUnitVariantComment.Type.INTEGRITY_CHECK),
            eq(TMTextUnitVariantComment.Severity.ERROR),
            eq("audit note")))
        .thenReturn(auditComment);

    BadTranslationMutationService.RejectMutationResult result =
        service.rejectTranslation(candidate, locale, "audit note");

    assertThat(result.previousTmTextUnitCurrentVariantId()).isEqualTo(52L);
    assertThat(result.previousTmTextUnitVariantId()).isEqualTo(63L);
    assertThat(result.currentTmTextUnitCurrentVariantId()).isEqualTo(88L);
    assertThat(result.currentTmTextUnitVariantId()).isEqualTo(99L);
    assertThat(result.statusAfter()).isEqualTo("TRANSLATION_NEEDED");
    assertThat(result.includedInLocalizedFileAfter()).isFalse();
    assertThat(result.auditCommentId()).isEqualTo(1001L);
    assertThat(result.updatedCurrentVariant()).isTrue();

    verify(tmTextUnitVariantCommentService)
        .addComment(
            99L,
            TMTextUnitVariantComment.Type.INTEGRITY_CHECK,
            TMTextUnitVariantComment.Severity.ERROR,
            "audit note");
  }
}
