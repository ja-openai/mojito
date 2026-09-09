package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.entity.AiReviewRequestUsage;
import com.box.l10n.mojito.service.security.user.UserRepository;
import java.time.ZonedDateTime;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiReviewRequestUsageService {

  public record StartInput(
      Long userId,
      Long pollableTaskId,
      Long tmTextUnitId,
      String locale,
      String surface,
      String requestType,
      String profileId,
      String modelName,
      String reasoningEffort,
      String requestedServiceTier,
      String requestJson) {}

  private static final Set<String> SURFACES =
      Set.of("review_project", "text_unit_detail", "unknown");
  private static final Set<String> REQUEST_TYPES =
      Set.of("automatic", "manual", "follow_up", "retry", "legacy");
  private static final Set<String> PROFILES =
      Set.of("version_a", "version_b", "fastest", "fast", "balanced", "thorough", "deep", "ultra");
  private static final Set<String> FINISHED_STATUSES =
      Set.of("completed", "timeout", "provider_failed", "failed");

  private final AiReviewRequestUsageRepository usageRepository;
  private final UserRepository userRepository;

  public AiReviewRequestUsageService(
      AiReviewRequestUsageRepository usageRepository, UserRepository userRepository) {
    this.usageRepository = usageRepository;
    this.userRepository = userRepository;
  }

  /** The caller captures the authenticated actor before entering an asynchronous job. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Long start(StartInput input) {
    AiReviewRequestUsage usage = new AiReviewRequestUsage();
    usage.setUser(input.userId() == null ? null : userRepository.getReferenceById(input.userId()));
    usage.setPollableTaskId(input.pollableTaskId());
    usage.setTmTextUnitId(input.tmTextUnitId());
    usage.setLocale(bounded(input.locale(), 64));
    usage.setSurface(allowed(input.surface(), SURFACES));
    usage.setRequestType(allowed(input.requestType(), REQUEST_TYPES));
    usage.setProfileId(allowed(input.profileId(), PROFILES));
    if (input.modelName() == null || input.modelName().isBlank()) {
      throw new IllegalArgumentException("AI review model name is required");
    }
    usage.setModelName(bounded(input.modelName(), 255));
    usage.setReasoningEffort(bounded(input.reasoningEffort(), 32));
    usage.setRequestedServiceTier(bounded(input.requestedServiceTier(), 32));
    usage.setRequestJson(input.requestJson());
    usage.setStatus("started");
    usage.setStartedAt(ZonedDateTime.now());
    return usageRepository.save(usage).getId();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(
      Long id,
      String status,
      long durationMs,
      String returnedModel,
      String returnedServiceTier,
      String responseJson) {
    allowed(status, FINISHED_STATUSES);
    if (durationMs < 0) {
      throw new IllegalArgumentException("AI review duration must not be negative");
    }
    if (id == null) {
      return;
    }
    usageRepository
        .findById(id)
        .filter(usage -> "started".equals(usage.getStatus()))
        .ifPresent(
            usage -> {
              usage.setStatus(status);
              usage.setFinishedAt(ZonedDateTime.now());
              usage.setDurationMs(durationMs);
              usage.setReturnedModel(bounded(returnedModel, 255));
              usage.setReturnedServiceTier(bounded(returnedServiceTier, 32));
              usage.setResponseJson("completed".equals(status) ? responseJson : null);
            });
  }

  private static String allowed(String value, Set<String> values) {
    if (value == null || !values.contains(value)) {
      throw new IllegalArgumentException("Unsupported AI review usage category");
    }
    return value;
  }

  private static String bounded(String value, int maxLength) {
    if (value != null && value.length() > maxLength) {
      throw new IllegalArgumentException("AI review usage metadata exceeds its maximum length");
    }
    return value;
  }
}
