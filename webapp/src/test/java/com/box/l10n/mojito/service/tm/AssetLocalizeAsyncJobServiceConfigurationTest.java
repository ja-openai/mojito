package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.queue.AsyncJobQueueSubmissionService;
import com.box.l10n.mojito.queue.AsyncJobStore;
import com.box.l10n.mojito.rest.asset.AssetWS;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.pushrun.PushRunRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class AssetLocalizeAsyncJobServiceConfigurationTest {

  @Test
  public void assetLocalizeAsyncJobComponentsAreNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = createContext()) {
      assertComponentsAreAbsent(context);
      assertProducerFlags(context, false, false, true);
    }
  }

  @Test
  public void assetLocalizeAsyncJobComponentsAreNotCreatedWhenOnlyQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.enabled=true")) {
      assertComponentsAreAbsent(context);
      assertProducerFlags(context, true, false, true);
    }
  }

  @Test
  public void assetLocalizeAsyncJobComponentsAreNotCreatedWhenOnlyAssetLocalizeQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertComponentsAreAbsent(context);
      assertProducerFlags(context, false, true, true);
    }
  }

  @Test
  public void assetLocalizeAsyncJobComponentsAreCreatedWhenBothFlagsAreEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext(
            "l10n.org.async-job-queue.enabled=true",
            "l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertComponentsArePresent(context);
      assertProducerFlags(context, true, true, true);
      assertThat(context.getBean(AssetLocalizeAsyncJobSubmissionService.class).producerEnabled)
          .isTrue();
    }
  }

  @Test
  public void producerFlagAloneDoesNotEnableAssetLocalizeQueue() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.asset-localize.producer-enabled=true")) {
      assertComponentsAreAbsent(context);
      assertProducerFlags(context, false, false, true);
    }
  }

  @Test
  public void explicitProducerFlagDoesNotOverrideDisabledUmbrellaFlags() {
    for (boolean globalEnabled : List.of(false, true)) {
      for (boolean assetEnabled : List.of(false, true)) {
        try (AnnotationConfigApplicationContext context =
            createContext(
                "l10n.org.async-job-queue.enabled=" + globalEnabled,
                "l10n.org.async-job-queue.asset-localize.enabled=" + assetEnabled,
                "l10n.org.async-job-queue.asset-localize.producer-enabled=true")) {
          assertProducerFlags(context, globalEnabled, assetEnabled, true);
          if (globalEnabled && assetEnabled) {
            assertComponentsArePresent(context);
            assertThat(
                    context.getBean(AssetLocalizeAsyncJobSubmissionService.class).producerEnabled)
                .isTrue();
          } else {
            assertComponentsAreAbsent(context);
          }
        }
      }
    }
  }

  @Test
  public void producerRollbackBindsAndRetainsServicesButBlocksDirectSubmission() {
    try (AnnotationConfigApplicationContext context =
        createContext(
            "l10n.org.async-job-queue.enabled=true",
            "l10n.org.async-job-queue.asset-localize.enabled=true",
            "l10n.org.async-job-queue.asset-localize.producer-enabled=false")) {
      assertComponentsArePresent(context);
      assertProducerFlags(context, true, true, false);
      AssetLocalizeAsyncJobSubmissionService service =
          context.getBean(AssetLocalizeAsyncJobSubmissionService.class);
      assertThat(service.producerEnabled).isFalse();

      assertThatThrownBy(
              () ->
                  service.scheduleJob(
                      QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                          .withInput(new LocalizedAssetBody())
                          .build()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("producer is disabled");

      verifyNoInteractions(
          context.getBean(PollableTaskService.class),
          context.getBean(PollableTaskBlobStorage.class),
          context.getBean(PollableTaskExceptionUtils.class),
          context.getBean(AsyncJobQueueSubmissionService.class),
          context.getBean(AsyncJobStore.class),
          context.getBean(StructuredBlobStorage.class),
          context.getBean(QuartzPollableTaskScheduler.class));
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
    context
        .getBeanFactory()
        .registerSingleton("structuredBlobStorage", mock(StructuredBlobStorage.class));
    context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
    context.registerAlias("objectMapper", "fail_on_unknown_properties_false");
    context.getBeanFactory().registerSingleton("meterRegistry", new SimpleMeterRegistry());
    context
        .getBeanFactory()
        .registerSingleton("repositoryRepository", mock(RepositoryRepository.class));
    context.getBeanFactory().registerSingleton("assetRepository", mock(AssetRepository.class));
    context
        .getBeanFactory()
        .registerSingleton("repositoryLocaleRepository", mock(RepositoryLocaleRepository.class));
    context.getBeanFactory().registerSingleton("tmService", mock(TMService.class));
    context.getBeanFactory().registerSingleton("assetService", mock(AssetService.class));
    context.getBeanFactory().registerSingleton("tmXliffRepository", mock(TMXliffRepository.class));
    context.getBeanFactory().registerSingleton("localeService", mock(LocaleService.class));
    context.getBeanFactory().registerSingleton("pushRunRepository", mock(PushRunRepository.class));
    context
        .getBeanFactory()
        .registerSingleton("quartzPollableTaskScheduler", mock(QuartzPollableTaskScheduler.class));
    context.register(
        AssetWS.class,
        GenerateMultiLocalizedAssetJob.class,
        AssetLocalizeAsyncJobSubmissionService.class,
        AssetLocalizeAsyncJobHandler.class,
        AssetLocalizeAsyncJobRepairService.class,
        AssetLocalizeAsyncJobOutputStorage.class);
    context.refresh();
    return context;
  }

  private void assertProducerFlags(
      AnnotationConfigApplicationContext context,
      boolean globalEnabled,
      boolean assetEnabled,
      boolean producerEnabled) {
    for (Object producer :
        List.of(
            context.getBean(AssetWS.class),
            context.getBean(GenerateMultiLocalizedAssetJob.class))) {
      assertThat(producer)
          .hasFieldOrPropertyWithValue("asyncJobQueueEnabled", globalEnabled)
          .hasFieldOrPropertyWithValue("asyncJobQueueAssetLocalizeEnabled", assetEnabled)
          .hasFieldOrPropertyWithValue(
              "asyncJobQueueAssetLocalizeProducerEnabled", producerEnabled);
    }
  }

  private void assertComponentsArePresent(AnnotationConfigApplicationContext context) {
    assertThat(context.getBean(AssetLocalizeAsyncJobSubmissionService.class)).isNotNull();
    assertThat(context.getBean(AssetLocalizeAsyncJobHandler.class)).isNotNull();
    assertThat(context.getBean(AssetLocalizeAsyncJobRepairService.class)).isNotNull();
    assertThat(context.getBean(AssetLocalizeAsyncJobOutputStorage.class)).isNotNull();
  }

  private void assertComponentsAreAbsent(AnnotationConfigApplicationContext context) {
    assertThat(context.getBeansOfType(AssetLocalizeAsyncJobSubmissionService.class)).isEmpty();
    assertThat(context.getBeansOfType(AssetLocalizeAsyncJobHandler.class)).isEmpty();
    assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairService.class)).isEmpty();
    assertThat(context.getBeansOfType(AssetLocalizeAsyncJobOutputStorage.class)).isEmpty();
  }
}
