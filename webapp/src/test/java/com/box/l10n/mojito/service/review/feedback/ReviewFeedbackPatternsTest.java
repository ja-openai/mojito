package com.box.l10n.mojito.service.review.feedback;

import static org.junit.Assert.*;

import com.box.l10n.mojito.entity.review.ReviewFeedbackEvent;
import java.util.*;
import org.junit.Test;

public class ReviewFeedbackPatternsTest {
  private ReviewFeedbackEvent event(long string, long reviewer, String category, String finalHash) {
    var e =
        new ReviewFeedbackEvent(
            "event",
            1L,
            string,
            reviewer,
            "bg",
            "model",
            "prompt",
            category,
            "quote-transform",
            "source",
            "baseline",
            finalHash,
            true,
            "{}");
    e.setId(string);
    return e;
  }

  @Test
  public void repeatedSmallEditsNeedIndependentStringsAndReviewers() {
    List<ReviewFeedbackEvent> window = new ArrayList<>();
    for (long i = 1; i <= 10; i++) window.add(event(i, i % 3, "QUOTE_STYLE", "final"));
    var pattern = ReviewFeedbackPatterns.aggregate(window).patterns().get(0);
    assertEquals("LOW", pattern.instanceSignificance());
    assertEquals("REPEATED", pattern.patternSignificance());
    assertEquals("READY_FOR_HUMAN_REVIEW", pattern.candidateStatus());
    assertEquals(3, pattern.reviewerCount());
    window.add(event(1, 4, "QUOTE_STYLE", "disagreed"));
    assertEquals(
        "OBSERVE", ReviewFeedbackPatterns.aggregate(window).patterns().get(0).candidateStatus());
  }

  @Test
  public void oneReviewRepeatedDoesNotInflateEvidence() {
    var events = Collections.nCopies(20, event(1, 1, "QUOTE_STYLE", "final"));
    var pattern = ReviewFeedbackPatterns.aggregate(events).patterns().get(0);
    assertEquals(1, pattern.distinctStrings());
    assertEquals(1, pattern.reviewerCount());
    assertEquals("OBSERVE", pattern.candidateStatus());
  }

  @Test
  public void workbenchPatternsKeepRepositoryCohortsSeparate() {
    var first =
        new ReviewFeedbackEvent(
            "one",
            null,
            1L,
            1L,
            "fr",
            "model",
            "prompt",
            "QUOTE_STYLE",
            "quotes",
            "source",
            "baseline",
            "final",
            true,
            "{\"repositoryId\":7}");
    var other =
        new ReviewFeedbackEvent(
            "two",
            null,
            2L,
            2L,
            "fr",
            "model",
            "prompt",
            "QUOTE_STYLE",
            "quotes",
            "source",
            "baseline",
            "final",
            true,
            "{\"repositoryId\":8}");
    var patterns = ReviewFeedbackPatterns.aggregate(List.of(first, other)).patterns();
    assertEquals(2, patterns.size());
    assertNull(patterns.get(0).projectId());
    assertEquals(
        Set.of(7L, 8L),
        patterns.stream()
            .map(ReviewFeedbackPatterns.PatternEvidence::repositoryId)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(1, patterns.get(0).opportunities());
  }

  @Test
  public void pendingWorkbenchSavesDoNotAddOpportunitiesOrReplaceReviewedJudgments() {
    var pending =
        new ReviewFeedbackEvent(
            "pending",
            null,
            1L,
            1L,
            "fr",
            "model",
            "prompt",
            "QUOTE_STYLE",
            "quotes",
            "source",
            "baseline",
            "pending",
            true,
            "{\"surface\":\"WORKBENCH\",\"reviewComplete\":false,\"repositoryId\":7}");
    var reviewed =
        new ReviewFeedbackEvent(
            "reviewed",
            null,
            1L,
            1L,
            "fr",
            "model",
            "prompt",
            "QUOTE_STYLE",
            "quotes",
            "source",
            "baseline",
            "reviewed",
            true,
            "{\"surface\":\"WORKBENCH\",\"reviewComplete\":true,\"repositoryId\":7}");
    assertTrue(ReviewFeedbackPatterns.aggregate(List.of(pending)).patterns().isEmpty());
    var pattern = ReviewFeedbackPatterns.aggregate(List.of(pending, reviewed)).patterns().get(0);
    assertEquals(1, pattern.opportunities());
    assertEquals(1, pattern.observations());
    assertEquals(0, pattern.disputedStrings());
    // Existing Review Project events have no Workbench marker and remain valid judgments.
    assertEquals(
        1,
        ReviewFeedbackPatterns.aggregate(List.of(event(2, 2, "QUOTE_STYLE", "final")))
            .patterns()
            .size());
  }
}
