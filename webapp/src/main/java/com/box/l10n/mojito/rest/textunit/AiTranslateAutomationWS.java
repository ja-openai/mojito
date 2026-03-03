package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationConfigService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationCronSchedulerService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationSchedulerService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiTranslateAutomationWS {

  private final AiTranslateAutomationConfigService aiTranslateAutomationConfigService;
  private final AiTranslateAutomationCronSchedulerService aiTranslateAutomationCronSchedulerService;
  private final AiTranslateAutomationSchedulerService aiTranslateAutomationSchedulerService;

  public AiTranslateAutomationWS(
      AiTranslateAutomationConfigService aiTranslateAutomationConfigService,
      AiTranslateAutomationCronSchedulerService aiTranslateAutomationCronSchedulerService,
      AiTranslateAutomationSchedulerService aiTranslateAutomationSchedulerService) {
    this.aiTranslateAutomationConfigService = aiTranslateAutomationConfigService;
    this.aiTranslateAutomationCronSchedulerService = aiTranslateAutomationCronSchedulerService;
    this.aiTranslateAutomationSchedulerService = aiTranslateAutomationSchedulerService;
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
        aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("manual", false);
    return new RunAutomationResponse(result.scheduledRepositoryCount());
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
}
