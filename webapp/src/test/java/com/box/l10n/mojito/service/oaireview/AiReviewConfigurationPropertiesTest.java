package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import java.util.Map;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class AiReviewConfigurationPropertiesTest {

  @Test
  public void defaultsUseMaximumReasoningAndStandardProcessing() {
    AiReviewConfigurationProperties properties = new AiReviewConfigurationProperties();

    assertEquals("max", properties.getResponses().getReasoningEffort());
    assertEquals("default", properties.getResponses().getServiceTier());
    assertEquals(
        Duration.ofSeconds(204),
        properties
            .getTimeout()
            .resolveRequestTimeout(1, 20, properties.getResponses().getReasoningEffort()));
  }

  @Test
  public void highestReasoningLevelsHaveLargerBoundedTimeouts() {
    AiReviewConfigurationProperties.TimeoutProperties timeout =
        new AiReviewConfigurationProperties.TimeoutProperties();

    assertEquals(Duration.ofSeconds(102), timeout.resolveRequestTimeout(1, 20, "high"));
    assertEquals(Duration.ofSeconds(136), timeout.resolveRequestTimeout(1, 20, " XHIGH "));
    assertEquals(Duration.ofSeconds(204), timeout.resolveRequestTimeout(1, 20, " MAX "));
    assertEquals(Duration.ofSeconds(300), timeout.resolveRequestTimeout(20, 5000, "max"));
  }

  @Test
  public void deploymentCanOverrideSpeedAndHighestReasoningTimeouts() {
    AiReviewConfigurationProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "l10n.ai-review.responses.reasoning-effort", "xhigh",
                        "l10n.ai-review.responses.service-tier", "ultrafast",
                        "l10n.ai-review.timeout.reasoning-xhigh-multiplier", "9.0",
                        "l10n.ai-review.timeout.reasoning-max-multiplier", "14.0")))
            .bind("l10n.ai-review", Bindable.of(AiReviewConfigurationProperties.class))
            .get();

    assertEquals("xhigh", properties.getResponses().getReasoningEffort());
    assertEquals("ultrafast", properties.getResponses().getServiceTier());
    assertEquals(
        Duration.ofSeconds(153), properties.getTimeout().resolveRequestTimeout(1, 20, "xhigh"));
    assertEquals(
        Duration.ofSeconds(238), properties.getTimeout().resolveRequestTimeout(1, 20, "max"));
  }

  @Test
  public void deploymentCanOverrideOnePresetWithoutReplacingOtherDefaults() {
    AiReviewConfigurationProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "l10n.ai-review.interactive.presets.fast.model-name", "configured-model",
                        "l10n.ai-review.interactive.presets.fast.reasoning-effort", "high",
                        "l10n.ai-review.interactive.presets.fast.service-tier", "default",
                        "l10n.ai-review.interactive.presets.fastest.service-tier", "default")))
            .bind("l10n.ai-review", Bindable.of(AiReviewConfigurationProperties.class))
            .get();

    var fast = properties.getInteractive().getPresets().get("fast");
    assertEquals("configured-model", fast.getModelName());
    assertEquals("high", fast.getReasoningEffort());
    assertEquals("default", fast.getServiceTier());
    var balanced = properties.getInteractive().getPresets().get("balanced");
    assertEquals("gpt-6-astra", balanced.getModelName());
    assertEquals("low", balanced.getReasoningEffort());
    assertEquals("priority", balanced.getServiceTier());
    var fastest = properties.getInteractive().getPresets().get("fastest");
    assertEquals("gpt-5.6-luna", fastest.getModelName());
    assertEquals("none", fastest.getReasoningEffort());
    assertEquals("default", fastest.getServiceTier());
    assertEquals(6, properties.getInteractive().getPresets().size());
    assertEquals("default", properties.getResponses().getServiceTier());
  }
}
