package com.box.l10n.mojito.rest.review;

import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.review.feedback.*;
import com.box.l10n.mojito.service.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ReviewFeedbackWS {
  private final ReviewProjectService projects;
  private final ReviewProjectTextUnitRepository rows;
  private final ReviewFeedbackCaptureService capture;
  private final ReviewFeedbackPatterns patterns;
  private final TeamService teams;

  public ReviewFeedbackWS(
      ReviewProjectService projects,
      ReviewProjectTextUnitRepository rows,
      ReviewFeedbackCaptureService capture,
      ReviewFeedbackPatterns patterns,
      TeamService teams) {
    this.projects = projects;
    this.rows = rows;
    this.capture = capture;
    this.patterns = patterns;
    this.teams = teams;
  }

  public record Baseline(String target, boolean ai, String kind) {}

  @GetMapping("/review-project-text-units/{id}/feedback-baseline")
  @Transactional(readOnly = true)
  public Baseline baseline(@PathVariable Long id) {
    // Reuse assignment/team/locale authorization before exposing any historical AI context.
    projects.getReviewProjectTextUnit(id);
    var baseline = capture.baseline(rows.findById(id).orElseThrow());
    return new Baseline(baseline.target(), baseline.ai(), baseline.kind());
  }

  @GetMapping("/review-feedback/patterns")
  public ReviewFeedbackPatterns.Report patterns() {
    if (!teams.isCurrentUserAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return patterns.report();
  }
}
