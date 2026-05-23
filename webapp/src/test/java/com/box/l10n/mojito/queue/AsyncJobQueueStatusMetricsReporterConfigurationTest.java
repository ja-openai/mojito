package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AsyncJobQueueStatusMetricsReporterConfigurationTest {

  @Test
  public void statusMetricsReporterBeanIsNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(
          AsyncJobQueueStatusMetricsReporter.class, StatusMetricsReporterTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobQueueStatusMetricsReporter.class)).isEmpty();
    }
  }

  @Test
  public void statusMetricsReporterBeanIsCreatedWhenQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(
          AsyncJobQueueStatusMetricsReporter.class, StatusMetricsReporterTestConfig.class);

      context.refresh();

      assertThat(context.getBean(AsyncJobQueueStatusMetricsReporter.class)).isNotNull();
    }
  }

  @Test
  public void statusMetricsReporterBeanIsNotCreatedWhenSchedulingIsDisabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("disablescheduling");
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(
          AsyncJobQueueStatusMetricsReporter.class, StatusMetricsReporterTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobQueueStatusMetricsReporter.class)).isEmpty();
    }
  }

  @Configuration
  static class StatusMetricsReporterTestConfig {

    @Bean
    AsyncJobStore asyncJobStore() {
      return new InMemoryAsyncJobStore();
    }

    @Bean
    AsyncJobQueueProperties asyncJobQueueProperties() {
      return new AsyncJobQueueProperties();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
