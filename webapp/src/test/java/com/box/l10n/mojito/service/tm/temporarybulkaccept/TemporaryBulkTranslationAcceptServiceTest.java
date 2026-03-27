package com.box.l10n.mojito.service.tm.temporarybulkaccept;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.rest.WSTestDataFactory;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class TemporaryBulkTranslationAcceptServiceTest extends ServiceTestBase {

  @Autowired TemporaryBulkTranslationAcceptService service;

  @Autowired WSTestDataFactory wsTestDataFactory;

  @Autowired TMTextUnitRepository tmTextUnitRepository;

  @Autowired TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;

  @Autowired TMTextUnitVariantCommentService tmTextUnitVariantCommentService;

  @Autowired TMTextUnitVariantCommentRepository tmTextUnitVariantCommentRepository;

  @Autowired TMService tmService;

  @Autowired LocaleService localeService;

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Test
  public void phraseImportedNeedsReviewDryRunAndExecuteCopiesComments() throws Exception {
    Repository repository = wsTestDataFactory.createRepoAndAssetAndTextUnits(testIdWatcher);
    Locale frFrLocale = localeService.findByBcp47Tag("fr-FR");
    TMTextUnit tmTextUnit = tmTextUnitRepository.findByTm_id(repository.getTm().getId()).get(0);

    TMTextUnitCurrentVariant currentVariant =
        tmService.addTMTextUnitCurrentVariant(
            tmTextUnit.getId(),
            frFrLocale.getId(),
            "Phrase imported review variant",
            null,
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            true,
            ZonedDateTime.now().minusDays(1));

    Long sourceVariantId = currentVariant.getTmTextUnitVariant().getId();
    tmTextUnitVariantCommentService.addComment(
        sourceVariantId,
        TMTextUnitVariantComment.Type.THIRD_PARTY_TMS_PULL,
        TMTextUnitVariantComment.Severity.INFO,
        "Import from Phrase Strings");

    TemporaryBulkTranslationAcceptService.Request request =
        new TemporaryBulkTranslationAcceptService.Request(
            TemporaryBulkTranslationAcceptService.Selector.PHRASE_IMPORTED_NEEDS_REVIEW,
            List.of(repository.getId()),
            null);

    TemporaryBulkTranslationAcceptService.DryRunResult dryRun = service.dryRun(request);
    Assert.assertEquals(1L, dryRun.totalMatchedCount());

    TemporaryBulkTranslationAcceptService.ExecuteResult execute = service.execute(request);
    Assert.assertEquals(1L, execute.processedCount());

    TMTextUnitCurrentVariant updatedCurrentVariant =
        tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            frFrLocale.getId(), tmTextUnit.getId());
    Assert.assertNotNull(updatedCurrentVariant);
    Assert.assertNotEquals(sourceVariantId, updatedCurrentVariant.getTmTextUnitVariant().getId());
    Assert.assertEquals(
        TMTextUnitVariant.Status.APPROVED,
        updatedCurrentVariant.getTmTextUnitVariant().getStatus());

    List<TMTextUnitVariantComment> currentVariantComments =
        tmTextUnitVariantCommentRepository.findAllByTmTextUnitVariant_id(
            updatedCurrentVariant.getTmTextUnitVariant().getId());
    Assert.assertEquals(1, currentVariantComments.size());
    Assert.assertEquals(
        TMTextUnitVariantComment.Type.THIRD_PARTY_TMS_PULL,
        currentVariantComments.get(0).getType());
  }

  @Test
  public void phraseImportedNeedsReviewDryRunAsyncReturnsTaskResponse() throws Exception {
    Repository repository = wsTestDataFactory.createRepoAndAssetAndTextUnits(testIdWatcher);
    Locale frFrLocale = localeService.findByBcp47Tag("fr-FR");
    TMTextUnit tmTextUnit = tmTextUnitRepository.findByTm_id(repository.getTm().getId()).get(0);

    TMTextUnitCurrentVariant currentVariant =
        tmService.addTMTextUnitCurrentVariant(
            tmTextUnit.getId(),
            frFrLocale.getId(),
            "Phrase imported async review variant",
            null,
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            true,
            ZonedDateTime.now().minusDays(1));

    tmTextUnitVariantCommentService.addComment(
        currentVariant.getTmTextUnitVariant().getId(),
        TMTextUnitVariantComment.Type.THIRD_PARTY_TMS_PULL,
        TMTextUnitVariantComment.Severity.INFO,
        "Import from Phrase Strings");

    TemporaryBulkTranslationAcceptService.Request request =
        new TemporaryBulkTranslationAcceptService.Request(
            TemporaryBulkTranslationAcceptService.Selector.PHRASE_IMPORTED_NEEDS_REVIEW,
            List.of(repository.getId()),
            null);

    PollableFuture<TemporaryBulkTranslationAcceptService.TaskResponse> pollableFuture =
        service.dryRunAsync(request);

    Assert.assertNotNull(pollableFuture.getPollableTask());
    TemporaryBulkTranslationAcceptService.TaskResponse result = pollableFuture.get();
    Assert.assertEquals(1L, result.totalCount());
    Assert.assertEquals(1, result.repositoryCounts().size());
  }

  @Test
  public void needsReviewOlderThanOnlyProcessesOlderVariants() throws Exception {
    Repository repository = wsTestDataFactory.createRepoAndAssetAndTextUnits(testIdWatcher);
    Locale frFrLocale = localeService.findByBcp47Tag("fr-FR");
    List<TMTextUnit> tmTextUnits = tmTextUnitRepository.findByTm_id(repository.getTm().getId());

    TMTextUnitCurrentVariant oldCurrentVariant =
        tmService.addTMTextUnitCurrentVariant(
            tmTextUnits.get(0).getId(),
            frFrLocale.getId(),
            "Old review variant",
            null,
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            true,
            ZonedDateTime.now().minusDays(30));
    TMTextUnitCurrentVariant recentCurrentVariant =
        tmService.addTMTextUnitCurrentVariant(
            tmTextUnits.get(1).getId(),
            frFrLocale.getId(),
            "Recent review variant",
            null,
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            true,
            ZonedDateTime.now().minusDays(1));

    TemporaryBulkTranslationAcceptService.Request request =
        new TemporaryBulkTranslationAcceptService.Request(
            TemporaryBulkTranslationAcceptService.Selector.NEEDS_REVIEW_OLDER_THAN,
            List.of(repository.getId()),
            LocalDate.now().minusDays(14));

    TemporaryBulkTranslationAcceptService.DryRunResult dryRun = service.dryRun(request);
    Assert.assertEquals(1L, dryRun.totalMatchedCount());

    TemporaryBulkTranslationAcceptService.ExecuteResult execute = service.execute(request);
    Assert.assertEquals(1L, execute.processedCount());

    TMTextUnitCurrentVariant updatedOldCurrentVariant =
        tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            frFrLocale.getId(), tmTextUnits.get(0).getId());
    TMTextUnitCurrentVariant updatedRecentCurrentVariant =
        tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            frFrLocale.getId(), tmTextUnits.get(1).getId());

    Assert.assertEquals(
        TMTextUnitVariant.Status.APPROVED,
        updatedOldCurrentVariant.getTmTextUnitVariant().getStatus());
    Assert.assertNotEquals(
        oldCurrentVariant.getTmTextUnitVariant().getId(),
        updatedOldCurrentVariant.getTmTextUnitVariant().getId());
    Assert.assertEquals(
        TMTextUnitVariant.Status.REVIEW_NEEDED,
        updatedRecentCurrentVariant.getTmTextUnitVariant().getStatus());
    Assert.assertEquals(
        recentCurrentVariant.getTmTextUnitVariant().getId(),
        updatedRecentCurrentVariant.getTmTextUnitVariant().getId());
  }
}
