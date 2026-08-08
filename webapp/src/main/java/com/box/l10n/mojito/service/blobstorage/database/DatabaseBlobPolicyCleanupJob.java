package com.box.l10n.mojito.service.blobstorage.database;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class DatabaseBlobPolicyCleanupJob implements Job {

  @Autowired DatabaseBlobCleanupPolicyService databaseBlobCleanupPolicyService;

  @Override
  public void execute(JobExecutionContext context) {
    databaseBlobCleanupPolicyService.runEnabledPolicies();
  }
}
