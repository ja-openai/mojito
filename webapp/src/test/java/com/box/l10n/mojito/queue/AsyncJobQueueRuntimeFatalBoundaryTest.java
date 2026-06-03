package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@RunWith(Parameterized.class)
public class AsyncJobQueueRuntimeFatalBoundaryTest {

  @Parameterized.Parameters(name = "{0}")
  public static Object[] boundaries() {
    return Boundary.values();
  }

  private final Boundary boundary;
  private final InMemoryAsyncJobStore store = spy(new InMemoryAsyncJobStore());
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AtomicInteger processed = new AtomicInteger();
  private final AtomicInteger callbacks = new AtomicInteger();
  private final AtomicReference<AsyncJobRecord> claimed = new AtomicReference<>();

  public AsyncJobQueueRuntimeFatalBoundaryTest(Boundary boundary) {
    this.boundary = boundary;
  }

  @After
  public void tearDown() {
    registry.close();
  }

  @Test(timeout = 5000)
  public void directFatalEscapesByIdentity() {
    FatalTestError fatal = new FatalTestError();
    assertFatal(fatal, fatal);
  }

  @Test(timeout = 5000)
  public void nestedFutureFailureEscapesAsOriginalFatal() {
    FatalTestError fatal = new FatalTestError();
    assertFatal(new ExecutionException(new CompletionException(fatal)), fatal);
  }

  @Test(timeout = 5000)
  public void resourceCleanupFatalEscapesInsteadOfRetryingPrimaryFailure() {
    FatalTestError fatal = new FatalTestError();
    Throwable compound =
        catchThrowable(
            () -> {
              try (AutoCloseable resource =
                  () -> {
                    throw fatal;
                  }) {
                throw new IllegalStateException("ordinary handler failure");
              }
            });

    assertThat(compound.getSuppressed()).containsExactly(fatal);
    assertFatal(compound, fatal);
    assertThat(compound.getSuppressed()).containsExactly(fatal);
  }

  @Test(timeout = 5000)
  public void fatalTakesPrecedenceOverPermanentFailureMarker() {
    FatalTestError fatal = new FatalTestError();
    AsyncJobPermanentFailureException failure =
        new AsyncJobPermanentFailureException("known rejection");
    failure.addSuppressed(fatal);

    assertFatal(failure, fatal);
  }

  @Test(timeout = 5000)
  public void fatalEscapesThroughCyclicFailureGraph() {
    FatalTestError fatal = new FatalTestError();
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);
    first.addSuppressed(second);
    second.addSuppressed(fatal);

