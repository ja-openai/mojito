package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.OpenAIClientResponseException;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesCall;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
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

  public AiReviewChatResponse chat(AiReviewChatRequest request) {
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
    try {
      return chatPreparedAsync(prepared, taskId, Instant.MAX, () -> true).join();
    } catch (CompletionException failure) {
      Throwable cause = unwrapCompletionException(failure);
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      throw failure;
    }
  }

  /**
   * The dispatcher supplies the durable claim deadline and cancels this future when ownership ends.
   */
  public CompletableFuture<AiReviewChatResponse> chatPreparedAsync(
      Prepared prepared, Long taskId, Instant deadline, BooleanSupplier stillActive) {
    return chatPreparedCall(prepared, taskId, deadline, stillActive).result();
  }

  public record ReviewCall(
      CompletableFuture<AiReviewChatResponse> result, CompletableFuture<Void> transportSettled) {}

  public ReviewCall chatPreparedCall(
      Prepared prepared, Long taskId, Instant deadline, BooleanSupplier stillActive) {
    Objects.requireNonNull(deadline, "deadline must not be null");
    Objects.requireNonNull(stillActive, "stillActive must not be null");
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
            .instructions(getPrompt(localeTag, request.reviewStyle()))
            .reasoningEffort(settings.reasoningEffort())
            .textVerbosity(settings.textVerbosity())
            .serviceTier(settings.serviceTier())
            .addUserText(inputPayload)
            .addJsonSchema(AiReviewTextUnitVariantOutput.class);
    String integrityContextMessage =
        buildIntegrityContextMessage(request.tmTextUnitId(), target, localeTag);
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
    Long usageId = interactiveService.start(prepared, taskId);
    AtomicReference<ResponsesResponse> returnedResponse = new AtomicReference<>();
    AtomicReference<Duration> attemptTimeout = new AtomicReference<>(requestTimeout);
    CompletableFuture<AiReviewChatResponse> result = new CompletableFuture<>();
    ResponsesCall providerCall =
        getResponsesWithRetryAsync(
            responsesRequest,
            localeTag,
            requestTimeout,
            settings,
            deadline,
            stillActive,
            attemptTimeout);
    CompletableFuture<ResponsesResponse> provider = providerCall.response();
    result.whenComplete(
        (response, failure) -> {
          if (!provider.isDone()) provider.cancel(true);
          RuntimeException error =
              failure == null ? null : (RuntimeException) unwrapCompletionException(failure);
          ResponsesResponse returned = returnedResponse.get();
          recordRequestDuration(localeTag, error, requestStopwatch, settings);
          interactiveService.finish(
              usageId,
              getRequestResultTag(error),
              requestStopwatch.elapsed().toMillis(),
              returned == null ? null : returned.model(),
              returned == null ? null : returned.serviceTier(),
              response);
        });
    provider.whenComplete(
        (responsesResponse, failure) -> {
          if (result.isDone()) return;
          if (failure != null) {
            Throwable cause = unwrapCompletionException(failure);
            if (cause instanceof CancellationException) {
              result.cancel(false);
            } else {
              result.completeExceptionally(
                  toResponseStatusException(
                      new CompletionException(cause), localeTag, attemptTimeout.get(), settings));
            }
            return;
          }
          try {
            if (!stillActive.getAsBoolean()) {
              result.cancel(false);
              return;
            }
            if (!Instant.now().isBefore(deadline)) {
              result.completeExceptionally(
                  toResponseStatusException(
                      new CompletionException(new TimeoutException("AI review deadline elapsed")),
                      localeTag,
                      attemptTimeout.get(),
                      settings));
              return;
            }
            returnedResponse.set(responsesResponse);
            String jsonResponse = AiReviewResponseValidator.outputText(responsesResponse);
            AiReviewTextUnitVariantOutput output =
                objectMapper.readValueUnchecked(jsonResponse, AiReviewTextUnitVariantOutput.class);
            if (output == null) {
              throw new InvalidReviewResponseException(
                  "AI review provider returned no review output.");
            }
            logger.debug(objectMapper.writeValueAsStringUnchecked(responsesResponse));
            result.complete(toChatResponse(output, target));
          } catch (InvalidReviewResponseException error) {
            result.completeExceptionally(
                new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI review provider returned an incomplete response. Please retry.",
                    error));
          } catch (RuntimeException error) {
            result.completeExceptionally(error);
          }
        });
    return new ReviewCall(result, providerCall.transportSettled());
  }

  private AiReviewChatResponse toChatResponse(
      AiReviewTextUnitVariantOutput output, String originalTarget) {
    String reply = output.target() != null ? output.target().explanation() : null;
    if (!hasText(reply) && output.reviewRequired() != null) {
      reply = output.reviewRequired().reason();
    }
    if (!hasText(reply)) {
      reply = "Here are the latest suggestions.";
    }

    List<AiReviewChatSuggestion> suggestions = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    boolean hasOriginalAssessment =
        originalTarget != null && hasUsefulExistingTargetRating(output.existingTargetRating());
    boolean unchangedTarget =
        originalTarget != null
            && output.target() != null
            && originalTarget.equals(output.target().content());
    String primaryKind = null;
    if (hasOriginalAssessment) {
      if (output.existingTargetRating().score() == 2) {
        primaryKind = "alternative";
      } else if (!unchangedTarget) {
        primaryKind = "correction";
      }
    }
    if (output.target() != null) {
      addSuggestion(
          suggestions,
          seen,
          output.target().content(),
          output.target().confidenceLevel(),
          output.target().explanation(),
          primaryKind);
    }
    if (output.altTarget() != null) {
      addSuggestion(
          suggestions,
          seen,
          output.altTarget().content(),
          output.altTarget().confidenceLevel(),
          output.altTarget().explanation(),
          "alternative");
    }

    AiReviewChatReview review = null;
    if (hasOriginalAssessment) {
      review =
          new AiReviewChatReview(
              output.existingTargetRating().score(),
              output.existingTargetRating().explanation(),
              unchangedTarget ? output.target().confidenceLevel() : null);
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
      String explanation,
      String kind) {
    if (!hasText(content)) {
      return;
    }
    if (seen.add(content)) {
      suggestions.add(new AiReviewChatSuggestion(content, confidenceLevel, explanation, kind));
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

  private String getPrompt(String localeTag, String reviewStyle) {
    String prompt = AiReviewType.interactivePrompt(reviewStyle);
    String promptSuffix = aiTranslateLocalePromptSuffixService.getLocalePromptSuffix(localeTag);
    return promptSuffix == null ? prompt : "%s %s".formatted(prompt, promptSuffix);
  }

  private String buildIntegrityContextMessage(Long tmTextUnitId, String target, String localeTag) {
    if (tmTextUnitId == null || !hasText(target)) {
      return null;
    }

    try {
      tmTextUnitIntegrityCheckService.checkTMTextUnitIntegrityForLocale(
          tmTextUnitId, target, localeTag);
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
    if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
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
          cause instanceof TimeoutException
              ? "AI review request deadline elapsed. Please retry."
              : "AI review request timed out after %d seconds. Please retry."
                  .formatted(timeoutSeconds),
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

  private ResponsesCall getResponsesWithRetryAsync(
      ResponsesRequest request,
      String localeTag,
      Duration adaptiveTimeout,
      Settings settings,
      Instant deadline,
      BooleanSupplier stillActive,
      AtomicReference<Duration> attemptTimeout) {
    CompletableFuture<ResponsesResponse> result = new CompletableFuture<>();
    ResponseTransports transports = new ResponseTransports();
    AtomicReference<CompletableFuture<ResponsesResponse>> active = new AtomicReference<>();
    CompletableFuture<Void> deadlineSignal = new CompletableFuture<>();
    result.whenComplete(
        (response, error) -> {
          deadlineSignal.cancel(false);
          transports.close();
          CompletableFuture<ResponsesResponse> inFlight = active.get();
          if (inFlight != null && !inFlight.isDone()) inFlight.cancel(true);
        });
    if (!Instant.MAX.equals(deadline)) {
      Duration remaining = Duration.between(Instant.now(), deadline);
      if (remaining.isNegative() || remaining.isZero()) {
        result.completeExceptionally(new TimeoutException("AI review deadline elapsed"));
        return new ResponsesCall(result, transports.settled);
      }
      // Leave the shared JDK delay thread free while completion hooks persist usage/outcomes.
      deadlineSignal
          .orTimeout(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS)
          .exceptionallyAsync(
              error -> {
                if (unwrapCompletionException(error) instanceof TimeoutException) {
                  result.completeExceptionally(error);
                }
                return null;
              });
    }
    class Attempts {
      void run(int attempt) {
        if (result.isDone()) return;
        try {
          if (!stillActive.getAsBoolean()) {
            result.cancel(false);
            return;
          }
          Duration remaining = Duration.between(Instant.now(), deadline);
          if (remaining.isNegative() || remaining.isZero()) {
            result.completeExceptionally(new TimeoutException("AI review deadline elapsed"));
            return;
          }
          Duration timeout = remaining.compareTo(adaptiveTimeout) < 0 ? remaining : adaptiveTimeout;
          attemptTimeout.set(timeout);
          if (!transports.start()) return;
          ResponsesCall providerCall;
          try {
            providerCall = openAIClient.getResponsesCall(request, timeout);
          } catch (RuntimeException failure) {
            transports.finished();
            throw failure;
          }
          providerCall.transportSettled().whenComplete((response, error) -> transports.finished());
          CompletableFuture<ResponsesResponse> provider = providerCall.response();
          active.set(provider);
          if (result.isDone()) {
            provider.cancel(true);
            return;
          }
          provider.whenComplete(
              (response, error) -> {
                if (result.isDone()) return;
                if (error == null) {
                  result.complete(response);
                  return;
                }
                Throwable cause = unwrapCompletionException(error);
                if (cause instanceof OpenAIClientResponseException providerError
                    && isRetryableProviderStatus(providerError.getStatusCode())
                    && attempt < MAX_RETRYABLE_PROVIDER_ATTEMPTS) {
                  logger.warn(
                      "Retrying AI review provider request after statusCode={}, attempt={}, model={}, locale={}",
                      providerError.getStatusCode(),
                      attempt,
                      settings.modelName(),
                      localeTag);
                  run(attempt + 1);
                } else {
                  result.completeExceptionally(cause);
                }
              });
        } catch (RuntimeException error) {
          result.completeExceptionally(error);
        }
      }
    }
    new Attempts().run(1);
    return new ResponsesCall(result, transports.settled);
  }

  /** Includes an attempt being created when cancellation races with publishing its HTTP handle. */
  private static final class ResponseTransports {
    private final CompletableFuture<Void> settled = new CompletableFuture<>();
    private int active;
    private boolean closed;

    synchronized boolean start() {
      if (closed) return false;
      active++;
      return true;
    }

    synchronized void finished() {
      active--;
      if (closed && active == 0) settled.complete(null);
    }

    synchronized void close() {
      closed = true;
      if (active == 0) settled.complete(null);
    }
  }

  private Throwable unwrapCompletionException(Throwable throwable) {
    while (throwable instanceof CompletionException && throwable.getCause() != null) {
      throwable = throwable.getCause();
    }
    return throwable;
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
      String presetId,
      String reviewStyle) {
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
        String reasoningEffort,
        String presetId) {
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
          presetId,
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
      String content, Integer confidenceLevel, String explanation, String kind) {
    public AiReviewChatSuggestion(String content, Integer confidenceLevel, String explanation) {
      this(content, confidenceLevel, explanation, null);
    }
  }

  public record AiReviewChatReview(int score, String explanation, Integer confidenceLevel) {
    public AiReviewChatReview(int score, String explanation) {
      this(score, explanation, null);
    }
  }

  public record AiReviewChatResponse(
      AiReviewChatMessage message,
      List<AiReviewChatSuggestion> suggestions,
      AiReviewChatReview review) {}
}
