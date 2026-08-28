package com.box.l10n.mojito.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.BatchResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.ItemResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Outcome;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.StoredTranslation;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Verification;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

public class GuardedTranslationCorrectionServiceTest {

  private final GuardedTranslationCorrectionTransactionService transactionService =
      Mockito.mock(GuardedTranslationCorrectionTransactionService.class);
  private final UserService userService = Mockito.mock(UserService.class);
  private final GuardedTranslationCorrectionService service =
      new GuardedTranslationCorrectionService(transactionService, userService);
  private final User admin = new User();

  @Before
  public void setUp() {
    admin.setId(42L);
    admin.setEnabled(true);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(userService.getCurrentUser()).thenReturn(Optional.of(admin));
  }

  @Test
  public void requiresEnabledAdminBeforeProcessingRows() {
    when(userService.isCurrentUserAdmin()).thenReturn(false);

    assertThatThrownBy(() -> service.applyCorrections(List.of(correction(1L, "old", "new"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Admin role required");
    verifyNoInteractions(transactionService);

    when(userService.isCurrentUserAdmin()).thenReturn(true);
    admin.setEnabled(false);
    assertThatThrownBy(() -> service.applyCorrections(List.of(correction(1L, "old", "new"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Enabled admin user required");
    verifyNoInteractions(transactionService);
  }

  @Test
  public void reportsOrderedAppliedConflictAndErrorRowsWithoutFailurePayloadEcho() {
    Correction appliedCorrection = correction(1L, "old-1", "stored-1");
    Correction conflictCorrection = correction(2L, "secret-old-2", "secret-new-2");
    Correction errorCorrection = correction(3L, "password=secret-old-3", "token-secret-new-3");
    StoredTranslation stored =
        new StoredTranslation(
            2L, 3L, 1L, "repo", 7L, "fr-FR", 6L, 8L, 101L, "stored-1", "REVIEW_NEEDED", true);
    Verification verification = new Verification(true, true, true, true, true, true, true);
    ItemResult applied =
        GuardedTranslationCorrectionService.applied(0, appliedCorrection, stored, verification);
    ItemResult conflict =
        GuardedTranslationCorrectionService.conflict(
            1, conflictCorrection, "CURRENT_VARIANT_ID_MISMATCH", "Current variant changed");

    when(transactionService.apply(0, appliedCorrection, admin.getId())).thenReturn(applied);
    when(transactionService.apply(1, conflictCorrection, admin.getId())).thenReturn(conflict);
    when(transactionService.apply(2, errorCorrection, admin.getId()))
        .thenThrow(
            new GuardedTranslationCorrectionTransactionService.CorrectionExecutionException(
                "READ_AFTER_WRITE_VERIFICATION_FAILED",
                "Stored correction did not pass read-after-write verification"));

    BatchResult result =
        service.applyCorrections(List.of(appliedCorrection, conflictCorrection, errorCorrection));

    assertThat(result.requestedCount()).isEqualTo(3);
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.conflictCount()).isEqualTo(1);
    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.results())
        .extracting(ItemResult::outcome)
        .containsExactly(Outcome.APPLIED, Outcome.CONFLICT, Outcome.ERROR);
    assertThat(result.results()).extracting(ItemResult::index).containsExactly(0, 1, 2);
    assertThat(result.results().get(2).code()).isEqualTo("READ_AFTER_WRITE_VERIFICATION_FAILED");
    assertThat(result.toString()).contains("stored-1");
    assertThat(result.toString())
        .doesNotContain(
            "secret-old-2", "secret-new-2", "password=secret-old-3", "token-secret-new-3");
  }

  @Test
  public void unexpectedRowFailureIsSanitizedAndDoesNotStopLaterRows() {
    Correction first = correction(1L, "credential-old", "credential-new");
    Correction second = correction(2L, "old", "new");
    ItemResult conflict =
        GuardedTranslationCorrectionService.conflict(
            1, second, "CURRENT_TRANSLATION_MISSING", "Current translation is missing");
    when(transactionService.apply(0, first, admin.getId()))
        .thenThrow(new IllegalStateException("jdbc:mysql://user:password@host/secret"));
    when(transactionService.apply(1, second, admin.getId())).thenReturn(conflict);

    Logger logger = (Logger) LoggerFactory.getLogger(GuardedTranslationCorrectionService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    BatchResult result;
    try {
      result = service.applyCorrections(List.of(first, second));
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(result.errorCount()).isEqualTo(1);
    assertThat(result.conflictCount()).isEqualTo(1);
    assertThat(result.results().get(0).code()).isEqualTo("CORRECTION_OUTCOME_UNKNOWN");
    assertThat(result.results().get(0).message())
        .isEqualTo(
            "Correction outcome is unknown; re-read the current translation before retrying");
    assertThat(result.toString()).doesNotContain("jdbc:mysql", "credential-old", "credential-new");
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .allSatisfy(
            message ->
                assertThat(message)
                    .doesNotContain(
                        "jdbc:mysql", "user:password", "credential-old", "credential-new"));
    assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
  }

  @Test
  public void rejectsEmptyAndOverlargeBatchesBeforeWorkerCalls() {
    assertThatThrownBy(() -> service.applyCorrections(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("corrections are required");
    List<Correction> tooMany =
        java.util.stream.IntStream.rangeClosed(
                0, GuardedTranslationCorrectionService.MAX_CORRECTIONS)
            .mapToObj(index -> correction((long) index + 1, "old", "new"))
            .toList();
    assertThatThrownBy(() -> service.applyCorrections(tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 1000");
    verifyNoInteractions(transactionService);
  }

  @Test
  public void rejectsPerStringAndAggregateBoundsBeforeWorkerCalls() {
    assertBoundRejected(
        correctionWithStrings(
            "r".repeat(GuardedTranslationCorrectionService.MAX_REPOSITORY_NAME_CHARACTERS + 1),
            "fr-FR",
            "old",
            "new"),
        "repositoryName");
    assertBoundRejected(
        correctionWithStrings(
            "repo",
            "l".repeat(GuardedTranslationCorrectionService.MAX_LOCALE_CHARACTERS + 1),
            "old",
            "new"),
        "locale");
    assertBoundRejected(
        correctionWithStrings(
            "repo",
            "fr-FR",
            "o".repeat(GuardedTranslationCorrectionService.MAX_TRANSLATION_CHARACTERS + 1),
            "new"),
        "expectedOldTarget");
    assertBoundRejected(
        correctionWithStrings(
            "repo",
            "fr-FR",
            "old",
            "n".repeat(GuardedTranslationCorrectionService.MAX_TRANSLATION_CHARACTERS + 1)),
        "replacementTarget");

    String maximumTranslation =
        "x".repeat(GuardedTranslationCorrectionService.MAX_TRANSLATION_CHARACTERS);
    List<Correction> aggregateTooLarge =
        java.util.stream.IntStream.range(0, 5)
            .mapToObj(
                index ->
                    new Correction(
                        2L,
                        (long) index + 3,
                        1L,
                        "repo",
                        "fr-FR",
                        (long) index + 6,
                        (long) index + 10,
                        maximumTranslation,
                        maximumTranslation))
            .toList();
    assertThatThrownBy(() -> service.applyCorrections(aggregateTooLarge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("aggregate limit")
        .hasMessageContaining(
            Long.toString(GuardedTranslationCorrectionService.MAX_TOTAL_STRING_CHARACTERS));

    verifyNoInteractions(transactionService);
  }

  private Correction correction(Long suffix, String expectedOldTarget, String replacementTarget) {
    return new Correction(
        2L,
        suffix + 2,
        1L,
        "repo",
        "fr-FR",
        suffix + 5,
        suffix + 9,
        expectedOldTarget,
        replacementTarget);
  }

  private Correction correctionWithStrings(
      String repositoryName, String locale, String expectedOldTarget, String replacementTarget) {
    return new Correction(
        2L, 3L, 1L, repositoryName, locale, 6L, 10L, expectedOldTarget, replacementTarget);
  }

  private void assertBoundRejected(Correction correction, String field) {
    assertThatThrownBy(() -> service.applyCorrections(List.of(correction)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field)
        .hasMessageNotContaining(correction.expectedOldTarget())
        .hasMessageNotContaining(correction.replacementTarget());
  }
}
