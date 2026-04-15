package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BadTranslationMutationService {

  public record RejectMutationResult(
      Long previousTmTextUnitCurrentVariantId,
      Long previousTmTextUnitVariantId,
      Long currentTmTextUnitCurrentVariantId,
      Long currentTmTextUnitVariantId,
      String statusAfter,
      boolean includedInLocalizedFileAfter,
      Long auditCommentId,
      boolean updatedCurrentVariant) {}

  private final TMService tmService;
  private final TMTextUnitVariantCommentService tmTextUnitVariantCommentService;

  public BadTranslationMutationService(
      TMService tmService, TMTextUnitVariantCommentService tmTextUnitVariantCommentService) {
    this.tmService = Objects.requireNonNull(tmService);
    this.tmTextUnitVariantCommentService = Objects.requireNonNull(tmTextUnitVariantCommentService);
  }

  public RejectMutationResult rejectTranslation(
      BadTranslationLookupService.TranslationCandidate candidate,
      BadTranslationLookupService.LocaleRef locale,
      String auditComment) {
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(locale);

    if (!candidate.canReject()) {
      throw new IllegalArgumentException("Candidate cannot be rejected");
    }

    AddTMTextUnitCurrentVariantResult result =
        tmService.addTMTextUnitCurrentVariantWithResult(
            candidate.tmTextUnitId(),
            locale.id(),
            candidate.target(),
            candidate.targetComment(),
            TMTextUnitVariant.Status.TRANSLATION_NEEDED,
            false,
            null);

    if (result.getTmTextUnitCurrentVariant() == null
        || result.getTmTextUnitCurrentVariant().getTmTextUnitVariant() == null) {
      throw new IllegalStateException("Current variant mutation did not return a variant");
    }

    TMTextUnitVariant currentVariant = result.getTmTextUnitCurrentVariant().getTmTextUnitVariant();
    TMTextUnitVariantComment comment =
        tmTextUnitVariantCommentService.addComment(
            currentVariant.getId(),
            TMTextUnitVariantComment.Type.INTEGRITY_CHECK,
            TMTextUnitVariantComment.Severity.ERROR,
            auditComment);

    return new RejectMutationResult(
        candidate.tmTextUnitCurrentVariantId(),
        candidate.tmTextUnitVariantId(),
        result.getTmTextUnitCurrentVariant().getId(),
        currentVariant.getId(),
        currentVariant.getStatus().name(),
        currentVariant.isIncludedInLocalizedFile(),
        comment == null ? null : comment.getId(),
        result.isTmTextUnitCurrentVariantUpdated());
  }
}
