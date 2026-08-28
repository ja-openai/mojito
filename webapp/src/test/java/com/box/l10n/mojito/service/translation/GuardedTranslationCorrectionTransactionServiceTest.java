package com.box.l10n.mojito.service.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TM;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.ItemResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Outcome;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.mockito.Mockito;

public class GuardedTranslationCorrectionTransactionServiceTest {

  @Test
  public void appliesNormalizedReplacementAsReviewNeededAndVerifiesDurableReadBack() {
    Fixture fixture = new Fixture();
    Correction correction = fixture.correction("ancien", "cafe\u0301");

    ItemResult result = fixture.service.apply(4, correction, fixture.operator.getId());

    assertThat(result.index()).isEqualTo(4);
    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(result.stored().target()).isEqualTo("caf\u00e9");
    assertThat(result.stored().status()).isEqualTo("REVIEW_NEEDED");
    assertThat(result.stored().variantId()).isEqualTo(fixture.writtenVariant.getId());
    assertThat(result.verification().readAfterWrite()).isTrue();
    assertThat(result.verification().verified()).isTrue();
    assertThat(result.stored().includedInLocalizedFile()).isFalse();

    verify(fixture.integrityCheckService)
        .checkTMTextUnitIntegrity(fixture.textUnit.getId(), "caf\u00e9");
    verify(fixture.tmService)
        .addTMTextUnitCurrentVariantWithResult(
            eq(fixture.current),
            eq(fixture.tm.getId()),
            eq(fixture.asset.getId()),
            eq(fixture.textUnit.getId()),
            eq(fixture.locale.getId()),
            eq("caf\u00e9"),
            eq("existing comment"),
            eq(TMTextUnitVariant.Status.REVIEW_NEEDED),
            eq(false),
            isNull(),
            eq(fixture.operator));
    verify(fixture.entityManager).flush();
    verify(fixture.entityManager, times(2)).clear();
    verify(fixture.currentVariantRepository, times(3))
        .findForUpdateByLocaleIdAndTmTextUnitId(fixture.locale.getId(), fixture.textUnit.getId());
  }

  @Test
  public void fullyMatchingSourceLocaleCorrectionIsRejectedBeforeLockOrWrite() {
    Fixture fixture = new Fixture();
    fixture.repository.setSourceLocale(fixture.locale);

    ItemResult result = fixture.service.apply(0, fixture.correction("ancien", "caf\u00e9"), 9L);

    assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
    assertThat(result.code()).isEqualTo("SOURCE_LOCALE_CORRECTION_FORBIDDEN");
    verifyNoInteractions(
        fixture.currentVariantRepository, fixture.integrityCheckService, fixture.tmService);
  }

  @Test
  public void nonTranslationProjectTypesCannotAuthorizeTranslationCorrections() {
    for (ReviewProjectType type :
        List.of(
            ReviewProjectType.TERMINOLOGY,
            ReviewProjectType.TERM_CANDIDATE,
            ReviewProjectType.UNKNOWN)) {
      Fixture fixture = new Fixture();
      fixture.reviewProject.setType(type);

      ItemResult result = fixture.service.apply(0, fixture.correction("ancien", "caf\u00e9"), 9L);

      assertThat(result.outcome()).as(type.name()).isEqualTo(Outcome.CONFLICT);
      assertThat(result.code()).isEqualTo("UNSUPPORTED_REVIEW_PROJECT_TYPE");
      verifyNoInteractions(
          fixture.currentVariantRepository, fixture.integrityCheckService, fixture.tmService);
    }
  }

  @Test
  public void allTranslationReviewProjectTypesRemainEligible() {
    for (ReviewProjectType type :
        List.of(
            ReviewProjectType.EMERGENCY, ReviewProjectType.NORMAL, ReviewProjectType.BUG_FIXES)) {
      Fixture fixture = new Fixture();
      fixture.reviewProject.setType(type);

      ItemResult result = fixture.service.apply(0, fixture.correction("ancien", "cafe\u0301"), 9L);

      assertThat(result.outcome()).as(type.name()).isEqualTo(Outcome.APPLIED);
    }
  }

