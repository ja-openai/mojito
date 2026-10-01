package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
  private final EntityManager entityManager;
  private final TMTextUnitCurrentVariantRepository currentVariants;

  public BadTranslationMutationService(
      TMService tmService,
      TMTextUnitVariantCommentService tmTextUnitVariantCommentService,
      EntityManager entityManager,
      TMTextUnitCurrentVariantRepository currentVariants) {
    this.tmService = Objects.requireNonNull(tmService);
    this.tmTextUnitVariantCommentService = Objects.requireNonNull(tmTextUnitVariantCommentService);
    this.entityManager = Objects.requireNonNull(entityManager);
    this.currentVariants = Objects.requireNonNull(currentVariants);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public RejectMutationResult rejectTranslation(
      BadTranslationLookupService.TranslationCandidate candidate,
      BadTranslationLookupService.LocaleRef locale,
      String auditComment) {
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(locale);

    if (!candidate.canReject()) {
      throw new IllegalArgumentException("Candidate cannot be rejected");
    }

    // Match guarded editor/review saves: lock the stable parent before the current translation.
    // Refresh after waiting so a concurrent correction cannot be replaced by the incident snapshot.
    var unit =
        entityManager.find(
            TMTextUnit.class, candidate.tmTextUnitId(), LockModeType.PESSIMISTIC_WRITE);
    if (unit == null) {
      throw staleCandidate();
    }
    entityManager.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    var current =
        currentVariants.findForUpdateByLocaleIdAndTmTextUnitId(
            locale.id(), candidate.tmTextUnitId());
    if (current == null) {
      throw staleCandidate();
    }
    entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
    var variant = current.getTmTextUnitVariant();
    if (variant == null
        || !Objects.equals(current.getId(), candidate.tmTextUnitCurrentVariantId())
        || !Objects.equals(variant.getId(), candidate.tmTextUnitVariantId())
        || !Objects.equals(unit.getContent(), candidate.source())
        || !Objects.equals(variant.getContent(), candidate.target())
        || !Objects.equals(variant.getComment(), candidate.targetComment())
        || variant.getStatus() == null
        || !Objects.equals(variant.getStatus().name(), candidate.status())
        || variant.isIncludedInLocalizedFile() != candidate.includedInLocalizedFile()) {
      throw staleCandidate();
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

  private ResponseStatusException staleCandidate() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "The translation changed. Create a new incident before rejecting it.");
  }
}
