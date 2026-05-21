package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.aspect.util.AspectJUtils;
import com.box.l10n.mojito.entity.PollableTask;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Creates and executes pollable tasks.
 *
 * <p>This keeps the old AspectJ entrypoint small while giving services a Spring-managed API to call
 * directly as the annotation usages are migrated.
 */
@Component
public class PollableTaskRunner {

  static Logger logger = LoggerFactory.getLogger(PollableTaskRunner.class);

  final PollableTaskService pollableTaskService;
  final AsyncTaskExecutor pollableTaskExecutor;
  final AspectJUtils aspectJUtils;
  final PollableTaskExceptionUtils pollableTaskExceptionUtils;

  public PollableTaskRunner(
      PollableTaskService pollableTaskService,
      @Qualifier("pollableTaskExecutor") AsyncTaskExecutor pollableTaskExecutor,
      AspectJUtils aspectJUtils,
      PollableTaskExceptionUtils pollableTaskExceptionUtils) {
    this.pollableTaskService = pollableTaskService;
    this.pollableTaskExecutor = pollableTaskExecutor;
    this.aspectJUtils = aspectJUtils;
    this.pollableTaskExceptionUtils = pollableTaskExceptionUtils;
  }

  @SuppressWarnings("FinallyDiscardsException")
  public Object run(ProceedingJoinPoint pjp) throws Throwable {
    PollableAspectParameters pollableAspectParameters =
        new PollableAspectParameters(pjp, aspectJUtils);

    logger.debug(
        "Create the PollableTask to keep track of method: {} execution",
        pollableAspectParameters.getName());
    PollableTask pollableTask =
        pollableTaskService.createPollableTask(
            pollableAspectParameters.getParentId(),
            pollableAspectParameters.getName(),
            pollableAspectParameters.getMessage(),
            pollableAspectParameters.getExpectedSubTaskNumber(),
            pollableAspectParameters.getTimeout());

    logger.debug(
        "Create the PollableFutureTask that will hold the method result and Pollable instance");
    PollableFutureTask<Object> pollableFuture =
        createPollableFuture(
            pollableTask, currentTask -> pjp.proceed(getInjectedArgs(pjp, currentTask)));

    if (pollableAspectParameters.isAsync()) {
      return asyncExecute(pollableFuture, getFunctionReturnType(pjp));
    } else {
      return syncExecute(pollableFuture, getFunctionReturnType(pjp));
    }
  }

  public <T> PollableFuture<T> runAsync(PollableTaskInvocation<T> invocation) {
    PollableFutureTask<T> pollableFuture =
        createPollableFuture(createPollableTask(invocation), invocation.operation());
    pollableTaskExecutor.submit(pollableFuture);
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

  /**
   * Executes the instrumented function synchronously.
   *
   * <p>Functions that are executed synchronously can return any type. That includes void and {@link
   * PollableFuture}.
   *
   * <p>If the return type is {@link PollableFuture}, the result is simply returned (no exception
   * will be thrown even though the instrumented function might have failed).
   *
   * <p>If the return type is not {@link PollableFuture}, the result is fetched via {@link
   * PollableFutureTask#get()}. This can throw an {@link ExecutionException} that contains the
   * original exception thrown by the instrumented function. In that case, the cause is unwrapped
   * and re-thrown by this function.
   *
   * @param pollableFuture contains the logic to be executed
   * @param functionReturnType function return type used to extract the result
   * @return the object returned by the instrumented function
   * @throws Throwable If the return type is not {@link PollableFuture}, the error thrown by the
   *     instrumented function
   */
  private Object syncExecute(PollableFutureTask<?> pollableFuture, Class<?> functionReturnType)
      throws Throwable {

    Object returnedValue = null;

    logger.debug("Execute the method synchronously");
    pollableFuture.run();

    if (PollableFuture.class.isAssignableFrom(functionReturnType)) {
      logger.debug(
          "Sync method with PollableFuture return type, return the PollableFuture instance (exception not thrown)");
      returnedValue = pollableFuture;
    } else {
      logger.debug(
          "Sync method without PollableFuture, return the result from the pollableFuture (potentially throws exceptions)");
      try {
        returnedValue = pollableFuture.get();
      } catch (ExecutionException ee) {
        throw ee.getCause();
      }
    }

    return returnedValue;
  }

  /**
   * Executes the instrumented function asynchronously.
   *
   * <p>Function that are executed asynchronously can return void or {@link PollableFuture}.
   *
   * <p>The result of the instrumented function can be retrieved via the {@link PollableFuture#get()
   * }.
   *
   * @param pollableFuture contains the logic to be executed
   * @param functionReturnType function return type used to extract the result
   * @return the pollableFuture passed as input
   * @throws RuntimeException if the instrumented function does return a proper type
   */
  private Object asyncExecute(PollableFutureTask<?> pollableFuture, Class<?> functionReturnType)
      throws RuntimeException {

    logger.debug("Check the return type for async execution");

    if (!PollableFuture.class.isAssignableFrom(functionReturnType)
        && !Void.TYPE.isAssignableFrom(functionReturnType)) {
      String msg =
          "@Pollable(async = \"true\") must be placed on a method that returns void or PollableFuture";
      logger.error(msg);
      throw new RuntimeException(msg);
    }

    logger.debug("Execute the method asynchronously");
    pollableTaskExecutor.submit(pollableFuture);

    logger.debug("Async method, return the PollableFuture instance (void is ignored)");
    return pollableFuture;
  }

  private Class<?> getFunctionReturnType(ProceedingJoinPoint pjp) {
    logger.debug("Get the return type of the instrumented method");
    MethodSignature methodSignature = (MethodSignature) pjp.getSignature();
    return methodSignature.getReturnType();
  }

  /**
   * Gets the injected method arguments.
   *
   * <p>Any argument annotated with {@link InjectCurrentTask} will be substituted by an instance of
   * the provided {@link PollableTask}.
   */
  private Object[] getInjectedArgs(ProceedingJoinPoint pjp, PollableTask pollableTask) {
    Object[] args = pjp.getArgs();

    List<AnnotatedMethodParam<InjectCurrentTask>> findAnnotatedMethodParams =
        aspectJUtils.findAnnotatedMethodParams(pjp, InjectCurrentTask.class);

    for (AnnotatedMethodParam<InjectCurrentTask> annotatedMethodParam : findAnnotatedMethodParams) {
      args[annotatedMethodParam.getIndex()] = pollableTask;
    }

    return args;
  }
}
