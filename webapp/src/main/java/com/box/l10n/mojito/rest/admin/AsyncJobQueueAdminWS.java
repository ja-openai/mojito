package com.box.l10n.mojito.rest.admin;

import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobStatusCountSummary;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only read-only queue operations that do not expose job payloads. */
@RestController
@RequestMapping("/api/admin/async-job-queue/queues")
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
public class AsyncJobQueueAdminWS {

  private final AsyncJobQueueInspectionService inspectionService;

  public AsyncJobQueueAdminWS(AsyncJobQueueInspectionService inspectionService) {
    this.inspectionService = inspectionService;
  }

  @GetMapping("/{queueName}/status-counts")
  public List<AsyncJobStatusCountSummary> countJobsByStatus(@PathVariable String queueName) {
    try {
      return inspectionService.countJobsByStatus(queueName);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }
}
