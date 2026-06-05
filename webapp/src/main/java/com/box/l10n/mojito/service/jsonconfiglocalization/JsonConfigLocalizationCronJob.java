package com.box.l10n.mojito.service.jsonconfiglocalization;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!disablescheduling")
@Component
@DisallowConcurrentExecution
public class JsonConfigLocalizationCronJob implements Job {

  static final String JSON_CONFIG_LOCALIZATION_ID = "jsonConfigLocalizationId";
  private static final Logger logger = LoggerFactory.getLogger(JsonConfigLocalizationCronJob.class);

  @Autowired JsonConfigLocalizationAutomationService jsonConfigLocalizationAutomationService;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    String setupIdValue = context.getMergedJobDataMap().getString(JSON_CONFIG_LOCALIZATION_ID);
    long setupId = Long.parseLong(setupIdValue);
    logger.info("Executing JSON config localization cron job: setupId={}", setupId);
    jsonConfigLocalizationAutomationService.runAutomationFromCron(setupId);
  }
}
