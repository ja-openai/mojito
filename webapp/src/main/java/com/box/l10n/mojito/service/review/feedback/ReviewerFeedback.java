package com.box.l10n.mojito.service.review.feedback;

/** Optional human labels, distinct from deterministic classification and model-inferred labels. */
public record ReviewerFeedback(
    Reason reason, String note, boolean chatUsed, boolean aiSuggestionUsed) {
  public enum Reason {
    TERMINOLOGY,
    WRONG_MEANING,
    TONE_STYLE,
    GRAMMAR,
    TOO_LONG,
    CONTEXT,
    OTHER
  }

  public ReviewerFeedback {
    if (note != null && note.length() > 500)
      throw new IllegalArgumentException("Feedback note must be at most 500 characters");
  }
}
