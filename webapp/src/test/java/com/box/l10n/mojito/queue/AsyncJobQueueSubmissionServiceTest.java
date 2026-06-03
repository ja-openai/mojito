package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;

public class AsyncJobQueueSubmissionServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-23T05:50:00Z");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final Logger originalLogger = AsyncJobQueueSubmissionService.logger;

  @After
  public void tearDown() {
    AsyncJobQueueSubmissionService.logger = originalLogger;
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

  @Test
  public void enqueueNowSucceedsAndWakesBothPathsWhenSuccessTelemetryFails() {
    assertSuccessDespiteTelemetryFailure(true, NOW);
  }

  @Test
  public void enqueueDueJobSucceedsAndWakesBothPathsWhenSuccessTelemetryFails() {
    assertSuccessDespiteTelemetryFailure(false, NOW);
  }

  @Test
  public void enqueueFutureJobSucceedsWithoutWakeupsWhenSuccessTelemetryFails() {
    assertSuccessDespiteTelemetryFailure(false, NOW.plusSeconds(60));
  }

  @Test
  public void enqueueReturnsCommittedJobsWhenSuccessCounterConflictsWithExistingGauge() {
    meterRegistry.gauge(
        "asyncJobQueue.enqueue", Tags.of("queueName", "assetlocalize", "result", "succeeded"), 1);
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueWakeupNotifier notifier = mock(AsyncJobQueueWakeupNotifier.class);
    AsyncJobQueueSubmissionService service = submissionService(store, coordinator, notifier);

    AsyncJobId immediateJob = service.enqueueNow("assetlocalize", "{}");
    AsyncJobId scheduledJob = service.enqueue("assetlocalize", "{}", NOW);

    assertThat(store.getByIds(List.of(immediateJob, scheduledJob))).hasSize(2);
    verify(coordinator, times(2)).triggerPollNow("assetlocalize");
    verify(notifier).notifyJobAvailable("assetlocalize", immediateJob);
    verify(notifier).notifyJobAvailable("assetlocalize", scheduledJob);
    verifyNoMoreInteractions(coordinator, notifier);
    assertNoEnqueueCounter("failed");
  }

  private void assertSuccessDespiteTelemetryFailure(boolean immediate, Instant availableAt) {
    for (MetricFailure failure : MetricFailure.values()) {
      Logger logger = mock(Logger.class);
      AsyncJobQueueSubmissionService.logger = logger;
      SubmissionFixture fixture = new SubmissionFixture(immediate, availableAt);
      fixture.failMetric(failure, "asyncJobQueue.enqueue", "result", "succeeded");

      assertThat(fixture.enqueue()).isSameAs(fixture.jobId);

      fixture.verifySingleStoreCall();
      fixture.verifyWakeups(immediate || !availableAt.isAfter(NOW));
      fixture.verifyNoFailedEnqueueMetric();
      verify(logger)
          .warn(
              "Failed to record async job queue metric {}",
              new Object[] {"asyncJobQueue.enqueue", failure.throwable});
    }
  }

  @Test
  public void enqueueNowPreservesStoreFailureWhenFailureTelemetryFails() {
    assertStoreFailureDespiteTelemetryFailure(true);
  }

  @Test
  public void enqueuePreservesStoreFailureWhenFailureTelemetryFails() {
    assertStoreFailureDespiteTelemetryFailure(false);
  }

  private void assertStoreFailureDespiteTelemetryFailure(boolean immediate) {
    for (MetricFailure failure : MetricFailure.values()) {
      for (Throwable storeFailure :
          List.of(new IllegalStateException("database unavailable"), new AssertionError("store"))) {
        SubmissionFixture fixture = new SubmissionFixture(immediate, NOW);
        fixture.failMetric(failure, "asyncJobQueue.enqueue", "result", "failed");
        if (immediate) {
          when(fixture.store.enqueueNow("assetlocalize", "{}")).thenThrow(storeFailure);
        } else {
          when(fixture.store.enqueue("assetlocalize", "{}", NOW)).thenThrow(storeFailure);
        }

        assertThatThrownBy(fixture::enqueue).isSameAs(storeFailure);

        fixture.verifySingleStoreCall();
        fixture.verifyWakeups(false);
      }
    }
  }

  @Test
  public void enqueuePreservesPayloadValidationFailureWhenTelemetryFails() {
    for (MetricFailure failure : MetricFailure.values()) {
      SubmissionFixture fixture = new SubmissionFixture(false, NOW);
      fixture.failMetric(failure, "asyncJobQueue.enqueue", "result", "invalidPayload");
      AsyncJobQueueSubmissionService service = fixture.service();
      String oversizedPayload = "x".repeat(AsyncJobQueueValidation.JOB_DATA_MAX_LENGTH + 1);

      assertThatThrownBy(() -> service.enqueueNow("assetlocalize", null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> service.enqueue("assetlocalize", null, NOW))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> service.enqueueNow("assetlocalize", oversizedPayload))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jobData must be at most");
      assertThatThrownBy(() -> service.enqueue("assetlocalize", oversizedPayload, NOW))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("jobData must be at most");

      verifyNoInteractions(fixture.store);
      fixture.verifyWakeups(false);
      fixture.verifyNoFailedEnqueueMetric();
    }
  }

  @Test
  public void enqueuePreservesTimestampValidationFailureWhenTelemetryFails() {
    for (MetricFailure failure : MetricFailure.values()) {
      SubmissionFixture fixture = new SubmissionFixture(false, NOW);
      fixture.failMetric(failure, "asyncJobQueue.enqueue", "result", "invalidAvailableAt");
      AsyncJobQueueSubmissionService service = fixture.service();

      assertThatThrownBy(() -> service.enqueue("assetlocalize", "{}", null))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(
              () ->
                  service.enqueue(
                      "assetlocalize",
                      "{}",
                      AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX.plusNanos(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("availableAt must be between");

      verifyNoInteractions(fixture.store);
      fixture.verifyWakeups(false);
      fixture.verifyNoFailedEnqueueMetric();
    }
  }

  @Test
  public void enqueueWakesBothPathsWhenDecisionFailureTelemetryFails() {
    for (MetricFailure failure : MetricFailure.values()) {
      SubmissionFixture fixture = new SubmissionFixture(false, NOW);
      fixture.clock = throwingClock();
      fixture.failMetric(failure, "asyncJobQueue.enqueueWakeup.decision.failed");

      assertThat(fixture.enqueue()).isSameAs(fixture.jobId);

      fixture.verifySingleStoreCall();
      fixture.verifyWakeups(true);
      fixture.verifyNoFailedEnqueueMetric();
    }
  }

  @Test
  public void enqueueNowNotifiesWhenLocalWakeupFailureTelemetryFails() {
    assertWakeupFailureDespiteTelemetryFailure(true, false);
  }

  @Test
  public void enqueueNotifiesWhenLocalWakeupFailureTelemetryFails() {
    assertWakeupFailureDespiteTelemetryFailure(false, false);
  }

  @Test
  public void enqueueNowSucceedsWhenNotifierFailureTelemetryFails() {
    assertWakeupFailureDespiteTelemetryFailure(true, true);
  }

  @Test
  public void enqueueSucceedsWhenNotifierFailureTelemetryFails() {
    assertWakeupFailureDespiteTelemetryFailure(false, true);
  }

  private void assertWakeupFailureDespiteTelemetryFailure(
      boolean immediate, boolean notifierFails) {
    for (MetricFailure failure : MetricFailure.values()) {
      SubmissionFixture fixture = new SubmissionFixture(immediate, NOW);
      fixture.failMetric(
          failure,
          notifierFails
              ? "asyncJobQueue.enqueueWakeup.notify.failed"
              : "asyncJobQueue.enqueueWakeup.failed");
      if (notifierFails) {
        doThrow(new IllegalStateException("notifier unavailable"))
            .when(fixture.notifier)
            .notifyJobAvailable("assetlocalize", fixture.jobId);
      } else {
        doThrow(new IllegalStateException("coordinator unavailable"))
            .when(fixture.coordinator)
            .triggerPollNow("assetlocalize");
      }

      assertThat(fixture.enqueue()).isSameAs(fixture.jobId);

      fixture.verifySingleStoreCall();
      fixture.verifyWakeups(true);
      fixture.verifyNoFailedEnqueueMetric();
    }
  }

  @Test
  public void enqueueSucceedsAndAttemptsBothWakeupsWhenMetricsAndLoggingFail() {
    for (boolean immediate : List.of(true, false)) {
      for (MetricFailure failure : MetricFailure.values()) {
        AsyncJobQueueSubmissionService.logger =
            mock(
                Logger.class,
                invocation -> {
                  throw failure.throwable;
                });
        SubmissionFixture fixture = new SubmissionFixture(immediate, NOW);
        fixture.clock = throwingClock();
        fixture.failMetric(failure, "asyncJobQueue.enqueue", "result", "succeeded");
        fixture.failMetric(failure, "asyncJobQueue.enqueueWakeup.decision.failed");
        fixture.failMetric(failure, "asyncJobQueue.enqueueWakeup.failed");
        fixture.failMetric(failure, "asyncJobQueue.enqueueWakeup.notify.failed");
        doThrow(new IllegalStateException("local wakeup unavailable"))
            .when(fixture.coordinator)
            .triggerPollNow("assetlocalize");
        doThrow(new IllegalStateException("notifier unavailable"))
            .when(fixture.notifier)
            .notifyJobAvailable("assetlocalize", fixture.jobId);

        assertThat(fixture.enqueue()).isSameAs(fixture.jobId);

        fixture.verifySingleStoreCall();
        fixture.verifyWakeups(true);
        fixture.verifyNoFailedEnqueueMetric();
      }
    }
  }

  @Test
  public void enqueuePreservesStoreFailureWhenMetricsAndLoggingFail() {
    for (MetricFailure failure : MetricFailure.values()) {
      AsyncJobQueueSubmissionService.logger =
          mock(
              Logger.class,
              invocation -> {
                throw failure.throwable;
              });
      assertStoreFailureDespiteTelemetryFailure(true);
      assertStoreFailureDespiteTelemetryFailure(false);
    }
  }

  @Test
  public void enqueuePropagatesFatalMetricFailures() {
    for (boolean immediate : List.of(true, false)) {
      for (boolean failIncrement : List.of(false, true)) {
        SubmissionFixture fixture = new SubmissionFixture(immediate, NOW);
        FatalTestError fatal = new FatalTestError("fatal metric");
        fixture.failMetric(failIncrement, fatal, "asyncJobQueue.enqueue", "result", "succeeded");

        assertThatThrownBy(fixture::enqueue).isSameAs(fatal);

        fixture.verifySingleStoreCall();
        fixture.verifyWakeups(false);
        fixture.verifyNoFailedEnqueueMetric();
      }
    }
  }

  @Test
  public void enqueuePropagatesFatalFailureMetricErrors() {
    for (boolean immediate : List.of(true, false)) {
      for (boolean failIncrement : List.of(false, true)) {
        SubmissionFixture fixture = new SubmissionFixture(immediate, NOW);
        FatalTestError fatal = new FatalTestError("fatal failure metric");
        fixture.failMetric(failIncrement, fatal, "asyncJobQueue.enqueue", "result", "failed");
        IllegalStateException storeFailure = new IllegalStateException("database unavailable");
        if (immediate) {
          when(fixture.store.enqueueNow("assetlocalize", "{}")).thenThrow(storeFailure);
        } else {
          when(fixture.store.enqueue("assetlocalize", "{}", NOW)).thenThrow(storeFailure);
        }

        assertThatThrownBy(fixture::enqueue).isSameAs(fatal);

        fixture.verifySingleStoreCall();
        fixture.verifyWakeups(false);
      }
    }
  }

  @Test
  public void enqueuePropagatesFatalLoggingFailures() {
    for (boolean immediate : List.of(true, false)) {
      SubmissionFixture fixture = new SubmissionFixture(immediate, NOW);
      fixture.failMetric(
          MetricFailure.REGISTRY_EXCEPTION, "asyncJobQueue.enqueue", "result", "succeeded");
      FatalTestError fatal = new FatalTestError("fatal logging");
      AsyncJobQueueSubmissionService.logger =
          mock(
              Logger.class,
              invocation -> {
                throw fatal;
              });

      assertThatThrownBy(fixture::enqueue).isSameAs(fatal);

      fixture.verifySingleStoreCall();
      fixture.verifyWakeups(false);
      fixture.verifyNoFailedEnqueueMetric();
    }
  }

  private enum MetricFailure {
    REGISTRY_EXCEPTION(false, new IllegalStateException("registry unavailable")),
    REGISTRY_ERROR(false, new AssertionError("registry unavailable")),
    COUNTER_EXCEPTION(true, new IllegalStateException("counter unavailable")),
    COUNTER_ERROR(true, new AssertionError("counter unavailable"));

    final boolean failIncrement;
    final Throwable throwable;

    MetricFailure(boolean failIncrement, Throwable throwable) {
      this.failIncrement = failIncrement;
      this.throwable = throwable;
    }
  }

  private static class SubmissionFixture {
    final AsyncJobStore store = mock(AsyncJobStore.class);
    final AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    final AsyncJobQueueWakeupNotifier notifier = mock(AsyncJobQueueWakeupNotifier.class);
    final MeterRegistry registry = mock(MeterRegistry.class);
    final AsyncJobId jobId = new AsyncJobId("42");
    final boolean immediate;
    final Instant availableAt;
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    SubmissionFixture(boolean immediate, Instant availableAt) {
      this.immediate = immediate;
      this.availableAt = availableAt;
      when(registry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
      when(store.enqueueNow("assetlocalize", "{}")).thenReturn(jobId);
      when(store.enqueue("assetlocalize", "{}", availableAt)).thenReturn(jobId);
    }

    void failMetric(MetricFailure failure, String metric, String... extraTags) {
      failMetric(failure.failIncrement, failure.throwable, metric, extraTags);
    }

    void failMetric(boolean failIncrement, Throwable failure, String metric, String... extraTags) {
      String[] tags = new String[extraTags.length + 2];
      tags[0] = "queueName";
      tags[1] = "assetlocalize";
      System.arraycopy(extraTags, 0, tags, 2, extraTags.length);
      if (failIncrement) {
        Counter counter = mock(Counter.class);
        doThrow(failure).when(counter).increment();
        doReturn(counter).when(registry).counter(metric, tags);
      } else {
        doThrow(failure).when(registry).counter(metric, tags);
      }
    }

    AsyncJobQueueSubmissionService service() {
      return new AsyncJobQueueSubmissionService(store, coordinator, notifier, registry, clock);
    }

    AsyncJobId enqueue() {
      return immediate
          ? service().enqueueNow("assetlocalize", "{}")
          : service().enqueue("assetlocalize", "{}", availableAt);
    }

    void verifySingleStoreCall() {
      if (immediate) {
        verify(store).enqueueNow("assetlocalize", "{}");
      } else {
        verify(store).enqueue("assetlocalize", "{}", availableAt);
      }
      verifyNoMoreInteractions(store);
    }

    void verifyWakeups(boolean expected) {
      if (expected) {
        verify(coordinator).triggerPollNow("assetlocalize");
        verify(notifier).notifyJobAvailable("assetlocalize", jobId);
        verifyNoMoreInteractions(coordinator, notifier);
      } else {
        verifyNoInteractions(coordinator, notifier);
      }
    }

    void verifyNoFailedEnqueueMetric() {
      verify(registry, never())
          .counter("asyncJobQueue.enqueue", "queueName", "assetlocalize", "result", "failed");
    }
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
