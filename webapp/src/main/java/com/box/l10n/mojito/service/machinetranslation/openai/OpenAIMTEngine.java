package com.box.l10n.mojito.service.machinetranslation.openai;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.machinetranslation.MachineTranslationEngine;
import com.box.l10n.mojito.service.machinetranslation.PlaceholderEncoder;
import com.box.l10n.mojito.service.machinetranslation.TextType;
import com.box.l10n.mojito.service.machinetranslation.TranslationDTO;
import com.box.l10n.mojito.service.machinetranslation.TranslationSource;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateConfigurationProperties;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.micrometer.core.annotation.Timed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class OpenAIMTEngine implements MachineTranslationEngine {

  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
  static final String REASONING_EFFORT = "none";
  static final String TEXT_VERBOSITY = "low";

  static final String PROMPT =
      """
      You are a professional localization machine translation engine.

      Translate every item from sourceBcp47Tag to its targetLocale.
      Return exactly one translation object for every input item, preserving each id and targetLocale.
      Preserve placeholders, ICU MessageFormat syntax, HTML/XML tags and attributes, variables, and code-like tokens exactly.
      Translate only natural-language text. Do not add explanations or commentary.
      """;

  final OpenAIClient openAIClient;
  final AiTranslateConfigurationProperties aiTranslateConfigurationProperties;
  final PlaceholderEncoder placeholderEncoder;
  final ObjectMapper objectMapper;

  public OpenAIMTEngine(
      OpenAIClient openAIClient,
      AiTranslateConfigurationProperties aiTranslateConfigurationProperties,
      PlaceholderEncoder placeholderEncoder) {
    this.openAIClient = openAIClient;
    this.aiTranslateConfigurationProperties = aiTranslateConfigurationProperties;
    this.placeholderEncoder = placeholderEncoder;
    this.objectMapper = new ObjectMapper();
    AiTranslateService.configureObjectMapper(this.objectMapper);
  }

  @Override
  public TranslationSource getSource() {
    return TranslationSource.OPENAI_MT;
  }

  @Override
  @Timed("OpenAIMTEngine.translate")
  public ImmutableMap<String, ImmutableList<TranslationDTO>> getTranslationsBySourceText(
      List<String> textSources,
      String sourceBcp47Tag,
      List<String> targetBcp47Tags,
      TextType sourceTextType,
      String customModel,
      boolean isFunctionalProtectionEnabled) {
    List<String> requestTextSources =
        isFunctionalProtectionEnabled ? placeholderEncoder.encode(textSources) : textSources;

    List<TranslationRequestItem> items = new ArrayList<>();
    for (int sourceIndex = 0; sourceIndex < requestTextSources.size(); sourceIndex++) {
      for (String targetBcp47Tag : targetBcp47Tags) {
        items.add(
            new TranslationRequestItem(
                items.size(), requestTextSources.get(sourceIndex), targetBcp47Tag));
      }
    }

    OpenAITranslationRequest translationRequest =
        new OpenAITranslationRequest(
            sourceBcp47Tag, sourceTextType == null ? TextType.TEXT : sourceTextType, items);

    OpenAIClient.ResponsesRequest responsesRequest =
        OpenAIClient.ResponsesRequest.builder()
            .model(aiTranslateConfigurationProperties.getModelName())
            .instructions(PROMPT)
            .reasoningEffort(REASONING_EFFORT)
            .textVerbosity(TEXT_VERBOSITY)
            .addUserText(objectMapper.writeValueAsStringUnchecked(translationRequest))
            .addJsonSchema(OpenAITranslationResponse.class)
            .build();

    OpenAITranslationResponse translationResponse =
        objectMapper.readValueUnchecked(
            openAIClient.getResponses(responsesRequest, REQUEST_TIMEOUT).join().outputText(),
            OpenAITranslationResponse.class);

    Map<Integer, TranslationResponseItem> translationsById =
        translationResponse.translations().stream()
            .collect(ImmutableMap.toImmutableMap(TranslationResponseItem::id, Function.identity()));

    ImmutableMap.Builder<String, ImmutableList<TranslationDTO>> translationsBySourceText =
        ImmutableMap.builder();
    int itemIndex = 0;
    for (String sourceText : textSources) {
      ImmutableList.Builder<TranslationDTO> translations = ImmutableList.builder();
      for (String targetBcp47Tag : targetBcp47Tags) {
        TranslationRequestItem item = items.get(itemIndex++);
        TranslationResponseItem responseItem = translationsById.get(item.id());
        if (responseItem == null) {
          throw new IllegalStateException("Missing MT response for item id: " + item.id());
        }
        if (!targetBcp47Tag.equals(responseItem.targetLocale())) {
          throw new IllegalStateException(
              "MT response target locale mismatch for item id: " + item.id());
        }

        String text = responseItem.text();
        TranslationDTO translation = new TranslationDTO();
        translation.setTranslationSource(getSource());
        translation.setBcp47Tag(targetBcp47Tag);
        translation.setText(isFunctionalProtectionEnabled ? placeholderEncoder.decode(text) : text);
        translations.add(translation);
      }

      translationsBySourceText.put(sourceText, translations.build());
    }

    return translationsBySourceText.build();
  }

  public record OpenAITranslationRequest(
      String sourceBcp47Tag, TextType sourceTextType, List<TranslationRequestItem> items) {}

  public record TranslationRequestItem(int id, String sourceText, String targetLocale) {}

  public record OpenAITranslationResponse(List<TranslationResponseItem> translations) {}

  public record TranslationResponseItem(int id, String targetLocale, String text) {}
}