    assertFatal(first, fatal);
    assertThat(first.getCause()).isSameAs(second);
    assertThat(second.getCause()).isSameAs(first);
    assertThat(second.getSuppressed()).containsExactly(fatal);
  }

  @Test(timeout = 5000)
  public void wrappedThreadDeathEscapesByIdentity() throws Exception {
    Error fatal =
        (Error) Class.forName("java.lang.ThreadDeath").getDeclaredConstructor().newInstance();

    assertFatal(new CompletionException(fatal), fatal);
  }

  @Test(timeout = 5000)
  public void nonFatalAssertionRetainsOrdinaryFailurePolicy() {
    assertOrdinary(new AssertionError("ordinary failure"));
  }

  @Test(timeout = 5000)
  public void directThreadDeathSubclassEscapesByIdentity() {
    Error fatal = new ThreadDeathSubclass();

    assertFatal(fatal, fatal);
  }

  @Test(timeout = 5000)
  public void wrappedThreadDeathSubclassEscapesByIdentity() {
    Error fatal = new ThreadDeathSubclass();

    assertFatal(new CompletionException(fatal), fatal);
  }

  @Test(timeout = 5000)
  public void suppressedThreadDeathSubclassEscapesByIdentity() {
    Error fatal = new ThreadDeathSubclass();
    IllegalStateException failure = new IllegalStateException("ordinary failure");
    failure.addSuppressed(fatal);

    assertFatal(failure, fatal);
    assertThat(failure.getSuppressed()).containsExactly(fatal);
  }

  @Test(timeout = 5000)
  public void nonFatalCycleRetainsOrdinaryFailurePolicy() {
    RuntimeException first = new RuntimeException("first");
    RuntimeException second = new RuntimeException("second", first);
    first.initCause(second);
    second.addSuppressed(first);

    assertOrdinary(first);
  }

  private void assertFatal(Throwable failure, Error expected) {
    RunResult result = run(failure);

    assertThat(result.escaped()).isSameAs(expected);
    assertThat(result.record().status()).isEqualTo(boundary.persistedStatus);
    verify(store, never())
        .requeueAfter(
            anyString(), any(), anyString(), anyString(), any(Duration.class), any(), any());
    verify(store, times(boundary == Boundary.DONE ? 1 : 0))
        .markDone(anyString(), any(), anyString(), anyString(), any());
    verify(store, times(boundary.persistedStatus == AsyncJobStatus.FAILED ? 1 : 0))
        .markFailed(anyString(), any(), anyString(), anyString(), any(), anyString());
    assertThat(registry.find("asyncJobQueue.retried").counter()).isNull();
    assertThat(registry.find("asyncJobQueue.handler.completion.failed").counter()).isNull();
    if (boundary.isProcess()) {
      assertThat(result.record()).isEqualTo(claimed.get());
      assertThat(callbacks.get()).isZero();
      assertThat(registry.find("asyncJobQueue.failed").counter()).isNull();
    } else {
      assertThat(result.record().workerId()).isNull();
      assertThat(result.record().leaseToken()).isNull();
      assertThat(result.record().leaseUntil()).isNull();
      assertThat(
              registry
                  .get(
                      boundary == Boundary.DONE
                          ? "asyncJobQueue.completed"
                          : "asyncJobQueue.failed")
                  .counter()
                  .count())
          .isEqualTo(1);
    }
  }

  private void assertOrdinary(Throwable failure) {
    RunResult result = run(failure);

    assertThat(result.escaped()).isNull();
    if (boundary.isProcess()) {
      assertThat(result.record().status())
          .isEqualTo(boundary == Boundary.PROCESS ? AsyncJobStatus.QUEUED : AsyncJobStatus.FAILED);
      assertThat(result.record().lastError()).contains(failure.toString());
      if (boundary == Boundary.PROCESS) {
        assertThat(registry.get("asyncJobQueue.retried").counter().count()).isEqualTo(1);
      } else {
        assertThat(registry.get("asyncJobQueue.failed").counter().count()).isEqualTo(1);
        assertThat(callbacks.get()).isEqualTo(1);
      }
    } else {
      assertThat(result.record().status()).isEqualTo(boundary.persistedStatus);
      assertThat(registry.get("asyncJobQueue.handler.completion.failed").counter().count())
          .isEqualTo(1);
      assertThat(registry.find("asyncJobQueue.retried").counter()).isNull();
    }
  }

  private RunResult run(Throwable failure) {
    TaskScheduler scheduler = mock(TaskScheduler.class);
    ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(invocation -> heartbeat);
    ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    AtomicReference<Runnable> submitted = new AtomicReference<>();
    doAnswer(
            invocation -> {
              submitted.set(invocation.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setMaxAttempts(boundary == Boundary.PROCESS || boundary == Boundary.DONE ? 5 : 1);
    AsyncJobId id = store.enqueue("example", "opaque-input", Instant.now().minusSeconds(1));
    if (boundary == Boundary.BEFORE_HANDLER) {
      AsyncJobRecord earlier =
          store.claimNextJobs("example", 1, "earlier-worker", Duration.ofSeconds(30)).getFirst();
      assertThat(
              store.requeue(
                  "example",
                  id,
                  earlier.workerId(),
                  earlier.leaseToken(),
                  Instant.now().minusSeconds(1),
                  null,
                  null))
          .isTrue();
    }
    AsyncJobQueueRuntime runtime =
        new AsyncJobQueueRuntime(
            "example",
            store,
            settings,
            new AsyncJobHandler() {
              @Override
              public String queueName() {
                return "example";
              }

              @Override
              public AsyncJobHandlerResult process(AsyncJobRecord record) throws Exception {
                processed.incrementAndGet();
                claimed.set(record);
                if (boundary.isProcess()) {
                  throwFailure(failure);
                }
                if (boundary == Boundary.FAILED) {
                  throw new IllegalStateException("exhausted handler failure");
                }
                return AsyncJobHandlerResult.done();
              }

              @Override
              public void onJobDone(AsyncJobRecord record, AsyncJobHandlerResult result)
                  throws Exception {
                callback(record, Boundary.DONE);
              }

              @Override
              public void onJobFailedPermanently(
                  AsyncJobRecord record, Throwable cause, String lastError) throws Exception {
                callback(record, Boundary.FAILED);
              }

              private void callback(AsyncJobRecord record, Boundary expected) throws Exception {
                callbacks.incrementAndGet();
                assertThat(record.status()).isEqualTo(expected.persistedStatus);
                assertThat(store.getByIds(List.of(id)).getFirst().status())
                    .isEqualTo(expected.persistedStatus);
                if (!boundary.isProcess()) {
                  throwFailure(failure);
                }
              }
            },
            scheduler,
            executor,
            registry,
            "worker");
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);
    assertThat(runtime.inFlightCount()).isEqualTo(1);
    assertThat(submitted.get()).isNotNull();
    // Run the accepted worker separately: a caller-runs executor would conflate submission errors.
    Throwable escaped = catchThrowable(submitted.get()::run);

    assertThat(runtime.inFlightCount()).isZero();
    if (boundary == Boundary.BEFORE_HANDLER) {
      verifyNoInteractions(heartbeat);
      assertThat(processed.get()).isZero();
    } else {
      verify(heartbeat).cancel(false);
      assertThat(processed.get()).isEqualTo(1);
    }
    if (!boundary.isProcess()) {
      assertThat(callbacks.get()).isEqualTo(1);
    }
    AsyncJobRecord record = store.getByIds(List.of(id)).getFirst();
    assertThat(record.attemptCount()).isEqualTo(boundary == Boundary.BEFORE_HANDLER ? 2 : 1);
    assertThat(record.jobData()).isEqualTo("opaque-input");
    return new RunResult(escaped, record);
  }

  private static void throwFailure(Throwable failure) throws Exception {
    if (failure instanceof Error error) {
      throw error;
    }
    throw (Exception) failure;
  }

  private enum Boundary {
    PROCESS(AsyncJobStatus.RUNNING),
    PROCESS_AT_LIMIT(AsyncJobStatus.RUNNING),
    DONE(AsyncJobStatus.DONE),
    FAILED(AsyncJobStatus.FAILED),
    BEFORE_HANDLER(AsyncJobStatus.FAILED);

    private final AsyncJobStatus persistedStatus;

    Boundary(AsyncJobStatus persistedStatus) {
      this.persistedStatus = persistedStatus;
    }

    boolean isProcess() {
      return this == PROCESS || this == PROCESS_AT_LIMIT;
    }
  }

  private record RunResult(Throwable escaped, AsyncJobRecord record) {}

  private static class ThreadDeathSubclass extends ThreadDeath {}

  private static class FatalTestError extends VirtualMachineError {}
}
