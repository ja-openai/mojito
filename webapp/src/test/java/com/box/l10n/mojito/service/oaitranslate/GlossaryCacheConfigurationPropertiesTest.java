package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

public class GlossaryCacheConfigurationPropertiesTest {

  private final ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              GlossaryCacheConfigurationProperties.class, TestConfiguration.class);

  @Test
  public void cacheLifetimeDefaultsToFiveMinutes() {
    applicationContextRunner.run(
        context ->
            assertThat(context.getBean(GlossaryCacheConfigurationProperties.class).getTtl())
                .isEqualTo(Duration.ofMinutes(5)));
  }

  @Test
  public void cacheLifetimeCanBeConfigured() {
    applicationContextRunner
        .withPropertyValues("l10n.glossary.cache.ttl=45m")
        .run(
            context ->
                assertThat(context.getBean(GlossaryCacheConfigurationProperties.class).getTtl())
                    .isEqualTo(Duration.ofMinutes(45)));
  }

  @Configuration
  @EnableConfigurationProperties
  static class TestConfiguration {}
}
