package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.AiTranslateInput;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiTranslateAutomationSchedulerService {

  static Logger logger = LoggerFactory.getLogger(AiTranslateAutomationSchedulerService.class);

  private static final String METRIC_PREFIX = "AiTranslateAutomation";
  private static final String UNIQUE_ID_PREFIX = "auto-ai-translate-repository-";

  private final AiTranslateAutomationConfigService aiTranslateAutomationConfigService;
  private final AiTranslateConfigurationProperties aiTranslateConfigurationProperties;
  private final AiTranslateService aiTranslateService;
  private final AiTranslateRunService aiTranslateRunService;
  private final RepositoryRepository repositoryRepository;
  private final MeterRegistry meterRegistry;

  public AiTranslateAutomationSchedulerService(
      AiTranslateAutomationConfigService aiTranslateAutomationConfigService,
      AiTranslateConfigurationProperties aiTranslateConfigurationProperties,
      AiTranslateService aiTranslateService,
      AiTranslateRunService aiTranslateRunService,
      RepositoryRepository repositoryRepository,
      MeterRegistry meterRegistry) {
    this.aiTranslateAutomationConfigService = aiTranslateAutomationConfigService;
    this.aiTranslateConfigurationProperties = aiTranslateConfigurationProperties;
    this.aiTranslateService = aiTranslateService;
    this.aiTranslateRunService = aiTranslateRunService;
    this.repositoryRepository = repositoryRepository;
    this.meterRegistry = meterRegistry;
  }

  public RunResult scheduleConfiguredRepositories(String triggerSource, boolean requireEnabled) {
    return scheduleConfiguredRepositories(triggerSource, requireEnabled, null);
  }

  public RunResult scheduleConfiguredRepositories(
      String triggerSource, boolean requireEnabled, Long requestedByUserId) {
    incrementCounter("runs", Tags.of("result", "started"));
    try {
      var config = aiTranslateAutomationConfigService.getConfig();
      logger.info(
          "AI translate automation run started: source={}, enabled={}, repositoryCount={}, excludedRepositoryCount={}, sourceTextMaxCountPerLocale={}",
          triggerSource,
          config.enabled(),
          config.repositoryIds().size(),
          config.excludedRepositoryIds().size(),
          config.sourceTextMaxCountPerLocale());
      if (requireEnabled && !config.enabled()) {
        logger.info(
            "AI translate automation run skipped: source={}, reason=disabled", triggerSource);
        incrementCounter("runs", Tags.of("result", "skipped_disabled"));
        return new RunResult(0);
      }

      List<Repository> eligibleRepositories =
          repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc();
      if (eligibleRepositories.isEmpty()) {
        logger.info(
            "AI translate automation run skipped: source={}, reason=no_eligible_repositories",
            triggerSource);
        incrementCounter("runs", Tags.of("result", "skipped_empty"));
        return new RunResult(0);
      }

      int scheduledRepositoryCount = 0;
      Set<Long> includedRepositoryIds = config.repositoryIds().stream().collect(Collectors.toSet());
      Set<Long> excludedRepositoryIds =
          includedRepositoryIds.isEmpty()
              ? config.excludedRepositoryIds().stream().collect(Collectors.toSet())
              : Set.of();
      for (Repository repository : eligibleRepositories) {
        if (!includedRepositoryIds.isEmpty()
            && !includedRepositoryIds.contains(repository.getId())) {
          logger.info(
              "AI translate automation repository skipped: source={}, repositoryId={}, repositoryName={}, reason=not_included",
              triggerSource,
              repository.getId(),
              repository.getName());
          incrementCounter("repositories", Tags.of("result", "not_included"));
          continue;
        }

        if (excludedRepositoryIds.contains(repository.getId())) {
          logger.info(
              "AI translate automation repository skipped: source={}, repositoryId={}, repositoryName={}, reason=excluded",
              triggerSource,
              repository.getId(),
              repository.getName());
          incrementCounter("repositories", Tags.of("result", "excluded"));
          continue;
        }

        String uniqueId = UNIQUE_ID_PREFIX + repository.getId();
        AiTranslateInput aiTranslateInput =
            new AiTranslateInput(
                repository.getName(),
                null,
                config.sourceTextMaxCountPerLocale(),
                null,
                false,
                null,
                null,
                AiTranslateDefaults.RELATED_STRINGS_TYPE,
                AiTranslateDefaults.TRANSLATE_TYPE,
                AiTranslateDefaults.STATUS_FILTER,
                AiTranslateDefaults.IMPORT_STATUS,
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
                null);
        var pollableFuture = aiTranslateService.aiTranslateAsync(aiTranslateInput, uniqueId);
        aiTranslateRunService.createScheduledRun(
            "manual".equalsIgnoreCase(triggerSource)
                ? AiTranslateRun.TriggerSource.MANUAL
                : AiTranslateRun.TriggerSource.CRON,
            repository,
            requestedByUserId,
            pollableFuture.getPollableTask(),
            aiTranslateConfigurationProperties.getModelName(),
            AiTranslateDefaults.TRANSLATE_TYPE,
            AiTranslateDefaults.RELATED_STRINGS_TYPE,
            config.sourceTextMaxCountPerLocale());
        logger.info(
            "AI translate automation repository scheduled: source={}, repositoryId={}, repositoryName={}, uniqueId={}",
            triggerSource,
            repository.getId(),
            repository.getName(),
            uniqueId);
        scheduledRepositoryCount++;
        incrementCounter("repositories", Tags.of("result", "scheduled"));
      }

      logger.info(
          "AI translate automation run completed: source={}, scheduledRepositoryCount={}",
          triggerSource,
          scheduledRepositoryCount);
      incrementCounter("runs", Tags.of("result", "completed"));
      return new RunResult(scheduledRepositoryCount);
    } catch (RuntimeException e) {
      logger.error("AI translate automation run failed: source={}", triggerSource, e);
      incrementCounter("runs", Tags.of("result", "failed"));
      throw e;
    }
  }

  private void incrementCounter(String metricSuffix, Tags tags) {
    meterRegistry.counter("%s.%s".formatted(METRIC_PREFIX, metricSuffix), tags).increment();
  }

  public record RunResult(int scheduledRepositoryCount) {}
}
