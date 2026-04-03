package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
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
            meterRegistry);
  }

  @Test
  public void chatAppendsLocalePromptSuffixToSystemPrompt() {
    when(aiTranslateLocalePromptSuffixService.getEffectivePromptSuffix("fr-FR", null))
        .thenReturn("Use Canadian French terminology.");
    doNothing().when(tmTextUnitIntegrityCheckService).checkTMTextUnitIntegrity(42L, "Bonjour");
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
        "%s %s".formatted(AiReviewType.PROMPT_ALL, "Use Canadian French terminology."),
        requestCaptor.getValue().instructions());
    assertEquals("gpt-5.4", requestCaptor.getValue().model());
    assertEquals("Prefer the accepted locale phrasing.", response.message().content());
    assertEquals("Bonjour", response.suggestions().getFirst().content());
    assertEquals(
        1L,
        meterRegistry
            .timer(
                "AiReviewChatWS.requestDuration",
                "model",
                "gpt-5.4",
                "locale",
                "fr-FR",
                "result",
                "completed")
            .count());
    verify(aiTranslateLocalePromptSuffixService).getEffectivePromptSuffix("fr-FR", null);
    verify(tmTextUnitIntegrityCheckService).checkTMTextUnitIntegrity(42L, "Bonjour");
  }

  @Test
  public void chatFallsBackToBasePromptWhenLocalePromptSuffixIsMissing() {
    when(aiTranslateLocalePromptSuffixService.getEffectivePromptSuffix("ja-JP", null))
        .thenReturn(null);
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

    assertEquals(AiReviewType.PROMPT_ALL, requestCaptor.getValue().instructions());
    verify(aiTranslateLocalePromptSuffixService).getEffectivePromptSuffix("ja-JP", null);
  }

  @Test
  public void chatUsesAdaptiveRequestTimeout() {
    when(aiTranslateLocalePromptSuffixService.getEffectivePromptSuffix("ja-JP", null))
        .thenReturn(null);
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

    ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(openAIClient).getResponses(any(), timeoutCaptor.capture());

    assertEquals(Duration.ofSeconds(53), timeoutCaptor.getValue());
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
        "AI review request timed out after 43 seconds. Please retry.", exception.getReason());
    assertEquals(
        1.0,
        meterRegistry
            .counter("AiReviewChatWS.timeouts", "model", "gpt-5.4", "locale", "ja-JP")
            .count(),
        0.0);
    assertEquals(
        1L,
        meterRegistry
            .timer(
                "AiReviewChatWS.requestDuration",
                "model",
                "gpt-5.4",
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
                "gpt-5.4",
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
                "gpt-5.4",
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
        "gpt-5.4",
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
