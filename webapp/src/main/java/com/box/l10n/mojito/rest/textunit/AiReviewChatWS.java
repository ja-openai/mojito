package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.OpenAIClientResponseException;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesRequest;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesResponse;
import com.box.l10n.mojito.rest.textunit.AiReviewType.AiReviewTextUnitVariantOutput;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Settings;
import com.box.l10n.mojito.service.oaireview.AiReviewResponseValidator;
import com.box.l10n.mojito.service.oaireview.AiReviewResponseValidator.InvalidReviewResponseException;
import com.box.l10n.mojito.service.oaireview.AiReviewService.AiReviewTextUnitVariantInput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateLocalePromptSuffixService;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.google.common.base.Stopwatch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AiReviewChatWS {

  static final int MAX_RETRYABLE_PROVIDER_ATTEMPTS = 3;
  static final String DEFAULT_REVIEW_PROMPT = "Review the translation and suggest improvements.";

  static Logger logger = LoggerFactory.getLogger(AiReviewChatWS.class);

  private final OpenAIClient openAIClient;
  private final AiReviewConfigurationProperties aiReviewConfigurationProperties;
  private final ObjectMapper objectMapper;
  private final TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService;
  private final AiTranslateLocalePromptSuffixService aiTranslateLocalePromptSuffixService;
  private final MeterRegistry meterRegistry;
  private final AiReviewInteractiveService interactiveService;

  public AiReviewChatWS(
      @Qualifier("openAIClientReview") @Nullable OpenAIClient openAIClient,
      AiReviewConfigurationProperties aiReviewConfigurationProperties,
      @Qualifier("objectMapperReview") ObjectMapper objectMapper,
      TMTextUnitIntegrityCheckService tmTextUnitIntegrityCheckService,
      AiTranslateLocalePromptSuffixService aiTranslateLocalePromptSuffixService,
      MeterRegistry meterRegistry,
      AiReviewInteractiveService interactiveService) {
    this.openAIClient = openAIClient;
    this.aiReviewConfigurationProperties = Objects.requireNonNull(aiReviewConfigurationProperties);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.tmTextUnitIntegrityCheckService = Objects.requireNonNull(tmTextUnitIntegrityCheckService);
    this.aiTranslateLocalePromptSuffixService =
        Objects.requireNonNull(aiTranslateLocalePromptSuffixService);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.interactiveService = Objects.requireNonNull(interactiveService);
  }

  @PostMapping("/api/ai/review")
  @ResponseStatus(HttpStatus.OK)
  public AiReviewChatResponse chat(@RequestBody AiReviewChatRequest request) {
    return chatPrepared(prepare(request), null);
  }

  public Prepared prepare(AiReviewChatRequest request) {
    validateRequest(request);
    return interactiveService.prepare(request);
  }

  public AiReviewChatResponse chatLegacyJob(AiReviewChatRequest request, Long taskId) {
    return chatPrepared(interactiveService.prepareLegacyJob(request, taskId), taskId);
  }

  public AiReviewChatResponse chatPrepared(Prepared prepared, Long taskId) {
    AiReviewChatRequest request = prepared.request();
    Settings settings = prepared.settings();
    validateRequest(request);

    String localeTag = request.localeTag();

    AiReviewTextUnitVariantInput.ExistingTarget existingTarget = null;
    String target = hasText(request.target()) ? request.target() : null;
    if (target != null) {
      existingTarget = new AiReviewTextUnitVariantInput.ExistingTarget(target, false);
    }

    String sourceDescription = normalizeOptionalText(request.sourceDescription());

    AiReviewTextUnitVariantInput aiReviewInput =
        new AiReviewTextUnitVariantInput(
            localeTag, request.source(), sourceDescription, existingTarget);

    String inputPayload = objectMapper.writeValueAsStringUnchecked(aiReviewInput);

    ResponsesRequest.Builder requestBuilder =
        ResponsesRequest.builder()
            .model(settings.modelName())
            .instructions(getPrompt(localeTag))
            .reasoningEffort(settings.reasoningEffort())
            .textVerbosity(settings.textVerbosity())
            .serviceTier(settings.serviceTier())
            .addUserText(inputPayload)
            .addJsonSchema(AiReviewTextUnitVariantOutput.class);
    String integrityContextMessage = buildIntegrityContextMessage(request.tmTextUnitId(), target);
    if (hasText(integrityContextMessage)) {
      requestBuilder.addUserText(integrityContextMessage);
    }

    List<AiReviewChatMessage> conversationMessages = new ArrayList<>();
    for (AiReviewChatMessage message : request.messages()) {
      if (message == null || !hasText(message.content())) {
        continue;
      }
      if ("user".equals(normalizeChatRole(message.role()))) {
        conversationMessages.add(message);
      }
    }
    if (conversationMessages.isEmpty()) {
      conversationMessages.add(new AiReviewChatMessage("user", DEFAULT_REVIEW_PROMPT));
    }

    for (AiReviewChatMessage message : conversationMessages) {
      requestBuilder.addText(normalizeChatRole(message.role()), message.content());
    }

    ResponsesRequest responsesRequest = requestBuilder.build();

    logger.debug(objectMapper.writeValueAsStringUnchecked(responsesRequest));

    Duration requestTimeout =
        resolveRequestTimeout(
            request.source(),
            sourceDescription,
            target,
            integrityContextMessage,
            conversationMessages,
            settings.reasoningEffort());
    Stopwatch requestStopwatch = Stopwatch.createStarted();
    AiReviewTextUnitVariantOutput output;
    Long usageId = interactiveService.start(prepared, taskId);
    String returnedModel = null;
    String returnedTier = null;
    try {
      ResponsesResponse responsesResponse =
          getResponsesWithRetry(responsesRequest, localeTag, requestTimeout, settings);
      if (responsesResponse != null) {
        returnedModel = responsesResponse.model();
        returnedTier = responsesResponse.serviceTier();
      }
      String jsonResponse = AiReviewResponseValidator.outputText(responsesResponse);
      output = objectMapper.readValueUnchecked(jsonResponse, AiReviewTextUnitVariantOutput.class);
      if (output == null) {
        throw new InvalidReviewResponseException("AI review provider returned no review output.");
      }
      logger.debug(objectMapper.writeValueAsStringUnchecked(responsesResponse));
    } catch (InvalidReviewResponseException e) {
      ResponseStatusException failure =
          new ResponseStatusException(
              HttpStatus.BAD_GATEWAY,
              "AI review provider returned an incomplete response. Please retry.",
              e);
      recordRequestDuration(localeTag, failure, requestStopwatch, settings);
      interactiveService.finish(
          usageId,
          getRequestResultTag(failure),
          requestStopwatch.elapsed().toMillis(),
          returnedModel,
          returnedTier);
      throw failure;
    } catch (RuntimeException e) {
      recordRequestDuration(localeTag, e, requestStopwatch, settings);
      interactiveService.finish(
          usageId,
          getRequestResultTag(e),
          requestStopwatch.elapsed().toMillis(),
          returnedModel,
          returnedTier);
      throw e;
    }
    recordRequestDuration(localeTag, null, requestStopwatch, settings);
    interactiveService.finish(
        usageId, "completed", requestStopwatch.elapsed().toMillis(), returnedModel, returnedTier);

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
    if (hasUsefulExistingTargetRating(output.existingTargetRating())) {
      review =
          new AiReviewChatReview(
              output.existingTargetRating().score(), output.existingTargetRating().explanation());
    }

    return new AiReviewChatResponse(
        new AiReviewChatMessage("assistant", reply), suggestions, review);
  }

  void validateRequest(AiReviewChatRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    if (request.messages() == null || request.messages().isEmpty()) {
      throw new IllegalArgumentException("messages must not be empty");
    }
    if (openAIClient == null) {
      throw new IllegalStateException("openAIClientReview bean must be configured");
    }
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

  private String getPrompt(String localeTag) {
    String promptSuffix = aiTranslateLocalePromptSuffixService.getLocalePromptSuffix(localeTag);
    return promptSuffix == null
        ? AiReviewType.PROMPT_ALL
        : "%s %s".formatted(AiReviewType.PROMPT_ALL, promptSuffix);
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

  private boolean hasUsefulExistingTargetRating(
      AiReviewTextUnitVariantOutput.ExistingTargetRating rating) {
    return rating != null && isUsefulRatingScore(rating.score()) && hasText(rating.explanation());
  }

  private boolean isUsefulRatingScore(Integer score) {
    return score != null && score >= 0 && score <= 2;
  }

  private Duration resolveRequestTimeout(
      String source,
      String sourceDescription,
      String target,
      String integrityContextMessage,
      List<AiReviewChatMessage> conversationMessages,
      String reasoningEffort) {
    int textCharCount =
        safeLength(source)
            + safeLength(sourceDescription)
            + safeLength(target)
            + safeLength(integrityContextMessage)
            + conversationMessages.stream()
                .mapToInt(message -> safeLength(message.content()))
                .sum();
    return aiReviewConfigurationProperties
        .getTimeout()
        .resolveRequestTimeout(conversationMessages.size(), textCharCount, reasoningEffort);
  }

  private ResponseStatusException toResponseStatusException(
      CompletionException e, String localeTag, Duration requestTimeout, Settings settings) {
    Throwable cause = unwrapCompletionException(e);
    if (cause instanceof HttpTimeoutException) {
      long timeoutSeconds = requestTimeout.toSeconds();
      meterRegistry
          .counter(
              "AiReviewChatWS.timeouts",
              Tags.of(
                  "model",
                  sanitizeTagValue(settings.modelName()),
                  "locale",
                  sanitizeTagValue(localeTag)))
          .increment();
      logger.warn("AI review request timed out, timeoutSeconds={}", timeoutSeconds, cause);
      return new ResponseStatusException(
          HttpStatus.GATEWAY_TIMEOUT,
          "AI review request timed out after %d seconds. Please retry.".formatted(timeoutSeconds),
          cause);
    }
    if (cause instanceof OpenAIClientResponseException openAIClientResponseException) {
      meterRegistry
          .counter(
              "AiReviewChatWS.providerFailures",
              Tags.of(
                  "model",
                  sanitizeTagValue(settings.modelName()),
                  "locale",
                  sanitizeTagValue(localeTag),
                  "statusCode",
                  Integer.toString(openAIClientResponseException.getStatusCode())))
          .increment();
      logger.warn(
          "AI review provider request failed, statusCode={}, model={}, locale={}",
          openAIClientResponseException.getStatusCode(),
          settings.modelName(),
          localeTag,
          cause);
      return new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "AI review provider request failed. Please retry.", cause);
    }
    logger.warn("AI review request failed unexpectedly", cause);
    return new ResponseStatusException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "AI review request failed unexpectedly. Please retry.",
        cause);
  }

  private int safeLength(String value) {
    return value == null ? 0 : value.length();
  }

  private ResponsesResponse getResponsesWithRetry(
      ResponsesRequest responsesRequest,
      String localeTag,
      Duration requestTimeout,
      Settings settings) {
    CompletionException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_RETRYABLE_PROVIDER_ATTEMPTS; attempt++) {
      try {
        return openAIClient.getResponses(responsesRequest, requestTimeout).join();
      } catch (CompletionException e) {
        lastFailure = e;
        Throwable cause = unwrapCompletionException(e);
        if (!(cause instanceof OpenAIClientResponseException openAIClientResponseException)
            || !isRetryableProviderStatus(openAIClientResponseException.getStatusCode())
            || attempt == MAX_RETRYABLE_PROVIDER_ATTEMPTS) {
          throw toResponseStatusException(e, localeTag, requestTimeout, settings);
        }
        logger.warn(
            "Retrying AI review provider request after statusCode={}, attempt={}, model={}, locale={}",
            openAIClientResponseException.getStatusCode(),
            attempt,
            settings.modelName(),
            localeTag);
      }
    }
    throw toResponseStatusException(lastFailure, localeTag, requestTimeout, settings);
  }

  private Throwable unwrapCompletionException(Throwable throwable) {
    return throwable instanceof CompletionException completionException
        ? Objects.requireNonNullElse(completionException.getCause(), completionException)
        : throwable;
  }

  private boolean isRetryableProviderStatus(int statusCode) {
    return statusCode == 408 || statusCode == 429 || statusCode >= 500;
  }

  private String sanitizeTagValue(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value;
  }

  private void recordRequestDuration(
      String localeTag, RuntimeException failure, Stopwatch requestStopwatch, Settings settings) {
    meterRegistry
        .timer(
            "AiReviewChatWS.requestDuration",
            Tags.of(
                "model",
                sanitizeTagValue(settings.modelName()),
                "locale",
                sanitizeTagValue(localeTag),
                "result",
                getRequestResultTag(failure)))
        .record(requestStopwatch.elapsed());
  }

  private String getRequestResultTag(RuntimeException failure) {
    if (failure == null) {
      return "completed";
    }
    if (failure instanceof ResponseStatusException responseStatusException) {
      if (HttpStatus.GATEWAY_TIMEOUT.equals(responseStatusException.getStatusCode())) {
        return "timeout";
      }
      if (HttpStatus.BAD_GATEWAY.equals(responseStatusException.getStatusCode())) {
        return "provider_failed";
      }
    }
    return "failed";
  }

  public record AiReviewChatRequest(
      String source,
      String target,
      String localeTag,
      String sourceDescription,
      Long tmTextUnitId,
      List<AiReviewChatMessage> messages,
      String profileId,
      String requestType,
      String surface,
      String reasoningEffort,
      String presetId) {
    public AiReviewChatRequest(
        String source,
        String target,
        String localeTag,
        String sourceDescription,
        Long tmTextUnitId,
        List<AiReviewChatMessage> messages,
        String profileId,
        String requestType,
        String surface,
        String reasoningEffort) {
      this(
          source,
          target,
          localeTag,
          sourceDescription,
          tmTextUnitId,
          messages,
          profileId,
          requestType,
          surface,
          reasoningEffort,
          null);
    }

    public AiReviewChatRequest(
        String source,
        String target,
        String localeTag,
        String sourceDescription,
        Long tmTextUnitId,
        List<AiReviewChatMessage> messages,
        String profileId,
        String requestType,
        String surface) {
      this(
          source,
          target,
          localeTag,
          sourceDescription,
          tmTextUnitId,
          messages,
          profileId,
          requestType,
          surface,
          null);
    }

    public AiReviewChatRequest(
        String source,
        String target,
        String localeTag,
        String sourceDescription,
        Long tmTextUnitId,
        List<AiReviewChatMessage> messages) {
      this(
          source,
          target,
          localeTag,
          sourceDescription,
          tmTextUnitId,
          messages,
          null,
          null,
          null,
          null);
    }
  }

  public record AiReviewChatMessage(String role, String content) {}

  public record AiReviewChatSuggestion(
      String content, Integer confidenceLevel, String explanation) {}

  public record AiReviewChatReview(int score, String explanation) {}

  public record AiReviewChatResponse(
      AiReviewChatMessage message,
      List<AiReviewChatSuggestion> suggestions,
      AiReviewChatReview review) {}
}
