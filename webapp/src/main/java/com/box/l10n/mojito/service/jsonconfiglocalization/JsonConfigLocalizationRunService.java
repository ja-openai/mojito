package com.box.l10n.mojito.service.jsonconfiglocalization;

import com.box.l10n.mojito.entity.JsonConfigLocalizationEntity;
import com.box.l10n.mojito.entity.JsonConfigLocalizationRunEntity;
import com.box.l10n.mojito.entity.JsonConfigLocalizationRunEntity.Status;
import com.box.l10n.mojito.entity.JsonConfigLocalizationRunEntity.TriggerSource;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JsonConfigLocalizationRunService {

  public static final int DEFAULT_RECENT_RUN_LIMIT = 20;
  public static final int MAX_RECENT_RUN_LIMIT = 200;

  private final JsonConfigLocalizationRunRepository runRepository;

  public JsonConfigLocalizationRunService(JsonConfigLocalizationRunRepository runRepository) {
    this.runRepository = runRepository;
  }

  @Transactional
  public JsonConfigLocalizationRunEntity createRunningRun(
      JsonConfigLocalizationEntity setup,
      TriggerSource triggerSource,
      JsonConfigLocalizationAutomationService.AutomationOptions options) {
    JsonConfigLocalizationRunEntity run = new JsonConfigLocalizationRunEntity();
    run.setJsonConfigLocalization(setup);
    run.setTriggerSource(triggerSource);
    run.setStatus(Status.RUNNING);
    run.setStartedAt(ZonedDateTime.now());
    run.setPullEnabled(JsonConfigLocalizationAutomationService.pull(options));
    run.setExtractEnabled(JsonConfigLocalizationAutomationService.extract(options));
    run.setTranslateEnabled(JsonConfigLocalizationAutomationService.translate(options));
    run.setMergeEnabled(JsonConfigLocalizationAutomationService.merge(options));
    run.setSaveConfigEnabled(JsonConfigLocalizationAutomationService.saveConfig(options));
    run.setPushEnabled(JsonConfigLocalizationAutomationService.push(options));
    run.setSummary("Automation started.");
    return runRepository.save(run);
  }

  @Transactional
  public void markCompleted(Long runId, RunSteps steps) {
    runRepository
        .findById(runId)
        .ifPresent(
            run -> {
              run.setStatus(Status.COMPLETED);
              if (run.getStartedAt() == null) {
                run.setStartedAt(ZonedDateTime.now());
              }
              run.setFinishedAt(ZonedDateTime.now());
              applySteps(run, steps);
              run.setSummary(summary(steps));
              run.setErrorMessage(null);
            });
  }

  @Transactional
  public void markFailed(Long runId, RunSteps steps, String errorMessage) {
    runRepository
        .findById(runId)
        .ifPresent(
            run -> {
              run.setStatus(Status.FAILED);
              if (run.getStartedAt() == null) {
                run.setStartedAt(ZonedDateTime.now());
              }
              run.setFinishedAt(ZonedDateTime.now());
              applySteps(run, steps);
              run.setSummary(summary(steps));
              run.setErrorMessage(truncate(errorMessage, 4000));
            });
  }

  @Transactional(readOnly = true)
  public List<RunSummary> getRecentRuns(Long setupId, int limit) {
    int resolvedLimit = Math.max(1, Math.min(MAX_RECENT_RUN_LIMIT, limit));
    return runRepository.findRecentBySetupId(setupId, PageRequest.of(0, resolvedLimit)).stream()
        .map(this::toSummary)
        .toList();
  }

  private void applySteps(JsonConfigLocalizationRunEntity run, RunSteps steps) {
    run.setPulled(steps.pulled());
    run.setExtracted(steps.extracted());
    run.setTranslated(steps.translated());
    run.setMerged(steps.merged());
    run.setSavedConfig(steps.savedConfig());
    run.setPushed(steps.pushed());
    run.setPushSkipped(steps.pushSkipped());
  }

  private RunSummary toSummary(JsonConfigLocalizationRunEntity run) {
    return new RunSummary(
        run.getId(),
        run.getJsonConfigLocalization().getId(),
        run.getTriggerSource().name(),
        run.getStatus().name(),
        run.getCreatedDate(),
        run.getStartedAt(),
        run.getFinishedAt(),
        run.isPullEnabled(),
        run.isExtractEnabled(),
        run.isTranslateEnabled(),
        run.isMergeEnabled(),
        run.isSaveConfigEnabled(),
        run.isPushEnabled(),
        run.isPulled(),
        run.isExtracted(),
        run.isTranslated(),
        run.isMerged(),
        run.isSavedConfig(),
        run.isPushed(),
        run.isPushSkipped(),
        run.getSummary(),
        run.getErrorMessage());
  }

  private String summary(RunSteps steps) {
    List<String> completed = new ArrayList<>();
    if (steps.pulled()) {
      completed.add("pulled config");
    }
    if (steps.extracted()) {
      completed.add("extracted strings");
    }
    if (steps.translated()) {
      completed.add("ran AI translation");
    }
    if (steps.merged()) {
      completed.add("merged translations");
    }
    if (steps.savedConfig()) {
      completed.add("saved config");
    }
    if (steps.pushed()) {
      completed.add(steps.pushSkipped() ? "skipped unchanged Statsig push" : "pushed to Statsig");
    }
    return completed.isEmpty() ? "No steps completed." : String.join(", ", completed) + ".";
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  public record RunSteps(
      boolean pulled,
      boolean extracted,
      boolean translated,
      boolean merged,
      boolean savedConfig,
      boolean pushed,
      boolean pushSkipped) {}

  public record RunSummary(
      Long id,
      Long setupId,
      String triggerSource,
      String status,
      ZonedDateTime createdDate,
      ZonedDateTime startedAt,
      ZonedDateTime finishedAt,
      boolean pullEnabled,
      boolean extractEnabled,
      boolean translateEnabled,
      boolean mergeEnabled,
      boolean saveConfigEnabled,
      boolean pushEnabled,
      boolean pulled,
      boolean extracted,
      boolean translated,
      boolean merged,
      boolean savedConfig,
      boolean pushed,
      boolean pushSkipped,
      String summary,
      String errorMessage) {}
}
