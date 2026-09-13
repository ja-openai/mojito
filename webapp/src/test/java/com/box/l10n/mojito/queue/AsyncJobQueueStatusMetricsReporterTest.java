package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;

public class AsyncJobQueueStatusMetricsReporterTest {

  private final MockClock clock = new MockClock();
  private final SimpleMeterRegistry meterRegistry =
      new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
  private final Logger originalLogger = AsyncJobQueueStatusMetricsReporter.logger;

  @After
  public void tearDown() {
    AsyncJobQueueStatusMetricsReporter.logger = originalLogger;
    meterRegistry.close();
  }

  @Test
  public void freshnessRemainsUnknownUntilTheFirstCompleteSample() {
    clock.add(Duration.ofHours(1));
    AsyncJobStore store = spy(new InMemoryAsyncJobStore());
    doThrow(new IllegalStateException("database unavailable")).when(store).countByStatus("broken");

    reporter(store).reportStatusCounts();

    assertLastSuccess("broken", 0);
    assertLastSuccess("assetlocalize", 3600);
  }

  @Test
  public void partialStoreFailureDoesNotRefreshTimestampOrPublishPartialCounts() {
    clock.add(Duration.ofHours(1));
    InMemoryAsyncJobStore store = spy(new InMemoryAsyncJobStore());
    AsyncJobQueueStatusMetricsReporter reporter = reporter(store);
    reporter.reportStatusCounts();
    store.enqueue("assetlocalize", "{}", Instant.now().minusSeconds(1));
    clock.add(Duration.ofSeconds(60));
    doThrow(new IllegalStateException("last query unavailable"))
        .when(store)
        .expiredLeaseStatus("assetlocalize");

    reporter.reportStatusCounts();

    assertLastSuccess("assetlocalize", 3600);
    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 0);
    assertReadyCountGaugeValue("assetlocalize", 0);
    assertLastSuccess("broken", 3660);
    doReturn(new AsyncJobExpiredLeaseStatus(0, null, Instant.now()))
        .when(store)
        .expiredLeaseStatus("assetlocalize");
    clock.add(Duration.ofSeconds(60));

    reporter.reportStatusCounts();

