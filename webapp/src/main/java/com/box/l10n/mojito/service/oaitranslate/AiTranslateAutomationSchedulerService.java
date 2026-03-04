package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.AiTranslateInput;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiTranslateAutomationSchedulerService {

  static Logger logger = LoggerFactory.getLogger(AiTranslateAutomationSchedulerService.class);

  private static final String METRIC_PREFIX = "AiTranslateAutomation";
  private static final String UNIQUE_ID_PREFIX = "auto-ai-translate-repository-";
  private static final String RELATED_STRINGS_TYPE = "USAGES";
  private static final String TRANSLATE_TYPE = "TARGET_ONLY_NEW";
  private static final String STATUS_FILTER = "FOR_TRANSLATION";
  private static final String IMPORT_STATUS = "REVIEW_NEEDED";

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
          "AI translate automation run started: source={}, enabled={}, repositoryCount={}, sourceTextMaxCountPerLocale={}",
          triggerSource,
          config.enabled(),
          config.repositoryIds().size(),
          config.sourceTextMaxCountPerLocale());
      if (requireEnabled && !config.enabled()) {
        logger.info(
            "AI translate automation run skipped: source={}, reason=disabled", triggerSource);
        incrementCounter("runs", Tags.of("result", "skipped_disabled"));
        return new RunResult(0);
      }

      if (config.repositoryIds().isEmpty()) {
        logger.info(
            "AI translate automation run skipped: source={}, reason=no_repositories",
            triggerSource);
        incrementCounter("runs", Tags.of("result", "skipped_empty"));
        return new RunResult(0);
      }

      int scheduledRepositoryCount = 0;
      List<Long> repositoryIds = config.repositoryIds();
      for (Long repositoryId : repositoryIds) {
        Optional<Repository> repositoryOptional =
            repositoryRepository.findNoGraphById(repositoryId);
        if (repositoryOptional.isEmpty()
            || Boolean.TRUE.equals(repositoryOptional.get().getDeleted())) {
          logger.info(
              "AI translate automation repository skipped: source={}, repositoryId={}, reason=missing_or_deleted",
              triggerSource,
              repositoryId);
          incrementCounter("repositories", Tags.of("result", "missing"));
          continue;
        }

        Repository repository = repositoryOptional.get();
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
                RELATED_STRINGS_TYPE,
                TRANSLATE_TYPE,
                STATUS_FILTER,
                IMPORT_STATUS,
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
            TRANSLATE_TYPE,
            RELATED_STRINGS_TYPE,
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
