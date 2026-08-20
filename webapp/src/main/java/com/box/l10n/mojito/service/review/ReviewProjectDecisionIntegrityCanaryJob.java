package com.box.l10n.mojito.service.review;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class ReviewProjectDecisionIntegrityCanaryJob implements Job {

  @Autowired ReviewProjectDecisionIntegrityCanaryService canaryService;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    try {
      canaryService.run();
    } catch (RuntimeException exception) {
      throw new JobExecutionException("Review Project decision-integrity canary failed", exception);
    }
  }
}
