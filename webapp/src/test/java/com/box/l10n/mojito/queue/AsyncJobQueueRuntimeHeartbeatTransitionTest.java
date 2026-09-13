package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.stubbing.Answer;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@RunWith(Parameterized.class)
public class AsyncJobQueueRuntimeHeartbeatTransitionTest {

  private static final String QUEUE = "heartbeat-transition";

  enum Transition {
    DONE,
    REQUEUE_DELAYED,
    REQUEUE_ABSOLUTE,
    FAILURE_RETRY,
    FAILURE_TERMINAL,
    REQUEUE_EXHAUSTED
  }

  @Parameterized.Parameters(name = "{0}")
  public static List<Transition> transitions() {
    return Arrays.asList(Transition.values());
  }

  @Parameterized.Parameter public Transition transition;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final InMemoryAsyncJobStore store = spy(new InMemoryAsyncJobStore());
  private final AsyncJobHandler handler = mock(AsyncJobHandler.class);
  private final TaskScheduler scheduler = mock(TaskScheduler.class);
  private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
  private final ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
  private final AtomicReference<Runnable> heartbeat = new AtomicReference<>();
  private final AtomicReference<Runnable> worker = new AtomicReference<>();
  private AsyncJobQueueRuntime runtime;
  private AsyncJobId id;

