package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class AsyncJobQueueFatalErrorsTest {

  @Test
  public void isJvmFatalMatchesVirtualMachineErrors() {
    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(new FatalTestError("fatal"))).isTrue();
  }

  @Test
  public void isJvmFatalMatchesThreadDeath() throws Exception {
    Throwable threadDeath =
        (Throwable) Class.forName("java.lang.ThreadDeath").getDeclaredConstructor().newInstance();

    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(threadDeath)).isTrue();
  }

  @Test
  public void isJvmFatalRejectsNonFatalErrors() {
    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(new AssertionError("non fatal"))).isFalse();
  }

  @Test
  public void isJvmFatalRejectsRuntimeExceptions() {
    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(new IllegalStateException("non fatal")))
        .isFalse();
  }

  @Test
  public void isJvmFatalRejectsNull() {
    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(null)).isFalse();
  }

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
