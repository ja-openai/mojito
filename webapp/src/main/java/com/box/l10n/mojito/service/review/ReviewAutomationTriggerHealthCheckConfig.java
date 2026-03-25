package com.box.l10n.mojito.service.review;

import org.quartz.JobDetail;
import org.quartz.SimpleTrigger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean;

@Profile("!disablescheduling")
@Configuration
public class ReviewAutomationTriggerHealthCheckConfig {

  @Bean(name = "jobDetailReviewAutomationTriggerHealthCheck")
  public JobDetailFactoryBean jobDetailReviewAutomationTriggerHealthCheck() {
    JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
    jobDetailFactory.setJobClass(ReviewAutomationTriggerHealthCheckJob.class);
    jobDetailFactory.setDescription("Check and heal review automation Quartz triggers");
    jobDetailFactory.setDurability(true);
    return jobDetailFactory;
  }

  @Bean
  public SimpleTriggerFactoryBean triggerReviewAutomationTriggerHealthCheck(
      @Qualifier("jobDetailReviewAutomationTriggerHealthCheck") JobDetail job,
      @Value("${l10n.review-automation.trigger-health-check.repeat-interval-ms:1800000}")
          long repeatIntervalMs,
      @Value("${l10n.review-automation.trigger-health-check.start-delay-ms:60000}")
          long startDelayMs) {
    SimpleTriggerFactoryBean trigger = new SimpleTriggerFactoryBean();
    trigger.setJobDetail(job);
    trigger.setRepeatInterval(Math.max(repeatIntervalMs, 60_000L));
    trigger.setRepeatCount(SimpleTrigger.REPEAT_INDEFINITELY);
    trigger.setStartDelay(Math.max(startDelayMs, 0L));
    return trigger;
  }
}
