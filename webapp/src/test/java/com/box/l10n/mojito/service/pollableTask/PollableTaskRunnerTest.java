package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.aspect.util.AspectJUtils;
import com.box.l10n.mojito.entity.PollableTask;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

class PollableTaskRunnerTest {

  @Test
  void runAsyncCreatesExecutesAndFinishesTask() throws Exception {
    PollableTaskService pollableTaskService = mock(PollableTaskService.class);
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(1L);
    PollableTask finishedTask = new PollableTask();
    finishedTask.setId(1L);
    finishedTask.setMessage("done");

    when(pollableTaskService.createPollableTask(null, "testTask", "starting", 0, -1L))
        .thenReturn(pollableTask);
    when(pollableTaskService.finishTask(eq(1L), eq("done"), any(ExceptionHolder.class), isNull()))
        .thenReturn(finishedTask);
    AsyncTaskExecutor pollableTaskExecutor = mock(AsyncTaskExecutor.class);
    when(pollableTaskExecutor.submit(any(Runnable.class)))
        .thenAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return CompletableFuture.completedFuture(null);
            });

    PollableTaskRunner pollableTaskRunner =
        new PollableTaskRunner(
            pollableTaskService,
            pollableTaskExecutor,
            mock(AspectJUtils.class),
            new PollableTaskExceptionUtils());

    PollableFuture<String> future =
        pollableTaskRunner.runAsync(
            PollableTaskInvocation.ofFuture(
                "testTask",
                "starting",
                currentTask -> {
                  assertThat(currentTask).isSameAs(pollableTask);
                  PollableFutureTaskResult<String> result = new PollableFutureTaskResult<>("ok");
                  result.setMessageOverride("done");
                  return result;
                }));

    assertThat(future.get()).isEqualTo("ok");
    assertThat(future.getPollableTask()).isSameAs(finishedTask);
    verify(pollableTaskService).createPollableTask(null, "testTask", "starting", 0, -1L);
    verify(pollableTaskService)
        .finishTask(eq(1L), eq("done"), any(ExceptionHolder.class), isNull());
  }
}
