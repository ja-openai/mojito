package com.box.l10n.mojito.rest.review;

import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService;
import com.box.l10n.mojito.service.agentreview.ManualIncidentReviewService;
import com.box.l10n.mojito.service.team.TeamService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/incident-review-projects")
public class IncidentReviewProjectWS {
  private final ManualIncidentReviewService batches;
  private final TeamService teams;

  public IncidentReviewProjectWS(ManualIncidentReviewService batches, TeamService teams) {
    this.batches = batches;
    this.teams = teams;
  }

  @PostMapping("/preview")
  public ManualIncidentReviewService.Preview preview(
      @RequestBody IncidentReviewBatchService.Request request) {
    return batches.preview(request, teams.getCurrentUserIdOrThrow());
  }

  @PostMapping
  public IncidentReviewBatchService.Result create(
      @RequestBody IncidentReviewBatchService.Request request) {
    return batches.create(request, teams.getCurrentUserIdOrThrow());
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
