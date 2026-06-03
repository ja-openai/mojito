package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AsyncJobQueueSubmissionServiceConfigurationTest {

  @Test
  public void submissionServiceBeanIsNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(AsyncJobQueueSubmissionService.class, SubmissionServiceTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobQueueSubmissionService.class)).isEmpty();
    }
  }

  @Test
  public void submissionServiceBeanIsCreatedWhenQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(AsyncJobQueueSubmissionService.class, SubmissionServiceTestConfig.class);

      context.refresh();

      assertThat(context.getBean(AsyncJobQueueSubmissionService.class)).isNotNull();
    }
  }

  @Configuration
  static class SubmissionServiceTestConfig {

    @Bean
    AsyncJobStore asyncJobStore() {
      return new InMemoryAsyncJobStore();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    AsyncJobQueueCoordinator asyncJobQueueCoordinator() {
      return mock(AsyncJobQueueCoordinator.class);
    }
  }
}
