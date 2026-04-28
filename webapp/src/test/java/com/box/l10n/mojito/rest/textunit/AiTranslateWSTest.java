package com.box.l10n.mojito.rest.textunit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateConfigurationProperties;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateDefaults;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateRunService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class AiTranslateWSTest {

  @Test
  public void aiTranslateRecordsManualRunHistory() {
    AiTranslateWS ws = new AiTranslateWS();
    ws.aiTranslateService = mock(AiTranslateService.class);
    ws.repositoryRepository = mock(RepositoryRepository.class);
    ws.aiTranslateConfigurationProperties = mock(AiTranslateConfigurationProperties.class);
    ws.aiTranslateRunService = mock(AiTranslateRunService.class);
    ws.auditorAwareImpl = mock(AuditorAwareImpl.class);

    Repository repository = new Repository();
    repository.setId(7L);
    repository.setName("repo");
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(42L);
    User user = new User();
    user.setId(5L);
    PollableFuture<Void> pollableFuture = mock(PollableFuture.class);

    when(ws.aiTranslateService.aiTranslateAsync(any())).thenReturn(pollableFuture);
    when(pollableFuture.getPollableTask()).thenReturn(pollableTask);
    when(ws.repositoryRepository.findByName("repo")).thenReturn(repository);
    when(ws.auditorAwareImpl.getCurrentAuditor()).thenReturn(Optional.of(user));

    ws.aiTranslate(
        new AiTranslateWS.ProtoAiTranslateRequest(
            "repo",
            List.of("fr-FR"),
            25,
            null,
            false,
            "model-name",
            null,
            "USAGES",
            "TARGET_ONLY_NEW",
            "FOR_TRANSLATION",
            "REVIEW_NEEDED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            null));

    verify(ws.aiTranslateRunService)
        .createScheduledRun(
            eq(AiTranslateRun.TriggerSource.MANUAL),
            eq(repository),
            eq(5L),
            eq(pollableTask),
            eq("model-name"),
            eq("TARGET_ONLY_NEW"),
            eq("USAGES"),
            eq(25));
  }

  @Test
  public void aiTranslateRecordsManualRunHistoryWithDefaults() {
    AiTranslateWS ws = new AiTranslateWS();
    ws.aiTranslateService = mock(AiTranslateService.class);
    ws.repositoryRepository = mock(RepositoryRepository.class);
    ws.aiTranslateConfigurationProperties = mock(AiTranslateConfigurationProperties.class);
    ws.aiTranslateRunService = mock(AiTranslateRunService.class);
    ws.auditorAwareImpl = mock(AuditorAwareImpl.class);

    Repository repository = new Repository();
    repository.setName("repo");
    PollableTask pollableTask = new PollableTask();
    PollableFuture<Void> pollableFuture = mock(PollableFuture.class);

    when(ws.aiTranslateService.aiTranslateAsync(any())).thenReturn(pollableFuture);
    when(pollableFuture.getPollableTask()).thenReturn(pollableTask);
    when(ws.repositoryRepository.findByName("repo")).thenReturn(repository);
    when(ws.aiTranslateConfigurationProperties.getModelName()).thenReturn("default-model");
    when(ws.auditorAwareImpl.getCurrentAuditor()).thenReturn(Optional.empty());

    ws.aiTranslate(
        new AiTranslateWS.ProtoAiTranslateRequest(
            "repo",
            List.of("fr-FR"),
            25,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            null));

    verify(ws.aiTranslateRunService)
        .createScheduledRun(
            eq(AiTranslateRun.TriggerSource.MANUAL),
            eq(repository),
            isNull(),
            eq(pollableTask),
            eq("default-model"),
            eq(AiTranslateDefaults.TRANSLATE_TYPE),
            eq(AiTranslateDefaults.RELATED_STRINGS_TYPE),
            eq(25));
  }
}
