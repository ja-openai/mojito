package com.box.l10n.mojito.service.review;

import java.util.TimeZone;
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
    value = "l10n.review-project.decision-integrity-canary.enabled",
    havingValue = "true")
public class ReviewProjectDecisionIntegrityCanaryConfig {

  @Bean(name = "jobDetailReviewProjectDecisionIntegrityCanary")
  public JobDetailFactoryBean jobDetailReviewProjectDecisionIntegrityCanary() {
    JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
    jobDetailFactory.setJobClass(ReviewProjectDecisionIntegrityCanaryJob.class);
    jobDetailFactory.setDescription(
        "Detect possible Review Project translation carryover without modifying translations");
    jobDetailFactory.setDurability(true);
    return jobDetailFactory;
  }

  @Bean
  public CronTriggerFactoryBean triggerReviewProjectDecisionIntegrityCanary(
      @Qualifier("jobDetailReviewProjectDecisionIntegrityCanary") JobDetail job,
      @Value("${l10n.review-project.decision-integrity-canary.cron:0 15 5 * * ?}") String cron) {
    CronTriggerFactoryBean trigger = new CronTriggerFactoryBean();
    trigger.setJobDetail(job);
    trigger.setCronExpression(cron);
    trigger.setTimeZone(TimeZone.getTimeZone("UTC"));
    return trigger;
  }
}