  @Test
  public void everyMissingGuardReturnsAConflictBeforeDatabaseLookup() {
    List<TestCase> cases =
        List.of(
            new TestCase(
                new Correction(null, 3L, 1L, "repo", "fr-FR", 6L, 10L, "old", "new"),
                "MISSING_REVIEW_PROJECT_ID"),
            new TestCase(
                new Correction(2L, null, 1L, "repo", "fr-FR", 6L, 10L, "old", "new"),
                "MISSING_REVIEW_PROJECT_TEXT_UNIT_ID"),
            new TestCase(
                new Correction(2L, 3L, null, "repo", "fr-FR", 6L, 10L, "old", "new"),
                "MISSING_REPOSITORY_ID"),
            new TestCase(
                new Correction(2L, 3L, 1L, " ", "fr-FR", 6L, 10L, "old", "new"),
                "MISSING_REPOSITORY_NAME"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", " ", 6L, 10L, "old", "new"), "MISSING_LOCALE"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", "fr-FR", null, 10L, "old", "new"),
                "MISSING_TM_TEXT_UNIT_ID"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", "fr-FR", 6L, null, "old", "new"),
                "MISSING_EXPECTED_CURRENT_VARIANT_ID"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", "fr-FR", 6L, 10L, null, "new"),
                "MISSING_EXPECTED_OLD_TARGET"));

