package com.box.l10n.mojito.service.translation;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Applies independently guarded translation corrections for an authenticated administrator. */
@Service
public class GuardedTranslationCorrectionService {

  public static final int MAX_CORRECTIONS = 1_000;
  public static final int MAX_REPOSITORY_NAME_CHARACTERS = Repository.NAME_MAX_LENGTH;
  public static final int MAX_LOCALE_CHARACTERS = 255;
  public static final int MAX_TRANSLATION_CHARACTERS = 1_000_000;
  public static final long MAX_TOTAL_STRING_CHARACTERS = 8_000_000L;

  private static final Logger logger =
      LoggerFactory.getLogger(GuardedTranslationCorrectionService.class);

  private final GuardedTranslationCorrectionTransactionService transactionService;
  private final UserService userService;

  public GuardedTranslationCorrectionService(
      GuardedTranslationCorrectionTransactionService transactionService, UserService userService) {
    this.transactionService = Objects.requireNonNull(transactionService);
    this.userService = Objects.requireNonNull(userService);
  }

  public enum Outcome {
    APPLIED,
    CONFLICT,
    ERROR
  }

  /**
   * Every field except {@code replacementTarget} is an audited current-state guard. Repository id
   * and name are both required so a stale human-readable plan cannot silently target a renamed or
   * different repository.
   */
  public record Correction(
      Long reviewProjectId,
      Long reviewProjectTextUnitId,
      Long repositoryId,
      String repositoryName,
      String locale,
      Long tmTextUnitId,
      Long expectedCurrentVariantId,
      String expectedOldTarget,
      String replacementTarget) {}

  /** Input identity safe to echo without returning either requested translation payload. */
  public record CorrectionIdentity(
      Long reviewProjectId,
      Long reviewProjectTextUnitId,
      Long repositoryId,
      String repositoryName,
      String locale,
      Long tmTextUnitId,
      Long expectedCurrentVariantId) {}

  /** Durable state loaded from the database after the write has been flushed and cleared. */
  public record StoredTranslation(
      Long reviewProjectId,
      Long reviewProjectTextUnitId,
      Long repositoryId,
      String repositoryName,
      Long localeId,
      String locale,
      Long tmTextUnitId,
      Long currentVariantRowId,
      Long variantId,
      String target,
      String status,
      boolean includedInLocalizedFile) {}

  public record Verification(
      boolean readAfterWrite,
      boolean identityMatched,
      boolean variantMatched,
      boolean targetMatched,
      boolean statusReviewNeeded,
      boolean includedInLocalizedFileMatched,
      boolean verified) {

    public static Verification notPerformed() {
      return new Verification(false, false, false, false, false, false, false);
    }
  }

  public record ItemResult(
      int index,
      Outcome outcome,
      String code,
      String message,
      CorrectionIdentity identity,
      StoredTranslation stored,
      Verification verification) {}

  public record BatchResult(
      int requestedCount,
      int appliedCount,
      int conflictCount,
      int errorCount,
      List<ItemResult> results) {

    public BatchResult {
      results = List.copyOf(results);
    }
  }

