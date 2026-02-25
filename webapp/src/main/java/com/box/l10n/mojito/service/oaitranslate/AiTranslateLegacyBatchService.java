package com.box.l10n.mojito.service.oaitranslate;

import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest;
import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.JsonFormat.JsonSchema.createJsonSchema;
import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.SystemMessage.systemMessageBuilder;
import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.UserMessage.userMessageBuilder;
import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.chatCompletionsRequest;
import static com.box.l10n.mojito.openai.OpenAIClient.CreateBatchRequest.forChatCompletion;
import static com.box.l10n.mojito.openai.OpenAIClient.TemperatureHelper.getTemperatureForReasoningModels;
import static com.box.l10n.mojito.openai.OpenAIClient.UploadFileRequest;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.CreateBatchResponse;
import com.box.l10n.mojito.openai.OpenAIClient.RequestBatchFileLine;
import com.box.l10n.mojito.openai.OpenAIClient.UploadFileResponse;
import com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitRepository;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateType.CompletionInput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateType.CompletionInput.ExistingTarget;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTrie;
import com.box.l10n.mojito.service.repository.RepositoryNameNotFoundException;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Stopwatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class AiTranslateLegacyBatchService {

  private static final Logger logger = LoggerFactory.getLogger(AiTranslateLegacyBatchService.class);

  private final TextUnitSearcher textUnitSearcher;
  private final RepositoryRepository repositoryRepository;
  private final RepositoryService repositoryService;
  private final StructuredBlobStorage structuredBlobStorage;
  private final AiTranslateConfigurationProperties aiTranslateConfigurationProperties;
  private final OpenAIClient openAIClient;
  private final ObjectMapper objectMapper;
  private final AssetTextUnitRepository assetTextUnitRepository;
  private final TMTextUnitVariantRepository tmTextUnitVariantRepository;
  private final GlossaryService glossaryService;

  AiTranslateLegacyBatchService(
      TextUnitSearcher textUnitSearcher,
      RepositoryRepository repositoryRepository,
      RepositoryService repositoryService,
      StructuredBlobStorage structuredBlobStorage,
      AiTranslateConfigurationProperties aiTranslateConfigurationProperties,
      @Qualifier("AiTranslate") @Autowired(required = false) OpenAIClient openAIClient,
      @Qualifier("AiTranslate") ObjectMapper objectMapper,
      AssetTextUnitRepository assetTextUnitRepository,
      TMTextUnitVariantRepository tmTextUnitVariantRepository,
      GlossaryService glossaryService) {
    this.textUnitSearcher = textUnitSearcher;
    this.repositoryRepository = repositoryRepository;
    this.repositoryService = repositoryService;
    this.structuredBlobStorage = structuredBlobStorage;
    this.aiTranslateConfigurationProperties = aiTranslateConfigurationProperties;
    this.openAIClient = openAIClient;
    this.objectMapper = objectMapper;
    this.assetTextUnitRepository = assetTextUnitRepository;
    this.tmTextUnitVariantRepository = tmTextUnitVariantRepository;
    this.glossaryService = glossaryService;
  }

  record LegacyBatchCreationResult(
      List<CreateBatchResponse> createdBatches,
      List<String> skippedLocales,
      List<String> batchCreationErrors) {}

  LegacyBatchCreationResult createBatches(AiTranslateService.AiTranslateInput aiTranslateInput) {
    Repository repository = getRepository(aiTranslateInput);

    logger.debug("Start legacy batch AI translation for repository: {}", repository.getName());

    Set<RepositoryLocale> repositoryLocalesWithoutRootLocale =
        getFilteredRepositoryLocales(aiTranslateInput, repository);

    logger.debug("Create legacy batches for repository: {}", repository.getName());

    List<CreateBatchResponse> createdBatches = new ArrayList<>();
    List<String> batchCreationErrors = new ArrayList<>();
    List<String> skippedLocales = new ArrayList<>();

    AiTranslateRelatedStringsProvider relatedStringsProvider =
        new AiTranslateRelatedStringsProvider(
            assetTextUnitRepository,
            AiTranslateRelatedStringsProvider.Type.fromString(
                aiTranslateInput.relatedStringsType()));

    for (RepositoryLocale repositoryLocale : repositoryLocalesWithoutRootLocale) {
      try {
        CreateBatchResponse createBatchResponse =
            createBatchForRepositoryLocale(
                repositoryLocale,
                repository,
                aiTranslateInput.sourceTextMaxCountPerLocale(),
                getModel(aiTranslateInput),
                aiTranslateInput.tmTextUnitIds(),
                aiTranslateInput.promptSuffix(),
                StatusFilter.valueOf(aiTranslateInput.statusFilter()),
                AiTranslateType.fromString(aiTranslateInput.translateType()),
                relatedStringsProvider,
                aiTranslateInput.glossaryName(),
                aiTranslateInput.glossaryTermSource(),
                aiTranslateInput.glossaryTermSourceDescription(),
                aiTranslateInput.glossaryTermTarget(),
                aiTranslateInput.glossaryTermTargetDescription(),
                aiTranslateInput.glossaryTermDoNotTranslate(),
                aiTranslateInput.glossaryTermCaseSensitive(),
                aiTranslateInput.glossaryOnlyMatchedTextUnits());

        if (createBatchResponse != null) {
          createdBatches.add(createBatchResponse);
        } else {
          skippedLocales.add(repositoryLocale.getLocale().getBcp47Tag());
        }
      } catch (Throwable t) {
        String errorMessage =
            "Can't create batch for locale: %s. Error: %s"
                .formatted(repositoryLocale.getLocale().getBcp47Tag(), t.getMessage());
        logger.error(errorMessage, t);
        batchCreationErrors.add(errorMessage);
      }
    }

    return new LegacyBatchCreationResult(createdBatches, skippedLocales, batchCreationErrors);
  }

  private CreateBatchResponse createBatchForRepositoryLocale(
      RepositoryLocale repositoryLocale,
      Repository repository,
      int sourceTextMaxCountPerLocale,
      String model,
      List<Long> tmTextUnitIds,
      String promptSuffix,
      StatusFilter statusFilter,
      AiTranslateType aiTranslateType,
      AiTranslateRelatedStringsProvider relatedStringsProvider,
      String glossaryName,
      String glossaryTermSource,
      String glossaryTermSourceDescription,
      String glossaryTermTarget,
      String glossaryTermTargetDescription,
      boolean glossaryTermDoNotTranslate,
      boolean glossaryTermCaseSensitive,
      boolean glossaryOnlyMatchedTextUnits) {

    List<AiTranslateService.TextUnitDTOWithVariantComments> textUnitDTOWithVariantCommentsList =
        getTextUnitDTOS(
            repository, sourceTextMaxCountPerLocale, tmTextUnitIds, repositoryLocale, statusFilter);

    GlossaryTrie glossaryTrie =
        getGlossaryTrieForLocale(
            repositoryLocale.getLocale().getBcp47Tag(),
            glossaryName,
            glossaryTermSource,
            glossaryTermSourceDescription,
            glossaryTermTarget,
            glossaryTermTargetDescription,
            glossaryTermDoNotTranslate,
            glossaryTermCaseSensitive);

    CreateBatchResponse createBatchResponse = null;
    if (textUnitDTOWithVariantCommentsList.isEmpty()) {
      logger.debug("Nothing to translate, don't create a legacy batch");
    } else {
      logger.debug("Save the TextUnitDTOs in blob storage for later batch import");
      String batchId =
          "%s_%s".formatted(repositoryLocale.getLocale().getBcp47Tag(), UUID.randomUUID());
      structuredBlobStorage.put(
          StructuredBlobStorage.Prefix.AI_TRANSLATE_WS,
          batchId,
          objectMapper.writeValueAsStringUnchecked(
              new AiTranslateService.AiTranslateBlobStorage(textUnitDTOWithVariantCommentsList)),
          Retention.MIN_1_DAY);

      logger.debug("Generate the legacy batch file content");
      String batchFileContent =
          generateBatchFileContent(
              textUnitDTOWithVariantCommentsList,
              model,
              promptSuffix,
              aiTranslateType,
              relatedStringsProvider,
              glossaryTrie,
              glossaryOnlyMatchedTextUnits);

      UploadFileResponse uploadFileResponse =
          getOpenAIClient()
              .uploadFile(
                  UploadFileRequest.forBatch("%s.jsonl".formatted(batchId), batchFileContent));

      logger.debug("Create the legacy batch using file: {}", uploadFileResponse);
      createBatchResponse =
          getOpenAIClient()
              .createBatch(
                  forChatCompletion(
                      uploadFileResponse.id(),
                      Map.of(AiTranslateService.METADATA__TEXT_UNIT_DTOS__BLOB_ID, batchId)));
    }

    logger.info(
        "Created legacy batch for locale: {} with {} text units",
        repositoryLocale.getLocale().getBcp47Tag(),
        textUnitDTOWithVariantCommentsList.size());

    return createBatchResponse;
  }

  private List<AiTranslateService.TextUnitDTOWithVariantComments> getTextUnitDTOS(
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

    return textUnitDTOS.stream()
        .map(
            textUnitDTO ->
                new AiTranslateService.TextUnitDTOWithVariantComments(
                    textUnitDTO, variantMap.get(textUnitDTO.getTmTextUnitVariantId())))
        .toList();
  }

  private String generateBatchFileContent(
      List<AiTranslateService.TextUnitDTOWithVariantComments>
          textUnitDTOSUnitDTOWithVariantComments,
      String model,
      String promptPrefix,
      AiTranslateType aiTranslateType,
      AiTranslateRelatedStringsProvider relatedStringsProvider,
      GlossaryTrie glossaryTrie,
      boolean glossaryOnlyMatchedTextUnits) {

    return textUnitDTOSUnitDTOWithVariantComments.stream()
        .map(
            textUnitDTOWithVariantComments -> {
              TextUnitDTO textUnitDTO = textUnitDTOWithVariantComments.textUnitDTO();

              AiTranslateService.FoundGlossaryTerms foundGlossaryTerms =
                  findGlossaryTermsOrSkip(
                      glossaryTrie, glossaryOnlyMatchedTextUnits, textUnitDTOWithVariantComments);

              if (foundGlossaryTerms.shouldSkip()) {
                return null;
              }

              CompletionInput completionInput =
                  getCompletionInput(
                      textUnitDTOWithVariantComments,
                      relatedStringsProvider,
                      foundGlossaryTerms.terms());

              ChatCompletionsRequest chatCompletionsRequest =
                  getChatCompletionsRequest(
                      model,
                      AiTranslateService.getPrompt(aiTranslateType.getPrompt(), promptPrefix),
                      completionInput,
                      aiTranslateType);

              return RequestBatchFileLine.forChatCompletion(
                  textUnitDTO.getTmTextUnitId().toString(), chatCompletionsRequest);
            })
        .filter(Objects::nonNull)
        .map(objectMapper::writeValueAsStringUnchecked)
        .collect(joining("\n"));
  }

  private AiTranslateService.FoundGlossaryTerms findGlossaryTermsOrSkip(
      GlossaryTrie glossaryTrie,
      boolean glossaryOnlyMatchedTextUnits,
      AiTranslateService.TextUnitDTOWithVariantComments textUnitDTOWithVariantComments) {

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

    return new AiTranslateService.FoundGlossaryTerms(terms, shouldSkip);
  }

  private CompletionInput getCompletionInput(
      AiTranslateService.TextUnitDTOWithVariantComments textUnitDTOWithVariantComments,
      AiTranslateRelatedStringsProvider relatedStringsProvider,
      Set<GlossaryService.GlossaryTerm> glossaryTerms) {
    TextUnitDTO textUnitDTO = textUnitDTOWithVariantComments.textUnitDTO();

    return new CompletionInput(
        textUnitDTO.getTargetLocale(),
        textUnitDTO.getSource(),
        textUnitDTO.getComment(),
        textUnitDTO.getTarget() == null
            ? null
            : new ExistingTarget(
                textUnitDTO.getTarget(),
                AiTranslateService.getTargetComment(textUnitDTO),
                !textUnitDTO.isIncludedInLocalizedFile(),
                textUnitDTOWithVariantComments.tmTextUnitVariantComments().stream()
                    .filter(
                        tmTextUnitVariantComment ->
                            TMTextUnitVariantComment.Severity.ERROR.equals(
                                tmTextUnitVariantComment.getSeverity()))
                    .map(TMTextUnitVariantComment::getContent)
                    .toList()),
        glossaryTerms.stream().map(AiTranslateLegacyBatchService::convertGlossaryTerm).toList(),
        relatedStringsProvider.getRelatedStrings(textUnitDTO));
  }

  private static CompletionInput.GlossaryTerm convertGlossaryTerm(GlossaryService.GlossaryTerm gt) {
    String target = gt.doNotTranslate() && gt.target() == null ? gt.source() : gt.target();
    return new CompletionInput.GlossaryTerm(gt.source(), gt.comment(), target, gt.targetComment());
  }

  private ChatCompletionsRequest getChatCompletionsRequest(
      String model,
      String prompt,
      CompletionInput completionInput,
      AiTranslateType aiTranslateType) {
    String inputAsJsonString = objectMapper.writeValueAsStringUnchecked(completionInput);
    ObjectNode jsonSchema = createJsonSchema(aiTranslateType.getOutputJsonSchemaClass());

    return chatCompletionsRequest()
        .model(model)
        .maxCompletionTokens(AiTranslateService.MAX_COMPLETION_TOKENS)
        .temperature(getTemperatureForReasoningModels(model))
        .messages(
            List.of(
                systemMessageBuilder().content(prompt).build(),
                userMessageBuilder().content(inputAsJsonString).build()))
        .responseFormat(
            new ChatCompletionsRequest.JsonFormat(
                "json_schema",
                new ChatCompletionsRequest.JsonFormat.JsonSchema(
                    true, "request_json_format", jsonSchema)))
        .build();
  }

  private Set<RepositoryLocale> getFilteredRepositoryLocales(
      AiTranslateService.AiTranslateInput aiTranslateInput, Repository repository) {
    return repositoryService.getRepositoryLocalesWithoutRootLocale(repository).stream()
        .filter(
            rl ->
                aiTranslateInput.targetBcp47tags() == null
                    || aiTranslateInput.targetBcp47tags().contains(rl.getLocale().getBcp47Tag()))
        .collect(Collectors.toSet());
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

  private Repository getRepository(AiTranslateService.AiTranslateInput aiTranslateInput) {
    Repository repository = repositoryRepository.findByName(aiTranslateInput.repositoryName());

    if (repository == null) {
      throw new RepositoryNameNotFoundException(
          String.format(
              "Repository with name '%s' can not be found!", aiTranslateInput.repositoryName()));
    }
    return repository;
  }

  private String getModel(AiTranslateService.AiTranslateInput aiTranslateInput) {
    return aiTranslateInput.useModel() != null
        ? aiTranslateInput.useModel()
        : aiTranslateConfigurationProperties.getModelName();
  }

  private OpenAIClient getOpenAIClient() {
    if (openAIClient == null) {
      String msg =
          "OpenAI client is not configured for legacy AiTranslate batch flow. Ensure that the OpenAI API key is provided in the configuration (qualifier='aiTranslate').";
      logger.error(msg);
      throw new RuntimeException(msg);
    }
    return openAIClient;
  }
}
