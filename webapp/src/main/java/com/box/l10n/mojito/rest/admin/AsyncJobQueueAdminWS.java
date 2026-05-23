package com.box.l10n.mojito.rest.admin;

import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobDetails;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobExpiredLeaseStatusSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobNotFoundException;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobReadyStatusSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobStatusCountSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobSummary;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  @GetMapping("/{queueName}/ready-status")
  public AsyncJobReadyStatusSummary readyStatus(@PathVariable String queueName) {
    try {
      return inspectionService.readyStatus(queueName);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  @GetMapping("/{queueName}/expired-lease-status")
  public AsyncJobExpiredLeaseStatusSummary expiredLeaseStatus(@PathVariable String queueName) {
    try {
      return inspectionService.expiredLeaseStatus(queueName);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  @GetMapping("/{queueName}/jobs")
  public List<AsyncJobRedactedSummary> findJobs(
      @PathVariable String queueName,
      @RequestParam(name = "status", defaultValue = "failed") String status,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      return inspectionService.findJobs(queueName, status, limit).stream()
          .map(this::toRedactedSummary)
          .toList();
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  @GetMapping("/{queueName}/jobs/{jobId}")
  public AsyncJobRedactedSummary getJob(
      @PathVariable String queueName, @PathVariable String jobId) {
    try {
      return toRedactedSummary(inspectionService.getJob(queueName, jobId));
    } catch (AsyncJobNotFoundException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  private AsyncJobRedactedSummary toRedactedSummary(AsyncJobSummary summary) {
    return new AsyncJobRedactedSummary(
        summary.id(),
        summary.queueName(),
        summary.status(),
        summary.availableAt(),
        summary.leaseUntil(),
        summary.workerId(),
        summary.attemptCount(),
        summary.lastError(),
        summary.jobDataLength(),
        summary.createdDate(),
        summary.updatedDate());
  }

  private AsyncJobRedactedSummary toRedactedSummary(AsyncJobDetails details) {
    return new AsyncJobRedactedSummary(
        details.id(),
        details.queueName(),
        details.status(),
        details.availableAt(),
        details.leaseUntil(),
        details.workerId(),
        details.attemptCount(),
        details.lastError(),
        details.jobData().length(),
        details.createdDate(),
        details.updatedDate());
  }

  public record AsyncJobRedactedSummary(
      String id,
      String queueName,
      String status,
      Instant availableAt,
      Instant leaseUntil,
      String workerId,
      int attemptCount,
      String lastError,
      int jobDataLength,
      Instant createdDate,
      Instant updatedDate) {}
}
