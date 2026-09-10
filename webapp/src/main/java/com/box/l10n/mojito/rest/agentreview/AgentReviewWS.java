package com.box.l10n.mojito.rest.agentreview;

import com.box.l10n.mojito.entity.agentreview.AgentReviewFeedback;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.*;
import com.box.l10n.mojito.service.agentreview.AgentReviewEvidenceService;
import com.box.l10n.mojito.service.agentreview.AgentReviewProjectService;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Shared, durable state used by the Codex skill and ordinary authenticated clients. */
@RestController
@RequestMapping("/api/agent-reviews")
public class AgentReviewWS {
  private final AgentReviewService reviews;
  private final AgentReviewProjectService projects;
  private final AgentReviewEvidenceService evidence;

  public AgentReviewWS(
      AgentReviewService reviews,
      AgentReviewProjectService projects,
      AgentReviewEvidenceService evidence) {
    this.reviews = reviews;
    this.projects = projects;
    this.evidence = evidence;
  }

  public record Progress(RunView run, AgentReviewProjectService.RouteResult routing) {}

  @PostMapping("/runs")
  public RunView create(@RequestBody CreateRunRequest request) {
    return reviews.createRun(request);
  }

  @GetMapping("/runs")
  public RunPage list(
      @RequestParam(required = false) Long teamId,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(required = false) Long beforeId) {
    return reviews.listRunsPage(teamId, limit, beforeId);
  }

  @GetMapping("/runs/{runId}")
  public RunView get(@PathVariable long runId) {
    return reviews.getRun(runId);
  }

  @PostMapping("/runs/{runId}/claim")
  public Progress claim(@PathVariable long runId, @RequestBody ClaimRequest request) {
    return progress(reviews.claimRun(runId, request));
  }

  @PostMapping("/runs/{runId}/checkpoints")
  public Progress checkpoint(@PathVariable long runId, @RequestBody CheckpointRequest request) {
    return progress(reviews.checkpoint(runId, request));
  }

  @PostMapping("/runs/{runId}/finish")
  public Progress finish(@PathVariable long runId, @RequestBody FinishRequest request) {
    return progress(reviews.finishRun(runId, request));
  }

  private Progress progress(RunView run) {
    return new Progress(run, projects.routeRun(run.id()));
  }

  @PostMapping("/runs/{runId}/artifacts")
  public Artifact uploadArtifact(@PathVariable long runId, @RequestBody ArtifactRequest request) {
    return reviews.uploadArtifact(runId, request);
  }

  @GetMapping("/runs/{runId}/artifacts/{sha256}")
  public Artifact artifact(@PathVariable long runId, @PathVariable String sha256) {
    return reviews.readArtifact(runId, sha256);
  }

  @PostMapping("/runs/{runId}/proposals")
  public List<SubmissionResult> submit(
      @PathVariable long runId, @RequestBody List<SubmitProposalRequest> proposals) {
    return reviews.submitProposals(runId, proposals);
  }

  @GetMapping("/runs/{runId}/proposals")
  public List<AgentReviewProposal> proposals(
      @PathVariable long runId,
      @RequestParam(defaultValue = "0") long afterId,
      @RequestParam(defaultValue = "50") int limit) {
    return reviews.listProposals(runId, afterId, limit);
  }

  @GetMapping("/proposals/{proposalId}/history")
  public List<AgentReviewProposal> history(
      @PathVariable long proposalId,
      @RequestParam(defaultValue = "0") long afterId,
      @RequestParam(defaultValue = "50") int limit) {
    return reviews.proposalHistory(proposalId, afterId, limit);
  }

  @GetMapping("/runs/{runId}/feedback")
  public List<AgentReviewFeedback> feedback(
      @PathVariable long runId,
      @RequestParam(defaultValue = "0") long afterId,
      @RequestParam(defaultValue = "50") int limit) {
    return reviews.pendingFeedback(runId, afterId, limit);
  }

  @GetMapping("/proposals/{proposalId}/feedback")
  public List<AgentReviewFeedback> feedbackHistory(
      @PathVariable long proposalId,
      @RequestParam(defaultValue = "0") long afterId,
      @RequestParam(defaultValue = "50") int limit) {
    return reviews.feedbackHistory(proposalId, afterId, limit);
  }

  @PostMapping("/runs/{runId}/responses")
  public AgentReviewFeedback respond(
      @PathVariable long runId, @RequestBody ResponseRequest request) {
    return reviews.respondToFeedback(runId, request);
  }

  @PostMapping("/runs/{runId}/route")
  public AgentReviewProjectService.RouteResult route(@PathVariable long runId) {
    return projects.routeRun(runId);
  }

  @GetMapping("/projects/{projectId}/proposals/{proposalId}/feedback")
  public List<AgentReviewProjectService.FeedbackView> projectFeedback(
      @PathVariable long projectId,
      @PathVariable long proposalId,
      @RequestParam(defaultValue = "0") long afterId,
      @RequestParam(defaultValue = "50") int limit) {
    return projects.history(projectId, proposalId, afterId, limit);
  }

  @GetMapping("/projects/{projectId}/proposals/{proposalId}/artifacts/{sha256}")
  public ResponseEntity<byte[]> projectArtifact(
      @PathVariable long projectId, @PathVariable long proposalId, @PathVariable String sha256) {
    Artifact artifact = evidence.read(projectId, proposalId, sha256);
    boolean image =
        Set.of("image/png", "image/jpeg", "image/webp", "image/gif")
            .contains(artifact.contentType());
    byte[] bytes = Base64.getDecoder().decode(artifact.contentBase64());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            image ? "inline" : "attachment; filename=review-evidence")
        .contentType(
            image
                ? MediaType.parseMediaType(artifact.contentType())
                : MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(bytes.length)
        .body(bytes);
  }
}
