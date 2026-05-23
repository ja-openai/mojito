package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.queue.AsyncJobQueueSubmissionService;
import com.box.l10n.mojito.queue.AsyncJobStore;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AssetLocalizeAsyncJobServiceConfigurationTest {

  @Test
  public void assetLocalizeAsyncJobComponentsAreNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = createContext()) {
      assertComponentsAreAbsent(context);
    }
  }

  @Test
  public void assetLocalizeAsyncJobComponentsAreNotCreatedWhenOnlyQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.enabled=true")) {
      assertComponentsAreAbsent(context);
    }
  }

  @Test
  public void assetLocalizeAsyncJobComponentsAreNotCreatedWhenOnlyAssetLocalizeQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertComponentsAreAbsent(context);
    }
  }

  @Test
  public void assetLocalizeAsyncJobComponentsAreCreatedWhenBothFlagsAreEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext(
            "l10n.org.async-job-queue.enabled=true",
            "l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertThat(context.getBean(AssetLocalizeAsyncJobSubmissionService.class)).isNotNull();
      assertThat(context.getBean(AssetLocalizeAsyncJobHandler.class)).isNotNull();
      assertThat(context.getBean(AssetLocalizeAsyncJobRepairService.class)).isNotNull();
    }
  }

  private AnnotationConfigApplicationContext createContext(String... properties) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of(properties).applyTo(context);
    context
        .getBeanFactory()
        .registerSingleton("pollableTaskService", mock(PollableTaskService.class));
    context
        .getBeanFactory()
        .registerSingleton("pollableTaskBlobStorage", mock(PollableTaskBlobStorage.class));
    context
        .getBeanFactory()
        .registerSingleton("pollableTaskExceptionUtils", mock(PollableTaskExceptionUtils.class));
    context
        .getBeanFactory()
        .registerSingleton(
            "localizedAssetGenerationService", mock(LocalizedAssetGenerationService.class));
    context
        .getBeanFactory()
        .registerSingleton(
            "asyncJobQueueSubmissionService", mock(AsyncJobQueueSubmissionService.class));
    context.getBeanFactory().registerSingleton("asyncJobStore", mock(AsyncJobStore.class));
    context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
    context.getBeanFactory().registerSingleton("meterRegistry", new SimpleMeterRegistry());
    context.register(
        AssetLocalizeAsyncJobSubmissionService.class,
        AssetLocalizeAsyncJobHandler.class,
        AssetLocalizeAsyncJobRepairService.class);
    context.refresh();
    return context;
  }

  private void assertComponentsAreAbsent(AnnotationConfigApplicationContext context) {
    assertThat(context.getBeansOfType(AssetLocalizeAsyncJobSubmissionService.class)).isEmpty();
    assertThat(context.getBeansOfType(AssetLocalizeAsyncJobHandler.class)).isEmpty();
    assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairService.class)).isEmpty();
  }
}
