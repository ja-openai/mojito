package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.Test;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;

public class JdbcPostgresAsyncJobQueueWakeupListenerCleanupFailureTest {

  private static final String UNLISTEN = "UNLISTEN \"mojito_async_job_queue\"";

  @Test
  public void fatalUnsubscribeEscapesOrdinaryReadFailure() throws Exception {
    FatalTestError fatal = new FatalTestError();
    try (Fixture fixture = new Fixture(new SQLException("read failed"))) {
      doThrow(fatal).when(fixture.unsubscribe).execute(UNLISTEN);
      fixture.assertFatal(fatal);
    }
  }

  @Test
  public void fatalUnsubscribeStatementCloseEscapesOrdinaryReadFailure() throws Exception {
    FatalTestError fatal = new FatalTestError();
    try (Fixture fixture = new Fixture(new SQLException("read failed"))) {
      doThrow(fatal).when(fixture.unsubscribe).close();
      fixture.assertFatal(fatal);
    }
  }

  @Test
  public void fatalAbortEscapesReadAndUnsubscribeFailures() throws Exception {
    FatalTestError fatal = new FatalTestError();
    try (Fixture fixture = new Fixture(new SQLException("read failed"))) {
      doThrow(new SQLException("unsubscribe failed")).when(fixture.unsubscribe).execute(UNLISTEN);
      doThrow(fatal).when(fixture.connection).abort(any());
      fixture.assertFatal(fatal);
    }
  }

  @Test
  public void fatalAutoCommitRestoreEscapesOrdinaryReadFailure() throws Exception {
    FatalTestError fatal = new FatalTestError();
    try (Fixture fixture = new Fixture(new SQLException("read failed"))) {
      when(fixture.connection.getAutoCommit()).thenReturn(false);
      doThrow(fatal).when(fixture.connection).setAutoCommit(false);
      fixture.assertFatal(fatal);
    }
  }

  @Test
  public void uncheckedConnectionCloseDoesNotMaskFatalRead() throws Exception {
    FatalTestError fatal = new FatalTestError();
    IllegalStateException closeFailure = new IllegalStateException("close failed");
    try (Fixture fixture = new Fixture(fatal)) {
      doThrow(closeFailure).when(fixture.connection).close();
      fixture.assertFatal(fatal);
      assertThat(fatal.getSuppressed()).contains(closeFailure);
    }
  }

  @Test
  public void ordinaryUnsubscribeFailureRemainsAttachedToFatalRead() throws Exception {
    FatalTestError fatal = new FatalTestError();
    SQLException unsubscribeFailure = new SQLException("unsubscribe failed");
    try (Fixture fixture = new Fixture(fatal)) {
      doThrow(unsubscribeFailure).when(fixture.unsubscribe).execute(UNLISTEN);
      fixture.assertFatal(fatal);
      assertThat(fatal.getSuppressed()).contains(unsubscribeFailure);
    }
  }

  @Test
  public void fatalConnectionCloseEscapesOrdinaryReadFailure() throws Exception {
    FatalTestError fatal = new FatalTestError();
    try (Fixture fixture = new Fixture(new SQLException("read failed"))) {
      doThrow(fatal).when(fixture.connection).close();
      fixture.assertFatal(fatal);
    }
  }

  @Test
  public void ordinaryUnsubscribeFailureRemainsAttachedToReadFailure() throws Exception {
    SQLException readFailure = new SQLException("read failed");
    SQLException unsubscribeFailure = new SQLException("unsubscribe failed");
    try (Fixture fixture = new Fixture(readFailure)) {
      doThrow(unsubscribeFailure).when(fixture.unsubscribe).execute(UNLISTEN);
      fixture.assertRecoverable(readFailure);
      assertThat(readFailure.getSuppressed()).contains(unsubscribeFailure);
      verify(fixture.connection).abort(any());
    }
  }

  @Test
  public void uncheckedCloseFailureRemainsAttachedToReadFailure() throws Exception {
    SQLException readFailure = new SQLException("read failed");
    IllegalStateException closeFailure = new IllegalStateException("close failed");
    try (Fixture fixture = new Fixture(readFailure)) {
      doThrow(closeFailure).when(fixture.connection).close();
      fixture.assertRecoverable(readFailure);
      assertThat(readFailure.getSuppressed()).contains(closeFailure);
    }
  }

  @Test
  public void fatalStatementCloseIsNotHiddenByUncheckedAbort() throws Exception {
    FatalTestError fatal = new FatalTestError();
    try (Fixture fixture = new Fixture(new SQLException("read failed"))) {
      doThrow(new SQLException("unsubscribe failed")).when(fixture.unsubscribe).execute(UNLISTEN);
      doThrow(fatal).when(fixture.unsubscribe).close();
      doThrow(new IllegalStateException("abort failed")).when(fixture.connection).abort(any());
      fixture.assertFatal(fatal);
    }
  }

  @Test
  public void uncheckedAbortFailureRemainsAttachedToUnsubscribeFailure() throws Exception {
    SQLException readFailure = new SQLException("read failed");
    SQLException unsubscribeFailure = new SQLException("unsubscribe failed");
    IllegalStateException abortFailure = new IllegalStateException("abort failed");
    try (Fixture fixture = new Fixture(readFailure)) {
      doThrow(unsubscribeFailure).when(fixture.unsubscribe).execute(UNLISTEN);
      doThrow(abortFailure).when(fixture.connection).abort(any());
      fixture.assertRecoverable(readFailure);
      assertThat(readFailure.getSuppressed()).contains(unsubscribeFailure);
      assertThat(unsubscribeFailure.getSuppressed()).contains(abortFailure);
    }
  }

