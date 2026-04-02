package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateLocalePromptSuffixService;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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

  @Before
  public void setUp() {
    AiReviewConfigurationProperties configurationProperties = new AiReviewConfigurationProperties();

    ObjectMapper objectMapper = new ObjectMapper();
    AiReviewService.configureObjectMapper(objectMapper);

    aiReviewChatWS =
        new AiReviewChatWS(
            openAIClient,
            configurationProperties,
            objectMapper,
            tmTextUnitIntegrityCheckService,
            aiTranslateLocalePromptSuffixService);
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
        "AI review request timed out after 15 seconds. Please retry.", exception.getReason());
  }

  @Test
  public void chatMapsProviderFailureToBadGateway() {
    HttpResponse<String> httpResponse = mock(HttpResponse.class);
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
  }

  private OpenAIClient.ResponsesResponse.Output responseOutput(String text) {
    return new OpenAIClient.ResponsesResponse.Output(
        "msg-1",
        "message",
        "completed",
        List.of(new OpenAIClient.ResponsesResponse.Content("output_text", text)),
        "assistant");
  }
}
