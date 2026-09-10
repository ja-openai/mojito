package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.rest.textunit.AiReviewChatWS;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Runs interactive review without keeping the browser's request open during provider work. */
@Component
@org.quartz.DisallowConcurrentExecution
public class AiReviewChatJob extends AiReviewAsyncJob<AiReviewChatRequest> {

  @Autowired AiReviewChatWS aiReviewChatWS;

  @Override
  public Result call(AiReviewChatRequest request) {
    try {
      return new Result(
          aiReviewChatWS.chatLegacyJob(request, getCurrentPollableTask().getId()), null);
    } catch (ResponseStatusException exception) {
      // Preserve the safe provider failure/timeout message across the asynchronous boundary.
      return new Result(
          null,
          new Error(
              exception.getStatusCode().value(),
              exception.getReason() == null
                  ? "AI review failed. Please retry."
                  : exception.getReason()));
    }
  }

  public record Result(AiReviewChatResponse response, Error error) {}

  public record Error(int status, String message) {}
}
