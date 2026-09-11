package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class AiReviewExecutionPropertiesTest {
  @Test
  public void presetDefaultsBoundTheWholeReview() {
    AiReviewExecutionProperties properties = new AiReviewExecutionProperties();

    assertEquals(180, properties.getTimeoutSeconds());
    assertEquals(15, properties.resolveTimeoutSeconds("fastest", "none"));
    assertEquals(20, properties.resolveTimeoutSeconds("fast", "none"));
    assertEquals(30, properties.resolveTimeoutSeconds("balanced", "low"));
    assertEquals(60, properties.resolveTimeoutSeconds("thorough", "medium"));
    assertEquals(90, properties.resolveTimeoutSeconds("deep", "high"));
    assertEquals(180, properties.resolveTimeoutSeconds("ultra", "max"));
    // A configured preset remains authoritative if its model's reasoning mapping changes.
    assertEquals(20, properties.resolveTimeoutSeconds("fast", "high"));
  }

  @Test
  public void partialSpringConfigurationRetainsUnspecifiedPresetDefaults() {
    AiReviewExecutionProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "l10n.ai-review.execution.preset-timeout-seconds.fastest", "12",
                        "l10n.ai-review.execution.preset-timeout-seconds.balanced", "40")))
            .bind("l10n.ai-review.execution", Bindable.of(AiReviewExecutionProperties.class))
            .get();

    assertEquals(12, properties.resolveTimeoutSeconds("fastest", "none"));
    assertEquals(20, properties.resolveTimeoutSeconds("fast", "none"));
    assertEquals(40, properties.resolveTimeoutSeconds("balanced", "low"));
    assertEquals(60, properties.resolveTimeoutSeconds("thorough", "medium"));
    assertEquals(90, properties.resolveTimeoutSeconds("deep", "high"));
    assertEquals(180, properties.resolveTimeoutSeconds("ultra", "max"));
    assertEquals(6, properties.getPresetTimeoutSeconds().size());
  }

  @Test
  public void globalCeilingAppliesToPresetsOverridesAndLegacyFallbacks() {
    AiReviewExecutionProperties properties = new AiReviewExecutionProperties();
    properties.setTimeoutSeconds(25);
    properties.setPresetTimeoutSeconds(Map.of("fast", 35L));

    assertEquals(15, properties.resolveTimeoutSeconds("fastest", "none"));
    assertEquals(25, properties.resolveTimeoutSeconds("fast", "none"));
    assertEquals(25, properties.resolveTimeoutSeconds("balanced", "low"));
    assertEquals(25, properties.resolveTimeoutSeconds("ultra", "max"));
    assertEquals(25, properties.resolveTimeoutSeconds("version_b", "max"));
    assertEquals(25, properties.resolveTimeoutSeconds(null, null));
  }

  @Test
  public void legacyAndUnknownPresetsUseEffectiveReasoning() {
    AiReviewExecutionProperties properties = new AiReviewExecutionProperties();
    for (String preset : new String[] {"version_a", "version_b", "unknown", null}) {
      assertEquals(20, properties.resolveTimeoutSeconds(preset, "none"));
      assertEquals(30, properties.resolveTimeoutSeconds(preset, "low"));
      assertEquals(60, properties.resolveTimeoutSeconds(preset, "medium"));
      assertEquals(90, properties.resolveTimeoutSeconds(preset, "high"));
      assertEquals(180, properties.resolveTimeoutSeconds(preset, "xhigh"));
      assertEquals(180, properties.resolveTimeoutSeconds(preset, "max"));
      assertEquals(180, properties.resolveTimeoutSeconds(preset, "unknown"));
      assertEquals(180, properties.resolveTimeoutSeconds(preset, null));
    }
    properties.setPresetTimeoutSeconds(Map.of("thorough", 45L));
    assertEquals(45, properties.resolveTimeoutSeconds("version_b", " MEDIUM "));
  }

  @Test
  public void invalidPresetOverridesFailBeforeReplacingConfiguration() {
    AiReviewExecutionProperties properties = new AiReviewExecutionProperties();
    for (long invalid : new long[] {0, -1, 901}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> properties.setPresetTimeoutSeconds(Map.of("fast", invalid)));
      assertThrows(IllegalArgumentException.class, () -> properties.setTimeoutSeconds(invalid));
    }
    Map<String, Long> missing = new HashMap<>();
    missing.put("fast", null);
    assertThrows(IllegalArgumentException.class, () -> properties.setPresetTimeoutSeconds(missing));
    assertThrows(IllegalArgumentException.class, () -> properties.setPresetTimeoutSeconds(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setPresetTimeoutSeconds(Map.of("typo", 10L)));
    assertEquals(20, properties.resolveTimeoutSeconds("fast", "none"));
  }

  @Test
  public void validBoundaryOverridesRemainSubjectToTheGlobalCeiling() {
    AiReviewExecutionProperties properties = new AiReviewExecutionProperties();
    properties.setPresetTimeoutSeconds(Map.of("fastest", 1L, "ultra", 900L));
    assertEquals(1, properties.resolveTimeoutSeconds("fastest", "none"));
    assertEquals(180, properties.resolveTimeoutSeconds("ultra", "max"));
    properties.setTimeoutSeconds(900);
    assertEquals(900, properties.resolveTimeoutSeconds("ultra", "max"));
  }
}
