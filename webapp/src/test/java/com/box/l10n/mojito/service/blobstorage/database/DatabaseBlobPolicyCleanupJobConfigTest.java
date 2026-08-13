package com.box.l10n.mojito.service.blobstorage.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.quartz.JobDetail;
import org.quartz.SimpleTrigger;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class DatabaseBlobPolicyCleanupJobConfigTest {

  private final ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(DatabaseBlobPolicyCleanupJobConfig.class);

  @Test
  public void cleanupJobIsDisabledByDefault() {
    applicationContextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(JobDetail.class);
          assertThat(context).doesNotHaveBean(SimpleTrigger.class);
        });
  }

  @Test
  public void cleanupJobUsesConfiguredScheduleWhenEnabled() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.blob-storage.database.policy-cleanup-enabled=true",
            "l10n.blob-storage.database.policy-cleanup-interval-seconds=7")
        .run(
            context -> {
              assertThat(context.getBean(JobDetail.class).getJobClass())
                  .isEqualTo(DatabaseBlobPolicyCleanupJob.class);
              assertThat(context.getBean(SimpleTrigger.class).getRepeatInterval()).isEqualTo(7_000);
            });
  }
}