    assertLastSuccess("assetlocalize", 3720);
    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 1);
    assertReadyCountGaugeValue("assetlocalize", 1);
  }

  @Test
  public void failedGaugeRegistrationCannotMarkAnIncompleteSampleFresh() {
    clock.add(Duration.ofHours(1));
    meterRegistry
        .config()
        .meterFilter(
            new MeterFilter() {
              @Override
              public Meter.Id map(Meter.Id id) {
                if (id.getName().equals("asyncJobQueue.running.expired.count")
                    && "broken".equals(id.getTag("queueName"))) {
                  throw new IllegalStateException("gauge registration unavailable");
                }
                return id;
              }
            });

    reporter(new InMemoryAsyncJobStore()).reportStatusCounts();

    assertLastSuccess("broken", 0);
    assertLastSuccess("assetlocalize", 3600);
    assertFailedCounter("broken", 1);
  }

  private void assertLastSuccess(String queue, double expectedEpochSeconds) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.statusMetrics.lastSuccessEpochSeconds")
                .tag("queueName", queue)
                .gauge()
                .value())
        .isEqualTo(expectedEpochSeconds);
  }

  @Test
  public void reportStatusCountsPublishesZerosAndUpdatesExistingGauges() {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    asyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().minusSeconds(1));
    asyncJobStore.enqueue("assetlocalize", "{\"id\":2}", Instant.now().minusSeconds(1));
    AsyncJobRecord runningJob =
        asyncJobStore.claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(10)).get(0);

    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore,
            queueProperties("assetlocalize"),
            List.of(handler("stats")),
            meterRegistry);

    reporter.reportStatusCounts();

    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 1);
    assertGaugeValue("assetlocalize", AsyncJobStatus.RUNNING, 1);
    assertGaugeValue("assetlocalize", AsyncJobStatus.DONE, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.FAILED, 0);
    assertGaugeValue("stats", AsyncJobStatus.QUEUED, 0);
    assertReadyCountGaugeValue("assetlocalize", 1);
    assertThat(readyOldestAgeGaugeValue("assetlocalize")).isGreaterThanOrEqualTo(0);
    assertReadyCountGaugeValue("stats", 0);
    assertReadyOldestAgeGaugeValue("stats", 0);
    assertExpiredLeaseCountGaugeValue("assetlocalize", 0);
    assertExpiredLeaseOldestAgeGaugeValue("assetlocalize", 0);
    assertExpiredLeaseCountGaugeValue("stats", 0);
    assertExpiredLeaseOldestAgeGaugeValue("stats", 0);

    asyncJobStore.markDone(
        "assetlocalize", runningJob.id(), "worker-a", runningJob.leaseToken(), "{\"id\":2}");

    reporter.reportStatusCounts();

    assertGaugeValue("assetlocalize", AsyncJobStatus.RUNNING, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.DONE, 1);
    assertReadyCountGaugeValue("assetlocalize", 1);
    assertExpiredLeaseCountGaugeValue("assetlocalize", 0);
  }

  @Test
  public void reportStatusCountsRecordsFailureCounterWithoutThrowing() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.countByStatus("assetlocalize"))
        .thenThrow(new IllegalStateException("database unavailable"));

    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    reporter.reportStatusCounts();

    assertFailedCounter("assetlocalize", 1);
  }

  @Test
  public void reportStatusCountsContinuesAfterOneQueueFails() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.countByStatus("broken"))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(asyncJobStore.countByStatus("assetlocalize"))
        .thenReturn(List.of(new AsyncJobStatusCount(AsyncJobStatus.QUEUED, 7)));
    when(asyncJobStore.readyStatus("assetlocalize"))
        .thenReturn(
            new AsyncJobReadyStatus(
                2, Instant.parse("2026-05-23T00:00:00Z"), Instant.parse("2026-05-23T00:00:03Z")));
    when(asyncJobStore.expiredLeaseStatus("assetlocalize"))
        .thenReturn(
            new AsyncJobExpiredLeaseStatus(
                1, Instant.parse("2026-05-23T00:00:00Z"), Instant.parse("2026-05-23T00:00:04Z")));

    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore, queueProperties("broken", "assetlocalize"), List.of(), meterRegistry);

    reporter.reportStatusCounts();

    assertFailedCounter("broken", 1);
    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 7);
    assertGaugeValue("assetlocalize", AsyncJobStatus.RUNNING, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.DONE, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.FAILED, 0);
    assertReadyCountGaugeValue("assetlocalize", 2);
    assertReadyOldestAgeGaugeValue("assetlocalize", 3000);
    assertExpiredLeaseCountGaugeValue("assetlocalize", 1);
    assertExpiredLeaseOldestAgeGaugeValue("assetlocalize", 4000);
  }

  @Test
  public void reportStatusCountsRecordsNonFatalStoreErrorAndContinuesAfterQueueFails() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.countByStatus("broken")).thenThrow(new AssertionError("store invariant"));
    when(asyncJobStore.countByStatus("assetlocalize"))
        .thenReturn(List.of(new AsyncJobStatusCount(AsyncJobStatus.FAILED, 3)));
    when(asyncJobStore.readyStatus("assetlocalize"))
        .thenReturn(new AsyncJobReadyStatus(0, null, Instant.parse("2026-05-23T00:00:00Z")));
    when(asyncJobStore.expiredLeaseStatus("assetlocalize"))
        .thenReturn(new AsyncJobExpiredLeaseStatus(0, null, Instant.parse("2026-05-23T00:00:00Z")));

    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore, queueProperties("broken", "assetlocalize"), List.of(), meterRegistry);

    reporter.reportStatusCounts();

    assertFailedCounter("broken", 1);
    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.RUNNING, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.DONE, 0);
    assertGaugeValue("assetlocalize", AsyncJobStatus.FAILED, 3);
    assertReadyCountGaugeValue("assetlocalize", 0);
    assertReadyOldestAgeGaugeValue("assetlocalize", 0);
    assertExpiredLeaseCountGaugeValue("assetlocalize", 0);
    assertExpiredLeaseOldestAgeGaugeValue("assetlocalize", 0);
  }

  @Test
  public void reportStatusCountsDoesNotCountFutureQueuedJobsAsReady() {
    InMemoryAsyncJobStore asyncJobStore = new InMemoryAsyncJobStore();
    asyncJobStore.enqueue("assetlocalize", "{\"id\":1}", Instant.now().plusSeconds(60));
    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    reporter.reportStatusCounts();

    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 1);
    assertReadyCountGaugeValue("assetlocalize", 0);
    assertReadyOldestAgeGaugeValue("assetlocalize", 0);
    assertExpiredLeaseCountGaugeValue("assetlocalize", 0);
    assertExpiredLeaseOldestAgeGaugeValue("assetlocalize", 0);
  }

  @Test
  public void reportStatusCountsPropagatesFatalJvmErrors() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    FatalTestError fatalTestError = new FatalTestError("fatal");
    when(asyncJobStore.countByStatus("assetlocalize")).thenThrow(fatalTestError);

    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    assertThat(assertThrows(FatalTestError.class, reporter::reportStatusCounts))
        .isSameAs(fatalTestError);
    assertNoFailedCounter("assetlocalize");
  }

  @Test
  public void wrappedAndSuppressedStoreFatalsStopSampling() {
    FatalTestError fatal = new FatalTestError("fatal");
    RuntimeException suppressed = new IllegalStateException("cleanup failed");
    suppressed.addSuppressed(fatal);
    for (Throwable failure : List.of(new IllegalStateException(fatal), suppressed)) {
      AsyncJobStore store = storeWithFirstQueueFailure(failure);
      assertThat(assertThrows(FatalTestError.class, reporter(store)::reportStatusCounts))
          .isSameAs(fatal);
      verify(store, never()).countByStatus("assetlocalize");
      assertNoFailedCounter("broken");
    }
  }

  @Test
  public void loggingFailureDoesNotPreventCounterOrLaterQueueSampling() {
    failLogging(new AssertionError("logger unavailable"));
    AsyncJobStore store =
        storeWithFirstQueueFailure(new IllegalStateException("store unavailable"));

    reporter(store).reportStatusCounts();

    assertFailedCounter("broken", 1);
    assertLaterQueueSampled(store);
  }

  @Test
  public void failureCounterRegistrationDoesNotPreventLaterQueueSampling() {
    failCounterRegistration(new IllegalStateException("counter registration unavailable"));
    AsyncJobStore store =
        storeWithFirstQueueFailure(new IllegalStateException("store unavailable"));

    reporter(store).reportStatusCounts();

    assertNoFailedCounter("broken");
    assertLaterQueueSampled(store);
  }

  @Test
  public void counterIncrementAndLoggingFailuresDoNotPreventLaterQueueSampling() {
    failLogging(new IllegalStateException("logger unavailable"));
    Counter counter = mock(Counter.class);
    doThrow(new AssertionError("counter increment unavailable")).when(counter).increment();
    SimpleMeterRegistry registry = spy(meterRegistry);
    doReturn(counter)
        .when(registry)
        .counter("asyncJobQueue.statusMetrics.failed", "queueName", "broken");
    AsyncJobStore store =
        storeWithFirstQueueFailure(new IllegalStateException("store unavailable"));
    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            store, queueProperties("broken", "assetlocalize"), List.of(), registry);

    reporter.reportStatusCounts();

    verify(counter).increment();
    assertLaterQueueSampled(store);
  }

  @Test
  public void wrappedLoggingFatalStopsSampling() {
    FatalTestError fatal = new FatalTestError("fatal logging");
    failLogging(new IllegalStateException(fatal));
    AsyncJobStore store =
        storeWithFirstQueueFailure(new IllegalStateException("store unavailable"));

    assertThat(assertThrows(FatalTestError.class, reporter(store)::reportStatusCounts))
        .isSameAs(fatal);
    verify(store, never()).countByStatus("assetlocalize");
    assertNoFailedCounter("broken");
  }

  @Test
  public void suppressedCounterFatalStopsSampling() {
    FatalTestError fatal = new FatalTestError("fatal counter");
    RuntimeException failure = new IllegalStateException("counter registration failed");
    failure.addSuppressed(fatal);
    failCounterRegistration(failure);
    AsyncJobStore store =
        storeWithFirstQueueFailure(new IllegalStateException("store unavailable"));

    assertThat(assertThrows(FatalTestError.class, reporter(store)::reportStatusCounts))
        .isSameAs(fatal);
    verify(store, never()).countByStatus("assetlocalize");
    assertNoFailedCounter("broken");
  }

  private AsyncJobStore storeWithFirstQueueFailure(Throwable failure) {
    AsyncJobStore store = spy(new InMemoryAsyncJobStore());
    doThrow(failure).when(store).countByStatus("broken");
    return store;
  }

  private AsyncJobQueueStatusMetricsReporter reporter(AsyncJobStore store) {
    return new AsyncJobQueueStatusMetricsReporter(
        store, queueProperties("broken", "assetlocalize"), List.of(), meterRegistry);
  }

  private void assertLaterQueueSampled(AsyncJobStore store) {
    verify(store).countByStatus("assetlocalize");
    verify(store).readyStatus("assetlocalize");
    verify(store).expiredLeaseStatus("assetlocalize");
    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 0);
    assertReadyCountGaugeValue("assetlocalize", 0);
    assertExpiredLeaseCountGaugeValue("assetlocalize", 0);
  }

  private void failLogging(Throwable failure) {
    AsyncJobQueueStatusMetricsReporter.logger =
        mock(
            Logger.class,
            invocation -> {
              if (invocation.getMethod().getName().equals("warn")) {
                throw failure;
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
  }

  private void failCounterRegistration(RuntimeException failure) {
    meterRegistry
        .config()
        .meterFilter(
            new MeterFilter() {
              @Override
              public Meter.Id map(Meter.Id id) {
                if (id.getName().equals("asyncJobQueue.statusMetrics.failed")) {
                  throw failure;
                }
                return id;
              }
            });
  }

  @Test
  public void reportStatusCountsHandlesNullConfiguredQueues() {
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    asyncJobQueueProperties.setQueues(null);
    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            new InMemoryAsyncJobStore(),
            asyncJobQueueProperties,
            List.of(handler("assetlocalize")),
            meterRegistry);

    reporter.reportStatusCounts();

    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 0);
  }

  @Test
  public void reportStatusCountsDeduplicatesConfiguredAndHandlerQueues() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    Instant observedAt = Instant.parse("2026-05-23T00:00:00Z");
    when(asyncJobStore.countByStatus("assetlocalize"))
        .thenReturn(List.of(new AsyncJobStatusCount(AsyncJobStatus.QUEUED, 1)));
    when(asyncJobStore.readyStatus("assetlocalize"))
        .thenReturn(new AsyncJobReadyStatus(0, null, observedAt));
    when(asyncJobStore.expiredLeaseStatus("assetlocalize"))
        .thenReturn(new AsyncJobExpiredLeaseStatus(0, null, observedAt));
    AsyncJobQueueStatusMetricsReporter reporter =
        new AsyncJobQueueStatusMetricsReporter(
            asyncJobStore,
            queueProperties("assetlocalize"),
            List.of(handler("assetlocalize")),
            meterRegistry);

    reporter.reportStatusCounts();

    verify(asyncJobStore, times(1)).countByStatus("assetlocalize");
    verify(asyncJobStore, times(1)).readyStatus("assetlocalize");
    verify(asyncJobStore, times(1)).expiredLeaseStatus("assetlocalize");
    assertGaugeValue("assetlocalize", AsyncJobStatus.QUEUED, 1);
  }

  @Test
  public void rejectsInvalidHandlerQueueNameBeforeReportingMetrics() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AsyncJobQueueStatusMetricsReporter(
                    new InMemoryAsyncJobStore(),
                    new AsyncJobQueueProperties(),
                    List.of(handler(" ")),
                    meterRegistry));

    assertThat(exception).hasMessageContaining("queueName must not be blank");
  }

  private AsyncJobQueueProperties queueProperties(String... queueNames) {
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    for (String queueName : queueNames) {
      asyncJobQueueProperties
          .getQueues()
          .put(queueName, new AsyncJobQueueProperties.QueueSettings());
    }
    return asyncJobQueueProperties;
  }

  private AsyncJobHandler handler(String queueName) {
    return new AsyncJobHandler() {
      @Override
      public String queueName() {
        return queueName;
      }

      @Override
      public AsyncJobHandlerResult process(AsyncJobRecord asyncJobRecord) {
        return AsyncJobHandlerResult.done();
      }
    };
  }

  private void assertGaugeValue(String queueName, AsyncJobStatus status, long expectedValue) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.status")
                .tag("queueName", queueName)
                .tag("status", status.getDatabaseValue())
                .gauge()
                .value())
        .isEqualTo(expectedValue);
  }

  private void assertFailedCounter(String queueName, double expectedCount) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.statusMetrics.failed")
                .tag("queueName", queueName)
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }

  private void assertNoFailedCounter(String queueName) {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.statusMetrics.failed")
                .tag("queueName", queueName)
                .counter())
        .isNull();
  }

  private void assertReadyCountGaugeValue(String queueName, long expectedValue) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.ready.count")
                .tag("queueName", queueName)
                .gauge()
                .value())
        .isEqualTo(expectedValue);
  }

  private void assertReadyOldestAgeGaugeValue(String queueName, long expectedValue) {
    assertThat(readyOldestAgeGaugeValue(queueName)).isEqualTo(expectedValue);
  }

  private double readyOldestAgeGaugeValue(String queueName) {
    return meterRegistry
        .get("asyncJobQueue.ready.oldestAgeMs")
        .tag("queueName", queueName)
        .gauge()
        .value();
  }

  private void assertExpiredLeaseCountGaugeValue(String queueName, long expectedValue) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.running.expired.count")
                .tag("queueName", queueName)
                .gauge()
                .value())
        .isEqualTo(expectedValue);
  }

  private void assertExpiredLeaseOldestAgeGaugeValue(String queueName, long expectedValue) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.running.expired.oldestAgeMs")
                .tag("queueName", queueName)
                .gauge()
                .value())
        .isEqualTo(expectedValue);
  }

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
