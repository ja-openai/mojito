package com.box.l10n.mojito.rest.textunit;

import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.JsonFormat.JsonSchema.createJsonSchema;
import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.SystemMessage.systemMessageBuilder;
import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.UserMessage.userMessageBuilder;
import static com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest.chatCompletionsRequest;
import static com.box.l10n.mojito.openai.OpenAIClient.TemperatureHelper.getTemperatureForReasoningModels;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsRequest;
import com.box.l10n.mojito.openai.OpenAIClient.ChatCompletionsResponse;
import com.box.l10n.mojito.rest.textunit.AiReviewType.AiReviewTextUnitVariantOutput;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewService.AiReviewTextUnitVariantInput;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiReviewChatWS {

  static Logger logger = LoggerFactory.getLogger(AiReviewChatWS.class);

  private final OpenAIClient openAIClient;
  private final AiReviewConfigurationProperties aiReviewConfigurationProperties;
  private final ObjectMapper objectMapper;
  private final TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService;

  public AiReviewChatWS(
      @Qualifier("openAIClientReview") @Nullable OpenAIClient openAIClient,
      AiReviewConfigurationProperties aiReviewConfigurationProperties,
      @Qualifier("objectMapperReview") ObjectMapper objectMapper,
      TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService) {
    this.openAIClient = openAIClient;
    this.aiReviewConfigurationProperties = Objects.requireNonNull(aiReviewConfigurationProperties);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.tmTextUnitIntegrityCheckService = Objects.requireNonNull(tmTextUnitIntegrityCheckService);
  }

  @PostMapping("/api/ai/review")
  @ResponseStatus(HttpStatus.OK)
  public AiReviewChatResponse chat(@RequestBody AiReviewChatRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    if (request.messages() == null || request.messages().isEmpty()) {
      throw new IllegalArgumentException("messages must not be empty");
    }

    String localeTag = hasText(request.localeTag()) ? request.localeTag().trim() : "en";

    if (openAIClient == null) {
      throw new IllegalStateException("openAIClientReview bean must be configured");
    }

    AiReviewTextUnitVariantInput.ExistingTarget existingTarget = null;
    String target = normalizeOptionalText(request.target());
    if (target != null) {
      existingTarget = new AiReviewTextUnitVariantInput.ExistingTarget(target, false);
    }

    String sourceDescription = normalizeOptionalText(request.sourceDescription());

    AiReviewTextUnitVariantInput aiReviewInput =
        new AiReviewTextUnitVariantInput(
            localeTag, request.source(), sourceDescription, existingTarget);

    String inputPayload = objectMapper.writeValueAsStringUnchecked(aiReviewInput);

    List<ChatCompletionsRequest.Message> messages = new ArrayList<>();
    messages.add(systemMessageBuilder().content(AiReviewType.PROMPT_ALL).build());
    messages.add(userMessageBuilder().content(inputPayload).build());
    String integrityContextMessage = buildIntegrityContextMessage(request.tmTextUnitId(), target);
    if (hasText(integrityContextMessage)) {
      messages.add(userMessageBuilder().content(integrityContextMessage).build());
    }

    List<AiReviewChatMessage> conversationMessages = new ArrayList<>();
    for (AiReviewChatMessage message : request.messages()) {
      if (message == null || !hasText(message.content())) {
        continue;
      }
      conversationMessages.add(message);
    }
    if (conversationMessages.isEmpty()) {
      throw new IllegalArgumentException("messages must include at least one non-empty message");
    }

    boolean hasUserMessage =
        conversationMessages.stream()
            .anyMatch(message -> "user".equals(normalizeChatRole(message.role())));
    if (!hasUserMessage) {
      conversationMessages.add(
          0, new AiReviewChatMessage("user", "Review the translation and suggest improvements."));
    }

    for (AiReviewChatMessage message : conversationMessages) {
      messages.add(
          userMessageBuilder()
              .role(normalizeChatRole(message.role()))
              .content(message.content())
              .build());
    }

    ChatCompletionsRequest chatCompletionsRequest =
        chatCompletionsRequest()
            .model(aiReviewConfigurationProperties.getModelName())
            .temperature(
                getTemperatureForReasoningModels(aiReviewConfigurationProperties.getModelName()))
            .maxCompletionTokens(null)
            .messages(messages)
            .responseFormat(
                new OpenAIClient.ChatCompletionsRequest.JsonFormat(
                    "json_schema",
                    new OpenAIClient.ChatCompletionsRequest.JsonFormat.JsonSchema(
                        true,
                        "request_json_format",
                        createJsonSchema(AiReviewTextUnitVariantOutput.class))))
            .build();

    logger.info(objectMapper.writeValueAsStringUnchecked(chatCompletionsRequest));

    ChatCompletionsResponse chatCompletionsResponse =
        openAIClient
            .getChatCompletions(chatCompletionsRequest, Duration.of(15, ChronoUnit.SECONDS))
            .join();

    logger.info(objectMapper.writeValueAsStringUnchecked(chatCompletionsResponse));

    String jsonResponse = chatCompletionsResponse.choices().getFirst().message().content();
    AiReviewTextUnitVariantOutput output =
        objectMapper.readValueUnchecked(jsonResponse, AiReviewTextUnitVariantOutput.class);

    String reply = output.target() != null ? output.target().explanation() : null;
    if (!hasText(reply) && output.reviewRequired() != null) {
      reply = output.reviewRequired().reason();
    }
    if (!hasText(reply)) {
      reply = "Here are the latest suggestions.";
    }

    List<AiReviewChatSuggestion> suggestions = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    if (output.target() != null) {
      addSuggestion(
          suggestions,
          seen,
          output.target().content(),
          output.target().confidenceLevel(),
          output.target().explanation());
    }
    if (output.altTarget() != null) {
      addSuggestion(
          suggestions,
          seen,
          output.altTarget().content(),
          output.altTarget().confidenceLevel(),
          output.altTarget().explanation());
    }

    AiReviewChatReview review = null;
    if (output.existingTargetRating() != null) {
      review =
          new AiReviewChatReview(
              output.existingTargetRating().score(), output.existingTargetRating().explanation());
    }

    return new AiReviewChatResponse(
        new AiReviewChatMessage("assistant", reply), suggestions, review);
  }

  private void addSuggestion(
      List<AiReviewChatSuggestion> suggestions,
      Set<String> seen,
      String content,
      Integer confidenceLevel,
      String explanation) {
    if (!hasText(content)) {
      return;
    }
    if (seen.add(content)) {
      suggestions.add(new AiReviewChatSuggestion(content, confidenceLevel, explanation));
    }
  }

  private String normalizeChatRole(String role) {
    if (!hasText(role)) {
      return "user";
    }
    String normalized = role.trim().toLowerCase(Locale.ROOT);
    return ("assistant".equals(normalized) || "user".equals(normalized)) ? normalized : "user";
  }

  private String normalizeOptionalText(String value) {
    if (!hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String buildIntegrityContextMessage(Long tmTextUnitId, String target) {
    if (tmTextUnitId == null || !hasText(target)) {
      return null;
    }

    try {
      tmTextUnitIntegrityCheckService.checkTMTextUnitIntegrity(tmTextUnitId, target);
      return null;
    } catch (IntegrityCheckException e) {
      String failureDetail =
          hasText(e.getMessage())
              ? e.getMessage().trim()
              : "The integrity checker reported a placeholder/tag mismatch.";
      return """
          Context only: placeholder/integrity check failed for the current target text.
          Issue: %s
          Prioritize preserving placeholders, tags, and ICU/message-format structure in suggestions.
          """
          .formatted(failureDetail);
    } catch (RuntimeException e) {
      logger.debug("Failed to run integrity check for AI review", e);
      return null;
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  public record AiReviewChatRequest(
      String source,
      String target,
      String localeTag,
      String sourceDescription,
      Long tmTextUnitId,
      List<AiReviewChatMessage> messages) {}

  public record AiReviewChatMessage(String role, String content) {}

  public record AiReviewChatSuggestion(
      String content, Integer confidenceLevel, String explanation) {}

  public record AiReviewChatReview(int score, String explanation) {}

  public record AiReviewChatResponse(
      AiReviewChatMessage message,
      List<AiReviewChatSuggestion> suggestions,
      AiReviewChatReview review) {}
}
