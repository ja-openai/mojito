package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

public class AsyncJobQueueCoordinatorConfigurationTest {

  @Test
  public void coordinatorBeanIsNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(AsyncJobQueueCoordinator.class, CoordinatorTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobQueueCoordinator.class)).isEmpty();
    }
  }

  @Test
  public void coordinatorBeanIsCreatedWhenQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(AsyncJobQueueCoordinator.class, CoordinatorTestConfig.class);

      context.refresh();

      assertThat(context.getBean(AsyncJobQueueCoordinator.class)).isNotNull();
    }
  }

  @Configuration
  static class CoordinatorTestConfig {

    @Bean
    AsyncJobStore asyncJobStore() {
      return new InMemoryAsyncJobStore();
    }

    @Bean
    AsyncJobQueueProperties asyncJobQueueProperties() {
      return new AsyncJobQueueProperties();
    }

    @Bean
    TaskScheduler taskScheduler() {
      return new ConcurrentTaskScheduler();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean(name = "lifecycleProcessor")
    LifecycleProcessor lifecycleProcessor() {
      return new NoOpLifecycleProcessor();
    }
  }

  private static class NoOpLifecycleProcessor implements LifecycleProcessor {

    @Override
    public void onRefresh() {}

    @Override
    public void onClose() {}

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public boolean isRunning() {
      return false;
    }
  }
}
