package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.entity.AiTranslateRun;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateConfigurationProperties;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateDefaults;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateRunService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.AiTranslateInput;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiTranslateWS {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(AiTranslateWS.class);

  @Autowired AiTranslateService aiTranslateService;

  @Autowired RepositoryRepository repositoryRepository;

  @Autowired QuartzPollableTaskScheduler quartzPollableTaskScheduler;

  @Autowired AiTranslateConfigurationProperties aiTranslateConfigurationProperties;

  @Autowired StructuredBlobStorage structuredBlobStorage;

  @Autowired AiTranslateRunService aiTranslateRunService;

  @Autowired AuditorAwareImpl auditorAwareImpl;

  @RequestMapping(method = RequestMethod.GET, value = "/api/proto-ai-translate/config")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiTranslateConfigResponse getConfig() {
    return new ProtoAiTranslateConfigResponse(
        aiTranslateConfigurationProperties.getModelName(),
        aiTranslateConfigurationProperties.getResponses().getReasoningEffort(),
        aiTranslateConfigurationProperties.getResponses().getTextVerbosity(),
        AiTranslateDefaults.RELATED_STRINGS_TYPE,
        AiTranslateDefaults.TRANSLATE_TYPE,
        AiTranslateDefaults.STATUS_FILTER,
        AiTranslateDefaults.IMPORT_STATUS);
  }

  @RequestMapping(method = RequestMethod.POST, value = "/api/proto-ai-translate")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiTranslateResponse aiTranslate(
      @RequestBody ProtoAiTranslateRequest protoAiTranslateRequest) {

    AiTranslateInput aiTranslateInput = toAiTranslateInput(protoAiTranslateRequest);
    PollableFuture<Void> pollableFuture = aiTranslateService.aiTranslateAsync(aiTranslateInput);
    PollableTask pollableTask = pollableFuture.getPollableTask();
    recordManualRun(protoAiTranslateRequest, pollableTask);

    return new ProtoAiTranslateResponse(pollableTask);
  }

  private AiTranslateInput toAiTranslateInput(ProtoAiTranslateRequest protoAiTranslateRequest) {
    return new AiTranslateInput(
        protoAiTranslateRequest.repositoryName(),
        protoAiTranslateRequest.targetBcp47tags(),
        protoAiTranslateRequest.sourceTextMaxCountPerLocale(),
        protoAiTranslateRequest.tmTextUnitIds(),
        protoAiTranslateRequest.useBatch(),
        protoAiTranslateRequest.useModel(),
        protoAiTranslateRequest.promptSuffix(),
        protoAiTranslateRequest.relatedStringsType(),
        protoAiTranslateRequest.translateType(),
        protoAiTranslateRequest.statusFilter(),
        protoAiTranslateRequest.importStatus(),
        protoAiTranslateRequest.reasoningEffort(),
        protoAiTranslateRequest.textVerbosity(),
        protoAiTranslateRequest.glossaryName(),
        protoAiTranslateRequest.glossaryTermSource(),
        protoAiTranslateRequest.glossaryTermSourceDescription(),
        protoAiTranslateRequest.glossaryTermTarget(),
        protoAiTranslateRequest.glossaryTermTargetDescription(),
        protoAiTranslateRequest.glossaryTermDoNotTranslate(),
        protoAiTranslateRequest.glossaryTermCaseSensitive(),
        protoAiTranslateRequest.glossaryOnlyMatchedTextUnits(),
        protoAiTranslateRequest.dryRun(),
        protoAiTranslateRequest.timeoutSeconds());
  }

  private void recordManualRun(
      ProtoAiTranslateRequest protoAiTranslateRequest, PollableTask pollableTask) {
    Repository repository =
        repositoryRepository.findByName(protoAiTranslateRequest.repositoryName());
    if (repository == null || Boolean.TRUE.equals(repository.getDeleted())) {
      return;
    }

    aiTranslateRunService.createScheduledRun(
        AiTranslateRun.TriggerSource.MANUAL,
        repository,
        auditorAwareImpl.getCurrentAuditor().map(user -> user.getId()).orElse(null),
        pollableTask,
        getModel(protoAiTranslateRequest),
        getValueOrDefault(
            protoAiTranslateRequest.translateType(), AiTranslateDefaults.TRANSLATE_TYPE),
        getValueOrDefault(
            protoAiTranslateRequest.relatedStringsType(), AiTranslateDefaults.RELATED_STRINGS_TYPE),
        protoAiTranslateRequest.sourceTextMaxCountPerLocale());
  }

  private String getModel(ProtoAiTranslateRequest protoAiTranslateRequest) {
    return getValueOrDefault(
        protoAiTranslateRequest.useModel(), aiTranslateConfigurationProperties.getModelName());
  }

  private String getValueOrDefault(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }

  public record ProtoAiTranslateRequest(
      String repositoryName,
      List<String> targetBcp47tags,
      int sourceTextMaxCountPerLocale,
      List<Long> tmTextUnitIds,
      boolean useBatch,
      String useModel,
      String promptSuffix,
      String relatedStringsType,
      String translateType,
      String statusFilter,
      String importStatus,
      String reasoningEffort,
      String textVerbosity,
      String glossaryName,
      String glossaryTermSource,
      String glossaryTermSourceDescription,
      String glossaryTermTarget,
      String glossaryTermTargetDescription,
      boolean glossaryTermDoNotTranslate,
      boolean glossaryTermCaseSensitive,
      boolean glossaryOnlyMatchedTextUnits,
      boolean dryRun,
      Integer timeoutSeconds) {}

  public record ProtoAiTranslateResponse(PollableTask pollableTask) {}

  public record ProtoAiTranslateConfigResponse(
      String modelName,
      String reasoningEffort,
      String textVerbosity,
      String relatedStringsType,
      String translateType,
      String statusFilter,
      String importStatus) {}

  @RequestMapping(method = RequestMethod.POST, value = "/api/proto-ai-translate/retry-import")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiTranslateRetryImportResponse aiTranslateRetryImport(
      @RequestBody ProtoAiTranslateRetryImportRequest protoAiTranslateRetryImportRequest) {
    PollableFuture<Void> pollableFuture =
        aiTranslateService.retryImport(
            protoAiTranslateRetryImportRequest.childPollableTaskId(),
            protoAiTranslateRetryImportRequest.resume(),
            PollableTask.INJECT_CURRENT_TASK);
    return new ProtoAiTranslateRetryImportResponse(pollableFuture.getPollableTask().getId());
  }

  @RequestMapping(
      method = RequestMethod.GET,
      value = "/api/proto-ai-translate/report/{pollableTaskId}")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiTranslateGetReportResponse aiTranslateReport(@PathVariable Long pollableTaskId) {
    AiTranslateService.ReportContent reportContent =
        aiTranslateService.getReportContent(pollableTaskId);
    return new ProtoAiTranslateGetReportResponse(reportContent.reportLocaleUrls());
  }

  @RequestMapping(
      method = RequestMethod.GET,
      value = "/api/proto-ai-translate/report/{pollableTaskId}/locale/{bcp47Tag}")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiTranslateGetReportLocaleResponse aiTranslateReportLocale(
      @PathVariable long pollableTaskId, @PathVariable String bcp47Tag) {
    var reportContentLocale = aiTranslateService.getReportContentLocale(pollableTaskId, bcp47Tag);
    return new ProtoAiTranslateGetReportLocaleResponse(reportContentLocale);
  }

  public record ProtoAiTranslateRetryImportRequest(long childPollableTaskId, boolean resume) {}

  public record ProtoAiTranslateRetryImportResponse(long pollableTaskId) {}

  public record ProtoAiTranslateGetReportResponse(List<String> reportLocaleUrls) {}

  public record ProtoAiTranslateGetReportLocaleResponse(String content) {}
}
