package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class AiTranslateConfigurationPropertiesTest {

  @Test
  public void messageFormatValidationIsDisabledByDefault() {
    assertFalse(new AiTranslateConfigurationProperties().isMessageFormatValidationEnabled());
  }

  @Test
  public void deploymentCanEnableAndDisableMessageFormatValidation() {
    for (boolean enabled : new boolean[] {true, false}) {
      AiTranslateConfigurationProperties properties =
          new Binder(
                  new MapConfigurationPropertySource(
                      Map.of(
                          "l10n.ai-translate.message-format-validation-enabled",
                          Boolean.toString(enabled))))
              .bind("l10n.ai-translate", Bindable.of(AiTranslateConfigurationProperties.class))
              .get();

      assertEquals(enabled, properties.isMessageFormatValidationEnabled());
    }
  }

  @Test
  public void defaultsUseMaximumReasoningAndStandardProcessing() {
    AiTranslateConfigurationProperties properties = new AiTranslateConfigurationProperties();

    assertEquals("max", properties.getResponses().getReasoningEffort());
    assertEquals("default", properties.getResponses().getServiceTier());
    assertEquals(
        204,
        properties
            .getNoBatch()
            .getTimeout()
            .applyReasoningEffortMultiplier(17, properties.getResponses().getReasoningEffort()));
  }

  @Test
  public void highestReasoningLevelsDoNotFallBackToNonReasoningTimeout() {
    AiTranslateConfigurationProperties.NoBatchProperties.TimeoutProperties timeout =
        new AiTranslateConfigurationProperties.NoBatchProperties.TimeoutProperties();

    assertEquals(102, timeout.applyReasoningEffortMultiplier(17, "high"));
    assertEquals(136, timeout.applyReasoningEffortMultiplier(17, " XHIGH "));
    assertEquals(204, timeout.applyReasoningEffortMultiplier(17, " MAX "));
  }

  @Test
  public void deploymentCanOverrideSpeedAndHighestReasoningTimeouts() {
    AiTranslateConfigurationProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "l10n.ai-translate.responses.reasoning-effort", "xhigh",
                        "l10n.ai-translate.responses.service-tier", "ultrafast",
                        "l10n.ai-translate.no-batch.timeout.reasoning-xhigh-multiplier", "9.0",
                        "l10n.ai-translate.no-batch.timeout.reasoning-max-multiplier", "14.0")))
            .bind("l10n.ai-translate", Bindable.of(AiTranslateConfigurationProperties.class))
            .get();

    assertEquals("xhigh", properties.getResponses().getReasoningEffort());
    assertEquals("ultrafast", properties.getResponses().getServiceTier());
    assertEquals(
        153, properties.getNoBatch().getTimeout().applyReasoningEffortMultiplier(17, "xhigh"));
    assertEquals(
        238, properties.getNoBatch().getTimeout().applyReasoningEffortMultiplier(17, "max"));
  }
}
