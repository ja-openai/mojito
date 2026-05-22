package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AsyncJobQueueInspectionServiceConfigurationTest {

  @Test
  public void inspectionServiceBeanIsNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(AsyncJobQueueInspectionService.class, InspectionServiceTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobQueueInspectionService.class)).isEmpty();
    }
  }

  @Test
  public void inspectionServiceBeanIsCreatedWhenQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(AsyncJobQueueInspectionService.class, InspectionServiceTestConfig.class);

      context.refresh();

      assertThat(context.getBean(AsyncJobQueueInspectionService.class)).isNotNull();
    }
  }

  @Configuration
  static class InspectionServiceTestConfig {

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
