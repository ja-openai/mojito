package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class AsyncJobQueueSubmissionServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-23T05:50:00Z");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void enqueueNowStoresJobAndTriggersImmediateWakeup() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);
    Instant beforeEnqueue = Instant.now().minusMillis(1);

    AsyncJobId asyncJobId = service.enqueueNow("assetlocalize", "{\"id\":1}");

    verify(coordinator).triggerPollNow("assetlocalize");
    AsyncJobRecord job = store.getByIds(List.of(asyncJobId)).get(0);
    assertThat(job.queueName()).isEqualTo("assetlocalize");
    assertThat(job.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(job.availableAt()).isBetween(beforeEnqueue, Instant.now().plusSeconds(1));
    assertEnqueueCounter("succeeded", 1);
    assertNoEnqueueWakeupFailureCounter();
  }

  @Test
  public void enqueueNowUsesStoreImmediateEnqueue() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    AsyncJobId asyncJobId = new AsyncJobId("42");
    when(store.enqueueNow("assetlocalize", "{\"id\":1}")).thenReturn(asyncJobId);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);

    assertThat(service.enqueueNow("assetlocalize", "{\"id\":1}")).isSameAs(asyncJobId);

    verify(store).enqueueNow("assetlocalize", "{\"id\":1}");
    verify(store, never()).enqueue(anyString(), anyString(), any(Instant.class));
    verify(coordinator).triggerPollNow("assetlocalize");
    assertEnqueueCounter("succeeded", 1);
  }

  @Test
  public void enqueueFutureJobDoesNotTriggerImmediateWakeup() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);

    service.enqueue("assetlocalize", "{\"id\":1}", NOW.plus(Duration.ofMinutes(5)));

    verify(coordinator, never()).triggerPollNow("assetlocalize");
    assertEnqueueCounter("succeeded", 1);
  }

  @Test
  public void enqueueRecordsWakeupFailureWithoutFailingSubmission() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    doThrow(new IllegalStateException("scheduler unavailable"))
        .when(coordinator)
        .triggerPollNow("assetlocalize");
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);

    AsyncJobId asyncJobId = service.enqueueNow("assetlocalize", "{\"id\":1}");

    assertThat(store.getByIds(List.of(asyncJobId))).hasSize(1);
    assertEnqueueCounter("succeeded", 1);
    assertEnqueueWakeupFailureCounter(1);
  }

  @Test
  public void enqueueRecordsStoreFailures() {
    AsyncJobQueueSubmissionService service =
        submissionService(
            new InMemoryAsyncJobStore() {
              @Override
              public AsyncJobId enqueueNow(String queueName, String jobData) {
                throw new IllegalStateException("database unavailable");
              }
            },
            mock(AsyncJobQueueCoordinator.class));

    assertThatThrownBy(() -> service.enqueueNow("assetlocalize", "{\"id\":1}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertEnqueueCounter("failed", 1);
  }

  @Test
  public void enqueuePropagatesFatalStoreErrorsWithoutFailureCounter() {
    AsyncJobQueueSubmissionService service =
        submissionService(
            new InMemoryAsyncJobStore() {
              @Override
              public AsyncJobId enqueueNow(String queueName, String jobData) {
                throw new FatalTestError("fatal");
              }
            },
            mock(AsyncJobQueueCoordinator.class));

    assertThatThrownBy(() -> service.enqueueNow("assetlocalize", "{\"id\":1}"))
        .isInstanceOf(FatalTestError.class)
        .hasMessageContaining("fatal");
    assertNoEnqueueCounter("failed");
  }

  @Test
  public void enqueueValidatesInputsBeforeCallingStore() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);

    assertThatThrownBy(() -> service.enqueueNow(" ", "{\"id\":1}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queueName must not be blank");
    assertThatThrownBy(() -> service.enqueueNow("assetlocalize", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.enqueue("assetlocalize", "{\"id\":1}", null))
        .isInstanceOf(NullPointerException.class);
    verify(store, never()).enqueueNow(anyString(), anyString());
    verify(store, never()).enqueue(anyString(), anyString(), any(Instant.class));
    verify(coordinator, never()).triggerPollNow("assetlocalize");
  }

  private AsyncJobQueueSubmissionService submissionService(
      AsyncJobStore store, AsyncJobQueueCoordinator coordinator) {
    return new AsyncJobQueueSubmissionService(
        store, coordinator, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void assertEnqueueCounter(String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.enqueue")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertNoEnqueueCounter(String result) {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.enqueue")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter())
        .isNull();
  }

  private void assertEnqueueWakeupFailureCounter(double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.enqueueWakeup.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertNoEnqueueWakeupFailureCounter() {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.enqueueWakeup.failed")
                .tag("queueName", "assetlocalize")
                .counter())
        .isNull();
  }

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
