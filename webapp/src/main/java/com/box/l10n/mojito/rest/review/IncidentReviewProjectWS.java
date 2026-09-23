package com.box.l10n.mojito.rest.review;

import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/incident-review-projects")
public class IncidentReviewProjectWS {
  public record StartResponse(long pollableTaskId) {}

  private final IncidentReviewJobService jobs;

  public IncidentReviewProjectWS(IncidentReviewJobService jobs) {
    this.jobs = jobs;
  }

  @PostMapping("/preview")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartResponse preview(@RequestBody IncidentReviewBatchService.Request request) {
    return new StartResponse(jobs.preview(request).getPollableTask().getId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartResponse create(@RequestBody IncidentReviewBatchService.Request request) {
    return new StartResponse(jobs.create(request).getPollableTask().getId());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidRequest(
      IllegalArgumentException exception) {
    return org.springframework.http.ResponseEntity.badRequest()
        .body(java.util.Map.of("message", exception.getMessage()));
  }

  @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
  public org.springframework.http.ResponseEntity<java.util.Map<String, String>> concurrentChange() {
    return org.springframework.http.ResponseEntity.status(409)
        .body(
            java.util.Map.of(
                "message", "An incident changed while creating the batch. Refresh and try again."));
  }
}
