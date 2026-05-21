package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/**
 * Implementation of {@link PollableFuture} that extends {@link FutureTask} to perform asynchronous
 * method executing using an {@link Executor} and keeping track of the state in a {@link
 * PollableTask}.
 *
 * @author jaurambault
 */
class PollableFutureTask<T> extends FutureTask<T> implements PollableFuture<T> {

  PollableTask pollableTask;

  /** Package-private constructor so {@link PollableTaskRunner} can link the callable and future. */
  PollableFutureTask(PollableCallable pollableCallable, PollableTask pollableTask) {
    super(pollableCallable);
    this.pollableTask = pollableTask;
  }

  @Override
  public PollableTask getPollableTask() {
    return pollableTask;
  }

  public void setPollableTask(PollableTask pollableTask) {
    this.pollableTask = pollableTask;
  }
}
