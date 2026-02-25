package com.box.l10n.mojito.service.oaitranslate;

import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionResponseBatchFileLine;
import static com.box.l10n.mojito.openai.OpenAIClient.DownloadFileContentRequest;
import static com.box.l10n.mojito.openai.OpenAIClient.DownloadFileContentResponse;
import static com.box.l10n.mojito.openai.OpenAIClient.RetrieveBatchRequest;
import static com.box.l10n.mojito.openai.OpenAIClient.RetrieveBatchResponse;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSLATE_WS;
import static com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT;
import static java.util.stream.Collectors.toMap;

import com.box.l10n.mojito.JSR310Migration;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.*;
import com.box.l10n.mojito.openai.OpenAIClientPool;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitRepository;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateBatchesImportJob.AiTranslateBatchesImportInput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateBatchesImportJob.AiTranslateBatchesImportOutput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateType.CompletionInput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateType.CompletionInput.ExistingTarget;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateType.CompletionMultiTextUnitInput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateType.CompletionMultiTextUnitInput.TextUnit;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTrie;
import com.box.l10n.mojito.service.pollableTask.InjectCurrentTask;
import com.box.l10n.mojito.service.pollableTask.MsgArg;
import com.box.l10n.mojito.service.pollableTask.Pollable;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableFutureTaskResult;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryNameNotFoundException;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.screenshot.ScreenshotService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.ImportResult;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.TextUnitDTOWithVariantComment;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheService;
import com.box.l10n.mojito.util.ImageBytes;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Stopwatch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

@Service
public class AiTranslateService {

  static final String METADATA__TEXT_UNIT_DTOS__BLOB_ID = "textUnitDTOs";
  static final Integer MAX_COMPLETION_TOKENS = null;
  static final String METRIC_PREFIX = "AiTranslateService";
  static final String MODE_BATCH = "batch";
  static final String MODE_NO_BATCH = "no_batch";
  static final String LOCALE_ALL = "all";
  static final String HAS_SCREENSHOT_NA = "n/a";
  static final String TAG_UNKNOWN = "unknown";

  /** logger */
  static Logger logger = LoggerFactory.getLogger(AiTranslateService.class);

  private final AiTranslateScreenshotService aiTranslateScreenshotService;
  private final AiTranslateLegacyBatchService aiTranslateLegacyBatchService;
  private final MeterRegistry meterRegistry;

  AssetTextUnitRepository assetTextUnitRepository;

  PollableTaskService pollableTaskService;

  TextUnitSearcher textUnitSearcher;

  RepositoryRepository repositoryRepository;

  RepositoryService repositoryService;

  AiTranslateConfigurationProperties aiTranslateConfigurationProperties;

  OpenAIClient openAIClient;

  OpenAIClientPool openAIClientPool;

  TextUnitBatchImporterService textUnitBatchImporterService;

  StructuredBlobStorage structuredBlobStorage;

  ObjectMapper objectMapper;

  RetryBackoffSpec retryBackoffSpec;

  QuartzPollableTaskScheduler quartzPollableTaskScheduler;

  PollableTaskBlobStorage pollableTaskBlobStorage;

  TextUnitDTOsCacheService textUnitDTOsCacheService;

  TMTextUnitVariantRepository tmTextUnitVariantRepository;

  GlossaryService glossaryService;

  public AiTranslateService(
      TextUnitSearcher textUnitSearcher,
      RepositoryRepository repositoryRepository,
      RepositoryService repositoryService,
      TextUnitBatchImporterService textUnitBatchImporterService,
      StructuredBlobStorage structuredBlobStorage,
      AiTranslateConfigurationProperties aiTranslateConfigurationProperties,
      @Qualifier("AiTranslate") @Autowired(required = false) OpenAIClient openAIClient,
      @Qualifier("AiTranslate") @Autowired(required = false) OpenAIClientPool openAIClientPool,
      @Qualifier("AiTranslate") ObjectMapper objectMapper,
      @Qualifier("AiTranslate") RetryBackoffSpec retryBackoffSpec,
      QuartzPollableTaskScheduler quartzPollableTaskScheduler,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      PollableTaskService pollableTaskService,
      TextUnitDTOsCacheService textUnitDTOsCacheService,
      AssetTextUnitRepository assetTextUnitRepository,
      TMTextUnitVariantRepository tmTextUnitVariantRepository,
      GlossaryService glossaryService,
      MeterRegistry meterRegistry,
      ScreenshotService screenshotService,
      AiTranslateScreenshotService aiTranslateScreenshotService,
      AiTranslateLegacyBatchService aiTranslateLegacyBatchService) {
    this.textUnitSearcher = textUnitSearcher;
    this.repositoryRepository = repositoryRepository;
    this.repositoryService = repositoryService;
    this.textUnitBatchImporterService = textUnitBatchImporterService;
    this.structuredBlobStorage = structuredBlobStorage;
    this.aiTranslateConfigurationProperties = aiTranslateConfigurationProperties;
    this.objectMapper = objectMapper;
    this.openAIClient = openAIClient;
    this.openAIClientPool = openAIClientPool;
    this.retryBackoffSpec = retryBackoffSpec;
    this.quartzPollableTaskScheduler = quartzPollableTaskScheduler;
    this.pollableTaskBlobStorage = pollableTaskBlobStorage;
    this.pollableTaskService = pollableTaskService;
    this.textUnitDTOsCacheService = textUnitDTOsCacheService;
    this.assetTextUnitRepository = assetTextUnitRepository;
    this.tmTextUnitVariantRepository = tmTextUnitVariantRepository;
    this.glossaryService = glossaryService;
    this.meterRegistry = meterRegistry;
    this.aiTranslateScreenshotService = aiTranslateScreenshotService;
    this.aiTranslateLegacyBatchService = aiTranslateLegacyBatchService;
  }

