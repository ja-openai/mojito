package com.box.l10n.mojito.service.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.RepositoryLocaleStatistic;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.statistics.RepositoryLocaleStatisticRepository;
import com.box.l10n.mojito.test.TestIdWatcher;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

public class RepositoryTargetLocalesPersistenceTest extends ServiceTestBase {

  @Autowired RepositoryService repositoryService;
  @Autowired RepositoryTargetLocalesService targetLocalesService;
  @Autowired RepositoryRepository repositories;
  @Autowired RepositoryLocaleRepository repositoryLocales;
  @Autowired RepositoryLocaleStatisticRepository statistics;
  @Autowired RepositoryGenerationSettingsRepository generationSettings;
  @Autowired LocaleService locales;
  @Autowired EntityManager entityManager;

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Test
  @Transactional
  public void targetChangesPreserveRetainedRowsAndMetadataAcrossFlushAndReload() throws Exception {
    String name = testIdWatcher.getEntityName("repository");
    Repository repository =
        repositoryService.createRepository(
            name, "Existing metadata", locales.findByBcp47Tag("en-GB"), true);
    Long repositoryId = repository.getId();
    Long tmId = repository.getTm().getId();
    Long rootId = locale(repository, "en-GB").getId();
    RepositoryLocale french = repositoryService.addRepositoryLocale(repository, "fr-FR");
    RepositoryLocale child =
        repositoryService.addRepositoryLocale(repository, "fr-CA", "fr-FR", false);
    RepositoryLocale japanese = repositoryService.addRepositoryLocale(repository, "ja-JP");
    Long frenchId = french.getId();
    Long childId = child.getId();
    Long japaneseId = japanese.getId();
    Long frenchStatisticId = addStatistic(repository, french, 11L).getId();
    Long childStatisticId = addStatistic(repository, child, 12L).getId();
    Long japaneseStatisticId = addStatistic(repository, japanese, 13L).getId();

    AssetIntegrityChecker checker = new AssetIntegrityChecker();
    checker.setRepository(repository);
    checker.setAssetExtension("json");
    checker.setIntegrityCheckerType(IntegrityCheckerType.MESSAGE_FORMAT);
    entityManager.persist(checker);
    repository.getAssetIntegrityCheckers().add(checker);
    Long checkerId = checker.getId();
    RepositoryGenerationSettings exclusions = new RepositoryGenerationSettings();
    exclusions.setRepositoryId(repositoryId);
    exclusions.setExcludedLocaleTags(List.of("ja-JP"));
    generationSettings.save(exclusions);

    Repository other = repositoryService.createRepository(testIdWatcher.getEntityName("other"));
    Long otherId = other.getId();
    Long otherLocaleId = repositoryService.addRepositoryLocale(other, "de-DE").getId();
    flushAndClear();

    targetLocalesService.updateTargetLocales(repositoryId, List.of("fr-FR", "fr-CA", "de-DE"));
    flushAndClear();
    repository = repositories.findById(repositoryId).orElseThrow();
    assertThat(locale(repository, "en-GB").getId()).isEqualTo(rootId);
    assertThat(locale(repository, "fr-FR").getId()).isEqualTo(frenchId);
    assertThat(locale(repository, "fr-CA").getId()).isEqualTo(childId);
    assertThat(locale(repository, "fr-CA").isToBeFullyTranslated()).isFalse();
    assertThat(locale(repository, "fr-CA").getParentLocale().getId()).isEqualTo(frenchId);
    assertThat(locale(repository, "de-DE").isToBeFullyTranslated()).isTrue();
    assertThat(locale(repository, "de-DE").getParentLocale().getId()).isEqualTo(rootId);
    assertThat(repositoryLocales.findById(japaneseId)).isEmpty();
    assertThat(statistics.findById(japaneseStatisticId)).isEmpty();
    assertThat(statistics.findById(frenchStatisticId).orElseThrow().getTranslatedCount())
        .isEqualTo(11L);
    assertThat(statistics.findById(childStatisticId)).isPresent();

    // Remove a child and its parent together with the real self-referencing foreign key active.
    targetLocalesService.updateTargetLocales(repositoryId, List.of("de-DE"));
    flushAndClear();
    assertThat(repositoryLocales.findById(childId)).isEmpty();
    assertThat(repositoryLocales.findById(frenchId)).isEmpty();
    assertThat(statistics.findById(childStatisticId)).isEmpty();
    assertThat(statistics.findById(frenchStatisticId)).isEmpty();

    targetLocalesService.updateTargetLocales(repositoryId, List.of());
    flushAndClear();
    repository = repositories.findById(repositoryId).orElseThrow();
    assertThat(repository.getRepositoryLocales())
        .extracting(RepositoryLocale::getId)
        .containsExactly(rootId);
    assertThat(locale(repository, "en-GB").getParentLocale()).isNull();
    assertThat(locale(repository, "en-GB").isToBeFullyTranslated()).isFalse();
    assertThat(targetLocalesService.getTargetLocales(repositoryId).targetLocaleTags()).isEmpty();
    assertThat(repository.getRepositoryStatistic().getRepositoryLocaleStatistics()).isEmpty();
    assertThat(repository.getName()).isEqualTo(name);
    assertThat(repository.getDescription()).isEqualTo("Existing metadata");
    assertThat(repository.getCheckSLA()).isTrue();
    assertThat(repository.getSourceLocale().getBcp47Tag()).isEqualTo("en-GB");
    assertThat(repository.getTm().getId()).isEqualTo(tmId);
    assertThat(repository.getAssetIntegrityCheckers())
        .extracting(AssetIntegrityChecker::getId)
        .containsExactly(checkerId);
    assertThat(generationSettings.findById(repositoryId).orElseThrow().getExcludedLocaleTags())
        .containsExactly("ja-JP");
    other = repositories.findById(otherId).orElseThrow();
    assertThat(other.getRepositoryLocales()).hasSize(2);
    assertThat(locale(other, "de-DE").getId()).isEqualTo(otherLocaleId);
  }

  private RepositoryLocaleStatistic addStatistic(
      Repository repository, RepositoryLocale locale, Long count) {
    RepositoryLocaleStatistic statistic = new RepositoryLocaleStatistic();
    statistic.setRepositoryStatistic(repository.getRepositoryStatistic());
    statistic.setLocale(locale.getLocale());
    statistic.setTranslatedCount(count);
    statistics.save(statistic);
    repository.getRepositoryStatistic().getRepositoryLocaleStatistics().add(statistic);
    return statistic;
  }

  private RepositoryLocale locale(Repository repository, String tag) {
    return repository.getRepositoryLocales().stream()
        .filter(locale -> locale.getLocale().getBcp47Tag().equals(tag))
        .findFirst()
        .orElseThrow();
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }
}
