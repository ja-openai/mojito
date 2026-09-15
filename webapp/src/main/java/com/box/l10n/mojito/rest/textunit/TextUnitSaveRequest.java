package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.service.review.feedback.ReviewerFeedback;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;

/** Optional review evidence metadata for the ordinary Workbench save endpoint. */
public class TextUnitSaveRequest extends TextUnitDTO {
  private Long reviewedVariantId;
  private String feedbackOperationId;
  private ReviewerFeedback reviewFeedback;

  public Long getReviewedVariantId() {
    return reviewedVariantId;
  }

  public void setReviewedVariantId(Long value) {
    reviewedVariantId = value;
  }

  public String getFeedbackOperationId() {
    return feedbackOperationId;
  }

  public void setFeedbackOperationId(String value) {
    feedbackOperationId = value;
  }

  public ReviewerFeedback getReviewFeedback() {
    return reviewFeedback;
  }

  public void setReviewFeedback(ReviewerFeedback value) {
    reviewFeedback = value;
  }

  public boolean hasReviewFeedbackMetadata() {
    return reviewedVariantId != null || feedbackOperationId != null || reviewFeedback != null;
  }
}
