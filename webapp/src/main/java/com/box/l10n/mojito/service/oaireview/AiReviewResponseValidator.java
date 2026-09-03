package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.openai.OpenAIClient.ResponsesResponse;

/** Reject partial provider output even when its text happens to be valid review JSON. */
public final class AiReviewResponseValidator {

  private AiReviewResponseValidator() {}

  public static String outputText(ResponsesResponse response) {
    if (response == null
        || !"completed".equals(response.status())
        || response.error() != null
        || response.incompleteDetails() != null) {
      throw new InvalidReviewResponseException("AI review provider response did not complete.");
    }
    if (response.output() != null
        && response.output().stream()
            .anyMatch(
                output ->
                    output == null
                        || ("message".equals(output.type())
                            && output.status() != null
                            && !"completed".equals(output.status())))) {
      throw new InvalidReviewResponseException("AI review provider message did not complete.");
    }
    String text = response.outputText();
    if (text == null || text.isBlank()) {
      throw new InvalidReviewResponseException("AI review provider returned no review output.");
    }
    return text;
  }

  public static class InvalidReviewResponseException extends IllegalStateException {
    public InvalidReviewResponseException(String message) {
      super(message);
    }
  }
}
