package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
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
    verify(repositoryRepository, never()).findNoGraphById(anyLong());
  }

  @Test
  public void skipsExcludedRepositoriesFromEligibleRepositorySet() {
    when(aiTranslateAutomationConfigService.getConfig()).thenReturn(config(List.of(), List.of(2L)));
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
    return new AiTranslateAutomationConfigService.Config(
        true, repositoryIds, excludedRepositoryIds, 25, "0 0 * * * ?");
  }

  private Repository repository(Long id, String name) {
    Repository repository = new Repository();
    repository.setId(id);
    repository.setName(name);
    TM tm = new TM();
    tm.setId(id);
    repository.setTm(tm);
    return repository;
  }

  private PollableFutureTaskResult<Void> pollableFuture() {
    PollableFutureTaskResult<Void> pollableFuture = new PollableFutureTaskResult<>();
    pollableFuture.setPollableTask(new PollableTask());
    return pollableFuture;
  }
}
