package com.box.l10n.mojito.service.jsonconfiglocalization;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.JsonConfigLocalizationEntity;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.asset.VirtualAssetBadRequestException;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExportResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExtractForRepositoryInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.SourceConfigProfile;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.TranslationScope;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationRunService.RunSteps;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.JsonConfigLocalization;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.JsonConfigLocalizationInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService.StatsigPullInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService.StatsigPushResult;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateConfigurationProperties;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateDefaults;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateRunService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.AiTranslateInput;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JsonConfigLocalizationAutomationService {

  private static final Logger logger =
      LoggerFactory.getLogger(JsonConfigLocalizationAutomationService.class);

  private final ObjectMapper objectMapper;
  private final JsonConfigLocalizationRepository jsonConfigLocalizationRepository;
  private final JsonConfigLocalizationService jsonConfigLocalizationService;
  private final JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService;
  private final StatsigJsonConfigLocalizationService statsigJsonConfigLocalizationService;
  private final JsonConfigLocalizationRunService jsonConfigLocalizationRunService;
  private final AiTranslateConfigurationProperties aiTranslateConfigurationProperties;
  private final AiTranslateService aiTranslateService;
  private final AiTranslateRunService aiTranslateRunService;
  private final PollableTaskService pollableTaskService;
  private final RepositoryRepository repositoryRepository;

  public JsonConfigLocalizationAutomationService(
      ObjectMapper objectMapper,
      JsonConfigLocalizationRepository jsonConfigLocalizationRepository,
      JsonConfigLocalizationService jsonConfigLocalizationService,
      JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService,
      StatsigJsonConfigLocalizationService statsigJsonConfigLocalizationService,
      JsonConfigLocalizationRunService jsonConfigLocalizationRunService,
      AiTranslateConfigurationProperties aiTranslateConfigurationProperties,
      AiTranslateService aiTranslateService,
      AiTranslateRunService aiTranslateRunService,
      PollableTaskService pollableTaskService,
      RepositoryRepository repositoryRepository) {
    this.objectMapper = objectMapper;
    this.jsonConfigLocalizationRepository = jsonConfigLocalizationRepository;
    this.jsonConfigLocalizationService = jsonConfigLocalizationService;
    this.jsonConfigLocalizationProcessorService = jsonConfigLocalizationProcessorService;
    this.statsigJsonConfigLocalizationService = statsigJsonConfigLocalizationService;
    this.jsonConfigLocalizationRunService = jsonConfigLocalizationRunService;
    this.aiTranslateConfigurationProperties = aiTranslateConfigurationProperties;
    this.aiTranslateService = aiTranslateService;
    this.aiTranslateRunService = aiTranslateRunService;
    this.pollableTaskService = pollableTaskService;
    this.repositoryRepository = repositoryRepository;
  }

  public void runAutomationFromCron(Long jsonConfigLocalizationId) {
    JsonConfigLocalizationEntity entity =
        jsonConfigLocalizationRepository
            .findById(jsonConfigLocalizationId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "JSON config localization not found: " + jsonConfigLocalizationId));
    if (!Boolean.TRUE.equals(entity.getAutomationEnabled())) {
      logger.info(
          "JSON config localization automation skipped because it is disabled: setupId={}",
          jsonConfigLocalizationId);
      return;
    }

    JsonConfigLocalization setup =
        jsonConfigLocalizationService.getByIdForSystem(jsonConfigLocalizationId);
    AutomationOptions options = readOptions(setup.automationOptionsJson());
    Long runId =
        jsonConfigLocalizationRunService
            .createRunningRun(
                entity,
                com.box.l10n.mojito.entity.JsonConfigLocalizationRunEntity.TriggerSource.CRON,
                options)
            .getId();
    RunStepTracker runSteps = new RunStepTracker();

    logger.info(
        "JSON config localization automation starting: setupId={}, repositoryId={}, pull={}, extract={}, translate={}, merge={}, saveConfig={}, push={}",
        setup.id(),
        setup.repository().id(),
        pull(options),
        extract(options),
        translate(options),
        merge(options),
        saveConfig(options),
        push(options));

    try {
      if (pull(options)) {
        statsigJsonConfigLocalizationService.pullForSetupForSystem(
            setup.id(),
            new StatsigPullInput(
                setup.providerConfigId(),
                setup.assetPath(),
                readProfile(setup.extractionMappingJson(), setup.schemaJson()),
                setup.outputLocaleMappingJson(),
                extract(options)));
        runSteps.pulled = true;
        runSteps.extracted = extract(options);
        setup = jsonConfigLocalizationService.getByIdForSystem(jsonConfigLocalizationId);
      } else if (extract(options)) {
        jsonConfigLocalizationProcessorService.extractForSetupForSystem(
            setup.id(),
            new ExtractForRepositoryInput(
                setup.name(),
                setup.assetPath(),
                setup.provider(),
                setup.providerConfigId(),
                setup.schemaJson(),
                setup.sourceConfigJson(),
                readProfile(setup.extractionMappingJson(), setup.schemaJson()),
                null,
                setup.outputLocaleMappingJson()));
        runSteps.extracted = true;
        setup = jsonConfigLocalizationService.getByIdForSystem(jsonConfigLocalizationId);
      }

      if (translate(options)) {
        runAiTranslate(setup.id());
        runSteps.translated = true;
      }

      String sourceConfigJson = setup.sourceConfigJson();
      if (merge(options)) {
        ExportResult exportResult =
            jsonConfigLocalizationProcessorService.exportForSetupForSystem(setup.id());
        sourceConfigJson = exportResult.json();
        runSteps.merged = true;
      }

      if (saveConfig(options)) {
        jsonConfigLocalizationService.updateForSystem(
            setup.id(),
            new JsonConfigLocalizationInput(
                setup.name(),
                setup.assetPath(),
                setup.provider(),
                setup.providerConfigId(),
                setup.schemaJson(),
                sourceConfigJson,
                setup.extractionMappingJson(),
                setup.outputLocaleMappingJson(),
                setup.automationEnabled(),
                setup.automationCronExpression(),
                setup.automationTimeZone(),
                setup.automationOptionsJson()));
        runSteps.savedConfig = true;
      }

      if (push(options)) {
        StatsigPushResult pushResult =
            statsigJsonConfigLocalizationService.pushForSetupForSystem(setup.id());
        runSteps.pushed = true;
        runSteps.pushSkipped = pushResult.skipped();
      }

      jsonConfigLocalizationRunService.markCompleted(runId, runSteps.toRunSteps());
    } catch (VirtualAssetBadRequestException e) {
      jsonConfigLocalizationRunService.markFailed(runId, runSteps.toRunSteps(), e.getMessage());
      throw new IllegalStateException("JSON config localization extraction failed", e);
    } catch (RuntimeException e) {
      jsonConfigLocalizationRunService.markFailed(runId, runSteps.toRunSteps(), e.getMessage());
      throw e;
    }

    logger.info(
        "JSON config localization automation completed: setupId={}, repositoryId={}",
        setup.id(),
        setup.repository().id());
  }

  private AutomationOptions readOptions(String optionsJson) {
    if (optionsJson == null || optionsJson.isBlank()) {
      return new AutomationOptions(null, null, null, true, true, true);
    }
    try {
      return objectMapper.readValue(optionsJson, AutomationOptions.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON config localization automation options", e);
    }
  }

  private SourceConfigProfile readProfile(String profileJson, String schemaJson) {
    if (profileJson != null && !profileJson.isBlank()) {
      try {
        return objectMapper.readValue(profileJson, SourceConfigProfile.class);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Invalid extraction mapping JSON", e);
      }
    }
    return jsonConfigLocalizationProcessorService
        .detectMapping(new JsonConfigLocalizationProcessorService.DetectMappingInput(schemaJson))
        .profile();
  }

  private void runAiTranslate(Long setupId) {
    TranslationScope scope =
        jsonConfigLocalizationProcessorService.getTranslationScopeForSetupForSystem(setupId);
    if (scope.tmTextUnitIds().isEmpty()) {
      logger.info(
          "JSON config localization AI translate skipped: setupId={}, reason=no_active_strings",
          setupId);
      return;
    }
    if (scope.targetLocaleTags().isEmpty()) {
      logger.info(
          "JSON config localization AI translate skipped: setupId={}, reason=no_target_locales",
          setupId);
      return;
    }

    Long repositoryId = jsonConfigLocalizationService.getByIdForSystem(setupId).repository().id();
    Repository repository =
        repositoryRepository
            .findNoGraphById(repositoryId)
            .filter(candidate -> !Boolean.TRUE.equals(candidate.getDeleted()))
            .orElseThrow(
                () -> new IllegalArgumentException("Repository not found: " + repositoryId));
    int sourceTextMaxCountPerLocale = Math.max(scope.tmTextUnitIds().size(), 1);
    AiTranslateInput input =
        new AiTranslateInput(
            scope.repositoryName(),
            scope.targetLocaleTags(),
            sourceTextMaxCountPerLocale,
            scope.tmTextUnitIds(),
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
    PollableFuture<Void> pollableFuture =
        aiTranslateService.aiTranslateAsync(input, "json-config-localization-" + setupId);
    aiTranslateRunService.createScheduledRun(
        AiTranslateRun.TriggerSource.CRON,
        repository,
        null,
        pollableFuture.getPollableTask(),
        aiTranslateConfigurationProperties.getModelName(),
        AiTranslateDefaults.TRANSLATE_TYPE,
        AiTranslateDefaults.RELATED_STRINGS_TYPE,
        sourceTextMaxCountPerLocale);

    try {
      pollableTaskService.waitForPollableTask(pollableFuture.getPollableTask().getId());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("JSON config localization AI translate was interrupted", e);
    }
  }

  static boolean pull(AutomationOptions options) {
    return Boolean.TRUE.equals(options.pull());
  }

  static boolean extract(AutomationOptions options) {
    return Boolean.TRUE.equals(options.extract());
  }

  static boolean translate(AutomationOptions options) {
    return Boolean.TRUE.equals(options.translate());
  }

  static boolean merge(AutomationOptions options) {
    return options.merge() == null || options.merge();
  }

  static boolean saveConfig(AutomationOptions options) {
    return options.saveConfig() == null || options.saveConfig();
  }

  static boolean push(AutomationOptions options) {
    return options.push() == null || options.push();
  }

  public record AutomationOptions(
      Boolean pull,
      Boolean extract,
      Boolean translate,
      Boolean merge,
      Boolean saveConfig,
      Boolean push) {}

  private static class RunStepTracker {
    boolean pulled;
    boolean extracted;
    boolean translated;
    boolean merged;
    boolean savedConfig;
    boolean pushed;
    boolean pushSkipped;

    RunSteps toRunSteps() {
      return new RunSteps(pulled, extracted, translated, merged, savedConfig, pushed, pushSkipped);
    }
  }
}
