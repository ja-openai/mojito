package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationConfigService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationCronSchedulerService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationSchedulerService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateRunService;
import com.box.l10n.mojito.service.team.TeamService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AiTranslateAutomationWS {

  private final AiTranslateAutomationConfigService aiTranslateAutomationConfigService;
  private final AiTranslateAutomationCronSchedulerService aiTranslateAutomationCronSchedulerService;
  private final AiTranslateAutomationSchedulerService aiTranslateAutomationSchedulerService;
  private final AiTranslateRunService aiTranslateRunService;
  private final TeamService teamService;

  public AiTranslateAutomationWS(
      AiTranslateAutomationConfigService aiTranslateAutomationConfigService,
      AiTranslateAutomationCronSchedulerService aiTranslateAutomationCronSchedulerService,
      AiTranslateAutomationSchedulerService aiTranslateAutomationSchedulerService,
      AiTranslateRunService aiTranslateRunService,
      TeamService teamService) {
    this.aiTranslateAutomationConfigService = aiTranslateAutomationConfigService;
    this.aiTranslateAutomationCronSchedulerService = aiTranslateAutomationCronSchedulerService;
    this.aiTranslateAutomationSchedulerService = aiTranslateAutomationSchedulerService;
    this.aiTranslateRunService = aiTranslateRunService;
    this.teamService = teamService;
  }

  @RequestMapping(method = RequestMethod.GET, value = "/api/ai-translate/automation")
  @ResponseStatus(HttpStatus.OK)
  public AutomationConfigResponse getAutomationConfig() {
    var config = aiTranslateAutomationConfigService.getConfig();
    return new AutomationConfigResponse(
        config.enabled(),
        config.repositoryIds(),
        config.sourceTextMaxCountPerLocale(),
        config.cronExpression());
  }

  @RequestMapping(method = RequestMethod.PUT, value = "/api/ai-translate/automation")
  @ResponseStatus(HttpStatus.OK)
  public AutomationConfigResponse updateAutomationConfig(
      @RequestBody AutomationConfigRequest request) {
    var updated =
        aiTranslateAutomationConfigService.updateConfig(
            new AiTranslateAutomationConfigService.Config(
                request.enabled(),
                request.repositoryIds(),
                request.sourceTextMaxCountPerLocale(),
                request.cronExpression()));
    aiTranslateAutomationCronSchedulerService.syncConfig(updated);
    return new AutomationConfigResponse(
        updated.enabled(),
        updated.repositoryIds(),
        updated.sourceTextMaxCountPerLocale(),
        updated.cronExpression());
  }

  @RequestMapping(method = RequestMethod.POST, value = "/api/ai-translate/automation/run")
  @ResponseStatus(HttpStatus.OK)
  public RunAutomationResponse runAutomationNow() {
    var result =
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories(
            "manual", false, teamService.getCurrentUserIdOrThrow());
    return new RunAutomationResponse(result.scheduledRepositoryCount());
  }

  @RequestMapping(method = RequestMethod.GET, value = "/api/ai-translate/automation/runs")
  @ResponseStatus(HttpStatus.OK)
  public List<AutomationRunResponse> getAutomationRuns(
      @RequestParam(value = "repositoryIds", required = false) List<Long> repositoryIds,
      @RequestParam(value = "limit", required = false) Integer limit) {
    int resolvedLimit = limit == null ? AiTranslateRunService.DEFAULT_RECENT_RUN_LIMIT : limit;
    if (resolvedLimit < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be at least 1");
    }

    return aiTranslateRunService.getRecentRuns(repositoryIds, resolvedLimit).stream()
        .map(
            run ->
                new AutomationRunResponse(
                    run.id(),
                    run.triggerSource(),
                    run.repositoryId(),
                    run.repositoryName(),
                    run.requestedByUserId(),
                    run.pollableTaskId(),
                    run.model(),
                    run.translateType(),
                    run.relatedStringsType(),
                    run.sourceTextMaxCountPerLocale(),
                    run.status(),
                    run.createdAt() == null ? null : run.createdAt().toString(),
                    run.startedAt() == null ? null : run.startedAt().toString(),
                    run.finishedAt() == null ? null : run.finishedAt().toString(),
                    run.inputTokens(),
                    run.cachedInputTokens(),
                    run.outputTokens(),
                    run.reasoningTokens(),
                    run.estimatedCostUsd()))
        .toList();
  }

  public record AutomationConfigRequest(
      boolean enabled,
      List<Long> repositoryIds,
      int sourceTextMaxCountPerLocale,
      String cronExpression) {}

  public record AutomationConfigResponse(
      boolean enabled,
      List<Long> repositoryIds,
      int sourceTextMaxCountPerLocale,
      String cronExpression) {}

  public record RunAutomationResponse(int scheduledRepositoryCount) {}

  public record AutomationRunResponse(
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
      String createdAt,
      String startedAt,
      String finishedAt,
      long inputTokens,
      long cachedInputTokens,
      long outputTokens,
      long reasoningTokens,
      java.math.BigDecimal estimatedCostUsd) {}
}
