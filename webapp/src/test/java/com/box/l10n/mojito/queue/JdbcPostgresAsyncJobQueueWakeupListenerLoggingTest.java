package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(Parameterized.class)
public class JdbcPostgresAsyncJobQueueWakeupListenerLoggingTest {

  @Parameterized.Parameters(name = "assertionError={0}")
  public static List<Boolean> parameters() {
    return List.of(false, true);
  }

  private final boolean assertionError;

  public JdbcPostgresAsyncJobQueueWakeupListenerLoggingTest(boolean assertionError) {
    this.assertionError = assertionError;
  }

  @Test
  public void failedConnectionLoggingCannotTerminateReconnectLoop() throws Exception {
    assertRecovery("reconnecting", ordinaryFailure(), null);
  }

  @Test
  public void interruptedReconnectLoggingCannotTerminateReconnectLoop() throws Exception {
    assertRecovery("reconnect sleep", ordinaryFailure(), null);
  }

  @Test
  public void stoppedConnectionLoggingCannotEscapeShutdown() throws Exception {
    assertRecovery("stopped", ordinaryFailure(), null);
  }

  @Test
  public void nestedFatalLoggingErrorEscapesWithoutReconnecting() throws Exception {
    Error fatal = assertionError ? new FatalThreadDeath() : new FatalVmError();
    RuntimeException wrapper = new IllegalStateException("logging wrapper");
    if (assertionError) {
      wrapper.addSuppressed(fatal);
    } else {
      wrapper.initCause(fatal);
    }
    assertRecovery("reconnecting", wrapper, fatal);
  }

  private Throwable ordinaryFailure() {
    return assertionError
        ? new AssertionError("logging unavailable")
        : new IllegalStateException("logging unavailable");
  }

  private void assertRecovery(String boundary, Throwable loggingFailure, Error expectedFatal)
      throws Exception {
    Logger originalLogger = JdbcPostgresAsyncJobQueueWakeupListener.logger;
    Logger logger = mock(Logger.class);
    if (boundary.equals("stopped")) {
      doThrow(loggingFailure).when(logger).debug(contains(boundary), any(), any());
    } else {
      doThrow(loggingFailure).when(logger).warn(contains(boundary), any(), any());
    }
    DataSource dataSource = mock(DataSource.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch releaseFailure = new CountDownLatch(1);
    CountDownLatch releaseReconnect = new CountDownLatch(1);
    CountDownLatch outcome = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<Throwable> uncaught = new AtomicReference<>();
    when(dataSource.getConnection())
        .thenAnswer(
            invocation -> {
              if (attempts.incrementAndGet() == 1) {
                entered.countDown();
                awaitUninterruptibly(releaseFailure);
                if (boundary.equals("reconnect sleep")) {
                  Thread.currentThread().interrupt();
                }
              } else {
                outcome.countDown();
                awaitUninterruptibly(releaseReconnect);
              }
              throw new SQLException("connection unavailable");
            });
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    when(coordinator.hasEnabledConsumers()).thenReturn(true);
    AsyncJobQueueProperties.WakeupSettings settings = new AsyncJobQueueProperties.WakeupSettings();
    settings.setPostgresListenTimeoutMs(1);
    settings.setReconnectDelayMs(1);
    settings.setReconnectJitterPercent(0);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    try (AutoCloseable registryCleanup = registry::close) {
      JdbcPostgresAsyncJobQueueWakeupListener listener =
          new JdbcPostgresAsyncJobQueueWakeupListener(dataSource, settings, coordinator, registry);
      Thread thread = null;
      JdbcPostgresAsyncJobQueueWakeupListener.logger = logger;
      try {
        listener.start();
        thread = (Thread) ReflectionTestUtils.getField(listener, "listenerThread");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        thread.setUncaughtExceptionHandler(
            (ignored, failure) -> {
              uncaught.set(failure);
              outcome.countDown();
            });
        if (boundary.equals("stopped")) {
          listener.stop();
        }
        releaseFailure.countDown();

        if (boundary.equals("stopped") || expectedFatal != null) {
          thread.join(2_000);
          assertThat(thread.isAlive()).isFalse();
          assertThat(listener.isRunning()).isFalse();
          assertThat(attempts).hasValue(1);
          assertThat(uncaught.get()).isSameAs(expectedFatal);
        } else {
          assertThat(outcome.await(2, TimeUnit.SECONDS)).isTrue();
          assertThat(uncaught.get()).as("logging must not terminate recovery").isNull();
          assertThat(attempts).hasValue(2);
          assertThat(listener.isRunning()).isTrue();
          assertThat(thread.isAlive()).isTrue();
        }
        if (boundary.equals("stopped")) {
          verify(logger).debug(contains(boundary), any(), any());
        } else {
          verify(logger).warn(contains(boundary), any(), any());
        }
      } finally {
        try {
          releaseFailure.countDown();
          try {
            listener.stop();
          } finally {
            releaseReconnect.countDown();
            if (thread != null) {
              thread.join(2_000);
              assertThat(thread.isAlive()).as("listener thread cleaned up").isFalse();
            }
          }
        } finally {
          JdbcPostgresAsyncJobQueueWakeupListener.logger = originalLogger;
        }
      }
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      try {
        if (latch.await(10, TimeUnit.MILLISECONDS)) {
          return;
        }
      } catch (InterruptedException ignored) {
        // A blocked JDBC call need not react to the listener's shutdown interrupt.
      }
    }
    throw new AssertionError("test did not release connection attempt");
  }

  private static final class FatalVmError extends VirtualMachineError {}

  private static final class FatalThreadDeath extends ThreadDeath {}
}
