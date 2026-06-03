package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

public class AsyncJobQueueRetentionDiagnosticsTest {

  private enum Fault {
    REGISTRATION,
    INCREMENT,
    LOGGING,
    COUNTER_AND_LOGGING
  }

  private final Logger originalLogger = AsyncJobQueueRetentionCleaner.logger;
  private final AsyncJobStore store = mock(AsyncJobStore.class);
  private final MeterRegistry registry = mock(MeterRegistry.class);
  private Throwable registrationFailure;
  private Throwable incrementFailure;
  private Throwable loggingFailure;
  private final Counter counter = counter();
  private final Error fatal = new InternalError("fatal retention failure");
  private AsyncJobQueueRetentionCleaner cleaner;

  @Before
  public void setup() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setQueues(new LinkedHashMap<>());
    for (String queue : List.of("first", "second")) {
      properties.getQueues().put(queue, new AsyncJobQueueProperties.QueueSettings());
    }
    properties.getRetention().setBatchSize(2);
    when(store.deleteTerminalJobsOlderThan(anyString(), any(), any(), anyInt())).thenReturn(2);
    when(registry.counter(anyString(), any(String[].class)))
        .thenAnswer(
            invocation -> {
              if (registrationFailure != null) {
                throw registrationFailure;
              }
              return counter;
            });
    AsyncJobQueueRetentionCleaner.logger =
        mock(
            Logger.class,
            invocation -> {
              if (loggingFailure != null
                  && List.of("info", "warn").contains(invocation.getMethod().getName())) {
                throw loggingFailure;
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
    cleaner = new AsyncJobQueueRetentionCleaner(store, properties, List.of(), registry);
  }

  @After
  public void tearDown() {
    AsyncJobQueueRetentionCleaner.logger = originalLogger;
  }

  @Test(timeout = 2_000)
  public void successSurvivesCounterRegistrationFailure() {
    assertContinues(Fault.REGISTRATION, false);
  }

  @Test(timeout = 2_000)
  public void successSurvivesCounterIncrementFailure() {
    assertContinues(Fault.INCREMENT, false);
  }

  @Test(timeout = 2_000)
  public void successSurvivesLoggingFailure() {
    assertContinues(Fault.LOGGING, false);
  }

  @Test(timeout = 2_000)
  public void successSurvivesCombinedDiagnosticsFailure() {
    assertContinues(Fault.COUNTER_AND_LOGGING, false);
  }

  @Test(timeout = 2_000)
  public void storeFailureContinuesDespiteCounterRegistrationFailure() {
    assertContinues(Fault.REGISTRATION, true);
  }

  @Test(timeout = 2_000)
  public void storeFailureContinuesDespiteCounterIncrementFailure() {
    assertContinues(Fault.INCREMENT, true);
  }

  @Test(timeout = 2_000)
  public void storeFailureContinuesDespiteLoggingFailure() {
    assertContinues(Fault.LOGGING, true);
  }

  @Test(timeout = 2_000)
  public void storeFailureContinuesDespiteCombinedDiagnosticsFailure() {
    assertContinues(Fault.COUNTER_AND_LOGGING, true);
  }

  @Test(timeout = 2_000)
  public void zeroDeletesProduceNoDiagnostics() {
    when(store.deleteTerminalJobsOlderThan(anyString(), any(), any(), anyInt())).thenReturn(0);
    cleaner.cleanupTerminalJobs();
    verifyAllDeletes();
    verifyNoInteractions(registry, counter, AsyncJobQueueRetentionCleaner.logger);
  }

  @Test(timeout = 2_000)
  public void directWrappedAndSuppressedStoreFatalsStopSweep() {
    for (Throwable failure :
        List.of(fatal, new IllegalStateException(new RuntimeException(fatal)), suppress(fatal))) {
      failFirstDelete(failure);
      assertFatal(fatal);
      verifyNoInteractions(registry, AsyncJobQueueRetentionCleaner.logger);
      clearInvocations(store);
    }
  }

  @Test(timeout = 2_000)
  public void directSuccessLoggingFatalStopsSweep() {
    loggingFailure = fatal;
    assertFatal(fatal);
  }

  @Test(timeout = 2_000)
  public void wrappedSuccessCounterRegistrationFatalStopsSweep() {
    registrationFailure = new IllegalStateException(fatal);
    assertFatal(fatal);
  }

  @Test(timeout = 2_000)
  public void suppressedSuccessCounterIncrementFatalStopsSweep() {
    incrementFailure = suppress(fatal);
    assertFatal(fatal);
  }

  @Test(timeout = 2_000)
  public void wrappedFailureLoggingFatalStopsSweep() throws Exception {
    Error fatal =
        (Error) Class.forName("java.lang.ThreadDeath").getDeclaredConstructor().newInstance();
    failFirstDelete(new IllegalStateException("store unavailable"));
    loggingFailure = new IllegalStateException(fatal);
    assertFatal(fatal);
  }

  @Test(timeout = 2_000)
  public void suppressedFailureCounterRegistrationFatalStopsSweep() {
    failFirstDelete(new IllegalStateException("store unavailable"));
    registrationFailure = suppress(fatal);
    assertFatal(fatal);
  }

  @Test(timeout = 2_000)
  public void directFailureCounterIncrementFatalStopsSweep() {
    failFirstDelete(new IllegalStateException("store unavailable"));
    incrementFailure = fatal;
    assertFatal(fatal);
  }

  private void assertContinues(Fault fault, boolean storeFails) {
    if (storeFails) {
      failFirstDelete(new AssertionError("store outcome unknown"));
    }
    if (fault == Fault.REGISTRATION || fault == Fault.COUNTER_AND_LOGGING) {
      registrationFailure = new IllegalStateException("registration unavailable");
    }
    if (fault == Fault.INCREMENT) {
      incrementFailure = new AssertionError("increment unavailable");
    }
    if (fault == Fault.LOGGING || fault == Fault.COUNTER_AND_LOGGING) {
      loggingFailure = new AssertionError("logging unavailable");
    }
    cleaner.cleanupTerminalJobs();
    verifyAllDeletes();
    for (String queue : List.of("first", "second")) {
      for (AsyncJobStatus status : List.of(AsyncJobStatus.DONE, AsyncJobStatus.FAILED)) {
        String result =
            storeFails && queue.equals("first") && status == AsyncJobStatus.DONE
                ? "failed"
                : "deleted";
        verify(registry)
            .counter(
                "asyncJobQueue.retention." + result,
                "queueName",
                queue,
                "status",
                status.getDatabaseValue());
      }
    }
    verifyNoMoreInteractions(registry);
    assertThat(mockingDetails(AsyncJobQueueRetentionCleaner.logger).getInvocations())
        .hasSizeLessThanOrEqualTo(12);
  }

  private Counter counter() {
    return mock(
        Counter.class,
        invocation -> {
          if (incrementFailure != null && invocation.getMethod().getName().equals("increment")) {
            throw incrementFailure;
          }
          return RETURNS_DEFAULTS.answer(invocation);
        });
  }

  private void verifyAllDeletes() {
    for (String queue : List.of("first", "second")) {
      verify(store).deleteTerminalJobsOlderThan(queue, AsyncJobStatus.DONE, Duration.ofDays(7), 2);
      verify(store)
          .deleteTerminalJobsOlderThan(queue, AsyncJobStatus.FAILED, Duration.ofDays(30), 2);
    }
    verifyNoMoreInteractions(store);
  }

  private void failFirstDelete(Throwable failure) {
    doThrow(failure)
        .when(store)
        .deleteTerminalJobsOlderThan("first", AsyncJobStatus.DONE, Duration.ofDays(7), 2);
  }

  private void assertFatal(Error fatal) {
    assertThatThrownBy(cleaner::cleanupTerminalJobs).isSameAs(fatal);
    verify(store).deleteTerminalJobsOlderThan("first", AsyncJobStatus.DONE, Duration.ofDays(7), 2);
    verifyNoMoreInteractions(store);
  }

  private RuntimeException suppress(Error fatal) {
    RuntimeException failure = new IllegalStateException("ordinary failure");
    failure.addSuppressed(fatal);
    return failure;
  }
}
