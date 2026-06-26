package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.AiTranslateInput;
import com.box.l10n.mojito.service.pollableTask.PollableFutureTaskResult;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
            new SimpleMeterRegistry());
    when(aiTranslateConfigurationProperties.getModelName()).thenReturn("gpt-test");
    when(aiTranslateService.aiTranslateAsync(any(), anyString())).thenReturn(pollableFuture());
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

  private AiTranslateAutomationConfigService.Config config(
      List<Long> repositoryIds, List<Long> excludedRepositoryIds) {
    return new AiTranslateAutomationConfigService.Config(
        true, repositoryIds, excludedRepositoryIds, 25, "0 0 * * * ?");
  }

  private Repository repository(Long id, String name) {
    Repository repository = new Repository();
    repository.setId(id);
    repository.setName(name);
    return repository;
  }

  private PollableFutureTaskResult<Void> pollableFuture() {
    PollableFutureTaskResult<Void> pollableFuture = new PollableFutureTaskResult<>();
    pollableFuture.setPollableTask(new PollableTask());
    return pollableFuture;
  }
}
