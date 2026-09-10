package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Settings;
import com.box.l10n.mojito.service.oaireview.AiReviewService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateLocalePromptSuffixService;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewChatWSTest {

  @Mock OpenAIClient openAIClient;

  @Mock TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService;

  @Mock AiTranslateLocalePromptSuffixService aiTranslateLocalePromptSuffixService;

  @Mock AiReviewInteractiveService interactiveService;

  private AiReviewChatWS aiReviewChatWS;

  private SimpleMeterRegistry meterRegistry;

  @Before
  public void setUp() {
    AiReviewConfigurationProperties configurationProperties = new AiReviewConfigurationProperties();

    ObjectMapper objectMapper = new ObjectMapper();
    AiReviewService.configureObjectMapper(objectMapper);
    meterRegistry = new SimpleMeterRegistry();

    aiReviewChatWS =
        new AiReviewChatWS(
            openAIClient,
            configurationProperties,
            objectMapper,
            tmTextUnitIntegrityCheckService,
            aiTranslateLocalePromptSuffixService,
            meterRegistry,
            interactiveService);
    lenient()
        .when(interactiveService.prepare(any()))
        .thenAnswer(
            invocation ->
                new Prepared(
                    invocation.getArgument(0),
                    7L,
                    new Settings("version_a", "gpt-5.6-sol", "max", "low", "default")));
  }

  @Test
  public void chatAppendsLocalePromptSuffixToSystemPrompt() {
    when(aiTranslateLocalePromptSuffixService.getLocalePromptSuffix("fr-FR"))
        .thenReturn("Use Canadian French terminology.");
    doNothing()
        .when(tmTextUnitIntegrityCheckService)
        .checkTMTextUnitIntegrityForLocale(42L, "Bonjour", "fr-FR");
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new OpenAIClient.ResponsesResponse(
                    "resp-1",
                    "response",
                    1712975853L,
                    "completed",
                    null,
                    null,
                    "gpt-4o-mini",
                    List.of(
                        responseOutput(
                            """
                    {
                      "source": "Hello",
                      "target": {
                        "content": "Bonjour",
                        "explanation": "Prefer the accepted locale phrasing.",
                        "confidenceLevel": 91
                      }
                    }
                    """)),
                    null,
                    null)));

    AiReviewChatWS.AiReviewChatResponse response =
        aiReviewChatWS.chat(
            new AiReviewChatWS.AiReviewChatRequest(
                "Hello",
                "Bonjour",
                "fr-FR",
                "Greeting shown in the app header.",
                42L,
                List.of(
                    new AiReviewChatWS.AiReviewChatMessage(
                        "user", "Review the translation and suggest improvements."))));

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), any());
    verify(openAIClient, never()).getChatCompletions(any(), any());

    assertEquals(
        "%s %s".formatted(AiReviewType.interactivePrompt(null), "Use Canadian French terminology."),
        requestCaptor.getValue().instructions());
    assertEquals("gpt-5.6-sol", requestCaptor.getValue().model());
    assertEquals("Prefer the accepted locale phrasing.", response.message().content());
    assertEquals("Bonjour", response.suggestions().getFirst().content());
    assertEquals(
        1L,
        meterRegistry
            .timer(
                "AiReviewChatWS.requestDuration",
                "model",
                "gpt-5.6-sol",
                "locale",
                "fr-FR",
                "result",
                "completed")
            .count());
    verify(aiTranslateLocalePromptSuffixService).getLocalePromptSuffix("fr-FR");
    verify(tmTextUnitIntegrityCheckService)
        .checkTMTextUnitIntegrityForLocale(42L, "Bonjour", "fr-FR");
  }

  @Test
  public void chatDoesNotReturnIncompleteExistingTargetRating() {
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new OpenAIClient.ResponsesResponse(
                    "resp-1",
                    "response",
                    1712975853L,
                    "completed",
                    null,
                    null,
                    "gpt-5.4",
                    List.of(
                        responseOutput(
                            """
                            {
                              "existingTargetRating": {
                                "explanation": "Rating without score should not be rendered."
                              }
                            }
                            """)),
                    null,
                    null)));

    AiReviewChatWS.AiReviewChatResponse response =
        aiReviewChatWS.chat(
            new AiReviewChatWS.AiReviewChatRequest(
                "Save",
                "保存",
                "ja-JP",
                null,
                null,
                List.of(
                    new AiReviewChatWS.AiReviewChatMessage("user", "Review this translation."))));

    assertNull(response.review());
    assertEquals("Here are the latest suggestions.", response.message().content());
  }

  @Test
  public void chatDoesNotReturnOutOfRangeExistingTargetRating() {
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new OpenAIClient.ResponsesResponse(
                    "resp-1",
                    "response",
                    1712975853L,
                    "completed",
                    null,
                    null,
                    "gpt-5.4",
                    List.of(
                        responseOutput(
                            """
                            {
                              "existingTargetRating": {
                                "score": 82,
                                "explanation": "Out-of-range score should not be rendered."
                              }
                            }
                            """)),
                    null,
                    null)));

    AiReviewChatWS.AiReviewChatResponse response =
        aiReviewChatWS.chat(
            new AiReviewChatWS.AiReviewChatRequest(
                "Save",
                "保存",
                "ja-JP",
                null,
                null,
                List.of(
                    new AiReviewChatWS.AiReviewChatMessage("user", "Review this translation."))));

    assertNull(response.review());
    assertEquals("Here are the latest suggestions.", response.message().content());
  }

  @Test
  public void chatFallsBackToBasePromptWhenLocalePromptSuffixIsMissing() {
    when(aiTranslateLocalePromptSuffixService.getLocalePromptSuffix("ja-JP")).thenReturn(null);
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new OpenAIClient.ResponsesResponse(
                    "resp-2",
                    "response",
                    1712975853L,
                    "completed",
                    null,
                    null,
                    "gpt-4o-mini",
                    List.of(
                        responseOutput(
                            """
                    {
                      "source": "Save",
                      "target": {
                        "content": "保存",
                        "explanation": "Natural imperative.",
                        "confidenceLevel": 88
                      }
                    }
                    """)),
                    null,
                    null)));

    aiReviewChatWS.chat(
        new AiReviewChatWS.AiReviewChatRequest(
            "Save",
            null,
            "ja-JP",
            null,
            null,
            List.of(new AiReviewChatWS.AiReviewChatMessage("user", "Review this translation."))));

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), any());

    assertEquals(AiReviewType.interactivePrompt(null), requestCaptor.getValue().instructions());
    verify(aiTranslateLocalePromptSuffixService).getLocalePromptSuffix("ja-JP");
  }

  @Test
  public void chatUsesAdaptiveRequestTimeout() {
    when(aiTranslateLocalePromptSuffixService.getLocalePromptSuffix("ja-JP")).thenReturn(null);
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(successResponse("Natural imperative.")));

    aiReviewChatWS.chat(
        new AiReviewChatWS.AiReviewChatRequest(
            "x".repeat(2000),
            null,
            "ja-JP",
            null,
            null,
            List.of(new AiReviewChatWS.AiReviewChatMessage("user", "go"))));

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), timeoutCaptor.capture());

    assertEquals("max", requestCaptor.getValue().reasoning().effort());
    assertEquals("default", requestCaptor.getValue().serviceTier());
    assertEquals(Duration.ofSeconds(252), timeoutCaptor.getValue());
  }

  @Test
  public void chatMapsTimeoutToGatewayTimeout() {
    CompletableFuture<OpenAIClient.ResponsesResponse> failedResponse = new CompletableFuture<>();
    failedResponse.completeExceptionally(new HttpTimeoutException("request timed out"));
    when(openAIClient.getResponses(any(), any())).thenReturn(failedResponse);

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                aiReviewChatWS.chat(
                    new AiReviewChatWS.AiReviewChatRequest(
                        "Save",
                        null,
                        "ja-JP",
                        null,
                        null,
                        List.of(
                            new AiReviewChatWS.AiReviewChatMessage(
                                "user", "Review this translation.")))));

    assertEquals(HttpStatus.GATEWAY_TIMEOUT, exception.getStatusCode());
    assertEquals(
        "AI review request timed out after 204 seconds. Please retry.", exception.getReason());
    assertEquals(
        1.0,
        meterRegistry
            .counter("AiReviewChatWS.timeouts", "model", "gpt-5.6-sol", "locale", "ja-JP")
            .count(),
        0.0);
    assertEquals(
        1L,
        meterRegistry
            .timer(
                "AiReviewChatWS.requestDuration",
                "model",
                "gpt-5.6-sol",
                "locale",
                "ja-JP",
                "result",
                "timeout")
            .count());
  }

  @Test
  public void chatMapsProviderFailureToBadGateway() {
    HttpResponse<String> httpResponse = mock(HttpResponse.class);
    when(httpResponse.statusCode()).thenReturn(400);
    when(httpResponse.body()).thenReturn("{\"error\":\"boom\"}");
    CompletableFuture<OpenAIClient.ResponsesResponse> failedResponse = new CompletableFuture<>();
    failedResponse.completeExceptionally(
        new OpenAIClient.OpenAIClientResponseException("Responses API failed", httpResponse));
    when(openAIClient.getResponses(any(), any())).thenReturn(failedResponse);

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                aiReviewChatWS.chat(
                    new AiReviewChatWS.AiReviewChatRequest(
                        "Save",
                        null,
                        "ja-JP",
                        null,
                        null,
                        List.of(
                            new AiReviewChatWS.AiReviewChatMessage(
                                "user", "Review this translation.")))));

    assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
    assertEquals("AI review provider request failed. Please retry.", exception.getReason());
    assertEquals(
        1.0,
        meterRegistry
            .counter(
                "AiReviewChatWS.providerFailures",
                "model",
                "gpt-5.6-sol",
                "locale",
                "ja-JP",
                "statusCode",
                "400")
            .count(),
        0.0);
    assertEquals(
        1L,
        meterRegistry
            .timer(
                "AiReviewChatWS.requestDuration",
                "model",
                "gpt-5.6-sol",
                "locale",
                "ja-JP",
                "result",
                "provider_failed")
            .count());
    verify(openAIClient).getResponses(any(), any());
  }

  @Test
  public void chatRetriesRetryableProviderFailure() {
    HttpResponse<String> httpResponse = mock(HttpResponse.class);
    when(httpResponse.statusCode()).thenReturn(503);
    when(httpResponse.body()).thenReturn("{\"error\":\"unavailable\"}");

    CompletableFuture<OpenAIClient.ResponsesResponse> failedResponse = new CompletableFuture<>();
    failedResponse.completeExceptionally(
        new OpenAIClient.OpenAIClientResponseException("Responses API failed", httpResponse));

    when(openAIClient.getResponses(any(), any()))
        .thenReturn(failedResponse)
        .thenReturn(CompletableFuture.completedFuture(successResponse("Retry succeeded.")));

    AiReviewChatWS.AiReviewChatResponse response =
        aiReviewChatWS.chat(
            new AiReviewChatWS.AiReviewChatRequest(
                "Save",
                null,
                "ja-JP",
                null,
                null,
                List.of(
                    new AiReviewChatWS.AiReviewChatMessage("user", "Review this translation."))));

    assertEquals("Retry succeeded.", response.message().content());
    verify(openAIClient, times(2)).getResponses(any(), any());
  }

  @Test
  public void chatIgnoresAssistantOnlyHistoryAndFallsBackToDefaultPrompt() {
    when(aiTranslateLocalePromptSuffixService.getLocalePromptSuffix("ja-JP")).thenReturn(null);
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(successResponse("Retry succeeded.")));

    aiReviewChatWS.chat(
        new AiReviewChatWS.AiReviewChatRequest(
            "Save",
            null,
            "ja-JP",
            null,
            null,
            List.of(new AiReviewChatWS.AiReviewChatMessage("assistant", "Previous AI reply."))));

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), any());

    List<OpenAIClient.ResponsesRequest.InputMessage> input = requestCaptor.getValue().input();
    assertEquals(2, input.size());
    assertEquals("user", input.get(0).role());
    assertEquals("user", input.get(1).role());
    assertEquals(
        AiReviewChatWS.DEFAULT_REVIEW_PROMPT,
        ((OpenAIClient.ResponsesRequest.InputMessage.Text) input.get(1).content().getFirst())
            .text());
  }

  @Test
  public void chatRejectsIncompleteResponseEvenWhenSuggestionJsonIsValid() {
    OpenAIClient.ResponsesResponse complete = successResponse("Unfinished suggestion.");
    OpenAIClient.ResponsesResponse incomplete =
        new OpenAIClient.ResponsesResponse(
            complete.id(),
            complete.object(),
            complete.createdAt(),
            "incomplete",
            null,
            new OpenAIClient.ResponsesResponse.IncompleteDetails("max_output_tokens"),
            complete.model(),
            complete.output(),
            complete.usage(),
            complete.metadata());
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(incomplete));

    ResponseStatusException failure =
        assertThrows(
            ResponseStatusException.class,
            () ->
                aiReviewChatWS.chat(
                    new AiReviewChatWS.AiReviewChatRequest(
                        "Save",
                        "保存",
                        "ja-JP",
                        null,
                        null,
                        List.of(new AiReviewChatWS.AiReviewChatMessage("user", "Review.")))));

    assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
    verify(openAIClient).getResponses(any(), any());
    assertEquals(
        1L,
        meterRegistry
            .find("AiReviewChatWS.requestDuration")
            .tag("result", "provider_failed")
            .timer()
            .count());
    assertNull(
        meterRegistry.find("AiReviewChatWS.requestDuration").tag("result", "completed").timer());
  }

  @Test
  public void chatPreservesMeaningfulTargetWhitespaceInReviewInput() {
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(successResponse("No change needed.")));

    aiReviewChatWS.chat(
        new AiReviewChatWS.AiReviewChatRequest(
            " Save ",
            " 保存 ",
            "ja-JP",
            null,
            42L,
            List.of(new AiReviewChatWS.AiReviewChatMessage("user", "Review this translation."))));

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), any());
    String inputJson =
        ((OpenAIClient.ResponsesRequest.InputMessage.Text)
                requestCaptor.getValue().input().getFirst().content().getFirst())
            .text();
    AiReviewService.AiReviewTextUnitVariantInput input =
        new ObjectMapper()
            .readValueUnchecked(inputJson, AiReviewService.AiReviewTextUnitVariantInput.class);
    assertEquals(" 保存 ", input.existingTarget().content());
    verify(tmTextUnitIntegrityCheckService).checkTMTextUnitIntegrityForLocale(42L, " 保存 ", "ja-JP");
  }

  @Test
  public void chatUsesThePreparedLocaleForProviderAndUsage() {
    AiReviewChatWS.AiReviewChatRequest original =
        new AiReviewChatWS.AiReviewChatRequest(
            "Hello",
            "Bonjour",
            " fr-CA ",
            null,
            42L,
            List.of(new AiReviewChatWS.AiReviewChatMessage("user", "Review.")));
    AiReviewChatWS.AiReviewChatRequest normalized =
        new AiReviewChatWS.AiReviewChatRequest(
            original.source(),
            original.target(),
            "fr-CA",
            original.sourceDescription(),
            original.tmTextUnitId(),
            original.messages());
    Prepared prepared =
        new Prepared(
            normalized, 7L, new Settings("version_b", "gpt-6-astra", "low", "low", "priority"));
    when(interactiveService.prepare(original)).thenReturn(prepared);
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(successResponse("No change needed.")));

    aiReviewChatWS.chat(original);

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), any());
    String inputJson =
        ((OpenAIClient.ResponsesRequest.InputMessage.Text)
                requestCaptor.getValue().input().getFirst().content().getFirst())
            .text();
    assertEquals(
        prepared.request().localeTag(),
        new ObjectMapper()
            .readValueUnchecked(inputJson, AiReviewService.AiReviewTextUnitVariantInput.class)
            .locale());
    verify(interactiveService).start(prepared, null);
    verify(aiTranslateLocalePromptSuffixService).getLocalePromptSuffix("fr-CA");
    verify(tmTextUnitIntegrityCheckService)
        .checkTMTextUnitIntegrityForLocale(42L, "Bonjour", "fr-CA");
  }

  @Test
  public void nullProviderResponseRecordsProviderFailureWithoutDereferencingMetadata() {
    assertInvalidProviderOutput(null, null);
  }

  @Test
  public void nullParsedReviewRecordsProviderFailureInsteadOfCompletion() {
    OpenAIClient.ResponsesResponse response =
        new OpenAIClient.ResponsesResponse(
            "resp-null",
            "response",
            1712975853L,
            "completed",
            null,
            null,
            "returned-model",
            List.of(responseOutput("null")),
            null,
            null);
    assertInvalidProviderOutput(response, "returned-model");
  }

  private void assertInvalidProviderOutput(
      OpenAIClient.ResponsesResponse response, String expectedReturnedModel) {
    when(interactiveService.start(any(), isNull())).thenReturn(91L);
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(response));
    AiReviewChatWS.AiReviewChatRequest request =
        new AiReviewChatWS.AiReviewChatRequest(
            "Save",
            "保存",
            "ja-JP",
            null,
            null,
            List.of(new AiReviewChatWS.AiReviewChatMessage("user", "Review.")));

    ResponseStatusException failure =
        assertThrows(ResponseStatusException.class, () -> aiReviewChatWS.chat(request));

    assertEquals(HttpStatus.BAD_GATEWAY, failure.getStatusCode());
    assertEquals(
        "AI review provider returned an incomplete response. Please retry.", failure.getReason());
    verify(interactiveService)
        .finish(
            eq(91L),
            eq("provider_failed"),
            anyLong(),
            eq(expectedReturnedModel),
            isNull(),
            isNull());
    verify(interactiveService, never())
        .finish(eq(91L), eq("completed"), anyLong(), any(), any(), any());
    assertNull(
        meterRegistry.find("AiReviewChatWS.requestDuration").tag("result", "completed").timer());
    assertEquals(
        1L,
        meterRegistry
            .find("AiReviewChatWS.requestDuration")
            .tag("result", "provider_failed")
            .timer()
            .count());
  }

  @Test
  public void selectedPresetControlsProviderTimeoutMetricsAndUsageWithoutExposingModel() {
    AiReviewChatWS.AiReviewChatRequest request =
        new AiReviewChatWS.AiReviewChatRequest(
            "x".repeat(2000),
            null,
            "ja-JP",
            null,
            42L,
            List.of(new AiReviewChatWS.AiReviewChatMessage("user", "Review")),
            null,
            "manual",
            "text_unit_detail",
            null,
            "deep");
    Prepared prepared =
        new Prepared(request, 7L, new Settings("deep", "gpt-6-astra", "high", "low", "priority"));
    when(interactiveService.prepare(request)).thenReturn(prepared);
    when(interactiveService.start(prepared, null)).thenReturn(91L);
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(successResponse("Keep this translation.")));

    var response = aiReviewChatWS.chat(request);

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), timeoutCaptor.capture());
    assertEquals("gpt-6-astra", requestCaptor.getValue().model());
    assertEquals("high", requestCaptor.getValue().reasoning().effort());
    assertEquals("priority", requestCaptor.getValue().serviceTier());
    org.junit.Assert.assertTrue(timeoutCaptor.getValue().toSeconds() > 100);
    assertEquals(
        1L,
        meterRegistry
            .find("AiReviewChatWS.requestDuration")
            .tag("model", "gpt-6-astra")
            .timer()
            .count());
    verify(interactiveService).start(prepared, null);
    verify(interactiveService)
        .finish(
            org.mockito.ArgumentMatchers.eq(91L),
            org.mockito.ArgumentMatchers.eq("completed"),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.eq(successResponse("").model()),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.same(response));
    org.junit.Assert.assertFalse(
        new ObjectMapper().writeValueAsStringUnchecked(response).contains("gpt-"));
  }

  @Test
  public void validOriginalCanHaveTwoAlternativesWithoutBeingClassifiedAsACorrection() {
    AiReviewChatWS.AiReviewChatResponse response =
        candidateResponse("corrections_and_alternatives", "Bonjour", "Salut", "Coucou", 2);

    assertEquals(2, response.review().score());
    assertNull(response.review().confidenceLevel());
    assertEquals(2, response.suggestions().size());
    assertEquals("alternative", response.suggestions().get(0).kind());
    assertEquals("alternative", response.suggestions().get(1).kind());
    assertEquals(Integer.valueOf(91), response.suggestions().get(0).confidenceLevel());
    ArgumentCaptor<OpenAIClient.ResponsesRequest> request =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    verify(openAIClient).getResponses(request.capture(), any());
    assertEquals(
        AiReviewType.interactivePrompt("corrections_and_alternatives"),
        request.getValue().instructions());
  }

  @Test
  public void concreteDefectClassifiesPrimaryFixSeparatelyFromOptionalAlternative() {
    AiReviewChatWS.AiReviewChatResponse response =
        candidateResponse("corrections_and_alternatives", "Bonjoure", "Bonjour", "Salut", 1);

    assertEquals(1, response.review().score());
    assertNull(response.review().confidenceLevel());
    assertEquals("correction", response.suggestions().get(0).kind());
    assertEquals("alternative", response.suggestions().get(1).kind());
  }

  @Test
  public void sourceOnlySuggestionsDoNotClaimAnOriginalAssessmentOrCorrectionKind() {
    for (String original : new String[] {null, "", " \t "}) {
      AiReviewChatWS.AiReviewChatResponse response =
          candidateResponse("corrections_and_alternatives", original, "Bonjour", "Salut", 2);

      assertNull(response.review());
      assertNull(response.suggestions().get(0).kind());
      assertEquals("alternative", response.suggestions().get(1).kind());
    }
  }

  @Test
  public void missingOrUnusableAssessmentsKeepPrimarySuggestionsNeutral() {
    for (Integer originalScore : new Integer[] {null, -1, 82}) {
      AiReviewChatWS.AiReviewChatResponse response =
          candidateResponse(
              "corrections_and_alternatives", "Bonjour", "Salut", "Coucou", originalScore);

      assertNull(response.review());
      assertNull(response.suggestions().get(0).kind());
      assertEquals("alternative", response.suggestions().get(1).kind());
    }
  }

  @Test
  public void unchangedTargetSuppliesItsOwnConfidenceWithoutRepeatingDuplicateCandidates() {
    AiReviewChatWS.AiReviewChatResponse response =
        candidateResponse("corrections_only", " Bonjour ", " Bonjour ", " Bonjour ", 2);

    assertEquals(Integer.valueOf(91), response.review().confidenceLevel());
    assertEquals(2, response.review().score());
    assertEquals(1, response.suggestions().size());
    assertEquals("alternative", response.suggestions().getFirst().kind());
  }

  @Test
  public void whitespaceChangeDoesNotReuseCandidateConfidenceAsAnAssessmentOfTheOriginal() {
    AiReviewChatWS.AiReviewChatResponse response =
        candidateResponse("corrections_only", " Bonjour ", "Bonjour", "", 1);

    assertNull(response.review().confidenceLevel());
    assertEquals("correction", response.suggestions().getFirst().kind());
  }

  @Test
  public void interactiveStylesKeepAssessmentSeparateAndDoNotEnableAlternativesForBatchReview() {
    String corrections = AiReviewType.interactivePrompt("corrections_only");
    String alternatives = AiReviewType.interactivePrompt("corrections_and_alternatives");
    assertEquals(corrections, AiReviewType.interactivePrompt(null));
    assertTrue(corrections.contains("return it verbatim as target.content"));
    assertTrue(corrections.contains("user explicitly requests"));
    assertTrue(alternatives.contains("offer two distinct, natural candidate wordings"));
    assertTrue(alternatives.contains("Keep the original's rating at 2"));
    assertTrue(alternatives.contains("Do not force arbitrary paraphrases"));
    for (String prompt : List.of(corrections, alternatives)) {
      assertTrue(
          prompt.contains("not an external\nquality measurement or a calibrated probability"));
      assertTrue(prompt.contains("required=true for a concrete defect"));
      assertTrue(
          prompt.contains("check meaning and structure")
              || prompt.contains("recheck meaning and structure"));
    }
    assertEquals(AiReviewType.PROMPT_ALL, AiReviewType.ALL.getPrompt());
    assertTrue(AiReviewType.PROMPT_ALL.contains("return it verbatim as target.content"));
    assertFalse(AiReviewType.PROMPT_ALL.contains("offer two distinct"));
    assertFalse(AiReviewType.PROMPT_ALL.contains("self-estimated confidence"));
  }

  private AiReviewChatWS.AiReviewChatResponse candidateResponse(
      String style, String original, String target, String alternative, Integer originalScore) {
    var output =
        new AiReviewType.AiReviewTextUnitVariantOutput(
            "Hello",
            new AiReviewType.AiReviewTextUnitVariantOutput.Target(target, "Primary wording.", 91),
            null,
            new AiReviewType.AiReviewTextUnitVariantOutput.AltTarget(
                alternative, "Optional wording.", 84),
            originalScore == null
                ? null
                : new AiReviewType.AiReviewTextUnitVariantOutput.ExistingTargetRating(
                    "Original assessment.", originalScore),
            null);
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new OpenAIClient.ResponsesResponse(
                    "resp-candidates",
                    "response",
                    1712975853L,
                    "completed",
                    null,
                    null,
                    "gpt-5.6-sol",
                    List.of(responseOutput(new ObjectMapper().writeValueAsStringUnchecked(output))),
                    null,
                    null)));
    return aiReviewChatWS.chat(
        new AiReviewChatWS.AiReviewChatRequest(
            "Hello",
            original,
            "fr",
            null,
            null,
            List.of(new AiReviewChatWS.AiReviewChatMessage("user", "Review this translation.")),
            null,
            "manual",
            "review_project",
            null,
            "balanced",
            style));
  }

  private OpenAIClient.ResponsesResponse.Output responseOutput(String text) {
    return new OpenAIClient.ResponsesResponse.Output(
        "msg-1",
        "message",
        "completed",
        List.of(new OpenAIClient.ResponsesResponse.Content("output_text", text)),
        "assistant");
  }

  private OpenAIClient.ResponsesResponse successResponse(String reply) {
    return new OpenAIClient.ResponsesResponse(
        "resp-success",
        "response",
        1712975853L,
        "completed",
        null,
        null,
        "gpt-5.6-sol",
        List.of(
            responseOutput(
                """
                {
                  "target": {
                    "content": "保存",
                    "explanation": "%s",
                    "confidenceLevel": 88
                  }
                }
                """
                    .formatted(reply))),
        null,
        null);
  }
}
