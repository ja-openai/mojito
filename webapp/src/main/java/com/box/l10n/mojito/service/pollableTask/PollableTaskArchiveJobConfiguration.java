package com.box.l10n.mojito.service.pollableTask;

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
    prefix = "l10n.pollable-task.archive",
    name = {"enabled", "scheduling-enabled"},
    havingValue = "true")
public class PollableTaskArchiveJobConfiguration {

  @Bean(name = "jobDetailPollableTaskArchive")
  public JobDetailFactoryBean jobDetailPollableTaskArchive() {
    JobDetailFactoryBean factory = new JobDetailFactoryBean();
    factory.setJobClass(PollableTaskArchiveJob.class);
    factory.setDescription("Archive old, unreferenced completed pollable tasks to Azure");
    factory.setDurability(true);
    return factory;
  }

  @Bean
  public CronTriggerFactoryBean triggerPollableTaskArchive(
      @Qualifier("jobDetailPollableTaskArchive") JobDetail job,
      @Value("${l10n.pollable-task.archive.cron:0 0 4 * * ?}") String archiveCron) {
    CronTriggerFactoryBean trigger = new CronTriggerFactoryBean();
    trigger.setJobDetail(job);
    trigger.setCronExpression(archiveCron);
    return trigger;
  }
}
