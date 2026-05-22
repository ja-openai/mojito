package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AsyncJobQueueRetentionCleanerConfigurationTest {

  @Test
  public void retentionCleanerBeanIsNotCreatedByDefaultWhenQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(
          AsyncJobQueueProperties.class,
          AsyncJobQueueRetentionCleaner.class,
          RetentionCleanerTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobQueueRetentionCleaner.class)).isEmpty();
    }
  }

  @Test
  public void retentionCleanerBeanIsCreatedOnlyWhenRetentionIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of(
              "l10n.org.async-job-queue.enabled=true",
              "l10n.org.async-job-queue.retention.enabled=true")
          .applyTo(context);
      context.register(
          AsyncJobQueueProperties.class,
          AsyncJobQueueRetentionCleaner.class,
          RetentionCleanerTestConfig.class);

      context.refresh();

      assertThat(context.getBean(AsyncJobQueueRetentionCleaner.class)).isNotNull();
    }
  }

  @Configuration
  @EnableConfigurationProperties(AsyncJobQueueProperties.class)
  static class RetentionCleanerTestConfig {

    @Bean
    AsyncJobStore asyncJobStore() {
      return new InMemoryAsyncJobStore();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
