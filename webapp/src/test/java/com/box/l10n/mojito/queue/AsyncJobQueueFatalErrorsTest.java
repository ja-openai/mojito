package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class AsyncJobQueueFatalErrorsTest {

  @Test
  public void isJvmFatalRecognizesThreadDeathSubclasses() {
    ThreadDeath fatal = new ThreadDeathSubclass();

    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(fatal)).isTrue();
    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(fatal)).isSameAs(fatal);
  }

  @Test
  public void findJvmFatalRecognizesWrappedThreadDeathSubclasses() {
    ThreadDeath fatal = new ThreadDeathSubclass();

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(new IllegalStateException(fatal)))
        .isSameAs(fatal);
  }

  @Test
  public void findJvmFatalRecognizesSuppressedThreadDeathSubclasses() {
    ThreadDeath fatal = new ThreadDeathSubclass();
    RuntimeException failure = new IllegalStateException("ordinary failure");
    failure.addSuppressed(fatal);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);
    assertThat(failure.getSuppressed()).containsExactly(fatal);
  }

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

  @Test(timeout = 5000)
  public void isJvmFatalDoesNotSearchCausesOrSuppressedErrors() {
    RuntimeException causedByFatal = new RuntimeException(new FatalTestError("cause"));
    AssertionError suppressingFatal = new AssertionError("non fatal");
    suppressingFatal.addSuppressed(new FatalTestError("cleanup"));

    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(causedByFatal)).isFalse();
    assertThat(AsyncJobQueueFatalErrors.isJvmFatal(suppressingFatal)).isFalse();
  }

  @Test(timeout = 5000)
  public void findJvmFatalReturnsNullForNull() {
    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(null)).isNull();
  }

  @Test(timeout = 5000)
  public void findJvmFatalReturnsNullForNonFatalGraph() {
    AssertionError cause = new AssertionError("non fatal cause");
    RuntimeException failure = new RuntimeException("failure", cause);
    RuntimeException cleanup = new RuntimeException("cleanup");
    cleanup.addSuppressed(new AssertionError("nested cleanup"));
    failure.addSuppressed(cleanup);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isNull();
  }

  @Test(timeout = 5000)
  public void findJvmFatalReturnsDirectVirtualMachineErrorByIdentity() {
    FatalTestError fatal = new FatalTestError("fatal");

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(fatal)).isSameAs(fatal);
  }

  @Test(timeout = 5000)
  public void findJvmFatalReturnsDirectThreadDeathByIdentity() throws Exception {
    Throwable threadDeath =
        (Throwable) Class.forName("java.lang.ThreadDeath").getDeclaredConstructor().newInstance();

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(threadDeath)).isSameAs(threadDeath);
  }

  @Test(timeout = 5000)
  public void findJvmFatalSearchesNestedCauses() {
    FatalTestError fatal = new FatalTestError("fatal");
    RuntimeException failure =
        new RuntimeException("outer", new IllegalStateException("inner", fatal));

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);
  }

  @Test(timeout = 5000)
  public void findJvmFatalSearchesSuppressedErrorsPastNonFatalFailures() throws Exception {
    Throwable threadDeath =
        (Throwable) Class.forName("java.lang.ThreadDeath").getDeclaredConstructor().newInstance();
    RuntimeException failure = new RuntimeException("failure");
    failure.addSuppressed(new AssertionError("non fatal cleanup"));
    failure.addSuppressed(threadDeath);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(threadDeath);
  }

  @Test(timeout = 5000)
  public void findJvmFatalSearchesCausesOfSuppressedCleanupFailures() {
    FatalTestError fatal = new FatalTestError("fatal cleanup");
    RuntimeException failure = new RuntimeException("failure");
    RuntimeException cleanup = new RuntimeException("cleanup");
    cleanup.addSuppressed(new RuntimeException("nested cleanup", fatal));
    failure.addSuppressed(cleanup);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);
  }

  @Test(timeout = 5000)
  public void findJvmFatalSearchesSuppressedCleanupFailuresOfCauses() {
    FatalTestError fatal = new FatalTestError("fatal cleanup");
    RuntimeException cause = new RuntimeException("cause");
    RuntimeException failure = new RuntimeException("failure", cause);
    RuntimeException cleanup = new RuntimeException("cleanup");
    cleanup.addSuppressed(fatal);
    cause.addSuppressed(cleanup);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);
  }

  @Test(timeout = 5000)
  public void findJvmFatalReturnsNullForCauseCycleWithoutMutatingGraph() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(first)).isNull();

    assertThat(first.getCause()).isSameAs(second);
    assertThat(second.getCause()).isSameAs(first);
    assertThat(first.getSuppressed()).isEmpty();
    assertThat(second.getSuppressed()).isEmpty();
  }

  @Test(timeout = 5000)
  public void findJvmFatalReturnsNullForSuppressedCycleWithoutMutatingGraph() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second");
    first.addSuppressed(second);
    second.addSuppressed(first);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(first)).isNull();

    assertThat(first.getCause()).isNull();
    assertThat(second.getCause()).isNull();
    assertThat(first.getSuppressed()).containsExactly(second);
    assertThat(second.getSuppressed()).containsExactly(first);
  }

  @Test(timeout = 5000)
  public void findJvmFatalReturnsNullForRepeatedReferencesWithoutMutatingGraph() {
    AssertionError shared = new AssertionError("shared non fatal");
    RuntimeException failure = new RuntimeException("failure", shared);
    failure.addSuppressed(shared);
    failure.addSuppressed(shared);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isNull();

    assertThat(failure.getCause()).isSameAs(shared);
    assertThat(failure.getSuppressed()).containsExactly(shared, shared);
    assertThat(shared.getCause()).isNull();
    assertThat(shared.getSuppressed()).isEmpty();
  }

  @Test(timeout = 5000)
  public void findJvmFatalSearchesPastMixedCyclesWithoutMutatingGraph() {
    FatalTestError fatal = new FatalTestError("fatal cleanup");
    RuntimeException failure = new RuntimeException("failure");
    RuntimeException cleanup = new RuntimeException("cleanup", failure);
    failure.initCause(cleanup);
    failure.addSuppressed(cleanup);
    cleanup.addSuppressed(failure);
    cleanup.addSuppressed(fatal);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);
    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);

    assertThat(failure.getCause()).isSameAs(cleanup);
    assertThat(failure.getSuppressed()).containsExactly(cleanup);
    assertThat(cleanup.getCause()).isSameAs(failure);
    assertThat(cleanup.getSuppressed()).containsExactly(failure, fatal);
    assertThat(fatal.getCause()).isNull();
    assertThat(fatal.getSuppressed()).isEmpty();
  }

  @Test(timeout = 5000)
  public void findJvmFatalTracksDistinctThrowablesByIdentityRatherThanEquality() {
    FatalTestError fatal = new FatalTestError("fatal");
    EqualTestException failure = new EqualTestException();
    EqualTestException cleanup = new EqualTestException();
    failure.addSuppressed(cleanup);
    cleanup.initCause(fatal);

    assertThat(failure).isEqualTo(cleanup).isNotSameAs(cleanup);
    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);
  }

  @Test(timeout = 5000)
  public void findJvmFatalReadsCauseOncePerDistinctThrowable() {
    FatalTestError fatal = new FatalTestError("fatal");
    CauseCountingException shared = new CauseCountingException(fatal);
    CauseCountingException failure = new CauseCountingException(shared);
    failure.addSuppressed(shared);
    failure.addSuppressed(shared);
    shared.addSuppressed(failure);

    assertThat(AsyncJobQueueFatalErrors.findJvmFatal(failure)).isSameAs(fatal);
    assertThat(failure.causeReads).isEqualTo(1);
    assertThat(shared.causeReads).isEqualTo(1);
  }

  private static class CauseCountingException extends RuntimeException {
    private int causeReads;

    CauseCountingException(Throwable cause) {
      super("failure", cause);
    }

    @Override
    public synchronized Throwable getCause() {
      causeReads++;
      return super.getCause();
    }
  }

  private static class EqualTestException extends RuntimeException {
    @Override
    public boolean equals(Object other) {
      return other instanceof EqualTestException;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }

  private static class ThreadDeathSubclass extends ThreadDeath {}

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
