package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import java.util.concurrent.Future;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.DeclareError;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Aspect to make a function execution "Pollable". Wraps the function to keep track of it's
 * execution and record the state in the {@link PollableTask} entity.
 *
 * <p>The function execution can be synchronous or asynchronous.
 *
 * <p>To get access to the {@link PollableTask} entity that was created, the instrumented function
 * can return a {@link PollableFuture}.
 *
 * <p>{@link PollableFuture} is an extension of {@link Future} and behave in a similar way to
 * retrieve results and for exception handling.
 *
 * <p>Asynchronous function must use void or a {@link PollableFuture} as return type.
 *
 * <p>Synchronous function can return any type. If an exception occurs it will be thrown as usual,
 * except when returning {@link PollableFuture}.
 *
 * <p>For synchronous function that returns {@link PollableFuture}, retrieving result and exception
 * handling becomes the same as for an asynchronous function (exception will be thrown only when
 * retrieving the result).
 *
 * @author jaurambault
 */
@Aspect
public class PollableAspect {

  @Autowired PollableTaskRunner pollableTaskRunner;

  @Around("methods()")
  public Object createPollableWrapper(ProceedingJoinPoint pjp) throws Throwable {
    return pollableTaskRunner.run(pjp);
  }

  @Pointcut("execution(@Pollable * *(..))")
  private void methods() {}

  @DeclareError("execution(!@Pollable * *(.., @ParentTask (*),..))")
  private static final String parentTaskShouldBeOnPollable =
      "@ParentTask should be applied on methods annotated with @Pollable";

  @DeclareError("execution(!@Pollable * *(.., @InjectCurrentTask (*),..))")
  private static final String injectTaskShouldBeOnPollable =
      "@InjectCurrentTask should be applied on methods annotated with @Pollable";

  @DeclareError("execution(!@Pollable * *(.., @MsgArg (*),..))")
  private static final String msgArgTaskShouldBeOnPollable =
      "@MsgArg should be applied on methods annotated with @Pollable";
}
