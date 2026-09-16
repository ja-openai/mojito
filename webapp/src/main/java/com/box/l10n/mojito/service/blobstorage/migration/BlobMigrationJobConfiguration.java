package com.box.l10n.mojito.service.blobstorage.migration;

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

@Configuration
@Profile("!disablescheduling")
@ConditionalOnProperty(
    prefix = "l10n.blob-storage.migration",
    name = {"enabled", "scheduling-enabled"},
    havingValue = "true")
public class BlobMigrationJobConfiguration {
  public static final String JOB_NAME = "jobDetailBlobMigration";

  @Bean(name = JOB_NAME)
  public JobDetailFactoryBean job() {
    JobDetailFactoryBean bean = new JobDetailFactoryBean();
    bean.setJobClass(BlobMigrationJob.class);
    bean.setDescription("Copy bounded immutable snapshots of legacy database blobs");
    bean.setDurability(true);
    bean.setRequestsRecovery(true);
    return bean;
  }

  @Bean
  public SimpleTriggerFactoryBean blobMigrationTrigger(
      @Qualifier(JOB_NAME) JobDetail job,
      @Value("${l10n.blob-storage.migration.interval-seconds:60}") long seconds) {
    SimpleTriggerFactoryBean bean = new SimpleTriggerFactoryBean();
    bean.setJobDetail(job);
    bean.setRepeatInterval(Math.max(1, seconds) * 1000);
    bean.setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY);
    return bean;
  }
}
