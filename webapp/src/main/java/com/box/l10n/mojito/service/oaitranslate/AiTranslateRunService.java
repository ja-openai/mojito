package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserRepository;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

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

  public static final int DEFAULT_RECENT_RUN_LIMIT = 50;

  private final AiTranslateRunRepository aiTranslateRunRepository;
  private final UserRepository userRepository;
  private final PlatformTransactionManager transactionManager;

  public AiTranslateRunService(
      AiTranslateRunRepository aiTranslateRunRepository,
      UserRepository userRepository,
      PlatformTransactionManager transactionManager) {
    this.aiTranslateRunRepository = aiTranslateRunRepository;
    this.userRepository = userRepository;
    this.transactionManager = transactionManager;
  }

  public AiTranslateRun createScheduledRun(
      AiTranslateRun.TriggerSource triggerSource,
      Repository repository,
      Long requestedByUserId,
      PollableTask pollableTask,
      String model,
      String translateType,
      String relatedStringsType,
      int sourceTextMaxCountPerLocale) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      AiTranslateRun run =
          createScheduledRunNoTx(
              triggerSource,
              repository,
              requestedByUserId,
              pollableTask,
              model,
              translateType,
              relatedStringsType,
              sourceTextMaxCountPerLocale);
      transactionManager.commit(transaction);
      return run;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  AiTranslateRun createScheduledRunNoTx(
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

  public void markRunning(Long pollableTaskId) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      markRunningNoTx(pollableTaskId);
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void markRunningNoTx(Long pollableTaskId) {
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

  public void markCompleted(Long pollableTaskId, AiTranslateService.AiTranslateRunTotals totals) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      markCompletedNoTx(pollableTaskId, totals);
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void markCompletedNoTx(Long pollableTaskId, AiTranslateService.AiTranslateRunTotals totals) {
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

  public void markFailed(Long pollableTaskId) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      markFailedNoTx(pollableTaskId);
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void markFailedNoTx(Long pollableTaskId) {
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

  public List<RunSummary> getRecentRuns(List<Long> repositoryIds, int limit) {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setReadOnly(true);
    TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

    try {
      List<RunSummary> runs = getRecentRunsNoTx(repositoryIds, limit);
      transactionManager.commit(transaction);
      return runs;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  List<RunSummary> getRecentRunsNoTx(List<Long> repositoryIds, int limit) {
    List<AiTranslateRunSummaryRow> rows =
        repositoryIds == null || repositoryIds.isEmpty()
            ? aiTranslateRunRepository.findRecentRunRows(PageRequest.of(0, limit))
            : aiTranslateRunRepository.findRecentRunRowsByRepositoryIds(
                repositoryIds, PageRequest.of(0, limit));

    return rows.stream()
        .map(
            row ->
                new RunSummary(
                    row.id(),
                    row.triggerSource().name(),
                    row.repositoryId(),
                    row.repositoryName(),
                    row.requestedByUserId(),
                    row.pollableTaskId(),
                    row.model(),
                    row.translateType(),
                    row.relatedStringsType(),
                    row.sourceTextMaxCountPerLocale(),
                    row.status().name(),
                    row.createdAt(),
                    row.startedAt(),
                    row.finishedAt(),
                    row.inputTokens(),
                    row.cachedInputTokens(),
                    row.outputTokens(),
                    row.reasoningTokens(),
                    row.estimatedCostUsd()))
        .toList();
  }

  private User getUserReference(Long requestedByUserId) {
    return userRepository.getReferenceById(requestedByUserId);
  }
}
