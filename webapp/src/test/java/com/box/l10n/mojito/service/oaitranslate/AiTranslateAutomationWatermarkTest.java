package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTestData;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantService;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

public class AiTranslateAutomationWatermarkTest extends ServiceTestBase {

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired AiTranslateRunRepository aiTranslateRunRepository;

  @Autowired AiTranslateRunService aiTranslateRunService;

  @Autowired TMService tmService;

  @Autowired TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;

  @Autowired TMTextUnitCurrentVariantService tmTextUnitCurrentVariantService;

  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  public void schedulesRepositoriesWithoutPreviousCompletedRuns() {
    TMTestData data = new TMTestData(testIdWatcher);

    assertThat(data.repository.getRepositoryStatistic().getRepositoryLocaleStatistics()).isEmpty();
    assertThat(aiTranslateRunService.getLatestCompletedRunStarts(List.of(data.repository.getId())))
        .isEmpty();
    assertThat(hasChangesSinceLastCompletedRun(data)).isTrue();
  }

  @Test
  public void skipsRepositoriesWithoutChangesSinceTheirLastCompletedRun() {
    TMTestData data = new TMTestData(testIdWatcher);
    recordCompletedRunAfterExistingCurrentVariants(data);

    assertThat(hasChangesSinceLastCompletedRun(data)).isFalse();
  }

  @Test
  public void detectsDeletedCurrentTranslationsSinceTheLastCompletedRun() {
    TMTestData data = new TMTestData(testIdWatcher);
    recordCompletedRunAfterExistingCurrentVariants(data);
    TMTextUnitCurrentVariant currentVariant =
        tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            data.frFR.getId(), data.addTMTextUnit1.getId());

    tmTextUnitCurrentVariantService.removeCurrentVariant(currentVariant.getId());

    assertThat(hasChangesSinceLastCompletedRun(data)).isTrue();
  }

  @Test
  public void detectsTargetVariantUpdatesSinceTheLastCompletedRun() {
    TMTestData data = new TMTestData(testIdWatcher);
    recordCompletedRunAfterExistingCurrentVariants(data);
    tmService.addCurrentTMTextUnitVariant(
        data.addTMTextUnit1.getId(), data.frFR.getId(), "Updated translation");

    assertThat(hasChangesSinceLastCompletedRun(data)).isTrue();
  }

  @Test
  public void detectsSourceVariantUpdatesSinceTheLastCompletedRun() {
    TMTestData data = new TMTestData(testIdWatcher);
    recordCompletedRunAfterExistingCurrentVariants(data);
    tmService.addTMTextUnit(
        data.tm.getId(), data.asset.getId(), "new-source-string", "New source string", null);

    assertThat(hasChangesSinceLastCompletedRun(data)).isTrue();
  }

  private boolean hasChangesSinceLastCompletedRun(TMTestData data) {
    ZonedDateTime lastCompletedRunStart =
        aiTranslateRunService
            .getLatestCompletedRunStarts(List.of(data.repository.getId()))
            .get(data.repository.getId());
    if (lastCompletedRunStart == null) {
      return true;
    }

    List<Long> localeIds =
        data.repository.getRepositoryLocales().stream()
            .map(repositoryLocale -> repositoryLocale.getLocale().getId())
            .toList();
    return tmTextUnitCurrentVariantRepository
        .findFirstChangeSince(data.tm.getId(), localeIds, lastCompletedRunStart)
        .isPresent();
  }

  private void recordCompletedRunAfterExistingCurrentVariants(TMTestData data) {
    ZonedDateTime startedAt = ZonedDateTime.now().minusSeconds(1);
    jdbcTemplate.update(
        "update tm_text_unit_current_variant set last_modified_date = ? where tm_id = ?",
        Timestamp.from(startedAt.minusSeconds(1).toInstant()),
        data.tm.getId());

    AiTranslateRun completedRun = new AiTranslateRun();
    completedRun.setTriggerSource(AiTranslateRun.TriggerSource.CRON);
    completedRun.setRepository(data.repository);
    completedRun.setModel("test-model");
    completedRun.setTranslateType("TRANSLATE");
    completedRun.setRelatedStringsType("NONE");
    completedRun.setSourceTextMaxCountPerLocale(10);
    completedRun.setStatus(AiTranslateRun.Status.COMPLETED);
    completedRun.setStartedAt(startedAt);
    completedRun.setFinishedAt(startedAt.plusNanos(1));
    aiTranslateRunRepository.saveAndFlush(completedRun);
  }
}
