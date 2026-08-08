package com.box.l10n.mojito.service.image;

import org.quartz.JobDetail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;

@Profile("!disablescheduling")
@Configuration
@ConditionalOnProperty(value = "l10n.image-service.migration.enabled", havingValue = "true")
public class ImageMigrationJobConfiguration {

  @Bean(name = "jobDetailImageMigration")
  public JobDetailFactoryBean jobDetailImageMigration() {
    JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
    jobDetailFactory.setJobClass(ImageMigrationJob.class);
    jobDetailFactory.setDescription("Migrate database images to remote blob storage");
    jobDetailFactory.setDurability(true);
    return jobDetailFactory;
  }

  @Bean
  public CronTriggerFactoryBean triggerImageMigration(
      @Qualifier("jobDetailImageMigration") JobDetail job,
      @Value("${l10n.image-service.migration.cron:0 0 * * * ?}") String migrationCron) {
    CronTriggerFactoryBean trigger = new CronTriggerFactoryBean();
    trigger.setJobDetail(job);
    trigger.setCronExpression(migrationCron);
    return trigger;
  }
}
