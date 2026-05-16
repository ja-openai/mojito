package com.box.l10n.mojito.test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public final class TestAwait {

  private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

  private TestAwait() {}

  public static void until(Duration timeout, ThrowingBooleanSupplier condition) {
    untilAsserted(
        timeout,
        () -> {
          if (!condition.getAsBoolean()) {
            throw new AssertionError("Condition returned false");
          }
        });
  }

  @SafeVarargs
  public static void untilAsserted(
      Duration timeout,
      ThrowingRunnable assertion,
      Class<? extends Throwable>... ignoredExceptionTypes) {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    List<Class<? extends Throwable>> ignoredExceptions = Arrays.asList(ignoredExceptionTypes);
    Throwable lastFailure = null;

    while (true) {
      try {
        assertion.run();
        return;
      } catch (Throwable t) {
        if (!(t instanceof AssertionError) && !isIgnored(t, ignoredExceptions)) {
          throw propagate(t);
        }
        lastFailure = t;
      }

      if (System.nanoTime() >= deadlineNanos) {
        throw new AssertionError("Condition was not met within " + timeout, lastFailure);
      }

      sleep();
    }
  }

  private static boolean isIgnored(
      Throwable throwable, List<Class<? extends Throwable>> ignoredExceptions) {
    return ignoredExceptions.stream().anyMatch(ignored -> ignored.isInstance(throwable));
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for condition", e);
    }
  }

  private static RuntimeException propagate(Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (throwable instanceof Error error) {
      throw error;
    }
    return new RuntimeException(throwable);
  }

  @FunctionalInterface
  public interface ThrowingBooleanSupplier {
    boolean getAsBoolean() throws Exception;
  }

  @FunctionalInterface
  public interface ThrowingRunnable {
    void run() throws Exception;
  }
}
