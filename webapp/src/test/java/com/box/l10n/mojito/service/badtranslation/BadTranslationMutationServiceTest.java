package com.box.l10n.mojito.service.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class BadTranslationMutationServiceTest {

  private final TMService tmService = Mockito.mock(TMService.class);
  private final TMTextUnitVariantCommentService tmTextUnitVariantCommentService =
      Mockito.mock(TMTextUnitVariantCommentService.class);
  private final EntityManager entities = mock(EntityManager.class);
  private final TMTextUnitCurrentVariantRepository currents =
      mock(TMTextUnitCurrentVariantRepository.class);
  private final TMTextUnit unit = new TMTextUnit();
  private final TMTextUnitCurrentVariant current = new TMTextUnitCurrentVariant();
  private ThreadBoundTransactionAdvice transactionAdvice;

  private final BadTranslationMutationService service =
      new BadTranslationMutationService(
          tmService, tmTextUnitVariantCommentService, entities, currents);

  @Before
  public void setUp() {
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    transactionAdvice = new ThreadBoundTransactionAdvice(transactions);
    unit.setContent("source");
    current.setId(52L);
    var variant = new TMTextUnitVariant();
    variant.setId(63L);
    variant.setContent("{bad}");
    variant.setComment("comment");
    variant.setStatus(TMTextUnitVariant.Status.APPROVED);
    variant.setIncludedInLocalizedFile(true);
    current.setTmTextUnitVariant(variant);
    when(entities.find(TMTextUnit.class, 41L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(unit);
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(7L, 41L)).thenReturn(current);
  }

  @After
  public void restoreTransactions() {
    transactionAdvice.close();
  }

  @Test
  public void rejectTranslationTransitionsVariantAndAddsAuditComment() {
    BadTranslationLookupService.TranslationCandidate candidate = candidate();
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

    var order = inOrder(entities, currents, tmService);
    order.verify(entities).find(TMTextUnit.class, 41L, LockModeType.PESSIMISTIC_WRITE);
    order.verify(entities).refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    order.verify(currents).findForUpdateByLocaleIdAndTmTextUnitId(7L, 41L);
    order.verify(entities).refresh(current, LockModeType.PESSIMISTIC_WRITE);
    order
        .verify(tmService)
        .addTMTextUnitCurrentVariantWithResult(
            41L, 7L, "{bad}", "comment", TMTextUnitVariant.Status.TRANSLATION_NEEDED, false, null);

    verify(tmTextUnitVariantCommentService)
        .addComment(
            99L,
            TMTextUnitVariantComment.Type.INTEGRITY_CHECK,
            TMTextUnitVariantComment.Severity.ERROR,
            "audit note");
  }

  @Test
  public void correctionCommittedWhileWaitingForLockIsNotOverwritten() {
    doAnswer(
            invocation -> {
              var replacement = new TMTextUnitVariant();
              replacement.setId(64L);
              replacement.setContent("A corrected translation");
              current.setTmTextUnitVariant(replacement);
              return null;
            })
        .when(entities)
        .refresh(current, LockModeType.PESSIMISTIC_WRITE);

    assertStale();
    assertThat(current.getTmTextUnitVariant().getId()).isEqualTo(64L);
    assertThat(current.getTmTextUnitVariant().getContent()).isEqualTo("A corrected translation");
  }

  @Test
  public void changedTargetOrSourceCannotPassWithTheSameVariantId() {
    current.getTmTextUnitVariant().setContent("A corrected translation");
    assertStale();
    current.getTmTextUnitVariant().setContent("{bad}");
    unit.setContent("Changed source");
    assertStale();
  }

  @Test
  public void deletedOrRecreatedCurrentRowCannotBeRejected() {
    current.setId(53L);
    assertStale();
    current.setId(52L);
    current.setTmTextUnitVariant(null);
    assertStale();
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(7L, 41L)).thenReturn(null);
    assertStale();
  }

  private void assertStale() {
    assertThatThrownBy(
            () ->
                service.rejectTranslation(
                    candidate(), new BadTranslationLookupService.LocaleRef(7L, "hr"), "audit note"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    verifyNoInteractions(tmService, tmTextUnitVariantCommentService);
  }

  private BadTranslationLookupService.TranslationCandidate candidate() {
    return new BadTranslationLookupService.TranslationCandidate(
        new BadTranslationLookupService.RepositoryRef(11L, "sample-app"),
        41L,
        null,
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
  }
}
