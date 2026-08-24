package com.box.l10n.mojito;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.JacksonConfigurationProperties;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

public class JacksonConfigurationPropertiesTest {

  private final ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(JacksonConfigurationProperties.class, TestConfiguration.class);

  @Test
  public void maxStringLengthDefaultsToThirtyMillionCharacters() {
    applicationContextRunner.run(
        context ->
            assertThat(context.getBean(JacksonConfigurationProperties.class).getMaxStringLength())
                .isEqualTo(30_000_000));
  }

  @Test
  public void maxStringLengthCanBeConfigured() {
    applicationContextRunner
        .withPropertyValues("l10n.jackson.max-string-length=40000000")
        .run(
            context ->
                assertThat(
                        context.getBean(JacksonConfigurationProperties.class).getMaxStringLength())
                    .isEqualTo(40_000_000));
  }

  @Configuration
  @EnableConfigurationProperties
  static class TestConfiguration {}
}
