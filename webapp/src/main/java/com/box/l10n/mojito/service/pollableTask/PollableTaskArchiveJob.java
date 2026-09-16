package com.box.l10n.mojito.service.pollableTask;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@DisallowConcurrentExecution
public class PollableTaskArchiveJob implements Job {

  @Autowired(required = false)
  PollableTaskArchiveService pollableTaskArchiveService;

  @Value("${l10n.pollable-task.archive.scheduling-enabled:false}")
  boolean schedulingEnabled;

  @Override
  public void execute(JobExecutionContext context) {
    // A previously enabled trigger may remain in the persistent Quartz store after rollback.
    if (schedulingEnabled && pollableTaskArchiveService != null) {
      pollableTaskArchiveService.archiveFinishedTasks();
    }
  }
}
