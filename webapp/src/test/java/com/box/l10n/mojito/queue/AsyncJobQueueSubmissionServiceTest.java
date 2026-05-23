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
  public void enqueueNowPublishesCrossProcessWakeupHintAfterStoreWrite() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    AsyncJobId asyncJobId = new AsyncJobId("42");
    when(store.enqueueNow("assetlocalize", "{\"id\":1}")).thenReturn(asyncJobId);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueWakeupNotifier wakeupNotifier = mock(AsyncJobQueueWakeupNotifier.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator, wakeupNotifier);

    assertThat(service.enqueueNow("assetlocalize", "{\"id\":1}")).isSameAs(asyncJobId);

    verify(coordinator).triggerPollNow("assetlocalize");
    verify(wakeupNotifier).notifyJobAvailable("assetlocalize", asyncJobId);
    assertEnqueueCounter("succeeded", 1);
  }

  @Test
  public void enqueueFutureJobDoesNotTriggerImmediateWakeup() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueWakeupNotifier wakeupNotifier = mock(AsyncJobQueueWakeupNotifier.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator, wakeupNotifier);

    service.enqueue("assetlocalize", "{\"id\":1}", NOW.plus(Duration.ofMinutes(5)));

    verify(coordinator, never()).triggerPollNow("assetlocalize");
    verify(wakeupNotifier, never()).notifyJobAvailable(anyString(), any(AsyncJobId.class));
    assertEnqueueCounter("succeeded", 1);
  }

  @Test
  public void enqueueDueJobTriggersImmediateWakeup() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    AsyncJobId asyncJobId = new AsyncJobId("42");
    when(store.enqueue("assetlocalize", "{\"id\":1}", NOW)).thenReturn(asyncJobId);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);

    assertThat(service.enqueue("assetlocalize", "{\"id\":1}", NOW)).isSameAs(asyncJobId);

    verify(store).enqueue("assetlocalize", "{\"id\":1}", NOW);
    verify(coordinator).triggerPollNow("assetlocalize");
    assertEnqueueCounter("succeeded", 1);
  }

  @Test
  public void enqueueRejectsOutOfRangeAvailableAtBeforeStoreOrWakeup() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);

    assertThatThrownBy(
            () ->
                service.enqueue(
                    "assetlocalize",
                    "{\"id\":1}",
                    AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX.plusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("availableAt must be between");

    verify(store, never()).enqueue(anyString(), anyString(), any(Instant.class));
    verify(coordinator, never()).triggerPollNow("assetlocalize");
    assertEnqueueCounter("invalidAvailableAt", 1);
    assertNoEnqueueCounter("failed");
  }

  @Test
  public void enqueueRecordsWakeupDecisionFailureWithoutFailingSubmission() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    AsyncJobId asyncJobId = new AsyncJobId("42");
    when(store.enqueue("assetlocalize", "{\"id\":1}", NOW)).thenReturn(asyncJobId);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service =
        new AsyncJobQueueSubmissionService(store, coordinator, meterRegistry, throwingClock());

    assertThat(service.enqueue("assetlocalize", "{\"id\":1}", NOW)).isSameAs(asyncJobId);

    verify(store).enqueue("assetlocalize", "{\"id\":1}", NOW);
    verify(coordinator).triggerPollNow("assetlocalize");
    assertEnqueueCounter("succeeded", 1);
    assertEnqueueWakeupDecisionFailureCounter(1);
    assertNoEnqueueCounter("failed");
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
  public void enqueueRecordsCrossProcessWakeupFailureWithoutFailingSubmission() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueWakeupNotifier wakeupNotifier = mock(AsyncJobQueueWakeupNotifier.class);
    doThrow(new IllegalStateException("notify unavailable"))
        .when(wakeupNotifier)
        .notifyJobAvailable(anyString(), any(AsyncJobId.class));
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator, wakeupNotifier);

    AsyncJobId asyncJobId = service.enqueueNow("assetlocalize", "{\"id\":1}");

    assertThat(store.getByIds(List.of(asyncJobId))).hasSize(1);
    verify(coordinator).triggerPollNow("assetlocalize");
    assertEnqueueCounter("succeeded", 1);
    assertEnqueueWakeupNotifyFailureCounter(1);
  }

  @Test
  public void enqueuePropagatesFatalCrossProcessWakeupErrorsWithoutNotifyFailureCounter() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueWakeupNotifier wakeupNotifier = mock(AsyncJobQueueWakeupNotifier.class);
    FatalTestError fatalTestError = new FatalTestError("fatal enqueue notify");
    doThrow(fatalTestError)
        .when(wakeupNotifier)
        .notifyJobAvailable(anyString(), any(AsyncJobId.class));
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator, wakeupNotifier);

    assertThatThrownBy(() -> service.enqueueNow("assetlocalize", "{\"id\":1}"))
        .isSameAs(fatalTestError);

    assertThat(store.findByStatus("assetlocalize", AsyncJobStatus.QUEUED, 10)).hasSize(1);
    verify(coordinator).triggerPollNow("assetlocalize");
    assertEnqueueCounter("succeeded", 1);
    assertNoEnqueueCounter("failed");
    assertNoEnqueueWakeupNotifyFailureCounter();
  }

  @Test
  public void enqueuePropagatesFatalWakeupErrorsWithoutWakeupFailureCounter() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    FatalTestError fatalTestError = new FatalTestError("fatal wakeup");
    doThrow(fatalTestError).when(coordinator).triggerPollNow("assetlocalize");
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);

    assertThatThrownBy(() -> service.enqueueNow("assetlocalize", "{\"id\":1}"))
        .isSameAs(fatalTestError);

    assertThat(store.findByStatus("assetlocalize", AsyncJobStatus.QUEUED, 10)).hasSize(1);
    assertEnqueueCounter("succeeded", 1);
    assertNoEnqueueWakeupFailureCounter();
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
    assertEnqueueCounter("invalidPayload", 1);
    assertEnqueueCounter("invalidAvailableAt", 1);
    assertNoEnqueueCounter("failed");
  }

  @Test
  public void enqueueRejectsOversizedPayloadBeforeStoreOrWakeup() {
    AsyncJobStore store = mock(AsyncJobStore.class);
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator);
    String oversizedPayload = "x".repeat(AsyncJobQueueValidation.JOB_DATA_MAX_LENGTH + 1);

    assertThatThrownBy(() -> service.enqueueNow("assetlocalize", oversizedPayload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jobData must be at most");

    verify(store, never()).enqueueNow(anyString(), anyString());
    verify(coordinator, never()).triggerPollNow("assetlocalize");
    assertEnqueueCounter("invalidPayload", 1);
    assertNoEnqueueCounter("failed");
  }

  private AsyncJobQueueSubmissionService submissionService(
      AsyncJobStore store, AsyncJobQueueCoordinator coordinator) {
    return submissionService(store, coordinator, AsyncJobQueueWakeupNotifier.NO_OP);
  }

  private AsyncJobQueueSubmissionService submissionService(
      AsyncJobStore store,
      AsyncJobQueueCoordinator coordinator,
      AsyncJobQueueWakeupNotifier wakeupNotifier) {
    return new AsyncJobQueueSubmissionService(
        store, coordinator, wakeupNotifier, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Clock throwingClock() {
    return new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        throw new IllegalStateException("clock unavailable");
      }
    };
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

  private void assertEnqueueWakeupNotifyFailureCounter(double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.enqueueWakeup.notify.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertNoEnqueueWakeupNotifyFailureCounter() {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.enqueueWakeup.notify.failed")
                .tag("queueName", "assetlocalize")
                .counter())
        .isNull();
  }

  private void assertEnqueueWakeupDecisionFailureCounter(double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.enqueueWakeup.decision.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(count);
  }

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
