package com.box.l10n.mojito.service.stringauthoring;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@DisallowConcurrentExecution
public class StringAuthoringCleanupJob implements Job {

  static Logger logger = LoggerFactory.getLogger(StringAuthoringCleanupJob.class);

  @Value("${l10n.string-authoring.cleanup-job.batch-size:100}")
  int batchSize;

  @Autowired StringAuthoringCleanupService stringAuthoringCleanupService;

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    int cleanupCount = stringAuthoringCleanupService.cleanupDueBranches(batchSize);
    if (cleanupCount > 0) {
      logger.info("Queued {} string authoring branch cleanup job(s).", cleanupCount);
    } else {
      logger.debug("No string authoring branches due for cleanup.");
    }
  }
}