    for (TestCase testCase : cases) {
      Fixture fixture = new Fixture();
      ItemResult result = fixture.service.apply(0, testCase.correction(), 9L);
      assertThat(result.outcome()).as(testCase.expectedCode()).isEqualTo(Outcome.CONFLICT);
      assertThat(result.code()).isEqualTo(testCase.expectedCode());
      verifyNoInteractions(fixture.reviewProjectTextUnitRepository, fixture.tmService);
    }
  }

  @Test
  public void missingReplacementReturnsStructuredErrorWithoutWriting() {
    Fixture fixture = new Fixture();
    Correction correction = new Correction(2L, 3L, 1L, "repo", "fr-FR", 6L, 10L, "old", null);

    ItemResult result = fixture.service.apply(0, correction, 9L);

    assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
    assertThat(result.code()).isEqualTo("MISSING_REPLACEMENT_TARGET");
    verifyNoInteractions(fixture.reviewProjectTextUnitRepository, fixture.tmService);
  }

  @Test
  public void everyIdentityAndCurrentStateGuardMismatchSkipsTheWrite() {
    List<TestCase> cases =
        List.of(
            new TestCase(
                new Correction(99L, 3L, 1L, "repo", "fr-FR", 6L, 10L, "ancien", "new"),
                "REVIEW_PROJECT_ID_MISMATCH"),
            new TestCase(
                new Correction(2L, 99L, 1L, "repo", "fr-FR", 6L, 10L, "ancien", "new"),
                "REVIEW_PROJECT_TEXT_UNIT_NOT_FOUND"),
            new TestCase(
                new Correction(2L, 3L, 99L, "repo", "fr-FR", 6L, 10L, "ancien", "new"),
                "REPOSITORY_ID_MISMATCH"),
            new TestCase(
                new Correction(2L, 3L, 1L, "renamed", "fr-FR", 6L, 10L, "ancien", "new"),
                "REPOSITORY_NAME_MISMATCH"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", "fr", 6L, 10L, "ancien", "new"),
                "LOCALE_MISMATCH"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", "fr-FR", 99L, 10L, "ancien", "new"),
                "TM_TEXT_UNIT_ID_MISMATCH"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", "fr-FR", 6L, 99L, "ancien", "new"),
                "CURRENT_VARIANT_ID_MISMATCH"),
            new TestCase(
                new Correction(2L, 3L, 1L, "repo", "fr-FR", 6L, 10L, "stale", "new"),
                "EXPECTED_OLD_TARGET_MISMATCH"));

    for (TestCase testCase : cases) {
      Fixture fixture = new Fixture();
      ItemResult result = fixture.service.apply(0, testCase.correction(), 9L);
      assertThat(result.outcome()).as(testCase.expectedCode()).isEqualTo(Outcome.CONFLICT);
      assertThat(result.code()).isEqualTo(testCase.expectedCode());
      verify(fixture.tmService, never())
          .addTMTextUnitCurrentVariantWithResult(
              Mockito.any(),
              Mockito.anyLong(),
              Mockito.anyLong(),
              Mockito.anyLong(),
              Mockito.anyLong(),
              Mockito.anyString(),
              Mockito.any(),
              Mockito.any(),
              Mockito.anyBoolean(),
              Mockito.any(),
              Mockito.any());
    }
  }

  @Test
  public void identityIsLockedReloadedAndRevalidatedAfterTheCurrentRowLock() {
    Fixture fixture = new Fixture();
    Repository renamedRepository = new Repository();
    renamedRepository.setId(fixture.repository.getId());
    renamedRepository.setName("renamed-repo");
    renamedRepository.setSourceLocale(fixture.sourceLocale);
    Asset reloadedAsset = new Asset();
    reloadedAsset.setId(fixture.asset.getId());
    reloadedAsset.setRepository(renamedRepository);
    TMTextUnit reloadedTextUnit = new TMTextUnit();
    reloadedTextUnit.setId(fixture.textUnit.getId());
    reloadedTextUnit.setTm(fixture.tm);
    reloadedTextUnit.setAsset(reloadedAsset);
    ReviewProjectTextUnit reloadedReviewTextUnit = new ReviewProjectTextUnit();
    reloadedReviewTextUnit.setId(fixture.reviewProjectTextUnit.getId());
    reloadedReviewTextUnit.setReviewProject(fixture.reviewProject);
    reloadedReviewTextUnit.setTmTextUnit(reloadedTextUnit);
    when(fixture.reviewProjectTextUnitRepository.findGuardedCorrectionIdentityForUpdateById(
            fixture.reviewProjectTextUnit.getId()))
        .thenReturn(Optional.of(reloadedReviewTextUnit));

    ItemResult result = fixture.service.apply(0, fixture.correction("ancien", "replacement"), 9L);

    assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
    assertThat(result.code()).isEqualTo("REPOSITORY_NAME_MISMATCH");
    verify(fixture.entityManager).clear();
    verifyNoInteractions(fixture.integrityCheckService, fixture.tmService);
  }

  @Test
  public void expectedOldTargetGuardIsExactAndIsNeverNormalized() {
    Fixture fixture = new Fixture();
    fixture.previousVariant.setContent("caf\u00e9");
    Correction correction = fixture.correction("cafe\u0301", "corrected");

    ItemResult result = fixture.service.apply(0, correction, 9L);

    assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
    assertThat(result.code()).isEqualTo("EXPECTED_OLD_TARGET_MISMATCH");
    verifyNoInteractions(fixture.integrityCheckService, fixture.tmService);
  }

  @Test
  public void replacementThatNormalizesToCurrentTargetIsAConflict() {
    Fixture fixture = new Fixture();
    fixture.previousVariant.setContent("caf\u00e9");
    Correction correction = fixture.correction("caf\u00e9", "cafe\u0301");

    ItemResult result = fixture.service.apply(0, correction, 9L);

    assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
    assertThat(result.code()).isEqualTo("REPLACEMENT_NORMALIZES_TO_CURRENT_TARGET");
    verifyNoInteractions(fixture.integrityCheckService, fixture.tmService);
  }

  @Test
  public void integrityFailureIsAWriteFreeSafeError() {
    Fixture fixture = new Fixture();
    Mockito.doThrow(new IntegrityCheckException("sensitive target details"))
        .when(fixture.integrityCheckService)
        .checkTMTextUnitIntegrity(fixture.textUnit.getId(), "replacement");

    assertThatThrownBy(
            () -> fixture.service.apply(0, fixture.correction("ancien", "replacement"), 9L))
        .isInstanceOf(
            GuardedTranslationCorrectionTransactionService.CorrectionExecutionException.class)
        .hasMessage("Replacement failed translation integrity checks");
    verifyNoInteractions(fixture.tmService);
  }

  @Test
  public void failedReadAfterWriteVerificationRaisesSafeRollbackError() {
    Fixture fixture = new Fixture();
    fixture.writtenVariant.setStatus(TMTextUnitVariant.Status.APPROVED);

    assertThatThrownBy(
            () -> fixture.service.apply(0, fixture.correction("ancien", "caf\u00e9"), 9L))
        .isInstanceOf(
            GuardedTranslationCorrectionTransactionService.CorrectionExecutionException.class)
        .hasMessage("Stored correction did not pass read-after-write verification");
    verify(fixture.entityManager).flush();
    verify(fixture.entityManager, times(2)).clear();
  }

  private record TestCase(Correction correction, String expectedCode) {}

  private static final class Fixture {

    private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository =
        Mockito.mock(ReviewProjectTextUnitRepository.class);
    private final TMTextUnitCurrentVariantRepository currentVariantRepository =
        Mockito.mock(TMTextUnitCurrentVariantRepository.class);
    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final TMService tmService = Mockito.mock(TMService.class);
    private final TMTextUnitIntegrityCheckService integrityCheckService =
        Mockito.mock(TMTextUnitIntegrityCheckService.class);
    private final EntityManager entityManager = Mockito.mock(EntityManager.class);

    private final Repository repository = new Repository();
    private final Locale sourceLocale = new Locale();
    private final Locale locale = new Locale();
    private final TM tm = new TM();
    private final Asset asset = new Asset();
    private final TMTextUnit textUnit = new TMTextUnit();
    private final ReviewProject reviewProject = new ReviewProject();
    private final ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    private final TMTextUnitVariant previousVariant = new TMTextUnitVariant();
    private final TMTextUnitCurrentVariant current = new TMTextUnitCurrentVariant();
    private final User operator = new User();
    private final TMTextUnitVariant writtenVariant = new TMTextUnitVariant();
    private final TMTextUnitCurrentVariant rereadCurrent = new TMTextUnitCurrentVariant();

    private final GuardedTranslationCorrectionTransactionService service;

    private Fixture() {
      repository.setId(1L);
      repository.setName("repo");
      sourceLocale.setId(12L);
      sourceLocale.setBcp47Tag("en");
      repository.setSourceLocale(sourceLocale);
      locale.setId(7L);
      locale.setBcp47Tag("fr-FR");
      tm.setId(4L);
      asset.setId(5L);
      asset.setRepository(repository);
      textUnit.setId(6L);
      textUnit.setTm(tm);
      textUnit.setAsset(asset);
      reviewProject.setId(2L);
      reviewProject.setType(ReviewProjectType.NORMAL);
      reviewProject.setLocale(locale);
      reviewProjectTextUnit.setId(3L);
      reviewProjectTextUnit.setReviewProject(reviewProject);
      reviewProjectTextUnit.setTmTextUnit(textUnit);
      previousVariant.setId(10L);
      previousVariant.setContent("ancien");
      previousVariant.setComment("existing comment");
      previousVariant.setStatus(TMTextUnitVariant.Status.APPROVED);
      previousVariant.setIncludedInLocalizedFile(false);
      current.setId(8L);
      current.setAsset(asset);
      current.setLocale(locale);
      current.setTmTextUnit(textUnit);
      current.setTmTextUnitVariant(previousVariant);
      operator.setId(9L);
      operator.setEnabled(true);
      writtenVariant.setId(11L);
      writtenVariant.setContent("caf\u00e9");
      writtenVariant.setComment("existing comment");
      writtenVariant.setStatus(TMTextUnitVariant.Status.REVIEW_NEEDED);
      writtenVariant.setIncludedInLocalizedFile(false);
      rereadCurrent.setId(8L);
      rereadCurrent.setAsset(asset);
      rereadCurrent.setLocale(locale);
      rereadCurrent.setTmTextUnit(textUnit);
      rereadCurrent.setTmTextUnitVariant(writtenVariant);

      when(reviewProjectTextUnitRepository.findById(3L))
          .thenReturn(Optional.of(reviewProjectTextUnit));
      when(reviewProjectTextUnitRepository.findGuardedCorrectionIdentityForUpdateById(3L))
          .thenReturn(Optional.of(reviewProjectTextUnit));
      when(currentVariantRepository.findForUpdateByLocaleIdAndTmTextUnitId(7L, 6L))
          .thenReturn(current, current, rereadCurrent);
      when(userRepository.findById(9L)).thenReturn(Optional.of(operator));
      when(tmService.addTMTextUnitCurrentVariantWithResult(
              eq(current),
              eq(4L),
              eq(5L),
              eq(6L),
              eq(7L),
              eq("caf\u00e9"),
              eq("existing comment"),
              eq(TMTextUnitVariant.Status.REVIEW_NEEDED),
              eq(false),
              isNull(),
              eq(operator)))
          .thenReturn(new AddTMTextUnitCurrentVariantResult(true, rereadCurrent));

      service =
          new GuardedTranslationCorrectionTransactionService(
              reviewProjectTextUnitRepository,
              currentVariantRepository,
              userRepository,
              tmService,
              integrityCheckService,
              entityManager);
    }

    private Correction correction(String expectedOldTarget, String replacementTarget) {
      return new Correction(
          reviewProject.getId(),
          reviewProjectTextUnit.getId(),
          repository.getId(),
          repository.getName(),
          locale.getBcp47Tag(),
          textUnit.getId(),
          previousVariant.getId(),
          expectedOldTarget,
          replacementTarget);
    }
  }
}
