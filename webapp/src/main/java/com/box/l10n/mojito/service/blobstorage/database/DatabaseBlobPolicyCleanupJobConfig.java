package com.box.l10n.mojito.service.blobstorage.database;

import java.time.Duration;
import org.quartz.JobDetail;
import org.quartz.SimpleTrigger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean;

@Profile("!disablescheduling")
@Configuration
@ConditionalOnProperty(
    name = "l10n.blob-storage.database.policy-cleanup-enabled",
    havingValue = "true")
public class DatabaseBlobPolicyCleanupJobConfig {

  public static final String JOB_NAME = "jobDetailDatabaseBlobPolicyCleanupJob";

  @Bean(name = JOB_NAME)
  public JobDetailFactoryBean jobDetailDatabaseBlobPolicyCleanupJob() {
    JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
    jobDetailFactory.setJobClass(DatabaseBlobPolicyCleanupJob.class);
    jobDetailFactory.setDescription("Drain configured database blob cleanup policies");
    jobDetailFactory.setDurability(true);
    jobDetailFactory.setRequestsRecovery(true);
    return jobDetailFactory;
  }

  @Bean
  public SimpleTriggerFactoryBean triggerDatabaseBlobPolicyCleanupJob(
      @Qualifier(JOB_NAME) JobDetail job,
      @Value("${l10n.blob-storage.database.policy-cleanup-interval-seconds:300}")
          long intervalSeconds) {
    SimpleTriggerFactoryBean trigger = new SimpleTriggerFactoryBean();
    trigger.setJobDetail(job);
    trigger.setRepeatInterval(Duration.ofSeconds(Math.max(1, intervalSeconds)).toMillis());
    trigger.setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY);
    return trigger;
  }
}
