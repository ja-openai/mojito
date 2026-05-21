package com.box.l10n.mojito.quartz;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExecutionException;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class QuartzPollableFutureTask<T> implements PollableFuture<T> {

  final PollableTaskService pollableTaskService;

  final PollableTaskBlobStorage pollableTaskBlobStorage;

  final PollableTask pollableTask;

  final Class<? extends T> outputClass;

  public QuartzPollableFutureTask(
      PollableTask pollableTask,
      Class<? extends T> outputClass,
      PollableTaskService pollableTaskService,
      PollableTaskBlobStorage pollableTaskBlobStorage) {
    this.pollableTask = pollableTask;
    this.outputClass = outputClass;
    this.pollableTaskService = pollableTaskService;
    this.pollableTaskBlobStorage = pollableTaskBlobStorage;
  }

  @Override
  public T get() throws InterruptedException, ExecutionException {
    return get(PollableTaskService.NO_TIMEOUT);
  }

  @Override
  public T get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    long milisecondeTimeout = TimeUnit.MILLISECONDS.convert(timeout, unit);
    return get(milisecondeTimeout);
  }

  T get(long milisecondTimeout) throws InterruptedException, ExecutionException {
    try {
      pollableTaskService.waitForPollableTask(pollableTask.getId(), milisecondTimeout, 100);
    } catch (PollableTaskExecutionException e) {
      throw new ExecutionException(e);
    }

    T output = null;

    if (!outputClass.equals(Void.class)) {
      output = (T) pollableTaskBlobStorage.getOutput(pollableTask.getId(), outputClass);
    }

    return output;
  }

  @Override
  public PollableTask getPollableTask() {
    return pollableTask;
  }
}
