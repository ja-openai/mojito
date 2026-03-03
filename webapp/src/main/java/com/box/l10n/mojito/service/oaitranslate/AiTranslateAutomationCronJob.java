package com.box.l10n.mojito.service.oaitranslate;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!disablescheduling")
@Component
@DisallowConcurrentExecution
public class AiTranslateAutomationCronJob implements Job {

  @Autowired AiTranslateAutomationSchedulerService aiTranslateAutomationSchedulerService;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    aiTranslateAutomationSchedulerService.scheduleConfiguredRepositories("cron", true);
  }
}
