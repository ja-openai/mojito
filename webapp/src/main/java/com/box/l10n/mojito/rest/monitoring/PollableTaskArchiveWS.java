package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveService.BatchResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Administrators can run one bounded batch with recurring scheduling disabled. */
@RestController
@RequestMapping("/api/monitoring/task-archive")
public class PollableTaskArchiveWS {
  private final ObjectProvider<PollableTaskArchiveService> service;

  public PollableTaskArchiveWS(ObjectProvider<PollableTaskArchiveService> service) {
    this.service = service;
  }

  @PostMapping("/batch")
  public BatchResult batch() {
    PollableTaskArchiveService archive = service.getIfAvailable();
    if (archive == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Pollable-task archival is disabled");
    }
    return archive.archiveFinishedTasks();
  }
}