  @Test
  public void repeatedAbortErrorDoesNotReplaceUnsubscribeFailure() throws Exception {
    SQLException readFailure = new SQLException("read failed");
    SQLException unsubscribeFailure = new SQLException("unsubscribe failed");
    try (Fixture fixture = new Fixture(readFailure)) {
      doThrow(unsubscribeFailure).when(fixture.unsubscribe).execute(UNLISTEN);
      doThrow(unsubscribeFailure).when(fixture.connection).abort(any());
      fixture.assertRecoverable(readFailure);
      assertThat(readFailure.getSuppressed()).contains(unsubscribeFailure);
      assertThat(unsubscribeFailure.getSuppressed()).isEmpty();
    }
  }

  private static class Fixture implements AutoCloseable {
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement unsubscribe = mock(Statement.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Logger originalLogger = JdbcPostgresAsyncJobQueueWakeupListener.logger;
    private final CountDownLatch readStarted = new CountDownLatch(1);
    private final CountDownLatch releaseRead = new CountDownLatch(1);
    private final CountDownLatch outcome = new CountDownLatch(1);
    private final AtomicReference<Throwable> uncaught = new AtomicReference<>();
    private final AtomicReference<Throwable> reconnectFailure = new AtomicReference<>();
    private final JdbcPostgresAsyncJobQueueWakeupListener listener;
    private Thread thread;

    Fixture(Throwable readFailure) throws Exception {
      Statement subscribe = mock(Statement.class);
      PGConnection pgConnection = mock(PGConnection.class);
      when(dataSource.getConnection()).thenReturn(connection);
      when(connection.getAutoCommit()).thenReturn(true);
      when(connection.createStatement()).thenReturn(subscribe, unsubscribe);
      when(connection.unwrap(PGConnection.class)).thenReturn(pgConnection);
      when(pgConnection.getNotifications(anyInt()))
          .thenAnswer(
              invocation -> {
                readStarted.countDown();
                assertThat(releaseRead.await(5, TimeUnit.SECONDS)).isTrue();
                throw readFailure;
              });
      AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
      when(coordinator.hasEnabledConsumers()).thenReturn(true);
      AsyncJobQueueProperties.WakeupSettings settings =
          new AsyncJobQueueProperties.WakeupSettings();
      settings.setPostgresListenTimeoutMs(100);
      settings.setReconnectDelayMs(60_000);
      settings.setReconnectJitterPercent(0);
      listener =
          new JdbcPostgresAsyncJobQueueWakeupListener(dataSource, settings, coordinator, registry);
      Logger logger = mock(Logger.class);
      doAnswer(
              invocation -> {
                reconnectFailure.set(invocation.getArgument(2));
                outcome.countDown();
                return null;
              })
          .when(logger)
          .warn(
              eq("PostgreSQL async queue wakeup listener failed for channel {}; reconnecting"),
              eq("mojito_async_job_queue"),
              any(Throwable.class));
      JdbcPostgresAsyncJobQueueWakeupListener.logger = logger;
    }

    private void startAndAwaitOutcome() throws Exception {
      listener.start();
      thread = (Thread) ReflectionTestUtils.getField(listener, "listenerThread");
      assertThat(readStarted.await(2, TimeUnit.SECONDS)).isTrue();
      thread.setUncaughtExceptionHandler(
          (ignored, failure) -> {
            uncaught.set(failure);
            outcome.countDown();
          });
      releaseRead.countDown();
      assertThat(outcome.await(2, TimeUnit.SECONDS)).as("fatal exit or reconnect outcome").isTrue();
      verify(connection).close();
      verify(dataSource).getConnection();
      assertThat(registry.get("asyncJobQueue.wakeup.listener.connected").gauge().value()).isZero();
    }

    void assertFatal(Error expected) throws Exception {
      startAndAwaitOutcome();
      assertThat(uncaught.get()).isSameAs(expected);
      thread.join(2_000);
      assertThat(thread.isAlive()).isFalse();
      assertThat(listener.isRunning()).isFalse();
      assertThat(reconnectFailure.get()).isNull();
      assertThat(registry.find("asyncJobQueue.wakeup.listen").tag("result", "failed").counter())
          .isNull();
    }

    void assertRecoverable(Throwable expected) throws Exception {
      startAndAwaitOutcome();
      assertThat(reconnectFailure.get()).isSameAs(expected);
      assertThat(uncaught.get()).isNull();
      assertThat(listener.isRunning()).isTrue();
      Counter failures =
          registry.get("asyncJobQueue.wakeup.listen").tag("result", "failed").counter();
      assertThat(failures.count()).isEqualTo(1);
    }

    @Override
    public void close() throws Exception {
      try {
        releaseRead.countDown();
        listener.stop();
        if (thread != null) {
          thread.join(2_000);
          assertThat(thread.isAlive()).as("listener cleanup").isFalse();
        }
      } finally {
        JdbcPostgresAsyncJobQueueWakeupListener.logger = originalLogger;
        registry.close();
      }
    }
  }

  private static class FatalTestError extends VirtualMachineError {}
}
