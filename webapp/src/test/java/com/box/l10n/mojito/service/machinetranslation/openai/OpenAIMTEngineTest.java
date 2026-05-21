package com.box.l10n.mojito.service.machinetranslation.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.machinetranslation.PlaceholderEncoder;
import com.box.l10n.mojito.service.machinetranslation.TextType;
import com.box.l10n.mojito.service.machinetranslation.TranslationDTO;
import com.box.l10n.mojito.service.machinetranslation.TranslationSource;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateConfigurationProperties;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OpenAIMTEngineTest {

  @Mock OpenAIClient openAIClient;

  AiTranslateConfigurationProperties aiTranslateConfigurationProperties;
  OpenAIMTEngine openAIMTEngine;

  @Before
  public void setup() {
    aiTranslateConfigurationProperties = new AiTranslateConfigurationProperties();
    aiTranslateConfigurationProperties.setModelName("gpt-test");
    aiTranslateConfigurationProperties.getResponses().setReasoningEffort("high");
    aiTranslateConfigurationProperties.getResponses().setTextVerbosity("high");
    openAIMTEngine =
        new OpenAIMTEngine(
            openAIClient,
            aiTranslateConfigurationProperties,
            new PlaceholderEncoder(),
            new SimpleMeterRegistry());
  }

  @Test
  public void getSourceReturnsOpenAiMt() {
    assertEquals(TranslationSource.OPENAI_MT, openAIMTEngine.getSource());
  }

  @Test
  public void translateMapsOpenAiJsonResponse() {
    when(openAIClient.getResponses(any(), any()))
        .thenReturn(
            CompletableFuture.completedFuture(
                responsesResponse(
                    """
                    {
                      "translations": [
                        {"id": 0, "targetLocale": "fr-FR", "text": "Bonjour"},
                        {"id": 1, "targetLocale": "de-DE", "text": "Hallo"},
                        {"id": 2, "targetLocale": "fr-FR", "text": "Enregistrer"},
                        {"id": 3, "targetLocale": "de-DE", "text": "Speichern"}
                      ]
                    }
                    """)));

    ImmutableMap<String, ImmutableList<TranslationDTO>> translationsBySourceText =
        openAIMTEngine.getTranslationsBySourceText(
            List.of("Hello", "Save"), "en", List.of("fr-FR", "de-DE"), TextType.TEXT, null, false);

    assertEquals("Bonjour", translationsBySourceText.get("Hello").get(0).getText());
    assertEquals("fr-FR", translationsBySourceText.get("Hello").get(0).getBcp47Tag());
    assertEquals(
        TranslationSource.OPENAI_MT,
        translationsBySourceText.get("Hello").get(0).getTranslationSource());
    assertEquals("Hallo", translationsBySourceText.get("Hello").get(1).getText());
    assertEquals("Enregistrer", translationsBySourceText.get("Save").get(0).getText());
    assertEquals("Speichern", translationsBySourceText.get("Save").get(1).getText());

    ArgumentCaptor<OpenAIClient.ResponsesRequest> requestCaptor =
        ArgumentCaptor.forClass(OpenAIClient.ResponsesRequest.class);
    ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(openAIClient).getResponses(requestCaptor.capture(), timeoutCaptor.capture());

    OpenAIClient.ResponsesRequest request = requestCaptor.getValue();
    assertEquals("gpt-test", request.model());
    assertEquals(OpenAIMTEngine.REASONING_EFFORT, request.reasoning().effort());
    assertEquals(OpenAIMTEngine.TEXT_VERBOSITY, request.text().verbosity());
    assertEquals(OpenAIMTEngine.REQUEST_TIMEOUT, timeoutCaptor.getValue());

    OpenAIClient.ResponsesRequest.InputMessage.Text inputText =
        (OpenAIClient.ResponsesRequest.InputMessage.Text)
            request.input().getFirst().content().getFirst();
    assertTrue(inputText.text().contains("\"sourceBcp47Tag\":\"en\""));
    assertTrue(inputText.text().contains("\"sourceText\":\"Hello\""));
    assertTrue(inputText.text().contains("\"targetLocale\":\"fr-FR\""));
    assertTrue(inputText.text().contains("\"targetLocale\":\"de-DE\""));
  }

  @Test
  public void translateLiveWithOpenAiWhenExplicitlyEnabled() {
    Assume.assumeTrue(Boolean.getBoolean("OpenAIMTEngineTest.live"));
    String apiKey = System.getenv("OPENAI_API_KEY");
    Assume.assumeTrue(apiKey != null && !apiKey.isBlank());

    AiTranslateConfigurationProperties properties = new AiTranslateConfigurationProperties();
    properties.setModelName(System.getProperty("OpenAIMTEngineTest.model", "gpt-5.4"));
    properties.getResponses().setReasoningEffort("low");
    properties.getResponses().setTextVerbosity("low");
    OpenAIMTEngine liveEngine =
        new OpenAIMTEngine(
            new OpenAIClient.Builder().apiKey(apiKey).build(),
            properties,
            new PlaceholderEncoder(),
            new SimpleMeterRegistry());

    String sourceText = "Good morning";
    ImmutableMap<String, ImmutableList<TranslationDTO>> translationsBySourceText =
        liveEngine.getTranslationsBySourceText(
            List.of(sourceText), "en", List.of("fr-FR"), TextType.TEXT, null, false);

    TranslationDTO translation = translationsBySourceText.get(sourceText).getFirst();
    assertEquals(TranslationSource.OPENAI_MT, translation.getTranslationSource());
    assertEquals("fr-FR", translation.getBcp47Tag());
    assertTrue(translation.getText() != null && !translation.getText().isBlank());
    assertNotEquals(sourceText, translation.getText());
  }

  private OpenAIClient.ResponsesResponse responsesResponse(String outputText) {
    return new OpenAIClient.ResponsesResponse(
        "resp-1",
        "response",
        1712975853L,
        "completed",
        null,
        null,
        "gpt-test",
        List.of(
            new OpenAIClient.ResponsesResponse.Output(
                "msg-1",
                "message",
                "completed",
                List.of(new OpenAIClient.ResponsesResponse.Content("output_text", outputText)),
                "assistant")),
        null,
        null);
  }
}