  @Before
  public void setUp() throws Exception {
    var settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setPollIntervalMs(100);
    settings.setMaxPollIntervalMs(1_000);
    settings.setClaimBatchSize(1);
    settings.setMaxConcurrency(1);
    settings.setLeaseDurationMs(60_000);
    settings.setHeartbeatIntervalMs(100);
    settings.setMaxAttempts(
        transition == Transition.FAILURE_TERMINAL || transition == Transition.REQUEUE_EXHAUSTED
            ? 1
            : 2);
    when(handler.process(any())).thenAnswer(invocation -> handlerResult());
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(
            invocation -> {
              heartbeat.set(invocation.getArgument(0));
              return heartbeatFuture;
            });
    doAnswer(
            invocation -> {
              worker.set(invocation.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
    runtime =
        new AsyncJobQueueRuntime(
            QUEUE, store, settings, handler, scheduler, executor, registry, "worker");
    id = store.enqueueNow(QUEUE, "original");
    runtime.pollOnce();
    assertThat(worker.get()).isNotNull();
  }

  @After
  public void tearDown() {
    registry.close();
  }

  @Test
  public void rejectionAfterCommitBeforeReturnIsNotLeaseLoss() throws Exception {
    interceptTransition(
        invocation -> {
          Object result = invocation.callRealMethod();
          heartbeat.get().run();
          heartbeat.get().run();
          return result;
        });
    worker.get().run();
    assertFinished();
    assertThat(heartbeatFailures()).isZero();
    verifyHeartbeats(1);
  }

  @Test
  public void lateScheduledInvocationAfterFinishDoesNotAccessStoreEvenIfCancelFails()
      throws Exception {
    when(heartbeatFuture.cancel(false)).thenThrow(new IllegalStateException("cancel failed"));
    worker.get().run();
    assertFinished();
    heartbeat.get().run();
    heartbeat.get().run();
    verifyHeartbeats(0);
    assertThat(heartbeatFailures()).isZero();
    assertThat(registry.get("asyncJobQueue.heartbeat.cancel.failed").counter().count())
        .isEqualTo(1);
  }

  @Test
  public void pendingTransitionDoesNotBlockRenewals() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    interceptTransition(
        invocation -> {
          entered.countDown();
          if (!commit.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test did not release transition");
          }
          return invocation.callRealMethod();
        });
    var threads = Executors.newFixedThreadPool(2);
    try {
      var processing = threads.submit(worker.get());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      threads.submit(() -> heartbeat.get().run()).get(2, TimeUnit.SECONDS);
      assertThat(store.getByIds(List.of(id)).getFirst().status()).isEqualTo(AsyncJobStatus.RUNNING);
      verifyHeartbeats(1);
      assertThat(heartbeatFailures()).isZero();
      commit.countDown();
      processing.get(5, TimeUnit.SECONDS);
      assertFinished();
    } finally {
      commit.countDown();
      threads.shutdownNow();
      assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  public void rejectedTransitionRetainsDeferredLeaseLossSignal() throws Exception {
    doReturn(false).when(store).heartbeat(anyString(), any(), anyString(), anyString(), any());
    interceptTransition(
        invocation -> {
          heartbeat.get().run();
          heartbeat.get().run();
          return false;
        });
    worker.get().run();
    assertThat(store.getByIds(List.of(id)).getFirst().status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(heartbeatFailures()).isEqualTo(1);
    assertThat(registry.get("asyncJobQueue.transition.failed").counter().count()).isEqualTo(1);
    verifyHeartbeats(1);
    verifyCallbacks(false);
  }

  @Test
  public void uncertainTransitionRetainsDeferredLeaseLossSignal() throws Exception {
    interceptTransition(
        invocation -> {
          invocation.callRealMethod();
          heartbeat.get().run();
          throw new IllegalStateException("commit response lost");
        });
    worker.get().run();
    assertThat(store.getByIds(List.of(id)).getFirst().status()).isEqualTo(expectedStatus());
    assertThat(heartbeatFailures()).isEqualTo(1);
    assertThat(registry.get("asyncJobQueue.transition.failed").counter().count()).isEqualTo(1);
    verifyCallbacks(false);
  }

  @Test
  public void heartbeatExceptionsRemainVisibleEvenWhenTransitionSucceeds() throws Exception {
    doThrow(new IllegalStateException("heartbeat connection unavailable"))
        .when(store)
        .heartbeat(anyString(), any(), anyString(), anyString(), any());
    interceptTransition(
        invocation -> {
          heartbeat.get().run();
          return invocation.callRealMethod();
        });
    worker.get().run();
    assertFinished();
    assertThat(heartbeatFailures()).isEqualTo(1);
  }

  @Test
  public void callbacksDoNotRenewReleasedLeases() throws Exception {
    var callbacks = Executors.newSingleThreadExecutor();
    try {
      Answer<Object> tick =
          invocation -> {
            callbacks.submit(() -> heartbeat.get().run()).get(2, TimeUnit.SECONDS);
            return null;
          };
      doAnswer(tick).when(handler).onJobDone(any(), any());
      doAnswer(tick).when(handler).onJobFailedPermanently(any(), any(), any());
      worker.get().run();
      assertFinished();
      verifyHeartbeats(0);
      assertThat(heartbeatFailures()).isZero();
      assertThat(registry.find("asyncJobQueue.handler.completion.failed").counter()).isNull();
    } finally {
      callbacks.shutdownNow();
      assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  public void alreadyReportedLeaseLossIsNotErasedByLaterMockedSuccess() throws Exception {
    doReturn(false).when(store).heartbeat(anyString(), any(), anyString(), anyString(), any());
    AtomicReference<Double> beforeTransition = new AtomicReference<>();
    doAnswer(
            invocation -> {
              heartbeat.get().run();
              beforeTransition.set(heartbeatFailures());
              return handlerResult();
            })
        .when(handler)
        .process(any());
    worker.get().run();
    assertFinished();
    assertThat(beforeTransition.get()).isEqualTo(1);
    assertThat(heartbeatFailures()).isEqualTo(1);
    verifyHeartbeats(1);
  }

  @Test
  public void inFlightRejectionAfterSuccessfulCleanupIsNotLeaseLoss() throws Exception {
    assertLateHeartbeat("success", false);
  }

  @Test
  public void inFlightRejectionAfterRejectedCleanupIsLeaseLoss() throws Exception {
    assertLateHeartbeat("rejected", false);
  }

  @Test
  public void inFlightRejectionAfterUncertainCleanupIsLeaseLoss() throws Exception {
    assertLateHeartbeat("uncertain", false);
  }

  @Test
  public void inFlightExceptionAfterSuccessfulCleanupIsStillVisible() throws Exception {
    assertLateHeartbeat("success", true);
  }

  @Test
  public void fatalDeferredTelemetryFailureStillCancelsHeartbeatAndReleasesCapacity()
      throws Exception {
    InternalError fatal = new InternalError("fatal telemetry failure");
    registry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("asyncJobQueue.heartbeat.failed")) {
                throw fatal;
              }
            });
    doReturn(false).when(store).heartbeat(anyString(), any(), anyString(), anyString(), any());
    interceptTransition(
        invocation -> {
          heartbeat.get().run();
          return false;
        });
    assertThat(assertThrows(InternalError.class, () -> worker.get().run())).isSameAs(fatal);
    assertThat(runtime.inFlightCount()).isZero();
    verify(heartbeatFuture).cancel(false);
    heartbeat.get().run();
    verifyHeartbeats(1);
    verifyCallbacks(false);
  }

  private void assertLateHeartbeat(String outcome, boolean heartbeatThrows) throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch respond = new CountDownLatch(1);
    var threads = Executors.newSingleThreadExecutor();
    AtomicReference<Future<?>> renewal = new AtomicReference<>();
    try {
      doAnswer(
              invocation -> {
                entered.countDown();
                if (!respond.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test did not release heartbeat");
                }
                if (heartbeatThrows) {
                  throw new IllegalStateException("heartbeat response unavailable");
                }
                return false;
              })
          .when(store)
          .heartbeat(anyString(), any(), anyString(), anyString(), any());
      doAnswer(
              invocation -> {
                renewal.set(threads.submit(() -> heartbeat.get().run()));
                if (!entered.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("heartbeat did not start");
                }
                return handlerResult();
              })
          .when(handler)
          .process(any());
      if (!outcome.equals("success")) {
        interceptTransition(
            invocation -> {
              if (outcome.equals("rejected")) {
                return false;
              }
              invocation.callRealMethod();
              throw new IllegalStateException("commit response lost");
            });
      }
      worker.get().run();
      assertThat(runtime.inFlightCount()).isZero();
      verify(heartbeatFuture).cancel(false);
      assertThat(heartbeatFailures()).isZero();
      assertThat(renewal.get()).isNotNull();
      respond.countDown();
      renewal.get().get(5, TimeUnit.SECONDS);
      assertThat(heartbeatFailures())
          .isEqualTo(heartbeatThrows || !outcome.equals("success") ? 1 : 0);
      verifyCallbacks(outcome.equals("success"));
      heartbeat.get().run();
      verifyHeartbeats(1);
      assertThat(store.getByIds(List.of(id)).getFirst().status())
          .isEqualTo(outcome.equals("rejected") ? AsyncJobStatus.RUNNING : expectedStatus());
    } finally {
      respond.countDown();
      threads.shutdownNow();
      assertThat(threads.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private AsyncJobHandlerResult handlerResult() {
    return switch (transition) {
      case DONE -> AsyncJobHandlerResult.done();
      case REQUEUE_DELAYED, REQUEUE_EXHAUSTED -> AsyncJobHandlerResult.requeue(null);
      case REQUEUE_ABSOLUTE -> AsyncJobHandlerResult.requeue(Instant.now().plusSeconds(60));
      case FAILURE_RETRY, FAILURE_TERMINAL -> throw new IllegalStateException("handler failed");
    };
  }

  private AsyncJobStatus expectedStatus() {
    return switch (transition) {
      case DONE -> AsyncJobStatus.DONE;
      case FAILURE_TERMINAL, REQUEUE_EXHAUSTED -> AsyncJobStatus.FAILED;
      default -> AsyncJobStatus.QUEUED;
    };
  }

  private void interceptTransition(Answer<?> answer) {
    switch (transition) {
      case DONE ->
          doAnswer(answer)
              .when(store)
              .markDone(anyString(), any(), anyString(), anyString(), any());
      case REQUEUE_DELAYED, FAILURE_RETRY ->
          doAnswer(answer)
              .when(store)
              .requeueAfter(anyString(), any(), anyString(), anyString(), any(), any(), any());
      case REQUEUE_ABSOLUTE ->
          doAnswer(answer)
              .when(store)
              .requeue(anyString(), any(), anyString(), anyString(), any(), any(), any());
      case FAILURE_TERMINAL, REQUEUE_EXHAUSTED ->
          doAnswer(answer)
              .when(store)
              .markFailed(anyString(), any(), anyString(), anyString(), any(), any());
    }
  }

  private void assertFinished() throws Exception {
    var record = store.getByIds(List.of(id)).getFirst();
    assertThat(record.status()).isEqualTo(expectedStatus());
    assertThat(record.attemptCount()).isEqualTo(1);
    assertThat(record.leaseToken()).isNull();
    assertThat(runtime.inFlightCount()).isZero();
    assertThat(registry.find("asyncJobQueue.transition.failed").counter()).isNull();
    verify(heartbeatFuture).cancel(false);
    verifyCallbacks(true);
  }

  private void verifyCallbacks(boolean success) throws Exception {
    verify(handler, times(success && expectedStatus() == AsyncJobStatus.DONE ? 1 : 0))
        .onJobDone(any(), any());
    verify(handler, times(success && expectedStatus() == AsyncJobStatus.FAILED ? 1 : 0))
        .onJobFailedPermanently(any(), any(), any());
  }

  private double heartbeatFailures() {
    var counter = registry.find("asyncJobQueue.heartbeat.failed").counter();
    return counter == null ? 0 : counter.count();
  }

  private void verifyHeartbeats(int count) {
    verify(store, times(count))
        .heartbeat(anyString(), any(), anyString(), anyString(), any(Duration.class));
  }
}