  public record AiTranslateInput(
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

  public PollableFuture<Void> aiTranslateAsync(AiTranslateInput aiTranslateInput) {

    QuartzJobInfo<AiTranslateInput, Void> quartzJobInfo =
        QuartzJobInfo.newBuilder(AiTranslateJob.class)
            .withInlineInput(false)
            .withInput(aiTranslateInput)
            .withScheduler(aiTranslateConfigurationProperties.getSchedulerName())
            .build();

    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  public PollableFuture<AiTranslateBatchesImportOutput> aiTranslateBatchesImportAsync(
      AiTranslateBatchesImportInput aiTranslateBatchesImportInput, PollableTask currentTask) {

    long backOffSeconds = Math.min(10 * (1L << aiTranslateBatchesImportInput.attempt()), 600);

    QuartzJobInfo<AiTranslateBatchesImportInput, AiTranslateBatchesImportOutput> quartzJobInfo =
        QuartzJobInfo.newBuilder(AiTranslateBatchesImportJob.class)
            .withInlineInput(false)
            .withInput(aiTranslateBatchesImportInput)
            .withScheduler(aiTranslateConfigurationProperties.getSchedulerName())
            .withTimeout(864000) // hardcoded 24h for now
            .withTriggerStartDate(
                JSR310Migration.dateTimeToDate(ZonedDateTime.now().plusSeconds(backOffSeconds)))
            .withParentId(
                currentTask
                    .getId()) // if running 24h, it will make record 144 sub-tasks. Might want to
            // reconsider
            .build();

    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  record TextUnitsByScreenshot(
      String groupId,
      String screenshotUUID,
      List<TextUnitDTOWithVariantComments> textUnitDTOWithVariantCommentsList) {}

  static Map<String, TextUnitsByScreenshot> groupTextUnitsByScreenshot(
      List<TextUnitDTOWithVariantComments> textUnitDTOWithVariantCommentsList) {
    Map<String, TextUnitsByScreenshot> groups = new LinkedHashMap<>();

    for (TextUnitDTOWithVariantComments textUnitDTOWithVariantComments :
        textUnitDTOWithVariantCommentsList) {

      TextUnitDTO textUnitDTO = textUnitDTOWithVariantComments.textUnitDTO();

      String screenshotUUID =
          AiTranslateScreenshotService.extractScreenshotUUID(textUnitDTO.getComment());
      String groupId =
          (screenshotUUID != null)
              ? screenshotUUID
              : textUnitDTO.getTmTextUnitVariantId() == null
                  ? textUnitDTO.getTmTextUnitId().toString()
                  : textUnitDTO.getTmTextUnitVariantId().toString();

      TextUnitsByScreenshot group =
          groups.computeIfAbsent(
              groupId, k -> new TextUnitsByScreenshot(k, screenshotUUID, new ArrayList<>()));

      group.textUnitDTOWithVariantCommentsList().add(textUnitDTOWithVariantComments);
    }

    return groups;
  }

  public void aiTranslate(AiTranslateInput aiTranslateInput, PollableTask currentTask)
      throws AiTranslateException {
    String mode = aiTranslateInput.useBatch() ? MODE_BATCH : MODE_NO_BATCH;
    String model = getModel(aiTranslateInput);
    Tags jobTags =
        metricTags(mode, aiTranslateInput.repositoryName(), model, LOCALE_ALL, HAS_SCREENSHOT_NA);
    incrementCounter("%s.jobs".formatted(METRIC_PREFIX), jobTags.and("result", "started"));

    try {
      if (aiTranslateInput.useBatch()) {
        aiTranslateBatch(aiTranslateInput, currentTask);
      } else {
        aiTranslateNoBatch(aiTranslateInput, currentTask);
      }
      incrementCounter("%s.jobs".formatted(METRIC_PREFIX), jobTags.and("result", "completed"));
    } catch (AiTranslateException | RuntimeException e) {
      incrementCounter("%s.jobs".formatted(METRIC_PREFIX), jobTags.and("result", "failed"));
      throw e;
    }
  }

  record TextextUnitsByScreenshotWithResponsesResponse(
      ResponsesRequest responsesRequest,
      TextUnitsByScreenshot textUnitsByScreenshot,
      CompletableFuture<ResponsesResponse> responsesResponseCompletableFuture) {}

  record TextUnitDTOWithResponsesResponse(
      ResponsesRequest responsesRequest,
      TextUnitDTO textUnitDTO,
      CompletableFuture<ResponsesResponse> responsesResponseCompletableFuture) {}

  public void aiTranslateNoBatch(AiTranslateInput aiTranslateInput, PollableTask currentTask) {
    Repository repository = getRepository(aiTranslateInput);

    logger.info("Start AI Translation (no batch) for repository: {}", repository.getName());

    Set<RepositoryLocale> filteredRepositoryLocales =
        getFilteredRepositoryLocales(aiTranslateInput, repository);

    AiTranslateRelatedStringsProvider relatedStringsProvider =
        new AiTranslateRelatedStringsProvider(
            assetTextUnitRepository,
            AiTranslateRelatedStringsProvider.Type.fromString(
                aiTranslateInput.relatedStringsType()));

    Stopwatch stopwatchForTotal = Stopwatch.createStarted();

    List<String> reportFilenames = new ArrayList<>();
    for (RepositoryLocale repositoryLocale : filteredRepositoryLocales) {
      String bcp47Tag = repositoryLocale.getLocale().getBcp47Tag();
      String model = getModel(aiTranslateInput);
      Tags localeTags =
          metricTags(MODE_NO_BATCH, repository.getName(), model, bcp47Tag, HAS_SCREENSHOT_NA);
      logger.info(
          "Start AI Translation (no batch) for repository: {} and locale: {}",
          repository.getName(),
          bcp47Tag);
      incrementCounter(
          "%s.localeRuns".formatted(METRIC_PREFIX), localeTags.and("result", "started"));

      Stopwatch stopwatchForLocale = Stopwatch.createStarted();
      try {
        List<TextUnitDTOWithVariantComments> textUnitDTOWithVariantCommentsList =
            getTextUnitDTOS(
                repository,
                aiTranslateInput.sourceTextMaxCountPerLocale(),
                aiTranslateInput.tmTextUnitIds(),
                repositoryLocale,
                StatusFilter.valueOf(aiTranslateInput.statusFilter()));

        if (textUnitDTOWithVariantCommentsList.isEmpty()) {
          logger.debug("Nothing to translate for locale: {}", bcp47Tag);
          incrementCounter(
              "%s.localeRuns".formatted(METRIC_PREFIX), localeTags.and("result", "skipped"));
          meterRegistry
              .timer("%s.localeDuration".formatted(METRIC_PREFIX), localeTags)
              .record(stopwatchForLocale.elapsed());
          continue;
        }

        incrementCounter(
            "%s.textUnits".formatted(METRIC_PREFIX),
            localeTags.and("result", "attempted"),
            textUnitDTOWithVariantCommentsList.size());

        GlossaryTrie glossaryTrie =
            getGlossaryTrieForLocale(
                bcp47Tag,
                aiTranslateInput.glossaryName(),
                aiTranslateInput.glossaryTermSource(),
                aiTranslateInput.glossaryTermSourceDescription(),
                aiTranslateInput.glossaryTermTarget(),
                aiTranslateInput.glossaryTermTargetDescription(),
                aiTranslateInput.glossaryTermDoNotTranslate(),
                aiTranslateInput.glossaryTermCaseSensitive());

        logger.info(
            "Translate (no batch) {} text units for repository: {} and locale: {}",
            textUnitDTOWithVariantCommentsList.size(),
            repository.getName(),
            bcp47Tag);

        AiTranslateType aiTranslateType =
            AiTranslateType.fromString(aiTranslateInput.translateType());
        String prompt = getPrompt(aiTranslateType.getPrompt(), aiTranslateInput.promptSuffix());
        Status importStatus = Status.valueOf(aiTranslateInput.importStatus());

        int groupedRequestCount = 0;
        int skippedTextUnitCount = 0;
        Set<Long> skippedTmTextUnitIds = new HashSet<>();

        List<TextextUnitsByScreenshotWithResponsesResponse>
            textUnitsByScreenshotWithResponsesResponseList = new ArrayList<>();

        for (TextUnitsByScreenshot textUnitsByScreenshot :
            groupTextUnitsByScreenshot(textUnitDTOWithVariantCommentsList).values()) {

          CompletionMultiTextUnitInput.Builder builder =
              CompletionMultiTextUnitInput.builder(bcp47Tag);
          int requestTextUnitCount = 0;
          int requestSourceCharCount = 0;

          for (TextUnitDTOWithVariantComments textUnitDTOWithVariantComments :
              textUnitsByScreenshot.textUnitDTOWithVariantCommentsList()) {

            TextUnitDTO textUnitDTO = textUnitDTOWithVariantComments.textUnitDTO();

            FoundGlossaryTerms glossaryTermsOrSkip =
                findGlossaryTermsOrSkip(
                    glossaryTrie,
                    aiTranslateInput.glossaryOnlyMatchedTextUnits(),
                    textUnitDTOWithVariantComments);

            if (glossaryTermsOrSkip.shouldSkip()) {
              skippedTextUnitCount++;
              skippedTmTextUnitIds.add(textUnitDTO.getTmTextUnitId());
              continue;
            }

            requestTextUnitCount++;
            requestSourceCharCount += safeLength(textUnitDTO.getSource());
            builder.addTextUnit(
                new TextUnit(
                    textUnitDTO.getTmTextUnitId(),
                    textUnitDTO.getSource(),
                    textUnitDTO.getComment(),
                    textUnitDTO.getTarget() == null
                        ? null
                        : new TextUnit.ExistingTarget(
                            textUnitDTO.getTarget(),
                            textUnitDTO.getTargetComment(),
                            !textUnitDTO.isIncludedInLocalizedFile(),
                            textUnitDTOWithVariantComments.tmTextUnitVariantComments().stream()
                                .filter(
                                    tmTextUnitVariantComment ->
                                        TMTextUnitVariantComment.Severity.ERROR.equals(
                                            tmTextUnitVariantComment.getSeverity()))
                                .map(TMTextUnitVariantComment::getContent)
                                .toList()),
                    glossaryTermsOrSkip.terms().stream()
                        .map(AiTranslateService::convertGlossaryTermForMulti)
                        .toList()));
          }

          CompletionMultiTextUnitInput completionMultiTextUnitInput = builder.build();

          String inputAsJsonString =
              objectMapper.writeValueAsStringUnchecked(completionMultiTextUnitInput);

          ResponsesRequest.Builder requestBuilder =
              ResponsesRequest.builder()
                  .model(model)
                  .instructions(prompt)
                  .addUserText(inputAsJsonString)
                  .addJsonSchema(aiTranslateType.getOutputJsonSchemaClass());

          boolean hasScreenshot = textUnitsByScreenshot.screenshotUUID() != null;
          Optional.ofNullable(textUnitsByScreenshot.screenshotUUID())
              .flatMap(aiTranslateScreenshotService::getImageBytes)
              .map(ImageBytes::toDataUrl)
              .ifPresent(requestBuilder::addUserImageUrl);

          ResponsesRequest responsesRequest = requestBuilder.build();
          Tags requestTags =
              metricTags(
                  MODE_NO_BATCH,
                  repository.getName(),
                  model,
                  bcp47Tag,
                  Boolean.toString(hasScreenshot));

          int timeout =
              resolveNoBatchTimeoutSeconds(
                  aiTranslateInput.timeoutSeconds(),
                  requestTextUnitCount,
                  requestSourceCharCount,
                  hasScreenshot);

          CompletableFuture<ResponsesResponse> responsesResponseCompletableFuture =
              openAIClientPool.submit(
                  openAIClient ->
                      openAIClient.getResponses(responsesRequest, Duration.ofSeconds(timeout)));

          incrementCounter("%s.groupedRequests".formatted(METRIC_PREFIX), requestTags);
          groupedRequestCount++;

          TextextUnitsByScreenshotWithResponsesResponse
              textextUnitsByScreenshotWithResponsesResponse =
                  new TextextUnitsByScreenshotWithResponsesResponse(
                      responsesRequest, textUnitsByScreenshot, responsesResponseCompletableFuture);
          textUnitsByScreenshotWithResponsesResponseList.add(
              textextUnitsByScreenshotWithResponsesResponse);
        }

        List<TextUnitDTOWithVariantCommentOrError> textUnitDTOWithVariantCommentOrErrors =
            textUnitsByScreenshotWithResponsesResponseList.stream()
                .flatMap(
                    textUnitsByScreenshotWithResponsesResponse -> {
                      ResponsesResponse responsesResponse;
                      Tags requestTags =
                          metricTags(
                              MODE_NO_BATCH,
                              repository.getName(),
                              model,
                              bcp47Tag,
                              Boolean.toString(
                                  textUnitsByScreenshotWithResponsesResponse
                                          .textUnitsByScreenshot()
                                          .screenshotUUID()
                                      != null));
                      try {
                        responsesResponse =
                            textUnitsByScreenshotWithResponsesResponse
                                .responsesResponseCompletableFuture()
                                .join();
                      } catch (Throwable t) {
                        String errorMessage =
                            "Error when getting the responsesResponse: %s"
                                .formatted(t.getMessage());
                        if (isTimeoutException(t)) {
                          incrementCounter("%s.timeouts".formatted(METRIC_PREFIX), requestTags);
                        }
                        logger.error(
                            errorMessage + ", skipping tmTextUnits: {}, locale: {}",
                            textUnitsByScreenshotWithResponsesResponse
                                .textUnitsByScreenshot()
                                .textUnitDTOWithVariantCommentsList
                                .stream()
                                .map(TextUnitDTOWithVariantComments::textUnitDTO)
                                .map(TextUnitDTO::getTmTextUnitId)
                                .toList(),
                            repositoryLocale.getLocale().getBcp47Tag(),
                            t);

                        return textUnitsByScreenshotWithResponsesResponse
                            .textUnitsByScreenshot()
                            .textUnitDTOWithVariantCommentsList
                            .stream()
                            .map(
                                textUnitDTOWithVariantComments ->
                                    new TextUnitDTOWithVariantCommentOrError(
                                        textUnitsByScreenshotWithResponsesResponse
                                            .responsesRequest(),
                                        null,
                                        new TextUnitDTOWithVariantComment(
                                            textUnitDTOWithVariantComments.textUnitDTO(), null),
                                        textUnitDTOWithVariantComments.textUnitDTO().getTarget(),
                                        errorMessage));
                      }

                      Object completionOutput;
                      try {
                        String completionOutputAsJson = responsesResponse.outputText();

                        completionOutput =
                            objectMapper.readValueUnchecked(
                                completionOutputAsJson, aiTranslateType.getOutputJsonSchemaClass());
                      } catch (Throwable t) {
                        String errorMessage =
                            "Error trying to parse the JSON completion output: %s"
                                .formatted(t.getMessage());
                        incrementCounter("%s.parseFailures".formatted(METRIC_PREFIX), requestTags);
                        logger.debug(errorMessage, t);

                        return textUnitsByScreenshotWithResponsesResponse
                            .textUnitsByScreenshot()
                            .textUnitDTOWithVariantCommentsList
                            .stream()
                            .map(
                                textUnitDTOWithVariantComments ->
                                    new TextUnitDTOWithVariantCommentOrError(
                                        textUnitsByScreenshotWithResponsesResponse
                                            .responsesRequest(),
                                        responsesResponse.id(),
                                        new TextUnitDTOWithVariantComment(
                                            textUnitDTOWithVariantComments.textUnitDTO(), null),
                                        textUnitDTOWithVariantComments.textUnitDTO().getTarget(),
                                        errorMessage));
                      }

                      return textUnitsByScreenshotWithResponsesResponse
                          .textUnitsByScreenshot()
                          .textUnitDTOWithVariantCommentsList()
                          .stream()
                          .map(
                              textUnitDTOWithVariantComments ->
                                  prepareForTextUnitDTOForImport(
                                      textUnitsByScreenshotWithResponsesResponse.responsesRequest(),
                                      responsesResponse.id(),
                                      aiTranslateType,
                                      importStatus,
                                      textUnitDTOWithVariantComments.textUnitDTO(),
                                      completionOutput));
                    })
                .toList();

        Duration elapsed = stopwatchForLocale.elapsed();

        final Map<Long, ImportResult> importResultByTmTextUnitId =
            aiTranslateInput.dryRun()
                ? new LinkedHashMap<>()
                : textUnitBatchImporterService
                    .importTextUnitsWithVariantComment(
                        textUnitDTOWithVariantCommentOrErrors.stream()
                            .filter(t -> t.error() == null)
                            .filter(t -> t.textUnitDTOWithVariantComment() != null)
                            .map(
                                TextUnitDTOWithVariantCommentOrError::textUnitDTOWithVariantComment)
                            .toList(),
                        TextUnitBatchImporterService.IntegrityChecksType
                            .KEEP_STATUS_IF_SAME_TARGET_AND_NOT_INCLUDED,
                        ALWAYS_IMPORT)
                    .stream()
                    .collect(
                        toMap(
                            importResult ->
                                importResult
                                    .addTMTextUnitCurrentVariantResult()
                                    .getTmTextUnitCurrentVariant()
                                    .getTmTextUnitVariant()
                                    .getTmTextUnit()
                                    .getId(),
                            Function.identity()));

        long successfulTextUnitCount =
            textUnitDTOWithVariantCommentOrErrors.stream()
                .filter(t -> t.textUnitDTOWithVariantComment() != null)
                .filter(
                    t ->
                        !skippedTmTextUnitIds.contains(
                            t.textUnitDTOWithVariantComment().textUnitDTO().getTmTextUnitId()))
                .filter(t -> t.error() == null)
                .count();
        long failedTextUnitCount =
            textUnitDTOWithVariantCommentOrErrors.stream()
                .filter(t -> t.textUnitDTOWithVariantComment() != null)
                .filter(
                    t ->
                        !skippedTmTextUnitIds.contains(
                            t.textUnitDTOWithVariantComment().textUnitDTO().getTmTextUnitId()))
                .filter(t -> t.error() != null)
                .count();
        long importedTextUnitCount = importResultByTmTextUnitId.size();

        incrementCounter(
            "%s.textUnits".formatted(METRIC_PREFIX),
            localeTags.and("result", "skipped"),
            skippedTextUnitCount);
        incrementCounter(
            "%s.textUnits".formatted(METRIC_PREFIX),
            localeTags.and("result", "successful"),
            successfulTextUnitCount);
        incrementCounter(
            "%s.textUnits".formatted(METRIC_PREFIX),
            localeTags.and("result", "failed"),
            failedTextUnitCount);
        incrementCounter(
            "%s.textUnits".formatted(METRIC_PREFIX),
            localeTags.and("result", "imported"),
            importedTextUnitCount);

        logger.info(
            "AI translate locale summary repository={}, locale={}, model={}, attemptedTextUnits={}, groupedRequests={}, successfulTextUnits={}, importedTextUnits={}, skippedTextUnits={}, failedTextUnits={}, duration={}",
            repository.getName(),
            bcp47Tag,
            model,
            textUnitDTOWithVariantCommentsList.size(),
            groupedRequestCount,
            successfulTextUnitCount,
            importedTextUnitCount,
            skippedTextUnitCount,
            failedTextUnitCount,
            elapsed);

        List<ImportReport.ImportReportLine> importReportLines =
            textUnitDTOWithVariantCommentOrErrors.stream()
                .map(
                    // in case of batch, it is possible that tu.textUnitDTOWithVariantComment() ==
                    // null so if sharing we need
                    // to make sure this works
                    tu -> {
                      TextUnitDTO textUnitDTO = tu.textUnitDTOWithVariantComment().textUnitDTO();
                      ImportResult importResult =
                          importResultByTmTextUnitId.get(textUnitDTO.getTmTextUnitId());

                      boolean tmTextUnitCurrentVariantUpdated;
                      if (aiTranslateInput.dryRun()) {
                        tmTextUnitCurrentVariantUpdated =
                            tu.oldTarget() == null
                                ? textUnitDTO.getTarget() != null
                                : !tu.oldTarget().equals(textUnitDTO.getTarget());
                      } else {
                        tmTextUnitCurrentVariantUpdated =
                            importResult != null
                                && importResult
                                    .addTMTextUnitCurrentVariantResult()
                                    .isTmTextUnitCurrentVariantUpdated();
                      }

                      return new ImportReport.ImportReportLine(
                          tu.responsesRequest(),
                          tu.completionId(),
                          textUnitDTO.getTmTextUnitId(),
                          repositoryLocale.getLocale().getBcp47Tag(),
                          textUnitDTO.getSource(),
                          tu.oldTarget(),
                          textUnitDTO
                              .getTmTextUnitVariantId(), // this should be the old target id, can be
                          // used to distinguish
                          textUnitDTO.getTarget(),
                          importResult == null
                              ? null
                              : importResult
                                  .addTMTextUnitCurrentVariantResult()
                                  .getTmTextUnitCurrentVariant()
                                  .getTmTextUnitVariant()
                                  .getId(),
                          tu.error(),
                          tmTextUnitCurrentVariantUpdated,
                          importResult == null
                              ? null
                              : importResult.tmTextUnitVariantComments().stream()
                                  .map(
                                      c ->
                                          new ImportReport.ImportReportLine.VariantComment(
                                              c.getSeverity().toString(),
                                              c.getType().toString(),
                                              c.getContent()))
                                  .toList());
                    })
                .toList();

        putReportContentLocale(currentTask, bcp47Tag, importReportLines, reportFilenames);
        incrementCounter(
            "%s.localeRuns".formatted(METRIC_PREFIX), localeTags.and("result", "completed"));
        meterRegistry
            .timer("%s.localeDuration".formatted(METRIC_PREFIX), localeTags)
            .record(elapsed);
      } catch (RuntimeException e) {
        incrementCounter(
            "%s.localeRuns".formatted(METRIC_PREFIX), localeTags.and("result", "failed"));
        meterRegistry
            .timer("%s.localeDuration".formatted(METRIC_PREFIX), localeTags)
            .record(stopwatchForLocale.elapsed());
        throw e;
      }
    }

    putReportContent(currentTask, reportFilenames);

    logger.info(
        "Done with AI Translation (no batch) for repository: {}, total time: {}",
        repository.getName(),
        stopwatchForTotal);
  }

  void putReportContent(PollableTask currentTask, List<String> reportFilenames) {
    logger.debug("Put report content for id: {}", currentTask.getId());
    structuredBlobStorage.put(
        AI_TRANSALATE_NO_BATCH_OUTPUT,
        getReportFilename(currentTask.getId()),
        objectMapper.writeValueAsStringUnchecked(new ReportContent(reportFilenames)),
        Retention.PERMANENT);
  }

  public record ReportContent(List<String> reportLocaleUrls) {}

  void putReportContentLocale(
      PollableTask currentTask,
      String bcp47Tag,
      List<ImportReport.ImportReportLine> importReportLines,
      List<String> reportFilenames) {
    logger.debug(
        "Put report locale content for id: {} and locale: {}", currentTask.getId(), bcp47Tag);
    String filename = getReportLocaleFilename(currentTask.getId(), bcp47Tag);
    structuredBlobStorage.put(
        AI_TRANSALATE_NO_BATCH_OUTPUT,
        filename,
        objectMapper.writeValueAsStringUnchecked(importReportLines),
        Retention.PERMANENT);
    reportFilenames.add(filename);
  }

  public ReportContent getReportContent(long pollableTaskId) {
    logger.debug("Get report content for id: {}", pollableTaskId);
    String reportContentAsJson =
        structuredBlobStorage
            .getString(AI_TRANSALATE_NO_BATCH_OUTPUT, getReportFilename(pollableTaskId))
            .get();
    return objectMapper.readValueUnchecked(reportContentAsJson, ReportContent.class);
  }

  public String getReportContentLocale(long pollableTaskId, String bcp47Tag) {
    logger.debug("Get report locale content for id: {} and locale: {}", pollableTaskId, bcp47Tag);
    return structuredBlobStorage
        .getString(AI_TRANSALATE_NO_BATCH_OUTPUT, getReportLocaleFilename(pollableTaskId, bcp47Tag))
        .get();
  }

  static String getReportFilename(long pollableTaskId) {
    return "%s/report".formatted(pollableTaskId);
  }

  static String getReportLocaleFilename(long pollableTaskId, String bcp47Tag) {
    return "%s/locale/%s".formatted(pollableTaskId, bcp47Tag);
  }

  record ImportReport(List<ImportReportLine> lines) {
    record ImportReportLine(
        ResponsesRequest responsesRequest,
        String completionId,
        long tmTexUnitId,
        String locale,
        String source,
        String oldTarget,
        Long oldTargetTmTextUnitVariantId,
        String newTarget,
        Long newTargetTmTextUnitVariantId,
        String error,
        boolean tmTextUnitCurrentVariantUpdated,
        List<VariantComment> variantComments) {
      record VariantComment(String severity, String type, String content) {}
    }
  }

  private GlossaryTrie getGlossaryTrieForLocale(
      String bcp47Tag,
      String glossaryName,
      String termSource,
      String termSourceDescription,
      String termTarget,
      String termTargetDescription,
      boolean termDoNotTranslate,
      boolean termCaseSensitive) {
    Stopwatch stopwatchForGlossary = Stopwatch.createStarted();
    GlossaryTrie glossaryTrie = null;
    if (glossaryName != null) {
      logger.debug("Loading the glossary: {} for locale: {}", glossaryName, bcp47Tag);
      glossaryTrie = glossaryService.loadGlossaryTrieForLocale(glossaryName, bcp47Tag);
      logger.info(
          "Loaded the glossary: {} for locale: {} in {}.",
          glossaryName,
          bcp47Tag,
          stopwatchForGlossary.elapsed());
    } else if (termSource != null) {
      logger.debug("Loading the glossary from term: {} for locale: {}", termSource, bcp47Tag);
      glossaryTrie = new GlossaryTrie();
      glossaryTrie.addTerm(
          new GlossaryService.GlossaryTerm(
              0L,
              termSource,
              termSource,
              termSourceDescription,
              termTarget,
              termTargetDescription,
              termDoNotTranslate,
              termCaseSensitive));
      logger.info(
          "Loaded the glossary from term: {} for locale: {} in {}.",
          termSource,
          bcp47Tag,
          stopwatchForGlossary.elapsed());
    } else {
      logger.info("No glossary to load for locale: {}", bcp47Tag);
    }
    return glossaryTrie;
  }

  private boolean isRetryableException(Throwable throwable) {
    Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
    return cause instanceof IOException || cause instanceof TimeoutException;
  }

  public void aiTranslateBatch(AiTranslateInput aiTranslateInput, PollableTask currentTask)
      throws AiTranslateException {

    try {
      AiTranslateLegacyBatchService.LegacyBatchCreationResult batchCreationResult =
          aiTranslateLegacyBatchService.createBatches(aiTranslateInput);

      PollableFuture<AiTranslateBatchesImportOutput> aiTranslateBatchesImportOutputPollableFuture =
          aiTranslateBatchesImportAsync(
              new AiTranslateBatchesImportInput(
                  batchCreationResult.createdBatches(),
                  batchCreationResult.skippedLocales(),
                  batchCreationResult.batchCreationErrors(),
                  List.of(),
                  Map.of(),
                  0,
                  aiTranslateInput.translateType(),
                  aiTranslateInput.importStatus()),
              currentTask);

      logger.info(
          "Schedule AiTranslateBatchesImportJob, id: {}",
          aiTranslateBatchesImportOutputPollableFuture.getPollableTask().getId());

    } catch (OpenAIClient.OpenAIClientResponseException openAIClientResponseException) {
      logger.error(
          "Failed to ai translate: %s".formatted(openAIClientResponseException),
          openAIClientResponseException);
      throw new AiTranslateException(openAIClientResponseException);
    }
  }

  private Set<RepositoryLocale> getFilteredRepositoryLocales(
      AiTranslateInput aiTranslateInput, Repository repository) {
    return repositoryService.getRepositoryLocalesWithoutRootLocale(repository).stream()
        .filter(
            rl ->
                aiTranslateInput.targetBcp47tags == null
                    || aiTranslateInput.targetBcp47tags.contains(rl.getLocale().getBcp47Tag()))
        .collect(Collectors.toSet());
  }

  record TextUnitDTOWithVariantCommentOrError(
      ResponsesRequest responsesRequest,
      String completionId,
      TextUnitDTOWithVariantComment textUnitDTOWithVariantComment,
      String oldTarget,
      String error) {}

  List<String> importBatch(
      RetrieveBatchResponse retrieveBatchResponse,
      AiTranslateType aiTranslateType,
      Status importStatus) {

    logger.info("Importing batch: {}", retrieveBatchResponse.id());

    String textUnitDTOsBlobId =
        retrieveBatchResponse.metadata().get(METADATA__TEXT_UNIT_DTOS__BLOB_ID);

    logger.info("Trying to load textUnitDTOs from blob: {}", textUnitDTOsBlobId);
    AiTranslateBlobStorage aiTranslateBlobStorage =
        structuredBlobStorage
            .getString(AI_TRANSLATE_WS, textUnitDTOsBlobId)
            .map(s -> objectMapper.readValueUnchecked(s, AiTranslateBlobStorage.class))
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "There must be an entry for textUnitDTOsBlobId: " + textUnitDTOsBlobId));

    Map<Long, TextUnitDTO> tmTextUnitIdToTextUnitDTOs =
        aiTranslateBlobStorage.textUnitDTOWithVariantComments().stream()
            .collect(
                toMap(
                    t -> t.textUnitDTO().getTmTextUnitId(),
                    TextUnitDTOWithVariantComments::textUnitDTO));

    DownloadFileContentResponse downloadFileContentResponse =
        getOpenAIClient()
            .downloadFileContent(
                new DownloadFileContentRequest(retrieveBatchResponse.outputFileId()));

    List<TextUnitDTOWithVariantCommentOrError> forImport =
        downloadFileContentResponse
            .content()
            .lines()
            .map(
                line -> {
                  ChatCompletionResponseBatchFileLine chatCompletionResponseBatchFileLine =
                      objectMapper.readValueUnchecked(
                          line, ChatCompletionResponseBatchFileLine.class);

                  if (chatCompletionResponseBatchFileLine.response().statusCode() != 200) {
                    String errorMessage =
                        "Response batch file line failed: " + chatCompletionResponseBatchFileLine;
                    logger.debug(errorMessage);
                    return new TextUnitDTOWithVariantCommentOrError(
                        null, null, null, null, errorMessage);
                  }

                  String completionOutputAsJson =
                      chatCompletionResponseBatchFileLine
                          .response()
                          .chatCompletionsResponse()
                          .choices()
                          .getFirst()
                          .message()
                          .content();

                  TextUnitDTO textUnitDTO =
                      tmTextUnitIdToTextUnitDTOs.get(
                          Long.valueOf(chatCompletionResponseBatchFileLine.customId()));

                  Object completionOutput;
                  try {
                    completionOutput =
                        objectMapper.readValueUnchecked(
                            completionOutputAsJson, aiTranslateType.getOutputJsonSchemaClass());
                  } catch (UncheckedIOException e) {
                    String errorMessage =
                        "Error trying to parse the JSON completion output: %s"
                            .formatted(e.getMessage());
                    logger.debug(errorMessage, e);
                    return new TextUnitDTOWithVariantCommentOrError(
                        null,
                        null,
                        new TextUnitDTOWithVariantComment(textUnitDTO, null),
                        textUnitDTO.getTarget(),
                        errorMessage);
                  }

                  return prepareForTextUnitDTOForImport(
                      null,
                      chatCompletionResponseBatchFileLine.id(),
                      aiTranslateType,
                      importStatus,
                      textUnitDTO,
                      completionOutput);
                })
            .toList();

    textUnitBatchImporterService.importTextUnitsWithVariantComment(
        forImport.stream()
            .filter(t -> t.error() == null)
            .map(TextUnitDTOWithVariantCommentOrError::textUnitDTOWithVariantComment)
            .toList(),
        TextUnitBatchImporterService.IntegrityChecksType
            .KEEP_STATUS_IF_SAME_TARGET_AND_NOT_INCLUDED,
        ALWAYS_IMPORT);

    return forImport.stream()
        .filter(t -> t.error() != null)
        .map(TextUnitDTOWithVariantCommentOrError::error)
        .toList();
  }

  private static TextUnitDTOWithVariantCommentOrError prepareForTextUnitDTOForImport(
      ResponsesRequest responsesRequest,
      String completionId,
      AiTranslateType aiTranslateType,
      Status importStatus,
      TextUnitDTO textUnitDTO,
      Object completionOutput) {

    String oldTarget = textUnitDTO.getTarget();

    AiTranslateType.TargetWithMetadata targetWithMetadata =
        aiTranslateType.getTargetWithMetadata(textUnitDTO.getTmTextUnitId(), completionOutput);

    if (targetWithMetadata == null) {
      logger.error(
          "Cannot find the target for tmTextUnitId: {} is missing in map: {}",
          textUnitDTO.getTmTextUnitId(),
          completionOutput);
      return new TextUnitDTOWithVariantCommentOrError(
          responsesRequest,
          completionId,
          new TextUnitDTOWithVariantComment(textUnitDTO, null),
          oldTarget,
          "Cannot find the target for tmTextUnitId: %s".formatted(textUnitDTO.getTmTextUnitId()));
    }

    textUnitDTO.setStatus(importStatus);
    String newTarget =
        AiTranslateTargetAutoFix.fixTarget(textUnitDTO.getSource(), targetWithMetadata.target());
    textUnitDTO.setTarget(newTarget);
    // Reset target comment if this translation has its own comment. Do not carry over the previous
    // comment.
    textUnitDTO.setTargetComment(null);

    TMTextUnitVariantComment tmTextUnitVariantComment = new TMTextUnitVariantComment();
    tmTextUnitVariantComment.setType(TMTextUnitVariantComment.Type.AI_TRANSLATE);
    tmTextUnitVariantComment.setSeverity(TMTextUnitVariantComment.Severity.INFO);
    tmTextUnitVariantComment.setContent(targetWithMetadata.targetComment());

    return new TextUnitDTOWithVariantCommentOrError(
        responsesRequest,
        completionId,
        new TextUnitDTOWithVariantComment(textUnitDTO, tmTextUnitVariantComment),
        oldTarget,
        null);
  }

  @Pollable(message = "AiTranslateService Retry import for job id: {id}")
  public PollableFuture<Void> retryImport(
      @MsgArg(name = "id") long childPollableTaskId,
      boolean resume,
      @InjectCurrentTask PollableTask currentTask) {
    PollableTask childPollableTask = pollableTaskService.getPollableTask(childPollableTaskId);

    AiTranslateBatchesImportInput aiTranslateBatchesImportInput =
        pollableTaskBlobStorage.getInput(childPollableTaskId, AiTranslateBatchesImportInput.class);

    AiTranslateBatchesImportInput aiTranslateBatchesImportInput0 =
        new AiTranslateBatchesImportInput(
            aiTranslateBatchesImportInput.createBatchResponses(),
            aiTranslateBatchesImportInput.skippedLocales(),
            aiTranslateBatchesImportInput.batchCreationErrors(),
            resume ? aiTranslateBatchesImportInput.processed() : List.of(),
            resume ? aiTranslateBatchesImportInput.failedImport() : Map.of(),
            0,
            aiTranslateBatchesImportInput.translateType(),
            aiTranslateBatchesImportInput.importStatus());

    PollableFuture<AiTranslateBatchesImportOutput> aiTranslateBatchesImportOutputPollableFuture =
        aiTranslateBatchesImportAsync(aiTranslateBatchesImportInput0, currentTask);
    logger.info(
        "[task id: {}] Retrying to import from child id: {} (parent: {}), new job created with pollable task id: {}",
        currentTask.getId(),
        childPollableTask.getId(),
        childPollableTask.getParentTask().getId(),
        aiTranslateBatchesImportOutputPollableFuture.getPollableTask().getId());

    return new PollableFutureTaskResult<>();
  }

  private List<TextUnitDTOWithVariantComments> getTextUnitDTOS(
      Repository repository,
      int sourceTextMaxCountPerLocale,
      List<Long> tmTextUnitIds,
      RepositoryLocale repositoryLocale,
      StatusFilter statusFilter) {
    logger.debug(
        "Get untranslated strings for locale: '{}' in repository: '{}'",
        repositoryLocale.getLocale().getBcp47Tag(),
        repository.getName());

    TextUnitSearcherParameters textUnitSearcherParameters = new TextUnitSearcherParameters();
    textUnitSearcherParameters.setRepositoryIds(repository.getId());
    textUnitSearcherParameters.setStatusFilter(statusFilter);
    textUnitSearcherParameters.setLocaleId(repositoryLocale.getLocale().getId());
    textUnitSearcherParameters.setUsedFilter(UsedFilter.USED);

    if (tmTextUnitIds != null) {
      logger.debug(
          "Using tmTextUnitIds: {} for ai translate repository: {}",
          tmTextUnitIds,
          repository.getName());
      textUnitSearcherParameters.setTmTextUnitIds(tmTextUnitIds);
    } else {
      textUnitSearcherParameters.setLimit(sourceTextMaxCountPerLocale);
    }

    List<TextUnitDTO> textUnitDTOS = textUnitSearcher.search(textUnitSearcherParameters);
    List<Long> tmTextUnitVariantIds =
        textUnitDTOS.stream()
            .map(TextUnitDTO::getTmTextUnitVariantId)
            .filter(Objects::nonNull)
            .toList();

    logger.debug("Getting TMTextUnitVariant for: {} text units", tmTextUnitVariantIds.size());
    Map<Long, Set<TMTextUnitVariantComment>> variantMap =
        tmTextUnitVariantRepository.findAllByIdIn(tmTextUnitVariantIds).stream()
            .collect(
                toMap(TMTextUnitVariant::getId, TMTextUnitVariant::getTmTextUnitVariantComments));

    List<TextUnitDTOWithVariantComments> textUnitDTOWithVariantComments =
        textUnitDTOS.stream()
            .map(
                textUnitDTO ->
                    new TextUnitDTOWithVariantComments(
                        textUnitDTO, variantMap.get(textUnitDTO.getTmTextUnitVariantId())))
            .toList();

    return textUnitDTOWithVariantComments;
  }

  record TextUnitDTOWithVariantComments(
      TextUnitDTO textUnitDTO, Set<TMTextUnitVariantComment> tmTextUnitVariantComments) {}

  record FoundGlossaryTerms(Set<GlossaryService.GlossaryTerm> terms, boolean shouldSkip) {}

  FoundGlossaryTerms findGlossaryTermsOrSkip(
      GlossaryTrie glossaryTrie,
      boolean glossaryOnlyMatchedTextUnits,
      TextUnitDTOWithVariantComments textUnitDTOWithVariantComments) {

    Stopwatch stopWatchFindTerm = Stopwatch.createStarted();

    Set<GlossaryService.GlossaryTerm> terms = Set.of();
    boolean shouldSkip = false;

    if (glossaryTrie != null) {
      terms = glossaryTrie.findTerms(textUnitDTOWithVariantComments.textUnitDTO().getSource());
      if (terms.isEmpty() && glossaryOnlyMatchedTextUnits) {
        logger.debug(
            "Skipping text unit because it contains no glossary term: {}",
            textUnitDTOWithVariantComments.textUnitDTO().getTmTextUnitId());
        shouldSkip = true;
      } else {
        logger.debug(
            "Found glossary terms for text unit {}: {}",
            textUnitDTOWithVariantComments.textUnitDTO().getTmTextUnitId(),
            terms);
      }
    }
    logger.debug("Time spent searching for terms: {}", stopWatchFindTerm);

    return new FoundGlossaryTerms(terms, shouldSkip);
  }

  CompletionInput getCompletionInput(
      TextUnitDTOWithVariantComments textUnitDTOWithVariantComments,
      AiTranslateRelatedStringsProvider relatedStringsProvider,
      Set<GlossaryService.GlossaryTerm> glossaryTerms) {
    TextUnitDTO textUnitDTO = textUnitDTOWithVariantComments.textUnitDTO();

    CompletionInput completionInput =
        new CompletionInput(
            textUnitDTO.getTargetLocale(),
            textUnitDTO.getSource(),
            textUnitDTO.getComment(),
            textUnitDTO.getTarget() == null
                ? null
                : new ExistingTarget(
                    textUnitDTO.getTarget(),
                    getTargetComment(textUnitDTO),
                    !textUnitDTO.isIncludedInLocalizedFile(),
                    textUnitDTOWithVariantComments.tmTextUnitVariantComments().stream()
                        .filter(
                            tmTextUnitVariantComment ->
                                TMTextUnitVariantComment.Severity.ERROR.equals(
                                    tmTextUnitVariantComment.getSeverity()))
                        .map(TMTextUnitVariantComment::getContent)
                        .toList()),
            glossaryTerms.stream().map(gt -> convertGlossaryTerm(gt)).toList(),
            relatedStringsProvider.getRelatedStrings(textUnitDTO));
    return completionInput;
  }

  private static CompletionInput.GlossaryTerm convertGlossaryTerm(GlossaryService.GlossaryTerm gt) {

    String target = gt.doNotTranslate() && gt.target() == null ? gt.source() : gt.target();

    return new CompletionInput.GlossaryTerm(gt.source(), gt.comment(), target, gt.targetComment());
  }

  private static CompletionMultiTextUnitInput.TextUnit.GlossaryTerm convertGlossaryTermForMulti(
      GlossaryService.GlossaryTerm gt) {

    String target = gt.doNotTranslate() && gt.target() == null ? gt.source() : gt.target();

    return new CompletionMultiTextUnitInput.TextUnit.GlossaryTerm(
        gt.source(), gt.comment(), target, gt.targetComment());
  }

  // TODO(ja) duplicated
  RetrieveBatchResponse retrieveBatchWithRetry(CreateBatchResponse batch) {

    return Mono.fromCallable(
            () -> getOpenAIClient().retrieveBatch(new RetrieveBatchRequest(batch.id())))
        .retryWhen(
            retryBackoffSpec.doBeforeRetry(
                doBeforeRetry -> {
                  incrementCounter(
                      "%s.retries".formatted(METRIC_PREFIX),
                      metricTags(
                          MODE_BATCH, TAG_UNKNOWN, TAG_UNKNOWN, LOCALE_ALL, HAS_SCREENSHOT_NA));
                  logger.info("Retrying retrieving batch: {}", batch.id());
                }))
        .doOnError(
            throwable -> new RuntimeException("Failed to retrieve batch: " + batch.id(), throwable))
        .block();
  }

  record AiTranslateBlobStorage(
      List<TextUnitDTOWithVariantComments> textUnitDTOWithVariantComments) {}

  static String getPrompt(String prompt, String promptSuffix) {
    return promptSuffix == null ? prompt : "%s %s".formatted(prompt, promptSuffix);
  }

  static String getTargetComment(TextUnitDTO textUnitDTO) {
    String targetComment = textUnitDTO.getTargetComment();

    if ("ai-translate".equals(targetComment)) {
      targetComment = null;
    }

    return targetComment;
  }

  private Repository getRepository(AiTranslateInput aiTranslateInput) {
    Repository repository = repositoryRepository.findByName(aiTranslateInput.repositoryName());

    if (repository == null) {
      throw new RepositoryNameNotFoundException(
          String.format(
              "Repository with name '%s' can not be found!", aiTranslateInput.repositoryName()));
    }
    return repository;
  }

  private String getModel(AiTranslateInput aiTranslateInput) {
    return aiTranslateInput.useModel() != null
        ? aiTranslateInput.useModel()
        : aiTranslateConfigurationProperties.getModelName();
  }

  /**
   * Typical configuration for the ObjectMapper needed by this class.
   *
   * <p>The ObjectMapper must not use indentation else Jsonl serialization will fail.
   */
  public static void configureObjectMapper(ObjectMapper objectMapper) {
    objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
    objectMapper.registerModule(new JavaTimeModule());
  }

  OpenAIClient getOpenAIClient() {
    if (openAIClient == null) {
      String msg =
          "OpenAI client is not configured for AiTranslateService. Ensure that the OpenAI API key is provided in the configuration (qualifier='aiTranslate').";
      logger.error(msg);
      throw new RuntimeException(msg);
    }
    return openAIClient;
  }

  public static class AiTranslateException extends Exception {
    public AiTranslateException(Throwable cause) {
      super(cause);
    }
  }

  private void incrementCounter(String metricName, Tags tags) {
    meterRegistry.counter(metricName, tags).increment();
  }

  private void incrementCounter(String metricName, Tags tags, double amount) {
    if (amount <= 0) {
      return;
    }
    meterRegistry.counter(metricName, tags).increment(amount);
  }

  private Tags metricTags(
      String mode, String repositoryName, String model, String locale, String hasScreenshot) {
    return Tags.of(
        "mode",
        sanitizeTagValue(mode),
        "repository",
        sanitizeTagValue(repositoryName),
        "model",
        sanitizeTagValue(model),
        "locale",
        sanitizeTagValue(locale),
        "hasScreenshot",
        sanitizeTagValue(hasScreenshot));
  }

  private String sanitizeTagValue(String value) {
    if (value == null || value.isBlank()) {
      return TAG_UNKNOWN;
    }
    return value;
  }

  private boolean isTimeoutException(Throwable throwable) {
    Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
    return cause instanceof TimeoutException;
  }

  private int resolveNoBatchTimeoutSeconds(
      Integer overrideTimeoutSeconds,
      int requestTextUnitCount,
      int requestSourceCharCount,
      boolean hasScreenshot) {
    if (overrideTimeoutSeconds != null) {
      return overrideTimeoutSeconds;
    }

    AiTranslateConfigurationProperties.NoBatchProperties.TimeoutProperties timeoutProperties =
        aiTranslateConfigurationProperties.getNoBatch().getTimeout();
    int baseTimeout = timeoutProperties.getBaseSeconds();
    int additionalTextUnitTimeout =
        Math.max(0, requestTextUnitCount - 1) * timeoutProperties.getPerAdditionalTextUnitSeconds();
    int sourceCharTimeout =
        ceilDiv(requestSourceCharCount, 1000) * timeoutProperties.getPer1000SourceCharsSeconds();
    int screenshotTimeout = hasScreenshot ? timeoutProperties.getScreenshotPenaltySeconds() : 0;

    int timeout = baseTimeout + additionalTextUnitTimeout + sourceCharTimeout + screenshotTimeout;

    int minTimeout = timeoutProperties.getMinSeconds();
    int maxTimeout = timeoutProperties.getMaxSeconds();
    if (maxTimeout > 0) {
      timeout = Math.min(timeout, maxTimeout);
    }
    if (minTimeout > 0) {
      timeout = Math.max(timeout, minTimeout);
    }

    return timeout;
  }

  private int ceilDiv(int dividend, int divisor) {
    if (dividend <= 0 || divisor <= 0) {
      return 0;
    }
    return (dividend + divisor - 1) / divisor;
  }

  private int safeLength(String value) {
    return value == null ? 0 : value.length();
  }
}
