package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateLocalePromptSuffixService;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewChatWSTest {

  @Mock OpenAIClient openAIClient;

  @Mock TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService;

  @Mock AiTranslateLocalePromptSuffixService aiTranslateLocalePromptSuffixService;

  private AiReviewChatWS aiReviewChatWS;

  @Before
  public void setUp() {
    AiReviewConfigurationProperties configurationProperties = new AiReviewConfigurationProperties();
    configurationProperties.setModelName("gpt-4o-mini");

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
    when(openAIClient.getChatCompletions(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new OpenAIClient.ChatCompletionsResponse(
                    "resp-1",
                    "chat.completion",
                    Instant.now(),
                    "gpt-4o-mini",
                    List.of(
                        new OpenAIClient.ChatCompletionsResponse.Choice(
                            0,
                            new OpenAIClient.ChatCompletionsResponse.Choice.Message(
                                "assistant",
                                """
                                {
                                  "source": "Hello",
                                  "target": {
                                    "content": "Bonjour",
                                    "explanation": "Prefer the accepted locale phrasing.",
                                    "confidenceLevel": 91
                                  }
                                }
                                """),
                            "stop")),
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

    ArgumentCaptor<OpenAIClient.ChatCompletionsRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ChatCompletionsRequest.class);
    verify(openAIClient).getChatCompletions(requestCaptor.capture(), any());

    assertEquals(
        "%s %s".formatted(AiReviewType.PROMPT_ALL, "Use Canadian French terminology."),
        requestCaptor.getValue().messages().getFirst().content());
    assertEquals("Prefer the accepted locale phrasing.", response.message().content());
    assertEquals("Bonjour", response.suggestions().getFirst().content());
    verify(aiTranslateLocalePromptSuffixService).getEffectivePromptSuffix("fr-FR", null);
    verify(tmTextUnitIntegrityCheckService).checkTMTextUnitIntegrity(42L, "Bonjour");
  }

  @Test
  public void chatFallsBackToBasePromptWhenLocalePromptSuffixIsMissing() {
    when(aiTranslateLocalePromptSuffixService.getEffectivePromptSuffix("ja-JP", null))
        .thenReturn(null);
    when(openAIClient.getChatCompletions(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                new OpenAIClient.ChatCompletionsResponse(
                    "resp-2",
                    "chat.completion",
                    Instant.now(),
                    "gpt-4o-mini",
                    List.of(
                        new OpenAIClient.ChatCompletionsResponse.Choice(
                            0,
                            new OpenAIClient.ChatCompletionsResponse.Choice.Message(
                                "assistant",
                                """
                                {
                                  "source": "Save",
                                  "target": {
                                    "content": "保存",
                                    "explanation": "Natural imperative.",
                                    "confidenceLevel": 88
                                  }
                                }
                                """),
                            "stop")),
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

    ArgumentCaptor<OpenAIClient.ChatCompletionsRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ChatCompletionsRequest.class);
    verify(openAIClient).getChatCompletions(requestCaptor.capture(), any());

    assertEquals(AiReviewType.PROMPT_ALL, requestCaptor.getValue().messages().getFirst().content());
    verify(aiTranslateLocalePromptSuffixService).getEffectivePromptSuffix("ja-JP", null);
  }
}
