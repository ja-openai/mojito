package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AsyncJobQueueStoreConfigurationTest {

  @Test
  public void asyncJobStoreBeanIsNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(AsyncJobQueueConfiguration.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobStore.class)).isEmpty();
    }
  }

  @Test
  public void missingStorePropertyCreatesInMemoryStoreWhenQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(AsyncJobQueueConfiguration.class);

      context.refresh();

      assertThat(context.getBean(AsyncJobStore.class)).isInstanceOf(InMemoryAsyncJobStore.class);
    }
  }
}
