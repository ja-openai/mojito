package com.box.l10n.mojito.service.stringauthoring;

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
@ConditionalOnProperty(
    value = "l10n.string-authoring.cleanup-job.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StringAuthoringCleanupJobConfig {

  @Bean(name = "jobDetailStringAuthoringCleanupJob")
  public JobDetailFactoryBean jobDetailStringAuthoringCleanupJob() {
    JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
    jobDetailFactory.setJobClass(StringAuthoringCleanupJob.class);
    jobDetailFactory.setDescription("Cleanup due string authoring branches.");
    jobDetailFactory.setDurability(true);
    return jobDetailFactory;
  }

  @Bean
  public CronTriggerFactoryBean triggerStringAuthoringCleanupJob(
      @Qualifier("jobDetailStringAuthoringCleanupJob") JobDetail job,
      @Value("${l10n.string-authoring.cleanup-job.cron:0 0 3 * * ?}") String cleanupCron) {
    CronTriggerFactoryBean trigger = new CronTriggerFactoryBean();
    trigger.setJobDetail(job);
    trigger.setCronExpression(cleanupCron);
    return trigger;
  }
}
