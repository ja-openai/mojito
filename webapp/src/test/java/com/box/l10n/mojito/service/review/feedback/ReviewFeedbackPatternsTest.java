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
}
