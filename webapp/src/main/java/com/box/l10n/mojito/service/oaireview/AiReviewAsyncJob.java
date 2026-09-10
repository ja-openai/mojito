package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Explicitly replaces QuartzPollableJob's finish-on-return contract for interactive review only.
 * Only drains jobs created by older deployments. New reviews do not use Quartz.
 */
public abstract class AiReviewAsyncJob<I> extends QuartzPollableJob<I, AiReviewChatJob.Result> {
  @Autowired AiReviewDispatchService dispatchService;

  @Override
  public final void execute(JobExecutionContext context) throws JobExecutionException {
    try {
      dispatchService.dispatch(context);
    } catch (Exception exception) {
      // No finishTask here. The completion handler or deadline cleanup terminates the task.
      throw new JobExecutionException(exception, false);
    }
  }
}
