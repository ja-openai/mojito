package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiReviewProto;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesRequest;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesResponse;
import com.box.l10n.mojito.openai.OpenAIClientPool;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.rest.textunit.AiReviewType;
import com.box.l10n.mojito.service.oaireview.AiReviewResponseValidator.InvalidReviewResponseException;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateLocalePromptSuffixService;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTerm;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTrie;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.AiReviewProtoRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import reactor.util.retry.Retry;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewServiceTest {

  @Mock TextUnitSearcher textUnitSearcher;
  @Mock AiReviewProtoRepository aiReviewProtoRepository;
  @Mock OpenAIClient openAIClient;
  @Mock OpenAIClientPool openAIClientPool;
  @Mock GlossaryService glossaryService;
  @Mock AiTranslateLocalePromptSuffixService localePromptSuffixService;

  private AiReviewService service;
  private ObjectMapper objectMapper;
  private RepositoryLocale repositoryLocale;
  private SimpleMeterRegistry meterRegistry;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
    AiReviewService.configureObjectMapper(objectMapper);
    meterRegistry = new SimpleMeterRegistry();
    service =
        new AiReviewService(
            textUnitSearcher,
            mock(RepositoryRepository.class),
            mock(TMTextUnitVariantRepository.class),
            aiReviewProtoRepository,
            mock(RepositoryService.class),
            new AiReviewConfigurationProperties(),
            openAIClient,
            openAIClientPool,
            objectMapper,
            Retry.backoff(1, Duration.ofMillis(1)),
            mock(QuartzPollableTaskScheduler.class),
            mock(PollableTaskBlobStorage.class),
            mock(PollableTaskService.class),
            meterRegistry,
            glossaryService,
            localePromptSuffixService);

    Repository repository = new Repository();
    repository.setId(7L);
    repository.setName("product");
    Locale locale = new Locale();
    locale.setId(8L);
    locale.setBcp47Tag("fr-FR");
    repositoryLocale = new RepositoryLocale();
    repositoryLocale.setRepository(repository);
    repositoryLocale.setLocale(locale);
  }

  @Test
  public void backgroundReviewUsesScopedGlossaryLocaleGuidanceAndCurrentCacheRun() {
    TextUnitDTO textUnit = textUnit("Open Workbench", 42L);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit));
    when(glossaryService.loadLinkedGlossaryTrieForLocale(7L, "fr-FR")).thenReturn(glossaryTrie());
    when(localePromptSuffixService.getLocalePromptSuffix("fr-FR"))
        .thenReturn("Use the formal register.");
    stubProvider("{\"source\":\"Open Workbench\"}");

    runNoBatch(AiReviewType.ALL, "for-frontend");

    ArgumentCaptor<ResponsesRequest> request = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(openAIClient).getResponses(request.capture(), any());
    JsonNode input = input(request.getValue());
    assertEquals("Workbench", input.at("/glossaryTerms/0/source").asText());
    assertEquals("Banc", input.at("/glossaryTerms/0/target").asText());
    assertTrue(input.at("/glossaryTerms/0/doNotTranslate").asBoolean());
    assertEquals("HARD", input.at("/glossaryTerms/0/enforcement").asText());
    assertEquals(1, input.get("glossaryTerms").size());
    assertEquals(
        AiReviewType.PROMPT_ALL + " Use the formal register.", request.getValue().instructions());
    assertEquals("max", request.getValue().reasoning().effort());
    assertEquals("default", request.getValue().serviceTier());
    verify(glossaryService).loadLinkedGlossaryTrieForLocale(7L, "fr-FR");
    verify(aiReviewProtoRepository)
        .findTmTextUnitVariantIdsByLocaleIdAndRepositoryId(8L, 7L, "for-frontend-v2");
    ArgumentCaptor<List<AiReviewProto>> saved = ArgumentCaptor.forClass(List.class);
    verify(aiReviewProtoRepository).saveAll(saved.capture());
    assertEquals("for-frontend-v2", saved.getValue().getFirst().getRunName());
  }

  @Test
  public void sourceReviewUsesRequestedPromptSchemaAndDescriptionWithoutTargetContext() {
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit("Open Workbench", 42L)));
    stubProvider("{\"rating\":1,\"explanation\":\"Clear source.\"}");

    runNoBatch(AiReviewType.SOURCE_RATING, "source-audit");

    ArgumentCaptor<ResponsesRequest> request = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(openAIClient).getResponses(request.capture(), any());
    assertEquals(AiReviewType.SOURCE_RATING.getPrompt(), request.getValue().instructions());
    JsonNode input = input(request.getValue());
    assertEquals("Button opening the named feature.", input.get("description").asText());
    assertFalse(input.has("existingTarget"));
    assertFalse(input.has("glossaryTerms"));
    JsonNode schema = objectMapper.valueToTree(request.getValue().text().format().schema());
    assertTrue(schema.path("properties").has("rating"));
    assertFalse(schema.path("properties").has("target"));
    verifyNoInteractions(glossaryService, localePromptSuffixService);
  }

  @Test
  public void batchIncludesOnlyApplicableTermsAndOmitsOnlineServiceTier() {
    String batch =
        service.generateBatchFileContent(
            List.of(textUnit("Open Workbench Workbench", 42L)),
            "gpt-5.6-sol",
            AiReviewType.ALL.getOutputJsonSchemaClass(),
            AiReviewType.PROMPT_ALL,
            true,
            glossaryTrie());

    JsonNode body = objectMapper.readValueUnchecked(batch, JsonNode.class).get("body");
    assertFalse(body.has("service_tier"));
    JsonNode input =
        objectMapper.readValueUnchecked(
            body.at("/input/0/content/0/text").asText(), JsonNode.class);
    assertEquals(1, input.get("glossaryTerms").size());
    assertEquals("Workbench", input.at("/glossaryTerms/0/source").asText());
    assertEquals("Ouvrir Workbench", input.at("/existingTarget/content").asText());
  }

  @Test
  public void singleReviewLooksUpRepositoryAndLocaleContextBeforeCallingConfiguredClient() {
    TextUnitDTO textUnit = textUnit("Open Workbench", 42L);
    GlossaryTerm term = glossaryTerm(100L, "Workbench", "Banc", true);
    when(glossaryService.findMatchesForRepositoryAndLocale(
            null, "product", null, "fr-FR", "Open Workbench", 42L))
        .thenReturn(
            List.of(
                new GlossaryService.MatchedGlossaryTerm(
                    term, GlossaryService.MatchType.EXACT, 5, 14, "Workbench")));
    when(localePromptSuffixService.getLocalePromptSuffix("fr-FR"))
        .thenReturn("Use the formal register.");
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(response("{}")));

    service.getAiReviewSingleTextUnit(textUnit);

    ArgumentCaptor<ResponsesRequest> request = ArgumentCaptor.forClass(ResponsesRequest.class);
    verify(openAIClient).getResponses(request.capture(), any());
    assertEquals("Banc", input(request.getValue()).at("/glossaryTerms/0/target").asText());
    assertTrue(request.getValue().instructions().endsWith("Use the formal register."));
    assertEquals("default", request.getValue().serviceTier());
  }

  @Test
  public void doNotTranslateTermWithoutLocaleTargetPreservesSource() {
    GlossaryTrie trie = new GlossaryTrie();
    trie.addTerm(glossaryTerm(100L, "Workbench", null, true));
    String batch =
        service.generateBatchFileContent(
            List.of(textUnit("Open Workbench", 42L)),
            "gpt-5.6-sol",
            AiReviewType.ALL.getOutputJsonSchemaClass(),
            AiReviewType.PROMPT_ALL,
            true,
            trie);
    JsonNode body = objectMapper.readValueUnchecked(batch, JsonNode.class).get("body");
    JsonNode input =
        objectMapper.readValueUnchecked(
            body.at("/input/0/content/0/text").asText(), JsonNode.class);

    assertEquals("Workbench", input.at("/glossaryTerms/0/target").asText());
    assertTrue(input.at("/glossaryTerms/0/doNotTranslate").asBoolean());
  }

  @Test
  public void completedStatusDoesNotOverrideProviderErrorOrIncompleteDetails() {
    ResponsesResponse complete = response("{\"target\":{\"content\":\"Suggestion\"}}");
    ResponsesResponse withError =
        new ResponsesResponse(
            complete.id(),
            complete.object(),
            complete.createdAt(),
            "completed",
            new ResponsesResponse.Error("server_error", "Provider failed"),
            null,
            complete.model(),
            complete.output(),
            complete.usage(),
            complete.metadata());
    ResponsesResponse withIncompleteDetails =
        new ResponsesResponse(
            complete.id(),
            complete.object(),
            complete.createdAt(),
            "completed",
            null,
            new ResponsesResponse.IncompleteDetails("max_output_tokens"),
            complete.model(),
            complete.output(),
            complete.usage(),
            complete.metadata());

    assertThrows(
        InvalidReviewResponseException.class,
        () -> AiReviewResponseValidator.outputText(withError));
    assertThrows(
        InvalidReviewResponseException.class,
        () -> AiReviewResponseValidator.outputText(withIncompleteDetails));
  }

  @Test
  public void incompleteBackgroundReviewWithValidJsonIsNotSavedOrCountedAsCompleted() {
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit("Open Workbench", 42L)));
    when(openAIClientPool.<ResponsesResponse>submit(any()))
        .thenReturn(CompletableFuture.completedFuture(incompleteResponse()));

    runNoBatch(AiReviewType.ALL, "for-frontend");

    verify(aiReviewProtoRepository, never()).saveAll(any());
    assertEquals(
        1L,
        meterRegistry
            .find("AiReviewService.requestDuration")
            .tag("result", "provider_failed")
            .timer()
            .count());
    assertNull(
        meterRegistry.find("AiReviewService.requestDuration").tag("result", "completed").timer());
  }

  @Test
  public void incompleteSingleReviewWithValidJsonIsNotReturned() {
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(incompleteResponse()));

    assertThrows(
        InvalidReviewResponseException.class,
        () ->
            service.getAiReviewSingleTextUnit(
                new AiReviewService.AiReviewTextUnitVariantInput(
                    "fr-FR", "Open Workbench", null, null)));

    assertEquals(
        1L,
        meterRegistry
            .find("AiReviewService.requestDuration")
            .tag("result", "provider_failed")
            .timer()
            .count());
    assertNull(
        meterRegistry.find("AiReviewService.requestDuration").tag("result", "completed").timer());
  }

  @Test
  public void incompleteBatchReviewWithValidJsonIsNotSaved() {
    OpenAIClient.ResponsesResponseBatchFileLine line =
        new OpenAIClient.ResponsesResponseBatchFileLine(
            "line-1",
            "1042",
            new OpenAIClient.ResponsesResponseBatchFileLine.Response(
                200, "req-1", incompleteResponse()));
    when(openAIClient.downloadFileContent(any()))
        .thenReturn(
            new OpenAIClient.DownloadFileContentResponse(
                objectMapper.writeValueAsStringUnchecked(line)));
    OpenAIClient.RetrieveBatchResponse batch =
        objectMapper.readValueUnchecked(
            "{\"id\":\"batch-1\",\"endpoint\":\"/v1/responses\",\"output_file_id\":\"file-1\"}",
            OpenAIClient.RetrieveBatchResponse.class);

    assertThrows(
        InvalidReviewResponseException.class,
        () -> service.importBatch(batch, "for-frontend-v2", AiReviewType.ALL));

    verify(aiReviewProtoRepository, never()).saveAll(any());
  }

  @Test
  public void noWorkDoesNotLoadGlossaryOrCallProvider() {
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());

    runNoBatch(AiReviewType.ALL, "for-frontend");

    verifyNoInteractions(
        glossaryService, localePromptSuffixService, openAIClientPool, openAIClient);
    verify(aiReviewProtoRepository, never()).saveAll(any());
  }

  @Test
  public void providerFailureDoesNotTryToSaveAnEmptyReviewGroup() {
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit("Open Workbench", 42L)));
    when(openAIClientPool.<ResponsesResponse>submit(any()))
        .thenReturn(
            CompletableFuture.failedFuture(new IllegalArgumentException("Invalid request")));

    runNoBatch(AiReviewType.ALL, "for-frontend");

    verify(aiReviewProtoRepository, never()).saveAll(any());
  }

  @Test
  public void importingOldInFlightBatchDoesNotPromoteItToCurrentReviewPolicy() {
    OpenAIClient.ResponsesResponseBatchFileLine line =
        new OpenAIClient.ResponsesResponseBatchFileLine(
            "line-1",
            "1042",
            new OpenAIClient.ResponsesResponseBatchFileLine.Response(200, "req-1", response("{}")));
    when(openAIClient.downloadFileContent(any()))
        .thenReturn(
            new OpenAIClient.DownloadFileContentResponse(
                objectMapper.writeValueAsStringUnchecked(line)));
    OpenAIClient.RetrieveBatchResponse batch =
        objectMapper.readValueUnchecked(
            "{\"id\":\"batch-1\",\"endpoint\":\"/v1/responses\",\"output_file_id\":\"file-1\"}",
            OpenAIClient.RetrieveBatchResponse.class);

    service.importBatch(batch, "for-frontend", AiReviewType.ALL);

    ArgumentCaptor<List<AiReviewProto>> saved = ArgumentCaptor.forClass(List.class);
    verify(aiReviewProtoRepository).saveAll(saved.capture());
    assertEquals("for-frontend", saved.getValue().getFirst().getRunName());
  }

  @Test
  public void reviewRunVersionOnlyChangesReservedFrontendName() {
    assertEquals("for-frontend-v2", AiReviewService.resolveReviewRunName("for-frontend"));
    assertEquals("for-frontend-v2", AiReviewService.resolveReviewRunName("for-frontend-v2"));
    assertEquals("custom-run", AiReviewService.resolveReviewRunName("custom-run"));
    assertNull(AiReviewService.resolveReviewRunName(null));
  }

  private void runNoBatch(AiReviewType reviewType, String runName) {
    service
        .asyncReviewNoBatchLocale(
            repositoryLocale, 100, null, "gpt-5.6-sol", runName, reviewType, openAIClientPool)
        .block();
  }

  private void stubProvider(String body) {
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(response(body)));
    when(openAIClientPool.<ResponsesResponse>submit(any()))
        .thenAnswer(
            invocation -> {
              Function<OpenAIClient, CompletableFuture<ResponsesResponse>> submit =
                  invocation.getArgument(0);
              return submit.apply(openAIClient);
            });
  }

  private ResponsesResponse response(String body) {
    return new ResponsesResponse(
        "resp-review",
        "response",
        0L,
        "completed",
        null,
        null,
        "gpt-5.6-sol",
        List.of(
            new ResponsesResponse.Output(
                "message-review",
                "message",
                "completed",
                List.of(new ResponsesResponse.Content("output_text", body)),
                "assistant")),
        null,
        null);
  }

  private ResponsesResponse incompleteResponse() {
    ResponsesResponse complete = response("{\"target\":{\"content\":\"Suggestion partielle\"}}");
    return new ResponsesResponse(
        complete.id(),
        complete.object(),
        complete.createdAt(),
        "incomplete",
        null,
        new ResponsesResponse.IncompleteDetails("max_output_tokens"),
        complete.model(),
        complete.output(),
        complete.usage(),
        complete.metadata());
  }

  private JsonNode input(ResponsesRequest request) {
    String input =
        ((ResponsesRequest.InputMessage.Text) request.input().getFirst().content().getFirst())
            .text();
    return objectMapper.readValueUnchecked(input, JsonNode.class);
  }

  private TextUnitDTO textUnit(String source, long id) {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(id);
    textUnit.setTmTextUnitVariantId(id + 1000);
    textUnit.setRepositoryName("product");
    textUnit.setTargetLocale("fr-FR");
    textUnit.setSource(source);
    textUnit.setComment("Button opening the named feature.");
    textUnit.setTarget("Ouvrir Workbench");
    textUnit.setIncludedInLocalizedFile(true);
    return textUnit;
  }

  private GlossaryTrie glossaryTrie() {
    GlossaryTrie trie = new GlossaryTrie();
    trie.addTerm(glossaryTerm(100L, "Workbench", "Banc", true));
    trie.addTerm(glossaryTerm(101L, "Settings", "Paramètres", false));
    trie.addTerm(glossaryTerm(42L, "Open", "Ouvrir", false));
    return trie;
  }

  private GlossaryTerm glossaryTerm(long id, String source, String target, boolean doNotTranslate) {
    return new GlossaryTerm(
        id,
        2L,
        "product-ui",
        source,
        source,
        "Source note",
        "Product feature",
        "noun",
        "FEATURE",
        "HARD",
        "APPROVED",
        "MANUAL",
        target,
        "Target note",
        doNotTranslate,
        true,
        List.of());
  }
}
