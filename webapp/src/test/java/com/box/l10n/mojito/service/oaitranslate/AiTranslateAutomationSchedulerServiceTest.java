package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TM;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.AiTranslateInput;
import com.box.l10n.mojito.service.pollableTask.PollableFutureTaskResult;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class AiTranslateAutomationSchedulerServiceTest {

  private final AiTranslateAutomationConfigService aiTranslateAutomationConfigService =
      Mockito.mock(AiTranslateAutomationConfigService.class);
  private final AiTranslateConfigurationProperties aiTranslateConfigurationProperties =
      Mockito.mock(AiTranslateConfigurationProperties.class);
  private final AiTranslateService aiTranslateService = Mockito.mock(AiTranslateService.class);
  private final AiTranslateRunService aiTranslateRunService =
      Mockito.mock(AiTranslateRunService.class);
  private final RepositoryRepository repositoryRepository =
      Mockito.mock(RepositoryRepository.class);
  private final TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository =
      Mockito.mock(TMTextUnitCurrentVariantRepository.class);

  private AiTranslateAutomationSchedulerService aiTranslateAutomationSchedulerService;

  @Before
  public void setUp() {
    aiTranslateAutomationSchedulerService =
        new AiTranslateAutomationSchedulerService(
            aiTranslateAutomationConfigService,
            aiTranslateConfigurationProperties,
            aiTranslateService,
            aiTranslateRunService,
            repositoryRepository,
            tmTextUnitCurrentVariantRepository,
            new SimpleMeterRegistry());
    when(aiTranslateConfigurationProperties.getModelName()).thenReturn("gpt-test");
    when(aiTranslateService.aiTranslateAsync(any(), anyString())).thenReturn(pollableFuture());
    when(aiTranslateRunService.getLatestCompletedRunStarts(anyCollection())).thenReturn(Map.of());
  }

  @Test
  public void schedulesAllEligibleRepositoriesWhenNoRepositoriesAreIncludedOrExcluded() {
    when(aiTranslateAutomationConfigService.getConfig()).thenReturn(config(List.of(), List.of()));
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(List.of(repository(1L, "repo-a"), repository(2L, "repo-b")));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("cron", true);

    assertEquals(2, result.scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    ArgumentCaptor<String> uniqueIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(aiTranslateService, times(2))
        .aiTranslateAsync(inputCaptor.capture(), uniqueIdCaptor.capture());
    assertEquals(
        List.of("repo-a", "repo-b"),
        inputCaptor.getAllValues().stream().map(AiTranslateInput::repositoryName).toList());
    assertEquals(
        List.of("auto-ai-translate-repository-1", "auto-ai-translate-repository-2"),
        uniqueIdCaptor.getAllValues());
    inputCaptor.getAllValues().forEach(input -> assertNull(input.targetBcp47tags()));
    verify(repositoryRepository, never()).findNoGraphById(anyLong());
  }

  @Test
  public void skipsExcludedRepositoriesFromEligibleRepositorySet() {
    when(aiTranslateAutomationConfigService.getConfig())
        .thenReturn(config(List.of(), List.of(2L), Map.of(2L, List.of("fr"))));
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(
            List.of(repository(1L, "repo-a"), repository(2L, "repo-b"), repository(3L, "repo-c")));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("manual", false, 5L);

    assertEquals(2, result.scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    verify(aiTranslateService, times(2)).aiTranslateAsync(inputCaptor.capture(), anyString());
    assertEquals(
        List.of("repo-a", "repo-c"),
        inputCaptor.getAllValues().stream().map(AiTranslateInput::repositoryName).toList());
  }

  @Test
  public void schedulesIncludedRepositoriesWithoutApplyingExclusions() {
    when(aiTranslateAutomationConfigService.getConfig())
        .thenReturn(config(List.of(2L, 3L), List.of(2L)));
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(
            List.of(repository(1L, "repo-a"), repository(2L, "repo-b"), repository(3L, "repo-c")));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("manual", false, 5L);

    assertEquals(2, result.scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    verify(aiTranslateService, times(2)).aiTranslateAsync(inputCaptor.capture(), anyString());
    assertEquals(
        List.of("repo-b", "repo-c"),
        inputCaptor.getAllValues().stream().map(AiTranslateInput::repositoryName).toList());
  }

  @Test
  public void skipsUnchangedRepositoriesBeforeCreatingPollableTasks() {
    when(aiTranslateAutomationConfigService.getConfig()).thenReturn(config(List.of(), List.of()));
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(List.of(repository(1L, "repo-a"), repository(2L, "repo-b")));
    ZonedDateTime lastCompletedRunStart = ZonedDateTime.now().minusHours(1);
    when(aiTranslateRunService.getLatestCompletedRunStarts(List.of(1L, 2L)))
        .thenReturn(Map.of(1L, lastCompletedRunStart, 2L, lastCompletedRunStart));
    when(tmTextUnitCurrentVariantRepository.findFirstChangeSince(eq(2L), eq(lastCompletedRunStart)))
        .thenReturn(Optional.of(1));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("cron", true);

    assertEquals(1, result.scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    verify(aiTranslateService).aiTranslateAsync(inputCaptor.capture(), anyString());
    assertEquals("repo-b", inputCaptor.getValue().repositoryName());
    verify(aiTranslateRunService).getLatestCompletedRunStarts(List.of(1L, 2L));
    verify(tmTextUnitCurrentVariantRepository).findFirstChangeSince(1L, lastCompletedRunStart);
    verify(tmTextUnitCurrentVariantRepository).findFirstChangeSince(2L, lastCompletedRunStart);
  }

  @Test
  public void clearingLocaleExclusionsReconsidersRepositoryWithoutTranslationChanges() {
    ZonedDateTime configLastModifiedDate = ZonedDateTime.now().minusHours(1);
    ZonedDateTime runBeforeConfigChange = configLastModifiedDate.minusMinutes(1);
    ZonedDateTime runAfterConfigChange = configLastModifiedDate.plusMinutes(1);
    when(aiTranslateAutomationConfigService.getConfig()).thenReturn(config(List.of(), List.of()));
    when(aiTranslateAutomationConfigService.getLastModifiedDate())
        .thenReturn(configLastModifiedDate);
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(List.of(repository(1L, "repo-a"), repository(2L, "repo-b")));
    when(aiTranslateRunService.getLatestCompletedRunStarts(List.of(1L, 2L)))
        .thenReturn(Map.of(1L, runBeforeConfigChange, 2L, runAfterConfigChange));
    when(aiTranslateRunService.getLatestCompletedRunCreatedDates(List.of(1L, 2L)))
        .thenReturn(Map.of(1L, runBeforeConfigChange, 2L, runAfterConfigChange));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("cron", true);

    assertEquals(1, result.scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    verify(aiTranslateService).aiTranslateAsync(inputCaptor.capture(), anyString());
    assertEquals("repo-a", inputCaptor.getValue().repositoryName());
    assertNull(inputCaptor.getValue().targetBcp47tags());
    verify(aiTranslateAutomationConfigService).getLastModifiedDate();
    verify(tmTextUnitCurrentVariantRepository, never())
        .findFirstChangeSince(1L, runBeforeConfigChange);
    verify(tmTextUnitCurrentVariantRepository).findFirstChangeSince(2L, runAfterConfigChange);
  }

  @Test
  public void clearingLocaleExclusionsReconsidersRunQueuedBeforeConfigChangeButStartedAfter() {
    ZonedDateTime configLastModifiedDate = ZonedDateTime.now().minusHours(1);
    ZonedDateTime createdBeforeConfigChange = configLastModifiedDate.minusMinutes(1);
    ZonedDateTime startedAfterConfigChange = configLastModifiedDate.plusMinutes(1);
    when(aiTranslateAutomationConfigService.getConfig()).thenReturn(config(List.of(), List.of()));
    when(aiTranslateAutomationConfigService.getLastModifiedDate())
        .thenReturn(configLastModifiedDate);
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(List.of(repository(1L, "repo-a")));
    when(aiTranslateRunService.getLatestCompletedRunStarts(List.of(1L)))
        .thenReturn(Map.of(1L, startedAfterConfigChange));
    when(aiTranslateRunService.getLatestCompletedRunCreatedDates(List.of(1L)))
        .thenReturn(Map.of(1L, createdBeforeConfigChange));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("cron", true);

    assertEquals(1, result.scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    verify(aiTranslateService).aiTranslateAsync(inputCaptor.capture(), anyString());
    assertNull(inputCaptor.getValue().targetBcp47tags());
    verify(aiTranslateRunService).getLatestCompletedRunCreatedDates(List.of(1L));
    verify(tmTextUnitCurrentVariantRepository, never())
        .findFirstChangeSince(1L, startedAfterConfigChange);
  }

  @Test
  public void clearingLocaleExclusionsReconsidersRunCreatedInSamePersistedSecond() {
    ZonedDateTime persistedSecond = ZonedDateTime.parse("2026-09-23T12:00:00Z");
    ZonedDateTime startedAfterConfigChange = persistedSecond.plusMinutes(1);
    when(aiTranslateAutomationConfigService.getConfig()).thenReturn(config(List.of(), List.of()));
    when(aiTranslateAutomationConfigService.getLastModifiedDate()).thenReturn(persistedSecond);
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(List.of(repository(1L, "repo-a")));
    when(aiTranslateRunService.getLatestCompletedRunStarts(List.of(1L)))
        .thenReturn(Map.of(1L, startedAfterConfigChange));
    when(aiTranslateRunService.getLatestCompletedRunCreatedDates(List.of(1L)))
        .thenReturn(Map.of(1L, persistedSecond));

    assertEquals(
        1,
        aiTranslateAutomationSchedulerService
            .scheduleConfiguredRepositories("cron", true)
            .scheduledRepositoryCount());
    verify(tmTextUnitCurrentVariantRepository, never())
        .findFirstChangeSince(1L, startedAfterConfigChange);

    ZonedDateTime newerRunCreatedDate = persistedSecond.plusMinutes(2);
    when(aiTranslateRunService.getLatestCompletedRunCreatedDates(List.of(1L)))
        .thenReturn(Map.of(1L, newerRunCreatedDate));
    when(aiTranslateRunService.getLatestCompletedRunStarts(List.of(1L)))
        .thenReturn(Map.of(1L, newerRunCreatedDate));

    assertEquals(
        0,
        aiTranslateAutomationSchedulerService
            .scheduleConfiguredRepositories("cron", true)
            .scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    verify(aiTranslateService).aiTranslateAsync(inputCaptor.capture(), anyString());
    assertNull(inputCaptor.getValue().targetBcp47tags());
    verify(tmTextUnitCurrentVariantRepository).findFirstChangeSince(1L, newerRunCreatedDate);
  }

  @Test
  public void includedRepositoryLocaleExclusionsAreExactAndLeaveOtherRepositoriesUnchanged() {
    when(aiTranslateAutomationConfigService.getConfig())
        .thenReturn(config(List.of(1L, 2L), List.of(1L), Map.of(1L, List.of("fr"))));
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(
            List.of(repository(1L, "repo-a"), repository(2L, "repo-b"), repository(3L, "repo-c")));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("cron", true);

    assertEquals(2, result.scheduledRepositoryCount());
    ArgumentCaptor<AiTranslateInput> inputCaptor = ArgumentCaptor.forClass(AiTranslateInput.class);
    verify(aiTranslateService, times(2)).aiTranslateAsync(inputCaptor.capture(), anyString());
    assertEquals("repo-a", inputCaptor.getAllValues().get(0).repositoryName());
    assertEquals(List.of("de", "fr-CA"), inputCaptor.getAllValues().get(0).targetBcp47tags());
    assertEquals("repo-b", inputCaptor.getAllValues().get(1).repositoryName());
    assertNull(inputCaptor.getAllValues().get(1).targetBcp47tags());
  }

  @Test
  public void skipsRepositoryWhenEveryTargetLocaleIsExcluded() {
    when(aiTranslateAutomationConfigService.getConfig())
        .thenReturn(config(List.of(1L), List.of(), Map.of(1L, List.of("fr", "fr-CA", "de"))));
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(List.of(repository(1L, "repo-a")));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("manual", false, 5L);

    assertEquals(0, result.scheduledRepositoryCount());
    verify(aiTranslateService, never()).aiTranslateAsync(any(), anyString());
    verify(aiTranslateRunService, never())
        .createScheduledRun(any(), any(), any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  public void createsNoPollableTasksWhenNoRepositoryChanged() {
    when(aiTranslateAutomationConfigService.getConfig()).thenReturn(config(List.of(), List.of()));
    when(repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc())
        .thenReturn(List.of(repository(1L, "repo-a"), repository(2L, "repo-b")));
    ZonedDateTime lastCompletedRunStart = ZonedDateTime.now().minusHours(1);
    when(aiTranslateRunService.getLatestCompletedRunStarts(List.of(1L, 2L)))
        .thenReturn(Map.of(1L, lastCompletedRunStart, 2L, lastCompletedRunStart));

    AiTranslateAutomationSchedulerService.RunResult result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("cron", true);

    assertEquals(0, result.scheduledRepositoryCount());
    verify(aiTranslateService, never()).aiTranslateAsync(any(), anyString());
  }

  private AiTranslateAutomationConfigService.Config config(
      List<Long> repositoryIds, List<Long> excludedRepositoryIds) {
    return config(repositoryIds, excludedRepositoryIds, Map.of());
  }

  private AiTranslateAutomationConfigService.Config config(
      List<Long> repositoryIds,
      List<Long> excludedRepositoryIds,
      Map<Long, List<String>> excludedLocaleTagsByRepositoryId) {
    return new AiTranslateAutomationConfigService.Config(
        true,
        repositoryIds,
        excludedRepositoryIds,
        25,
        "0 0 * * * ?",
        excludedLocaleTagsByRepositoryId);
  }

  private Repository repository(Long id, String name) {
    Repository repository = new Repository();
    repository.setId(id);
    repository.setName(name);
    TM tm = new TM();
    tm.setId(id);
    repository.setTm(tm);
    RepositoryLocale rootLocale = repositoryLocale(repository, "en", null);
    repository.getRepositoryLocales().add(rootLocale);
    repository.getRepositoryLocales().add(repositoryLocale(repository, "fr", rootLocale));
    repository.getRepositoryLocales().add(repositoryLocale(repository, "fr-CA", rootLocale));
    repository.getRepositoryLocales().add(repositoryLocale(repository, "de", rootLocale));
    return repository;
  }

  private RepositoryLocale repositoryLocale(
      Repository repository, String localeTag, RepositoryLocale parentLocale) {
    Locale locale = new Locale();
    locale.setBcp47Tag(localeTag);
    return new RepositoryLocale(repository, locale, true, parentLocale);
  }

  private PollableFutureTaskResult<Void> pollableFuture() {
    PollableFutureTaskResult<Void> pollableFuture = new PollableFutureTaskResult<>();
    pollableFuture.setPollableTask(new PollableTask());
    return pollableFuture;
  }
}
