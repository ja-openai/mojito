package com.box.l10n.mojito.service.oaireview;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Per-user admission and cluster-wide occupancy warnings are independent of Quartz's threads. */
@Component
@ConfigurationProperties("l10n.ai-review.execution")
public class AiReviewExecutionProperties {
  // Retain the existing configuration key; this is a warning threshold, not a global gate.
  private int maxInFlight = 800;
  private int maxInFlightPerUser = 12;
  private long timeoutSeconds = 180;
  private Map<String, Long> presetTimeoutSeconds = defaultPresetTimeoutSeconds();

  public int getMaxInFlight() {
    return maxInFlight;
  }

  public void setMaxInFlight(int value) {
    if (value < 1 || value > 1000) throw new IllegalArgumentException("Invalid review capacity");
    maxInFlight = value;
  }

  public int getMaxInFlightPerUser() {
    return maxInFlightPerUser;
  }

  public void setMaxInFlightPerUser(int value) {
    if (value < 1 || value > 1000)
      throw new IllegalArgumentException("Invalid per-user review capacity");
    maxInFlightPerUser = value;
  }

  public long getTimeoutSeconds() {
    return timeoutSeconds;
  }

  public void setTimeoutSeconds(long value) {
    if (value < 1 || value > 900) throw new IllegalArgumentException("Invalid review deadline");
    timeoutSeconds = value;
  }

  public Map<String, Long> getPresetTimeoutSeconds() {
    return Map.copyOf(presetTimeoutSeconds);
  }

  public void setPresetTimeoutSeconds(Map<String, Long> configured) {
    if (configured == null) throw new IllegalArgumentException("Missing review preset deadlines");
    Map<String, Long> merged = defaultPresetTimeoutSeconds();
    configured.forEach(
        (preset, seconds) -> {
          if (!merged.containsKey(preset))
            throw new IllegalArgumentException("Unknown review deadline preset: " + preset);
          if (seconds == null || seconds < 1 || seconds > 900)
            throw new IllegalArgumentException("Invalid review deadline for preset: " + preset);
          merged.put(preset, seconds);
        });
    presetTimeoutSeconds = merged;
  }

  /** Resolve from effective settings, after automatic-review policy has selected its preset. */
  public long resolveTimeoutSeconds(String presetId, String reasoningEffort) {
    Long budget = presetTimeoutSeconds.get(normalize(presetId));
    if (budget == null) {
      // Older requests use version_a/version_b rather than today's speed presets. Their actual
      // reasoning setting determines the fallback without coupling this policy to model names.
      String fallback =
          switch (normalize(reasoningEffort)) {
            case "none" -> "fast";
            case "low" -> "balanced";
            case "medium" -> "thorough";
            case "high" -> "deep";
            case "xhigh", "max" -> "ultra";
            default -> null;
          };
      budget = fallback == null ? timeoutSeconds : presetTimeoutSeconds.get(fallback);
    }
    return Math.min(timeoutSeconds, budget);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static Map<String, Long> defaultPresetTimeoutSeconds() {
    return new LinkedHashMap<>(
        Map.of(
            "fastest", 15L,
            "fast", 20L,
            "balanced", 30L,
            "thorough", 60L,
            "deep", 90L,
            "ultra", 180L));
  }
}
