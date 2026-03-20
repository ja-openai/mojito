package com.box.l10n.mojito.service.review;

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
public class ReviewAutomationCronJob implements Job {

  static final String REVIEW_AUTOMATION_ID = "reviewAutomationId";
  private static final Logger logger = LoggerFactory.getLogger(ReviewAutomationCronJob.class);

  @Autowired ReviewAutomationSchedulerService reviewAutomationSchedulerService;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    String automationIdValue = context.getMergedJobDataMap().getString(REVIEW_AUTOMATION_ID);
    long automationId = Long.parseLong(automationIdValue);
    logger.info("Executing review automation cron job: automationId={}", automationId);
    reviewAutomationSchedulerService.runAutomationFromCron(automationId);
  }
}
