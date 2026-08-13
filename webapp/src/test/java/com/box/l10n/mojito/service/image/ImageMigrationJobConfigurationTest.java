package com.box.l10n.mojito.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class ImageMigrationJobConfigurationTest {

  private final ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner().withUserConfiguration(ImageMigrationJobConfiguration.class);

  @Test
  public void migrationJobIsDisabledByDefault() {
    applicationContextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(JobDetail.class);
          assertThat(context).doesNotHaveBean(CronTrigger.class);
        });
  }

  @Test
  public void migrationJobUsesConfiguredScheduleWhenEnabled() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.image-service.migration.enabled=true",
            "l10n.image-service.migration.cron=0 15 3 * * ?")
        .run(
            context -> {
              assertThat(context.getBean(JobDetail.class).getJobClass())
                  .isEqualTo(ImageMigrationJob.class);
              assertThat(context.getBean(CronTrigger.class).getCronExpression())
                  .isEqualTo("0 15 3 * * ?");
            });
  }
}
