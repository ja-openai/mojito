package com.box.l10n.mojito.service.oaitranslate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;

@Profile("!disablescheduling")
@Configuration
public class AiTranslateAutomationCronConfig {

  @Bean(name = "aiTranslateAutomationCron")
  public JobDetailFactoryBean jobDetailAiTranslateAutomationCron() {
    JobDetailFactoryBean jobDetailFactory = new JobDetailFactoryBean();
    jobDetailFactory.setJobClass(AiTranslateAutomationCronJob.class);
    jobDetailFactory.setDescription("Schedule automatic AI translate backlog jobs");
    jobDetailFactory.setDurability(true);
    return jobDetailFactory;
  }
}
