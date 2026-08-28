package com.box.l10n.mojito.service.translation;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.ItemResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.StoredTranslation;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Verification;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Performs one compare-and-set correction under a database lock and independent transaction. */
@Service
public class GuardedTranslationCorrectionTransactionService {

  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository;
  private final TMTextUnitCurrentVariantRepository currentVariantRepository;
  private final UserRepository userRepository;
  private final TMService tmService;
  private final TMTextUnitIntegrityCheckService integrityCheckService;
  private final EntityManager entityManager;

  public GuardedTranslationCorrectionTransactionService(
      ReviewProjectTextUnitRepository reviewProjectTextUnitRepository,
      TMTextUnitCurrentVariantRepository currentVariantRepository,
      UserRepository userRepository,
      TMService tmService,
      TMTextUnitIntegrityCheckService integrityCheckService,
      EntityManager entityManager) {
    this.reviewProjectTextUnitRepository = Objects.requireNonNull(reviewProjectTextUnitRepository);
    this.currentVariantRepository = Objects.requireNonNull(currentVariantRepository);
    this.userRepository = Objects.requireNonNull(userRepository);
    this.tmService = Objects.requireNonNull(tmService);
    this.integrityCheckService = Objects.requireNonNull(integrityCheckService);
    this.entityManager = Objects.requireNonNull(entityManager);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ItemResult apply(int index, Correction correction, Long operatorUserId) {
    ItemResult requiredFieldsConflict = validateRequiredFields(index, correction);
    if (requiredFieldsConflict != null) {
      return requiredFieldsConflict;
    }

    ReviewProjectTextUnit reviewProjectTextUnit =
        reviewProjectTextUnitRepository.findById(correction.reviewProjectTextUnitId()).orElse(null);
    if (reviewProjectTextUnit == null) {
      return conflict(
          index,
          correction,
          "REVIEW_PROJECT_TEXT_UNIT_NOT_FOUND",
          "Review Project text unit is missing");
    }

    ReviewProject reviewProject = reviewProjectTextUnit.getReviewProject();
    TMTextUnit tmTextUnit = reviewProjectTextUnit.getTmTextUnit();
    if (reviewProject == null
        || reviewProject.getType() == null
        || reviewProject.getLocale() == null
        || tmTextUnit == null
        || tmTextUnit.getTm() == null
        || tmTextUnit.getAsset() == null
        || tmTextUnit.getAsset().getRepository() == null
        || tmTextUnit.getAsset().getRepository().getSourceLocale() == null) {
      throw new CorrectionExecutionException(
          "IDENTITY_UNAVAILABLE", "Stored correction identity is incomplete");
    }

    Repository repository = tmTextUnit.getAsset().getRepository();
    Locale locale = reviewProject.getLocale();
    ItemResult identityConflict =
        validateIdentity(
            index,
            correction,
            reviewProjectTextUnit,
            reviewProject,
            repository,
            locale,
            tmTextUnit);
    if (identityConflict != null) {
      return identityConflict;
    }

    TMTextUnitCurrentVariant current =
        currentVariantRepository.findForUpdateByLocaleIdAndTmTextUnitId(
            locale.getId(), tmTextUnit.getId());
    if (current == null || current.getTmTextUnitVariant() == null) {
      return conflict(
          index, correction, "CURRENT_TRANSLATION_MISSING", "Current translation is missing");
    }

    // Keep lock order deterministic: current translation first, then the complete audited
    // identity graph. Clear the initial unlocked lookup and reload the identity through a locking
    // query so MySQL REPEATABLE_READ cannot return a stale snapshot.
    entityManager.clear();
    reviewProjectTextUnit =
        reviewProjectTextUnitRepository
            .findGuardedCorrectionIdentityForUpdateById(correction.reviewProjectTextUnitId())
            .orElse(null);
    if (reviewProjectTextUnit == null) {
      return conflict(
          index,
          correction,
          "REVIEW_PROJECT_TEXT_UNIT_NOT_FOUND",
          "Review Project text unit is missing");
    }
    reviewProject = reviewProjectTextUnit.getReviewProject();
    tmTextUnit = reviewProjectTextUnit.getTmTextUnit();
    if (reviewProject == null
        || reviewProject.getType() == null
        || reviewProject.getLocale() == null
        || tmTextUnit == null
        || tmTextUnit.getTm() == null
        || tmTextUnit.getAsset() == null
        || tmTextUnit.getAsset().getRepository() == null
        || tmTextUnit.getAsset().getRepository().getSourceLocale() == null) {
      throw new CorrectionExecutionException(
          "IDENTITY_UNAVAILABLE", "Stored correction identity is incomplete");
    }
    repository = tmTextUnit.getAsset().getRepository();
    locale = reviewProject.getLocale();
    identityConflict =
        validateIdentity(
            index,
            correction,
            reviewProjectTextUnit,
            reviewProject,
            repository,
            locale,
            tmTextUnit);
    if (identityConflict != null) {
      return identityConflict;
    }

    // Reattach the locked current row after clearing the initial identity snapshot.
    current =
        currentVariantRepository.findForUpdateByLocaleIdAndTmTextUnitId(
            locale.getId(), tmTextUnit.getId());
    if (current == null || current.getTmTextUnitVariant() == null) {
      return conflict(
          index, correction, "CURRENT_TRANSLATION_MISSING", "Current translation is missing");
    }
    if (current.getTmTextUnit() == null
        || current.getLocale() == null
        || !Objects.equals(current.getTmTextUnit().getId(), tmTextUnit.getId())
        || !Objects.equals(current.getLocale().getId(), locale.getId())) {
      return conflict(
          index,
          correction,
          "CURRENT_ROW_IDENTITY_MISMATCH",
          "Current translation identity no longer matches");
    }

    TMTextUnitVariant previousVariant = current.getTmTextUnitVariant();
    if (!Objects.equals(previousVariant.getId(), correction.expectedCurrentVariantId())) {
      return conflict(index, correction, "CURRENT_VARIANT_ID_MISMATCH", "Current variant changed");
    }
    // Intentionally compare the exact stored value before normalizing the replacement.
    if (!Objects.equals(previousVariant.getContent(), correction.expectedOldTarget())) {
      return conflict(index, correction, "EXPECTED_OLD_TARGET_MISMATCH", "Current target changed");
    }

    String normalizedReplacement = NormalizationUtils.normalize(correction.replacementTarget());
    if (Objects.equals(previousVariant.getContent(), normalizedReplacement)) {
      return conflict(
          index,
          correction,
          "REPLACEMENT_NORMALIZES_TO_CURRENT_TARGET",
          "Replacement would not change the stored target");
    }
    try {
      integrityCheckService.checkTMTextUnitIntegrity(tmTextUnit.getId(), normalizedReplacement);
    } catch (IntegrityCheckException exception) {
      throw new CorrectionExecutionException(
          "INTEGRITY_CHECK_FAILED", "Replacement failed translation integrity checks");
    }

    User operator =
        operatorUserId == null ? null : userRepository.findById(operatorUserId).orElse(null);
    if (operator == null || !Boolean.TRUE.equals(operator.getEnabled())) {
      throw new CorrectionExecutionException(
          "OPERATOR_UNAVAILABLE", "Enabled admin user is no longer available");
    }

    Long currentVariantRowId = current.getId();
    boolean previousIncludedInLocalizedFile = previousVariant.isIncludedInLocalizedFile();
    AddTMTextUnitCurrentVariantResult writeResult =
        tmService.addTMTextUnitCurrentVariantWithResult(
            current,
            tmTextUnit.getTm().getId(),
            tmTextUnit.getAsset().getId(),
            tmTextUnit.getId(),
            locale.getId(),
            normalizedReplacement,
            previousVariant.getComment(),
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            previousIncludedInLocalizedFile,
            null,
            operator);
    if (writeResult == null
        || !writeResult.isTmTextUnitCurrentVariantUpdated()
        || writeResult.getTmTextUnitCurrentVariant() == null
        || writeResult.getTmTextUnitCurrentVariant().getTmTextUnitVariant() == null) {
      throw new CorrectionExecutionException(
          "WRITE_NOT_APPLIED", "Correction write did not create a new current variant");
    }
    Long writtenVariantId =
        writeResult.getTmTextUnitCurrentVariant().getTmTextUnitVariant().getId();

    // Force SQL, discard the managed write result, and load the durable row back from the DB.
    entityManager.flush();
    entityManager.clear();
    StoredTranslation stored =
        readStoredTranslation(
            correction.reviewProjectTextUnitId(), locale.getId(), tmTextUnit.getId());
    Verification verification =
        verifyReadBack(
            correction,
            stored,
            currentVariantRowId,
            writtenVariantId,
            normalizedReplacement,
            previousIncludedInLocalizedFile);
    if (!verification.verified()) {
      throw new CorrectionExecutionException(
          "READ_AFTER_WRITE_VERIFICATION_FAILED",
          "Stored correction did not pass read-after-write verification");
    }

    return GuardedTranslationCorrectionService.applied(index, correction, stored, verification);
  }

  private ItemResult validateRequiredFields(int index, Correction correction) {
    if (correction == null) {
      return GuardedTranslationCorrectionService.error(
          index, null, "MISSING_CORRECTION", "Correction entry is required");
    }
    if (!isPositive(correction.reviewProjectId())) {
      return conflict(
          index, correction, "MISSING_REVIEW_PROJECT_ID", "reviewProjectId guard is required");
    }
    if (!isPositive(correction.reviewProjectTextUnitId())) {
      return conflict(
          index,
          correction,
          "MISSING_REVIEW_PROJECT_TEXT_UNIT_ID",
          "reviewProjectTextUnitId guard is required");
    }
    if (!isPositive(correction.repositoryId())) {
      return conflict(index, correction, "MISSING_REPOSITORY_ID", "repositoryId guard is required");
    }
    if (isBlank(correction.repositoryName())) {
      return conflict(
          index, correction, "MISSING_REPOSITORY_NAME", "repositoryName guard is required");
    }
    if (isBlank(correction.locale())) {
      return conflict(index, correction, "MISSING_LOCALE", "locale guard is required");
    }
    if (!isPositive(correction.tmTextUnitId())) {
      return conflict(
          index, correction, "MISSING_TM_TEXT_UNIT_ID", "tmTextUnitId guard is required");
    }
    if (!isPositive(correction.expectedCurrentVariantId())) {
      return conflict(
          index,
          correction,
          "MISSING_EXPECTED_CURRENT_VARIANT_ID",
          "expectedCurrentVariantId guard is required");
    }
    if (correction.expectedOldTarget() == null) {
      return conflict(
          index, correction, "MISSING_EXPECTED_OLD_TARGET", "expectedOldTarget guard is required");
    }
    if (correction.replacementTarget() == null) {
      return GuardedTranslationCorrectionService.error(
          index, correction, "MISSING_REPLACEMENT_TARGET", "replacementTarget is required");
    }
    return null;
  }

  private ItemResult validateIdentity(
      int index,
      Correction correction,
      ReviewProjectTextUnit reviewProjectTextUnit,
      ReviewProject reviewProject,
      Repository repository,
      Locale locale,
      TMTextUnit tmTextUnit) {
    if (!Objects.equals(reviewProjectTextUnit.getId(), correction.reviewProjectTextUnitId())) {
      return conflict(
          index,
          correction,
          "REVIEW_PROJECT_TEXT_UNIT_ID_MISMATCH",
          "Review Project text-unit identity changed");
    }
    if (!Objects.equals(reviewProject.getId(), correction.reviewProjectId())) {
      return conflict(
          index, correction, "REVIEW_PROJECT_ID_MISMATCH", "Review Project identity changed");
    }
    if (!Objects.equals(repository.getId(), correction.repositoryId())) {
      return conflict(index, correction, "REPOSITORY_ID_MISMATCH", "Repository identity changed");
    }
    if (!Objects.equals(repository.getName(), correction.repositoryName())) {
      return conflict(index, correction, "REPOSITORY_NAME_MISMATCH", "Repository name changed");
    }
    if (!Objects.equals(locale.getBcp47Tag(), correction.locale())) {
      return conflict(index, correction, "LOCALE_MISMATCH", "Locale identity changed");
    }
    if (!Objects.equals(tmTextUnit.getId(), correction.tmTextUnitId())) {
      return conflict(
          index, correction, "TM_TEXT_UNIT_ID_MISMATCH", "TM text-unit identity changed");
    }
    if (!isTranslationReviewProjectType(reviewProject.getType())) {
      return conflict(
          index,
          correction,
          "UNSUPPORTED_REVIEW_PROJECT_TYPE",
          "Review Project type cannot authorize translation corrections");
    }
    if (Objects.equals(repository.getSourceLocale().getId(), locale.getId())) {
      return conflict(
          index,
          correction,
          "SOURCE_LOCALE_CORRECTION_FORBIDDEN",
          "Repository source-locale content cannot be corrected");
    }
    return null;
  }

  private StoredTranslation readStoredTranslation(
      Long reviewProjectTextUnitId, Long localeId, Long tmTextUnitId) {
    ReviewProjectTextUnit textUnit =
        reviewProjectTextUnitRepository
            .findGuardedCorrectionIdentityForUpdateById(reviewProjectTextUnitId)
            .orElse(null);
    TMTextUnitCurrentVariant current =
        currentVariantRepository.findForUpdateByLocaleIdAndTmTextUnitId(localeId, tmTextUnitId);
    if (textUnit == null
        || textUnit.getReviewProject() == null
        || textUnit.getReviewProject().getLocale() == null
        || textUnit.getTmTextUnit() == null
        || textUnit.getTmTextUnit().getAsset() == null
        || textUnit.getTmTextUnit().getAsset().getRepository() == null
        || current == null
        || current.getTmTextUnitVariant() == null) {
      throw new CorrectionExecutionException(
          "READ_AFTER_WRITE_MISSING", "Stored correction row could not be re-read");
    }

    ReviewProject project = textUnit.getReviewProject();
    Repository repository = textUnit.getTmTextUnit().getAsset().getRepository();
    Locale locale = project.getLocale();
    TMTextUnitVariant variant = current.getTmTextUnitVariant();
    return new StoredTranslation(
        project.getId(),
        textUnit.getId(),
        repository.getId(),
        repository.getName(),
        locale.getId(),
        locale.getBcp47Tag(),
        textUnit.getTmTextUnit().getId(),
        current.getId(),
        variant.getId(),
        variant.getContent(),
        variant.getStatus().name(),
        variant.isIncludedInLocalizedFile());
  }

  private Verification verifyReadBack(
      Correction correction,
      StoredTranslation stored,
      Long currentVariantRowId,
      Long writtenVariantId,
      String normalizedReplacement,
      boolean expectedIncludedInLocalizedFile) {
    boolean identityMatched =
        Objects.equals(stored.reviewProjectId(), correction.reviewProjectId())
            && Objects.equals(
                stored.reviewProjectTextUnitId(), correction.reviewProjectTextUnitId())
            && Objects.equals(stored.repositoryId(), correction.repositoryId())
            && Objects.equals(stored.repositoryName(), correction.repositoryName())
            && Objects.equals(stored.locale(), correction.locale())
            && Objects.equals(stored.tmTextUnitId(), correction.tmTextUnitId())
            && Objects.equals(stored.currentVariantRowId(), currentVariantRowId);
    boolean variantMatched = Objects.equals(stored.variantId(), writtenVariantId);
    boolean targetMatched = Objects.equals(stored.target(), normalizedReplacement);
    boolean statusReviewNeeded =
        TMTextUnitVariant.Status.REVIEW_NEEDED.name().equals(stored.status());
    boolean includedInLocalizedFileMatched =
        stored.includedInLocalizedFile() == expectedIncludedInLocalizedFile;
    boolean verified =
        identityMatched
            && variantMatched
            && targetMatched
            && statusReviewNeeded
            && includedInLocalizedFileMatched;
    return new Verification(
        true,
        identityMatched,
        variantMatched,
        targetMatched,
        statusReviewNeeded,
        includedInLocalizedFileMatched,
        verified);
  }

  private static ItemResult conflict(
      int index, Correction correction, String code, String message) {
    return GuardedTranslationCorrectionService.conflict(index, correction, code, message);
  }

  private static boolean isPositive(Long value) {
    return value != null && value > 0;
  }

  private static boolean isTranslationReviewProjectType(ReviewProjectType type) {
    return switch (type) {
      case EMERGENCY, NORMAL, BUG_FIXES -> true;
      case TERMINOLOGY, TERM_CANDIDATE, UNKNOWN -> false;
    };
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Carries only a bounded operator-safe code/message across the transaction boundary. */
  public static class CorrectionExecutionException extends RuntimeException {

    private final String code;
    private final String safeMessage;

    public CorrectionExecutionException(String code, String safeMessage) {
      super(safeMessage);
      this.code = Objects.requireNonNull(code);
      this.safeMessage = Objects.requireNonNull(safeMessage);
    }

    public String getCode() {
      return code;
    }

    public String getSafeMessage() {
      return safeMessage;
    }
  }
}
