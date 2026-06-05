package com.box.l10n.mojito.service.jsonconfiglocalization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class StatsigJsonConfigLocalizationPropertiesTest {

  @Test
  public void getConsoleUrlReturnsNullWhenTemplateIsMissing() {
    StatsigJsonConfigLocalizationProperties properties =
        new StatsigJsonConfigLocalizationProperties();

    assertThat(properties.getConsoleUrl("config_id")).isNull();
  }

  @Test
  public void getConsoleUrlSubstitutesEncodedConfigId() {
    StatsigJsonConfigLocalizationProperties properties =
        new StatsigJsonConfigLocalizationProperties();
    properties.setConsoleUrlTemplate("https://example.test/dynamic-configs/{configId}");

    assertThat(properties.getConsoleUrl("config with spaces"))
        .isEqualTo("https://example.test/dynamic-configs/config%20with%20spaces");
  }
}
