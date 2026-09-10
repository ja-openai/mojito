package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.rest.textunit.AiReviewChatWS;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Uses settings and the authenticated requester captured by the submission endpoint. */
@Component
@org.quartz.DisallowConcurrentExecution
public class AiReviewConfiguredChatJob extends AiReviewAsyncJob<Prepared> {
  @Autowired AiReviewChatWS aiReviewChatWS;

  @Override
  public AiReviewChatJob.Result call(Prepared input) {
    try {
      return new AiReviewChatJob.Result(
          aiReviewChatWS.chatPrepared(input, getCurrentPollableTask().getId()), null);
    } catch (ResponseStatusException exception) {
      return new AiReviewChatJob.Result(
          null,
          new AiReviewChatJob.Error(
              exception.getStatusCode().value(),
              exception.getReason() == null
                  ? "AI review failed. Please retry."
                  : exception.getReason()));
    }
  }
}
