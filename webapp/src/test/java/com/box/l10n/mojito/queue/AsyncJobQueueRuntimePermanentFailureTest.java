package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class AsyncJobQueueRuntimePermanentFailureTest {

  private final InMemoryAsyncJobStore store = spy(new InMemoryAsyncJobStore());
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
  private final AtomicInteger processed = new AtomicInteger();
  private final AtomicInteger callbacks = new AtomicInteger();
  private final AtomicReference<AsyncJobRecord> claimed = new AtomicReference<>();
  private final AtomicReference<AsyncJobRecord> callbackRecord = new AtomicReference<>();
  private final AtomicReference<AsyncJobRecord> persistedAtCallback = new AtomicReference<>();
  private final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

  @After
  public void tearDown() {
    executor.shutdown();
    registry.close();
  }

  @Test
  public void directPermanentFailureFailsFirstAttemptBeforeCallback() throws Exception {
    AsyncJobPermanentFailureException failure = permanentFailure();
    AsyncJobRecord failed = run(failure, null);

    assertFailed(failed);
    assertThat(callbacks.get()).isEqualTo(1);
    assertThat(callbackFailure.get()).isSameAs(failure);
    assertThat(callbackRecord.get().status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(callbackRecord.get().attemptCount()).isEqualTo(1);
    assertThat(callbackRecord.get().jobData()).isEqualTo("opaque-input");
    assertThat(callbackRecord.get().lastError()).isEqualTo(failed.lastError());
    assertThat(callbackRecord.get().workerId()).isNull();
    assertThat(callbackRecord.get().leaseToken()).isNull();
    assertThat(callbackRecord.get().leaseUntil()).isNull();
    assertThat(persistedAtCallback.get()).isEqualTo(failed);
    verifyFailedFence(failed);
    assertThat(registry.get("asyncJobQueue.failed").counter().count()).isEqualTo(1);
    assertNoRetry();
  }

  @Test
  public void callbackFailureCannotRetryATerminalRow() throws Exception {
    AsyncJobRecord failed = run(permanentFailure(), permanentFailure());

    assertFailed(failed);
    assertThat(callbacks.get()).isEqualTo(1);
    assertThat(
            registry
                .get("asyncJobQueue.handler.completion.failed")
                .tag("callback", "failed")
                .counter()
                .count())
        .isEqualTo(1);
    assertNoRetry();
  }

  @Test
  public void rejectedFailureTransitionDoesNotInvokeCallbackOrRequeue() throws Exception {
    doAnswer(invocation -> false)
        .when(store)
        .markFailed(anyString(), any(), anyString(), anyString(), any(), anyString());

    assertUnacknowledgedFailure(AsyncJobStatus.RUNNING);
  }

  @Test
  public void failedTransitionBeforeCommitDoesNotInvokeCallbackOrRequeue() throws Exception {
    failTransition(false);

    assertUnacknowledgedFailure(AsyncJobStatus.RUNNING);
  }

  @Test
  public void lostFailedCommitAcknowledgementDoesNotInvokeCallbackOrRequeue() throws Exception {
    failTransition(true);

    assertUnacknowledgedFailure(AsyncJobStatus.FAILED);
  }

  @Test
  public void wrappedPermanentFailureUsesOrdinaryRetryPolicy() throws Exception {
    assertRetry(run(new IllegalStateException("wrapped", permanentFailure()), null));
  }

  @Test
  public void suppressedPermanentFailureUsesOrdinaryRetryPolicy() throws Exception {
    Exception failure = new SQLException("transient database failure", "08006");
    failure.addSuppressed(permanentFailure());

    assertRetry(run(failure, null));
  }

  @Test
  public void ordinaryFailureUsesOrdinaryRetryPolicy() throws Exception {
    assertRetry(run(new IllegalStateException("ordinary failure"), null));
  }

  @Test
  public void permanentMarkerFromStoreIsNotAHandlerDecision() throws Exception {
    // Answer can throw a checked exception even though the store API does not declare one.
    doAnswer(
            invocation -> {
              throw permanentFailure();
            })
        .when(store)
        .markDone(anyString(), any(), anyString(), anyString(), any());

    AsyncJobRecord record = run(null, null);
    assertThat(record.status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(record.attemptCount()).isEqualTo(1);
    assertThat(callbacks.get()).isZero();
    verify(store, never())
        .markFailed(anyString(), any(), anyString(), anyString(), any(), anyString());
    assertThat(
            registry
                .get("asyncJobQueue.transition.failed")
                .tag("transition", "done")
                .counter()
                .count())
        .isEqualTo(1);
    assertNoRetry();
  }

  private void failTransition(boolean commitFirst) {
    doAnswer(
            invocation -> {
              if (commitFirst) {
                assertThat(invocation.callRealMethod()).isEqualTo(true);
              }
              throw new IllegalStateException(
                  "commit outcome unknown", new SQLException("lost", "08006"));
            })
        .when(store)
        .markFailed(anyString(), any(), anyString(), anyString(), any(), anyString());
  }

  private void assertUnacknowledgedFailure(AsyncJobStatus status) throws Exception {
    AsyncJobRecord record = run(permanentFailure(), null);
    assertThat(record.status()).isEqualTo(status);
    assertThat(record.attemptCount()).isEqualTo(1);
    assertThat(callbacks.get()).isZero();
    verifyFailedFence(record);
    assertThat(
            registry
                .get("asyncJobQueue.transition.failed")
                .tag("transition", "failed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.find("asyncJobQueue.failed").counter()).isNull();
    assertNoRetry();
  }

  private void verifyFailedFence(AsyncJobRecord record) {
    verify(store)
        .markFailed(
            "example",
            record.id(),
            claimed.get().workerId(),
            claimed.get().leaseToken(),
            null,
            permanentFailure().toString());
  }

  private void assertFailed(AsyncJobRecord record) {
    assertThat(record.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(record.attemptCount()).isEqualTo(1);
    assertThat(record.lastError()).isEqualTo(permanentFailure().toString());
    assertThat(record.jobData()).isEqualTo("opaque-input");
    assertThat(record.workerId()).isNull();
    assertThat(record.leaseToken()).isNull();
    assertThat(record.leaseUntil()).isNull();
  }

  private void assertRetry(AsyncJobRecord record) {
    assertThat(record.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(record.attemptCount()).isEqualTo(1);
    assertThat(callbacks.get()).isZero();
    verify(store, never())
        .markFailed(anyString(), any(), anyString(), anyString(), any(), anyString());
    assertThat(registry.get("asyncJobQueue.retried").counter().count()).isEqualTo(1);
  }

  private void assertNoRetry() {
    verify(store, never())
        .requeueAfter(
            anyString(), any(), anyString(), anyString(), any(Duration.class), any(), any());
    assertThat(registry.find("asyncJobQueue.retried").counter()).isNull();
  }

  private AsyncJobRecord run(Exception failure, Exception callbackException) throws Exception {
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    executor.initialize();
    AsyncJobQueueProperties.QueueSettings settings = new AsyncJobQueueProperties.QueueSettings();
    settings.setMaxAttempts(5);
    settings.setMaxConcurrency(1);
    settings.setClaimBatchSize(1);
    settings.setHeartbeatIntervalMs(1_000);
    TaskScheduler scheduler = mock(TaskScheduler.class);
    ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    when(scheduler.scheduleAtFixedRate(any(Runnable.class), any(Date.class), anyLong()))
        .thenAnswer(invocation -> heartbeat);
    AsyncJobId id = store.enqueue("example", "opaque-input", Instant.now().minusSeconds(1));
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
                if (failure != null) {
                  throw failure;
                }
                return AsyncJobHandlerResult.done();
              }

              @Override
              public void onJobFailedPermanently(
                  AsyncJobRecord record, Throwable cause, String lastError) throws Exception {
                callbacks.incrementAndGet();
                callbackRecord.set(record);
                callbackFailure.set(cause);
                persistedAtCallback.set(store.getByIds(List.of(id)).getFirst());
                if (callbackException != null) {
                  throw callbackException;
                }
              }
            },
            scheduler,
            executor,
            registry,
            "worker");
    assertThat(runtime.pollOnce().claimedCount()).isEqualTo(1);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (runtime.inFlightCount() != 0 && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(runtime.inFlightCount()).isZero();
    verify(heartbeat).cancel(false);
    assertThat(processed.get()).isEqualTo(1);
    return store.getByIds(List.of(id)).getFirst();
  }

  private static AsyncJobPermanentFailureException permanentFailure() {
    return new AsyncJobPermanentFailureException("business task already finished");
  }
}
