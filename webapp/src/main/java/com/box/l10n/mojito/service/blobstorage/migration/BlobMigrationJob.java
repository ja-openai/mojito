package com.box.l10n.mojito.service.blobstorage.migration;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@DisallowConcurrentExecution
public class BlobMigrationJob implements Job {
  public static final String MANUAL_RUN_ID = "blobMigrationRunId";

  @Autowired BlobMigrationService service;
  @Autowired BlobMigrationProperties properties;

  @Value("${l10n.blob-storage.migration.scheduling-enabled:false}")
  boolean schedulingEnabled;

  @Override
  public void execute(JobExecutionContext context) {
    if (!properties.isEnabled()) return;
    // Read the trigger's data, not the durable job's data: a manual request selects one run.
    JobDataMap data = context.getTrigger().getJobDataMap();
    if (data.containsKey(MANUAL_RUN_ID)) {
      String runId = data.getString(MANUAL_RUN_ID);
      if (runId == null || runId.isBlank())
        throw new IllegalArgumentException("Manual blob migration trigger requires a run ID");
      service.runBatch(runId);
    } else if (schedulingEnabled) {
      // Previously registered recurring triggers may survive in the persistent Quartz store.
      service.runRequested();
    }
  }
}
