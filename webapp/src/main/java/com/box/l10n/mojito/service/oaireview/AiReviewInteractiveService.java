package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.security.UserPreferences;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserPreferencesService;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves account choices at submission; frozen settings and ownership survive the job boundary.
 */
@Service
public class AiReviewInteractiveService {
  private static final Logger logger = LoggerFactory.getLogger(AiReviewInteractiveService.class);
  private final AiReviewConfigurationProperties configuration;
  private final UserService users;
  private final UserPreferencesService preferences;
  private final PollableTaskService tasks;
  private final AiReviewRequestUsageService usage;
  private final ObjectMapper objectMapper;

  public AiReviewInteractiveService(
      AiReviewConfigurationProperties configuration,
      UserService users,
      UserPreferencesService preferences,
      PollableTaskService tasks,
      AiReviewRequestUsageService usage,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper) {
    this.configuration = configuration;
    this.users = users;
    this.preferences = preferences;
    this.tasks = tasks;
    this.usage = usage;
    this.objectMapper = objectMapper;
  }

  public Prepared prepare(AiReviewChatRequest request) {
    Long userId =
        users
            .getCurrentUser()
            .map(User::getId)
            .orElseThrow(() -> new AccessDeniedException("No authenticated user"));
    UserPreferences saved = preferences.getCurrentUserPreferences();
    String type = requestType(request);
    if ("automatic".equals(type) && saved.aiReviewAutomaticDisabled()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Automatic AI review is turned off.");
    }
    surface(request);
    boolean hasLegacySelector = request.profileId() != null || request.reasoningEffort() != null;
    if (request.presetId() != null && hasLegacySelector) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choose either an AI review preset or legacy model settings.");
    }
    if (!hasLegacySelector) {
      String preset =
          oneOf(
              request.presetId(),
              saved.aiReviewPreset(),
              Set.of("fastest", "fast", "balanced", "thorough", "deep", "ultra"));
      var selected = configuration.getInteractive().getPresets().get(preset);
      if (selected == null
          || selected.getModelName() == null
          || selected.getModelName().isBlank()
          || selected.getReasoningEffort() == null
          || selected.getReasoningEffort().isBlank()
          || selected.getServiceTier() == null
          || selected.getServiceTier().isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "The selected AI review preset is unavailable.");
      }
      return new Prepared(
          normalizeRequestLocale(request),
          userId,
          new Settings(
              preset,
              selected.getModelName(),
              selected.getReasoningEffort(),
              configuration.getResponses().getTextVerbosity(),
              selected.getServiceTier()));
    }
    String profile = request.profileId() == null ? saved.aiReviewProfile() : request.profileId();
    String reasoningEffort =
        oneOf(
            request.reasoningEffort(),
            saved.aiReviewReasoningEffort(),
            Set.of("low", "medium", "high"));
    var selected =
        switch (profile) {
          case "version_a" -> configuration.getInteractive().getVersionA();
          case "version_b" -> configuration.getInteractive().getVersionB();
          default ->
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown AI review model.");
        };
    return new Prepared(
        normalizeRequestLocale(request),
        userId,
        new Settings(
            profile,
            selected.getModelName(),
            reasoningEffort,
            configuration.getResponses().getTextVerbosity(),
            configuration.getResponses().getServiceTier()));
  }

  /** Drain jobs submitted by an older deployment without depending on an HTTP security context. */
  public Prepared prepareLegacyJob(AiReviewChatRequest request, Long taskId) {
    return new Prepared(
        normalizeRequestLocale(request),
        tasks.getCreatedByUserIdWithAncestorFallback(taskId),
        new Settings(
            "version_b",
            configuration.getModelName(),
            configuration.getResponses().getReasoningEffort(),
            configuration.getResponses().getTextVerbosity(),
            configuration.getResponses().getServiceTier()));
  }

  public Long start(Prepared prepared, Long taskId) {
    var request = prepared.request();
    var settings = prepared.settings();
    try {
      return usage.start(
          new AiReviewRequestUsageService.StartInput(
              prepared.userId(),
              taskId,
              request.tmTextUnitId(),
              request.localeTag(),
              surface(request),
              requestType(request),
              settings.profileId(),
              settings.modelName(),
              settings.reasoningEffort(),
              settings.serviceTier(),
              objectMapper.writeValueAsStringUnchecked(request)));
    } catch (RuntimeException e) {
      logger.warn("Unable to record AI review usage start", e);
      return null;
    }
  }

  public void finish(
      Long id,
      String status,
      long durationMs,
      String returnedModel,
      String returnedTier,
      AiReviewChatResponse response) {
    if (id == null) return;
    try {
      usage.finish(
          id,
          status,
          durationMs,
          returnedModel,
          returnedTier,
          response == null ? null : objectMapper.writeValueAsStringUnchecked(response));
    } catch (RuntimeException e) {
      logger.warn("Unable to record AI review usage outcome, usageId={}", id, e);
    }
  }

  private AiReviewChatRequest normalizeRequestLocale(AiReviewChatRequest request) {
    String locale = request.localeTag();
    String normalized = locale == null || locale.isBlank() ? "en" : locale.trim();
    if (normalized.length() > 64) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "AI review locale must be at most 64 characters.");
    }
    if (normalized.equals(locale)) {
      return request;
    }
    return new AiReviewChatRequest(
        request.source(),
        request.target(),
        normalized,
        request.sourceDescription(),
        request.tmTextUnitId(),
        request.messages(),
        request.profileId(),
        request.requestType(),
        request.surface(),
        request.reasoningEffort(),
        request.presetId());
  }

  private String requestType(AiReviewChatRequest request) {
    return oneOf(
        request.requestType(),
        "legacy",
        Set.of("automatic", "manual", "follow_up", "retry", "legacy"));
  }

  private String surface(AiReviewChatRequest request) {
    return oneOf(
        request.surface(), "unknown", Set.of("review_project", "text_unit_detail", "unknown"));
  }

  private String oneOf(String value, String fallback, Set<String> allowed) {
    if (value == null) return fallback;
    if (!allowed.contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid AI review request metadata.");
    }
    return value;
  }

  public record Settings(
      String profileId,
      String modelName,
      String reasoningEffort,
      String textVerbosity,
      String serviceTier) {}

  public record Prepared(AiReviewChatRequest request, Long userId, Settings settings) {}
}
