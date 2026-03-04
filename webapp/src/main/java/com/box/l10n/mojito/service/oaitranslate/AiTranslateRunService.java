package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserRepository;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTranslateRunService {

  public record RunSummary(
      Long id,
      String triggerSource,
      Long repositoryId,
      String repositoryName,
      Long requestedByUserId,
      Long pollableTaskId,
      String model,
      String translateType,
      String relatedStringsType,
      int sourceTextMaxCountPerLocale,
      String status,
      ZonedDateTime createdAt,
      ZonedDateTime startedAt,
      ZonedDateTime finishedAt,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningTokens,
      BigDecimal estimatedCostUsd) {}

  private final AiTranslateRunRepository aiTranslateRunRepository;
  private final UserRepository userRepository;

  public AiTranslateRunService(
      AiTranslateRunRepository aiTranslateRunRepository, UserRepository userRepository) {
    this.aiTranslateRunRepository = aiTranslateRunRepository;
    this.userRepository = userRepository;
  }

  @Transactional
  public AiTranslateRun createScheduledRun(
      AiTranslateRun.TriggerSource triggerSource,
      Repository repository,
      Long requestedByUserId,
      PollableTask pollableTask,
      String model,
      String translateType,
      String relatedStringsType,
      int sourceTextMaxCountPerLocale) {
    AiTranslateRun run = new AiTranslateRun();
    run.setTriggerSource(triggerSource);
    run.setRepository(repository);
    run.setRequestedByUser(requestedByUserId == null ? null : getUserReference(requestedByUserId));
    run.setPollableTask(pollableTask);
    run.setModel(model);
    run.setTranslateType(translateType);
    run.setRelatedStringsType(relatedStringsType);
    run.setSourceTextMaxCountPerLocale(sourceTextMaxCountPerLocale);
    run.setStatus(AiTranslateRun.Status.SCHEDULED);
    return aiTranslateRunRepository.save(run);
  }

  @Transactional
  public void markRunning(Long pollableTaskId) {
    aiTranslateRunRepository
        .findByPollableTask_Id(pollableTaskId)
        .ifPresent(
            run -> {
              run.setStatus(AiTranslateRun.Status.RUNNING);
              if (run.getStartedAt() == null) {
                run.setStartedAt(ZonedDateTime.now());
              }
            });
  }

  @Transactional
  public void markCompleted(Long pollableTaskId, AiTranslateService.AiTranslateRunTotals totals) {
    aiTranslateRunRepository
        .findByPollableTask_Id(pollableTaskId)
        .ifPresent(
            run -> {
              run.setStatus(AiTranslateRun.Status.COMPLETED);
              if (run.getStartedAt() == null) {
                run.setStartedAt(ZonedDateTime.now());
              }
              run.setFinishedAt(ZonedDateTime.now());
              run.setInputTokens(totals.inputTokens());
              run.setCachedInputTokens(totals.cachedInputTokens());
              run.setOutputTokens(totals.outputTokens());
              run.setReasoningTokens(totals.reasoningTokens());
              run.setEstimatedCostUsd(totals.estimatedCostUsd());
            });
  }

  @Transactional
  public void markFailed(Long pollableTaskId) {
    aiTranslateRunRepository
        .findByPollableTask_Id(pollableTaskId)
        .ifPresent(
            run -> {
              run.setStatus(AiTranslateRun.Status.FAILED);
              if (run.getStartedAt() == null) {
                run.setStartedAt(ZonedDateTime.now());
              }
              run.setFinishedAt(ZonedDateTime.now());
            });
  }

  @Transactional(readOnly = true)
  public List<RunSummary> getRecentRuns() {
    return aiTranslateRunRepository.findTop50ByOrderByCreatedDateDesc().stream()
        .map(
            run ->
                new RunSummary(
                    run.getId(),
                    run.getTriggerSource().name(),
                    run.getRepository().getId(),
                    run.getRepository().getName(),
                    run.getRequestedByUser() == null ? null : run.getRequestedByUser().getId(),
                    run.getPollableTask() == null ? null : run.getPollableTask().getId(),
                    run.getModel(),
                    run.getTranslateType(),
                    run.getRelatedStringsType(),
                    run.getSourceTextMaxCountPerLocale(),
                    run.getStatus().name(),
                    run.getCreatedDate(),
                    run.getStartedAt(),
                    run.getFinishedAt(),
                    run.getInputTokens(),
                    run.getCachedInputTokens(),
                    run.getOutputTokens(),
                    run.getReasoningTokens(),
                    run.getEstimatedCostUsd()))
        .toList();
  }

  private User getUserReference(Long requestedByUserId) {
    return userRepository.getReferenceById(requestedByUserId);
  }
}