  /**
   * Applies each correction in its own transaction. A conflict or error therefore cannot hide or
   * roll back an unrelated successful row, and every result retains its input index.
   */
  public BatchResult applyCorrections(List<Correction> corrections) {
    User operator = requireCurrentAdmin();
    if (corrections == null || corrections.isEmpty()) {
      throw new IllegalArgumentException("corrections are required");
    }
    if (corrections.size() > MAX_CORRECTIONS) {
      throw new IllegalArgumentException(
          "corrections must contain at most " + MAX_CORRECTIONS + " entries");
    }
    validateInputBounds(corrections);

    List<ItemResult> results = new ArrayList<>(corrections.size());
    int appliedCount = 0;
    int conflictCount = 0;
    int errorCount = 0;
    for (int index = 0; index < corrections.size(); index++) {
      Correction correction = corrections.get(index);
      ItemResult result;
      try {
        result = transactionService.apply(index, correction, operator.getId());
      } catch (GuardedTranslationCorrectionTransactionService.CorrectionExecutionException e) {
        result = error(index, correction, e.getCode(), e.getSafeMessage());
      } catch (RuntimeException e) {
        CorrectionIdentity identity = identity(correction);
        logger.error(
            "Guarded translation correction failed: index={}, reviewProjectId={}, reviewProjectTextUnitId={}, repositoryId={}, tmTextUnitId={}, exceptionType={}",
            index,
            identity.reviewProjectId(),
            identity.reviewProjectTextUnitId(),
            identity.repositoryId(),
            identity.tmTextUnitId(),
            e.getClass().getName());
        result =
            error(
                index,
                correction,
                "CORRECTION_OUTCOME_UNKNOWN",
                "Correction outcome is unknown; re-read the current translation before retrying");
      }

      results.add(result);
      switch (result.outcome()) {
        case APPLIED -> appliedCount++;
        case CONFLICT -> conflictCount++;
        case ERROR -> errorCount++;
      }
    }

    return new BatchResult(corrections.size(), appliedCount, conflictCount, errorCount, results);
  }

  private static void validateInputBounds(List<Correction> corrections) {
    long totalCharacters = 0;
    for (int index = 0; index < corrections.size(); index++) {
      Correction correction = corrections.get(index);
      if (correction == null) {
        continue;
      }
      totalCharacters =
          addBoundedString(
              index,
              "repositoryName",
              correction.repositoryName(),
              MAX_REPOSITORY_NAME_CHARACTERS,
              totalCharacters);
      totalCharacters =
          addBoundedString(
              index, "locale", correction.locale(), MAX_LOCALE_CHARACTERS, totalCharacters);
      totalCharacters =
          addBoundedString(
              index,
              "expectedOldTarget",
              correction.expectedOldTarget(),
              MAX_TRANSLATION_CHARACTERS,
              totalCharacters);
      totalCharacters =
          addBoundedString(
              index,
              "replacementTarget",
              correction.replacementTarget(),
              MAX_TRANSLATION_CHARACTERS,
              totalCharacters);
    }
  }

  private static long addBoundedString(
      int index, String field, String value, int maximum, long totalCharacters) {
    if (value == null) {
      return totalCharacters;
    }
    if (value.length() > maximum) {
      throw new IllegalArgumentException(
          "corrections[" + index + "]." + field + " exceeds " + maximum + " characters");
    }
    long updatedTotal = totalCharacters + value.length();
    if (updatedTotal > MAX_TOTAL_STRING_CHARACTERS) {
      throw new IllegalArgumentException(
          "correction strings exceed aggregate limit of "
              + MAX_TOTAL_STRING_CHARACTERS
              + " characters");
    }
    return updatedTotal;
  }

  private User requireCurrentAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
    return userService
        .getCurrentUser()
        .filter(user -> Boolean.TRUE.equals(user.getEnabled()))
        .orElseThrow(() -> new AccessDeniedException("Enabled admin user required"));
  }

  static ItemResult applied(
      int index, Correction correction, StoredTranslation stored, Verification verification) {
    return new ItemResult(
        index,
        Outcome.APPLIED,
        "APPLIED",
        "Correction applied and verified",
        identity(correction),
        stored,
        verification);
  }

  static ItemResult conflict(int index, Correction correction, String code, String message) {
    return new ItemResult(
        index,
        Outcome.CONFLICT,
        code,
        message,
        identity(correction),
        null,
        Verification.notPerformed());
  }

  static ItemResult error(int index, Correction correction, String code, String message) {
    return new ItemResult(
        index,
        Outcome.ERROR,
        code,
        message,
        identity(correction),
        null,
        Verification.notPerformed());
  }

  static CorrectionIdentity identity(Correction correction) {
    if (correction == null) {
      return new CorrectionIdentity(null, null, null, null, null, null, null);
    }
    return new CorrectionIdentity(
        correction.reviewProjectId(),
        correction.reviewProjectTextUnitId(),
        correction.repositoryId(),
        correction.repositoryName(),
        correction.locale(),
        correction.tmTextUnitId(),
        correction.expectedCurrentVariantId());
  }
}
