package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

public class PollableTaskArchiveConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              PollableTaskArchiveService.class,
              PollableTaskArchiveJobConfiguration.class,
              TestConfiguration.class);

  @Test
  public void archiveWorkerAndSchedulerAreDisabledByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(PollableTaskArchiveService.class);
          assertThat(context).doesNotHaveBean("jobDetailPollableTaskArchive");
          assertThat(context.getBean(PollableTaskArchiveProperties.class).getRetentionDays())
              .isEqualTo(90);
          assertThat(context.getBean(PollableTaskArchiveProperties.class).getBatchSize())
              .isEqualTo(100);
          assertThat(context.getBean(PollableTaskArchiveProperties.class).getLeaseSeconds())
              .isEqualTo(300);
          assertThat(context.getBean(PollableTaskArchiveProperties.class).isDeleteSource())
              .isFalse();
        });
  }

  @Test
  public void persistedQuartzTriggerIsHarmlessAfterArchiveWorkerIsDisabled() {
    new PollableTaskArchiveJob().execute(null);
  }

  @Test
  public void persistedQuartzTriggerCannotRunManualOnlyWorker() {
    PollableTaskArchiveJob job = new PollableTaskArchiveJob();
    job.pollableTaskArchiveService = mock(PollableTaskArchiveService.class);
    job.execute(null);
    verifyNoInteractions(job.pollableTaskArchiveService);
  }

  @Test
  public void enabledManualWorkerDoesNotRegisterRecurringTrigger() {
    contextRunner
        .withPropertyValues("l10n.pollable-task.archive.enabled=true")
        .withBean(
            PollableTaskArchiveStorage.class,
            () -> {
              PollableTaskArchiveStorage storage = mock(PollableTaskArchiveStorage.class);
              when(storage.isAzureArchiveConfigured()).thenReturn(true);
              return storage;
            })
        .run(
            context -> {
              assertThat(context).hasSingleBean(PollableTaskArchiveService.class);
              assertThat(context).doesNotHaveBean("triggerPollableTaskArchive");
            });
  }

  @Test
  public void explicitlyEnabledArchiveWorkerAndSchedulerUseConfiguredSafeDefaults() {
    contextRunner
        .withPropertyValues(
            "l10n.pollable-task.archive.enabled=true",
            "l10n.pollable-task.archive.scheduling-enabled=true",
            "l10n.pollable-task.archive.retention-days=45",
            "l10n.pollable-task.archive.batch-size=20")
        .withBean(
            PollableTaskArchiveStorage.class,
            () -> {
              PollableTaskArchiveStorage storage = mock(PollableTaskArchiveStorage.class);
              when(storage.isAzureArchiveConfigured()).thenReturn(true);
              return storage;
            })
        .run(
            context -> {
              assertThat(context).hasSingleBean(PollableTaskArchiveService.class);
              assertThat(context).hasBean("jobDetailPollableTaskArchive");
              assertThat(context).hasBean("triggerPollableTaskArchive");
              assertThat(context.getBean(PollableTaskArchiveProperties.class).getRetentionDays())
                  .isEqualTo(45);
              assertThat(context.getBean(PollableTaskArchiveProperties.class).getBatchSize())
                  .isEqualTo(20);
              assertThat(context.getBean(PollableTaskArchiveProperties.class).isDeleteSource())
                  .isFalse();
            });
  }

  @Test
  public void explicitlyEnabledArchiveWorkerRefusesNonAzureDestination() {
    contextRunner
        .withPropertyValues("l10n.pollable-task.archive.enabled=true")
        .withBean(PollableTaskArchiveStorage.class, () -> mock(PollableTaskArchiveStorage.class))
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage(
                        "Pollable-task archival requires the pollable-task-archive blob prefix to route directly to Azure"));
  }

  @Configuration
  @EnableConfigurationProperties(PollableTaskArchiveProperties.class)
  static class TestConfiguration {

    @Bean
    PollableTaskRepository pollableTaskRepository() {
      return mock(PollableTaskRepository.class);
    }

    @Bean
    PollableTaskArchiveCheckpointRepository pollableTaskArchiveCheckpointRepository() {
      return mock(PollableTaskArchiveCheckpointRepository.class);
    }

    @Bean
    PollableTaskArchiveRetryRepository pollableTaskArchiveRetryRepository() {
      return mock(PollableTaskArchiveRetryRepository.class);
    }

    @Bean
    PollableTaskArchiveReferenceService pollableTaskArchiveReferenceService() {
      return mock(PollableTaskArchiveReferenceService.class);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return mock(PlatformTransactionManager.class);
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
