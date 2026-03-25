package com.box.l10n.mojito.service.review;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class ReviewAutomationTriggerHealthCheckJob implements Job {

  private static final Logger logger =
      LoggerFactory.getLogger(ReviewAutomationTriggerHealthCheckJob.class);

  @Autowired ReviewAutomationTriggerHealthCheckService reviewAutomationTriggerHealthCheckService;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    logger.debug("Checking review automation trigger health");
    reviewAutomationTriggerHealthCheckService.checkAndHealTriggers();
  }
}
