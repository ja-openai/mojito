package com.box.l10n.mojito.service.translation;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Correction;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.ItemResult;
import com.box.l10n.mojito.service.translation.GuardedTranslationCorrectionService.Outcome;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class GuardedTranslationCorrectionTransactionServiceDbTest extends ServiceTestBase {

  private static final String OLD_TARGET = "ancien";
  private static final String CORRECTION_TARGET = "corrig\u00e9";
  private static final String SOURCE_CORRECTION_TARGET = "Changed source";
  private static final String NEWER_TRANSLATOR_TARGET = "nouvelle traduction";

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired private RepositoryService repositoryService;
  @Autowired private RepositoryRepository repositoryRepository;
  @Autowired private AssetService assetService;
  @Autowired private TMService tmService;
  @Autowired private LocaleService localeService;
  @Autowired private ReviewProjectRepository reviewProjectRepository;
  @Autowired private ReviewProjectTextUnitRepository reviewProjectTextUnitRepository;
  @Autowired private TMTextUnitCurrentVariantRepository currentVariantRepository;
  @Autowired private TMTextUnitVariantRepository variantRepository;
  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private GuardedTranslationCorrectionTransactionService transactionService;

  @Test
  public void appliesAndVerifiesTheDurablyStoredCurrentVariant() throws Exception {
    Fixture fixture = createFixture();

    ItemResult result = transactionService.apply(0, fixture.correction(), fixture.operatorUserId());

    assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
    assertThat(result.stored().target()).isEqualTo(CORRECTION_TARGET);
    assertThat(result.stored().status()).isEqualTo("REVIEW_NEEDED");
    assertThat(result.verification().verified()).isTrue();

    TMTextUnitCurrentVariant stored =
        currentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            fixture.localeId(), fixture.tmTextUnitId());
    assertThat(stored.getId()).isEqualTo(result.stored().currentVariantRowId());
    assertThat(stored.getTmTextUnitVariant().getId()).isEqualTo(result.stored().variantId());
    assertThat(stored.getTmTextUnitVariant().getContent()).isEqualTo(CORRECTION_TARGET);
    assertThat(stored.getTmTextUnitVariant().getStatus())
        .isEqualTo(TMTextUnitVariant.Status.REVIEW_NEEDED);
  }

  @Test
  public void fullyMatchingSourceLocaleRequestConflictsWithoutChangingSourceVariant()
      throws Exception {
    Fixture fixture = createSourceLocaleFixture();

    ItemResult result = transactionService.apply(0, fixture.correction(), fixture.operatorUserId());

    assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
    assertThat(result.code()).isEqualTo("SOURCE_LOCALE_CORRECTION_FORBIDDEN");
    assertThat(result.stored()).isNull();

    TMTextUnitCurrentVariant stored =
        currentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            fixture.localeId(), fixture.tmTextUnitId());
    assertThat(stored.getTmTextUnitVariant().getId())
        .isEqualTo(fixture.correction().expectedCurrentVariantId());
    assertThat(stored.getTmTextUnitVariant().getContent())
        .isEqualTo(fixture.correction().expectedOldTarget());
    assertThat(
            variantRepository.findAllByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(
                fixture.tmTextUnitId(), fixture.localeId()))
        .extracting(TMTextUnitVariant::getContent)
        .doesNotContain(SOURCE_CORRECTION_TARGET);
  }

  @Test
  public void fullyMatchingTerminologyProjectRequestsConflictWithoutChangingTargetVariant()
      throws Exception {
    for (ReviewProjectType type :
        List.of(ReviewProjectType.TERMINOLOGY, ReviewProjectType.TERM_CANDIDATE)) {
      Fixture fixture = createTerminologyFixture(type);

      ItemResult result =
          transactionService.apply(0, fixture.correction(), fixture.operatorUserId());

      assertThat(result.outcome()).as(type.name()).isEqualTo(Outcome.CONFLICT);
      assertThat(result.code()).isEqualTo("UNSUPPORTED_REVIEW_PROJECT_TYPE");
      assertThat(result.stored()).isNull();

      TMTextUnitCurrentVariant stored =
          currentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
              fixture.localeId(), fixture.tmTextUnitId());
      assertThat(stored.getTmTextUnitVariant().getId())
          .isEqualTo(fixture.correction().expectedCurrentVariantId());
      assertThat(stored.getTmTextUnitVariant().getContent()).isEqualTo(OLD_TARGET);
      assertThat(
              variantRepository.findAllByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(
                  fixture.tmTextUnitId(), fixture.localeId()))
          .extracting(TMTextUnitVariant::getContent)
          .doesNotContain(CORRECTION_TARGET);
    }
  }

  @Test
  public void waitsForConcurrentWriterThenConflictsWithoutOverwritingItsTranslation()
      throws Exception {
    Fixture fixture = createFixture();
    CountDownLatch writerLockHeld = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    CountDownLatch correctionStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<Long> writerFuture = null;
    Future<ItemResult> correctionFuture = null;

    try {
      writerFuture =
          executor.submit(
              () ->
                  requiresNewTransaction()
                      .execute(
                          ignored -> {
                            TMTextUnitCurrentVariant locked =
                                currentVariantRepository.findForUpdateByLocaleIdAndTmTextUnitId(
                                    fixture.localeId(), fixture.tmTextUnitId());
                            writerLockHeld.countDown();
                            await(releaseWriter);

                            User operator =
                                userRepository.findById(fixture.operatorUserId()).orElseThrow();
                            TMTextUnitVariant previous = locked.getTmTextUnitVariant();
                            AddTMTextUnitCurrentVariantResult writeResult =
                                tmService.addTMTextUnitCurrentVariantWithResult(
                                    locked,
                                    fixture.tmId(),
                                    fixture.assetId(),
                                    fixture.tmTextUnitId(),
                                    fixture.localeId(),
                                    NEWER_TRANSLATOR_TARGET,
                                    previous.getComment(),
                                    TMTextUnitVariant.Status.REVIEW_NEEDED,
                                    previous.isIncludedInLocalizedFile(),
                                    null,
                                    operator);
                            return writeResult
                                .getTmTextUnitCurrentVariant()
                                .getTmTextUnitVariant()
                                .getId();
                          }));

      assertThat(writerLockHeld.await(5, SECONDS)).isTrue();
      correctionFuture =
          executor.submit(
              () -> {
                correctionStarted.countDown();
                return transactionService.apply(0, fixture.correction(), fixture.operatorUserId());
              });
      assertThat(correctionStarted.await(5, SECONDS)).isTrue();

      Future<ItemResult> blockedCorrection = correctionFuture;
      assertThatThrownBy(() -> blockedCorrection.get(1, SECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseWriter.countDown();
      Long writerVariantId = writerFuture.get(5, SECONDS);
      ItemResult result = correctionFuture.get(5, SECONDS);

      assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
      assertThat(result.code()).isEqualTo("CURRENT_VARIANT_ID_MISMATCH");
      assertThat(result.stored()).isNull();
      assertThat(result.verification().readAfterWrite()).isFalse();
      assertThat(result.verification().verified()).isFalse();

      TMTextUnitCurrentVariant stored =
          currentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
              fixture.localeId(), fixture.tmTextUnitId());
      assertThat(stored.getTmTextUnitVariant().getId()).isEqualTo(writerVariantId);
      assertThat(stored.getTmTextUnitVariant().getContent()).isEqualTo(NEWER_TRANSLATOR_TARGET);
      assertThat(
              variantRepository.findAllByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(
                  fixture.tmTextUnitId(), fixture.localeId()))
          .extracting(TMTextUnitVariant::getContent)
          .doesNotContain(CORRECTION_TARGET);
    } finally {
      releaseWriter.countDown();
      if (writerFuture != null) {
        writerFuture.cancel(true);
      }
      if (correctionFuture != null) {
        correctionFuture.cancel(true);
      }
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
    }
  }

  @Test
  public void waitsForConcurrentRepositoryRenameThenConflictsWithoutWriting() throws Exception {
    Fixture fixture = createFixture();
    CountDownLatch renameFlushed = new CountDownLatch(1);
    CountDownLatch releaseRename = new CountDownLatch(1);
    CountDownLatch correctionStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<String> renameFuture = null;
    Future<ItemResult> correctionFuture = null;

    try {
      renameFuture =
          executor.submit(
              () ->
                  requiresNewTransaction()
                      .execute(
                          ignored -> {
                            Repository repository =
                                repositoryRepository.findById(fixture.repositoryId()).orElseThrow();
                            String renamed = repository.getName() + "-renamed";
                            repository.setName(renamed);
                            repositoryRepository.saveAndFlush(repository);
                            renameFlushed.countDown();
                            await(releaseRename);
                            return renamed;
                          }));

      assertThat(renameFlushed.await(5, SECONDS)).isTrue();
      correctionFuture =
          executor.submit(
              () -> {
                correctionStarted.countDown();
                return transactionService.apply(0, fixture.correction(), fixture.operatorUserId());
              });
      assertThat(correctionStarted.await(5, SECONDS)).isTrue();

      Future<ItemResult> blockedCorrection = correctionFuture;
      assertThatThrownBy(() -> blockedCorrection.get(1, SECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseRename.countDown();
      String renamed = renameFuture.get(5, SECONDS);
      ItemResult result = correctionFuture.get(5, SECONDS);

      assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT);
      assertThat(result.code()).isEqualTo("REPOSITORY_NAME_MISMATCH");
      assertThat(repositoryRepository.findById(fixture.repositoryId()).orElseThrow().getName())
          .isEqualTo(renamed);

      TMTextUnitCurrentVariant stored =
          currentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
              fixture.localeId(), fixture.tmTextUnitId());
      assertThat(stored.getTmTextUnitVariant().getContent()).isEqualTo(OLD_TARGET);
      assertThat(
              variantRepository.findAllByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(
                  fixture.tmTextUnitId(), fixture.localeId()))
          .extracting(TMTextUnitVariant::getContent)
          .doesNotContain(CORRECTION_TARGET);
    } finally {
      releaseRename.countDown();
      if (renameFuture != null) {
        renameFuture.cancel(true);
      }
      if (correctionFuture != null) {
        correctionFuture.cancel(true);
      }
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
    }
  }

  private Fixture createFixture() throws Exception {
    return createFixture(false, ReviewProjectType.NORMAL, "repository");
  }

  private Fixture createSourceLocaleFixture() throws Exception {
    return createFixture(true, ReviewProjectType.NORMAL, "repository");
  }

  private Fixture createTerminologyFixture(ReviewProjectType type) throws Exception {
    return createFixture(false, type, "repository-" + type.name());
  }

  private Fixture createFixture(
      boolean useSourceLocale, ReviewProjectType projectType, String repositoryName)
      throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName(repositoryName));
    Asset asset =
        assetService.createAssetWithContent(repository.getId(), "path/to/messages.json", "{}");
    TMTextUnit textUnit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), asset.getId(), "greeting", "Hello", null);
    Locale locale =
        useSourceLocale ? repository.getSourceLocale() : localeService.findByBcp47Tag("fr-FR");
    TMTextUnitVariant oldVariant;
    String expectedOldTarget;
    String replacementTarget;
    if (useSourceLocale) {
      oldVariant =
          currentVariantRepository
              .findByLocale_IdAndTmTextUnit_Id(locale.getId(), textUnit.getId())
              .getTmTextUnitVariant();
      expectedOldTarget = textUnit.getContent();
      replacementTarget = SOURCE_CORRECTION_TARGET;
    } else {
      oldVariant =
          tmService.addCurrentTMTextUnitVariant(
              textUnit.getId(),
              locale.getId(),
              OLD_TARGET,
              TMTextUnitVariant.Status.APPROVED,
              true);
      expectedOldTarget = OLD_TARGET;
      replacementTarget = CORRECTION_TARGET;
    }

    ReviewProject reviewProject = new ReviewProject();
    reviewProject.setType(projectType);
    reviewProject.setLocale(locale);
    reviewProject.setDueDate(ZonedDateTime.now().plusDays(1));
    reviewProject = reviewProjectRepository.saveAndFlush(reviewProject);

    ReviewProjectTextUnit reviewProjectTextUnit = new ReviewProjectTextUnit();
    reviewProjectTextUnit.setReviewProject(reviewProject);
    reviewProjectTextUnit.setTmTextUnit(textUnit);
    reviewProjectTextUnit.setTmTextUnitVariant(oldVariant);
    reviewProjectTextUnit = reviewProjectTextUnitRepository.saveAndFlush(reviewProjectTextUnit);

    Long operatorUserId = userService.getCurrentUser().orElseThrow().getId();
    Correction correction =
        new Correction(
            reviewProject.getId(),
            reviewProjectTextUnit.getId(),
            repository.getId(),
            repository.getName(),
            locale.getBcp47Tag(),
            textUnit.getId(),
            oldVariant.getId(),
            expectedOldTarget,
            replacementTarget);
    return new Fixture(
        correction,
        operatorUserId,
        repository.getId(),
        repository.getTm().getId(),
        asset.getId(),
        textUnit.getId(),
        locale.getId());
  }

  private TransactionTemplate requiresNewTransaction() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, SECONDS)) {
        throw new AssertionError("Timed out waiting to release concurrent writer");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting to release concurrent writer", exception);
    }
  }

  private record Fixture(
      Correction correction,
      Long operatorUserId,
      Long repositoryId,
      Long tmId,
      Long assetId,
      Long tmTextUnitId,
      Long localeId) {}
}
