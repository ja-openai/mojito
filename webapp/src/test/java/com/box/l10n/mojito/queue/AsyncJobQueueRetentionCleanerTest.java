package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Test;

public class AsyncJobQueueRetentionCleanerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test(timeout = 2_000)
  public void cleanupTerminalJobsDeletesOnlyBoundedTerminalStatusesAndRecordsMetrics() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenReturn(100);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100))
        .thenReturn(100);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    retentionCleaner.cleanupTerminalJobs();

    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan("assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100);
    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100);
    verifyNoMoreInteractions(asyncJobStore);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.DONE, 100);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 100);
  }

  @Test
  public void cleanupTerminalJobsPassesConfiguredAgesWithoutLosingMilliseconds() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    AsyncJobQueueProperties properties = queueProperties("assetlocalize");
    Duration doneAge = Duration.ofHours(6).plusMillis(123);
    Duration failedAge = Duration.ofDays(2).plusMillis(456);
    properties.getRetention().setDoneRetentionMs(doneAge.toMillis());
    properties.getRetention().setFailedRetentionMs(failedAge.toMillis());
    properties.getRetention().setBatchSize(7);

    new AsyncJobQueueRetentionCleaner(asyncJobStore, properties, List.of(), meterRegistry)
        .cleanupTerminalJobs();

    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan("assetlocalize", AsyncJobStatus.DONE, doneAge, 7);
    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan("assetlocalize", AsyncJobStatus.FAILED, failedAge, 7);
    verifyNoMoreInteractions(asyncJobStore);
  }

  @Test
  public void cleanupTerminalJobsRecordsFailureAndContinuesAcrossStatuses() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100))
        .thenReturn(2);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    retentionCleaner.cleanupTerminalJobs();

    assertFailedCounter("assetlocalize", AsyncJobStatus.DONE, 1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 2);
    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100);
  }

  @Test(timeout = 2_000)
  public void cleanupTerminalJobsFailsClosedForLegacyStoreWithoutStoreClockRetention() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class, CALLS_REAL_METHODS);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    retentionCleaner.cleanupTerminalJobs();

    assertFailedCounter("assetlocalize", AsyncJobStatus.DONE, 1);
    assertFailedCounter("assetlocalize", AsyncJobStatus.FAILED, 1);
    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan("assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100);
    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100);
    verify(asyncJobStore, never()).deleteTerminalJobs(any(), any(), any(), anyInt());
    verifyNoMoreInteractions(asyncJobStore);
    assertThat(meterRegistry.find("asyncJobQueue.retention.deleted").counter()).isNull();
  }

  @Test
  public void cleanupTerminalJobsRecordsNonFatalStoreErrorAndContinuesAcrossStatuses() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenThrow(new AssertionError("store invariant"));
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100))
        .thenReturn(2);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    retentionCleaner.cleanupTerminalJobs();

    assertFailedCounter("assetlocalize", AsyncJobStatus.DONE, 1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 2);
  }

  @Test
  public void cleanupTerminalJobsPropagatesFatalStoreErrorsWithoutFailureCounter() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    FatalTestError fatalTestError = new FatalTestError("fatal retention");
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenThrow(fatalTestError);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore, queueProperties("assetlocalize"), List.of(), meterRegistry);

    FatalTestError exception =
        assertThrows(FatalTestError.class, retentionCleaner::cleanupTerminalJobs);

    assertThat(exception).isSameAs(fatalTestError);
    assertNoFailedCounter("assetlocalize", AsyncJobStatus.DONE);
    verify(asyncJobStore, never())
        .deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100);
  }

  @Test
  public void cleanupTerminalJobsContinuesAfterOneQueueFails() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "broken", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenThrow(new IllegalStateException("database unavailable"));
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenReturn(3);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100))
        .thenReturn(4);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore, queueProperties("broken", "assetlocalize"), List.of(), meterRegistry);

    retentionCleaner.cleanupTerminalJobs();

    assertFailedCounter("broken", AsyncJobStatus.DONE, 1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.DONE, 3);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 4);
    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan("assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100);
    verify(asyncJobStore)
        .deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100);
  }

  @Test
  public void cleanupTerminalJobsHandlesNullConfiguredQueues() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenReturn(1);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100))
        .thenReturn(2);
    AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
    asyncJobQueueProperties.setQueues(null);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore,
            asyncJobQueueProperties,
            List.of(handler("assetlocalize")),
            meterRegistry);

    retentionCleaner.cleanupTerminalJobs();

    assertDeletedCounter("assetlocalize", AsyncJobStatus.DONE, 1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 2);
  }

  @Test
  public void cleanupTerminalJobsDeduplicatesConfiguredAndHandlerQueues() {
    AsyncJobStore asyncJobStore = mock(AsyncJobStore.class);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100))
        .thenReturn(1);
    when(asyncJobStore.deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100))
        .thenReturn(2);

    AsyncJobQueueRetentionCleaner retentionCleaner =
        new AsyncJobQueueRetentionCleaner(
            asyncJobStore,
            queueProperties("assetlocalize"),
            List.of(handler("assetlocalize")),
            meterRegistry);

    retentionCleaner.cleanupTerminalJobs();

    verify(asyncJobStore, times(1))
        .deleteTerminalJobsOlderThan("assetlocalize", AsyncJobStatus.DONE, Duration.ofDays(7), 100);
    verify(asyncJobStore, times(1))
        .deleteTerminalJobsOlderThan(
            "assetlocalize", AsyncJobStatus.FAILED, Duration.ofDays(30), 100);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.DONE, 1);
    assertDeletedCounter("assetlocalize", AsyncJobStatus.FAILED, 2);
  }

  @Test
  public void rejectsInvalidHandlerQueueNameBeforeCleanup() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AsyncJobQueueRetentionCleaner(
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

  private void assertDeletedCounter(String queueName, AsyncJobStatus status, double expectedCount) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.retention.deleted")
                .tag("queueName", queueName)
                .tag("status", status.getDatabaseValue())
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }

  private void assertFailedCounter(String queueName, AsyncJobStatus status, double expectedCount) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.retention.failed")
                .tag("queueName", queueName)
                .tag("status", status.getDatabaseValue())
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }

  private void assertNoFailedCounter(String queueName, AsyncJobStatus status) {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.retention.failed")
                .tag("queueName", queueName)
                .tag("status", status.getDatabaseValue())
                .counter())
        .isNull();
  }

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
