package com.box.l10n.mojito.rest.agentreview;

import com.box.l10n.mojito.service.agentreview.AgentReviewReReviewService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent-reviews/projects/{projectId}/proposals/{proposalId}")
public class AgentReviewReReviewWS {
  private final AgentReviewReReviewService reviews;

  public AgentReviewReReviewWS(AgentReviewReReviewService reviews) {
    this.reviews = reviews;
  }

  @PostMapping("/review-again")
  public AgentReviewReReviewService.Result reviewAgain(
      @PathVariable long projectId,
      @PathVariable long proposalId,
      @RequestBody AgentReviewReReviewService.Request request) {
    return reviews.reviewAgain(projectId, proposalId, request);
  }

  @PostMapping("/reopen")
  public AgentReviewReReviewService.Result reopen(
      @PathVariable long projectId,
      @PathVariable long proposalId,
      @RequestBody AgentReviewReReviewService.Request request) {
    return reviews.reopen(projectId, proposalId, request);
  }
}
