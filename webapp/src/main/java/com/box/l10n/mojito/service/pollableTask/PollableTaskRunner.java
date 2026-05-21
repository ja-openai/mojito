package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/** Creates and executes pollable tasks. */
@Component
public class PollableTaskRunner {

  static Logger logger = LoggerFactory.getLogger(PollableTaskRunner.class);

  final PollableTaskService pollableTaskService;
  final AsyncTaskExecutor pollableTaskExecutor;
  final PollableTaskExceptionUtils pollableTaskExceptionUtils;

  public PollableTaskRunner(
      PollableTaskService pollableTaskService,
      @Qualifier("pollableTaskExecutor") AsyncTaskExecutor pollableTaskExecutor,
      PollableTaskExceptionUtils pollableTaskExceptionUtils) {
    this.pollableTaskService = pollableTaskService;
    this.pollableTaskExecutor = pollableTaskExecutor;
    this.pollableTaskExceptionUtils = pollableTaskExceptionUtils;
  }

  public <T> PollableFuture<T> runAsync(PollableTaskInvocation<T> invocation) {
    PollableFutureTask<T> pollableFuture =
        createPollableFuture(createPollableTask(invocation), invocation.operation());
    pollableTaskExecutor.submit(pollableFuture);
    return pollableFuture;
  }

  public <T> PollableFuture<T> runSyncFuture(PollableTaskInvocation<T> invocation) {
    PollableFutureTask<T> pollableFuture =
        createPollableFuture(createPollableTask(invocation), invocation.operation());
    pollableFuture.run();
    return pollableFuture;
  }

  public <T> T runSync(PollableTaskInvocation<T> invocation) throws Throwable {
    PollableFutureTask<T> pollableFuture =
        createPollableFuture(createPollableTask(invocation), invocation.operation());
    pollableFuture.run();
    try {
      return pollableFuture.get();
    } catch (ExecutionException ee) {
      throw ee.getCause();
    }
  }

  private PollableTask createPollableTask(PollableTaskInvocation<?> invocation) {
    logger.debug(
        "Create the PollableTask to keep track of method: {} execution", invocation.name());
    return pollableTaskService.createPollableTask(
        invocation.parentId(),
        invocation.name(),
        invocation.message(),
        invocation.expectedSubTaskNumber(),
        invocation.timeout());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  <T> PollableFutureTask<T> createPollableFuture(
      final PollableTask pollableTask, final PollableTaskOperation<?> operation) {
    PollableCallable pollableCallable =
        new PollableCallable(
            pollableTask, operation, pollableTaskService, pollableTaskExceptionUtils);
    PollableFutureTask<T> pollableFutureTask =
        new PollableFutureTask(pollableCallable, pollableTask);
    pollableCallable.setPollableFutureTask(pollableFutureTask);
    return pollableFutureTask;
  }
}
