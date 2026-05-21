package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See {@link PollableAspect}.
 *
 * <p>Contains the logic to call the instrumented function and record its state when finished.
 * Implementing {@link Callable} to potentially execute the function asynchronously.
 *
 * @author jaurambault
 */
public class PollableCallable implements Callable {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(PollableCallable.class);

  final PollableTaskService pollableTaskService;

  final PollableTaskExceptionUtils pollableTaskExceptionUtils;

  PollableTask pollableTask;
  PollableTaskOperation<?> operation;

  PollableFutureTask pollableFutureTask;

  public PollableCallable(
      PollableTask pollableTask,
      PollableTaskOperation<?> operation,
      PollableTaskService pollableTaskService,
      PollableTaskExceptionUtils pollableTaskExceptionUtils) {
    this.pollableTask = pollableTask;
    this.operation = operation;
    this.pollableTaskService = pollableTaskService;
    this.pollableTaskExceptionUtils = pollableTaskExceptionUtils;
  }

  @Override
  public Object call() throws Exception {

    ExceptionHolder exceptionHolder = new ExceptionHolder(pollableTask);
    PollableFuture pollableFuture = new PollableFutureTaskResult();

    try {
      Object proceed = operation.call(pollableTask);

      if (proceed instanceof PollableFuture) {
        pollableFuture = (PollableFuture) proceed;
      } else {
        ((PollableFutureTaskResult) pollableFuture).setResult(proceed);
      }

    } catch (Throwable t) {
      pollableTaskExceptionUtils.processException(t, exceptionHolder);
      throw exceptionHolder.getException();
    } finally {

      pollableTask =
          pollableTaskService.finishTask(
              pollableTask.getId(),
              getMessageOverride(pollableFuture),
              exceptionHolder,
              getExpectedSubTaskNumberOverride(pollableFuture));

      pollableFutureTask.setPollableTask(pollableTask);
    }

    return pollableFuture.get();
  }

  /**
   * Gets the message override value if the {@link PollableFuture} is an instance of {@link
   * PollableFutureTaskResult}.
   *
   * @param pollableFuture to extract the message override from
   * @return the message override or {@code null} if no message override or if the pollableFuture is
   *     not an instance of {@link PollableFutureTaskResult}
   */
  private String getMessageOverride(PollableFuture pollableFuture) {
    String message = null;

    if (pollableFuture instanceof PollableFutureTaskResult) {
      message = ((PollableFutureTaskResult) pollableFuture).getMessageOverride();
    }

    return message;
  }

  /**
   * Gets the excepted sub task number override value if the {@link PollableFuture} is an instance
   * of {@link PollableFutureTaskResult}.
   *
   * @param pollableFuture to extract the message override from
   * @return the expected sub task number override or {@code null} if no message override or if the
   *     pollableFuture is not an instance of {@link PollableFutureTaskResult}
   */
  private Integer getExpectedSubTaskNumberOverride(PollableFuture pollableFuture) {
    Integer expectedSubTaskNumberOverride = null;

    if (pollableFuture instanceof PollableFutureTaskResult) {
      expectedSubTaskNumberOverride =
          ((PollableFutureTaskResult) pollableFuture).getExpectedSubTaskNumberOverride();
    }

    return expectedSubTaskNumberOverride;
  }

  void setPollableFutureTask(PollableFutureTask pollableFutureTask) {
    this.pollableFutureTask = pollableFutureTask;
  }
}
