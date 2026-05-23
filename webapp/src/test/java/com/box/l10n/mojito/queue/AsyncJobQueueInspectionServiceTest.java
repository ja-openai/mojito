package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class AsyncJobQueueInspectionServiceTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  public void findJobsReturnsBoundedSafeSummaries() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    String payload = "{\"value\":\"" + "x".repeat(600) + "\"}";
    AsyncJobId id = failedJob(store, "assetlocalize", payload, "handler failed");
    AsyncJobQueueInspectionService service = inspectionService(store);

    List<AsyncJobQueueInspectionService.AsyncJobSummary> jobs =
        service.findJobs("assetlocalize", "failed", 10);

    assertThat(jobs).hasSize(1);
    AsyncJobQueueInspectionService.AsyncJobSummary job = jobs.get(0);
    assertThat(job.id()).isEqualTo(id.value());
    assertThat(job.status()).isEqualTo("failed");
    assertThat(job.lastError()).isEqualTo("handler failed");
    assertThat(job.jobDataLength()).isEqualTo(payload.length());
    assertThat(job.jobDataPreview())
        .hasSize(AsyncJobQueueInspectionService.JOB_DATA_PREVIEW_MAX_LENGTH);
    assertThat(job.leaseUntil()).isNull();
    assertThat(job.workerId()).isNull();
    assertFindCounter("failed", "succeeded", 1);
  }

  @Test
  public void getJobReturnsFullPayloadForMatchingQueueOnly() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    String payload = "{\"repositoryId\":42,\"force\":true}";
    AsyncJobId id = failedJob(store, "assetlocalize", payload, "bad data");
    AsyncJobQueueInspectionService service = inspectionService(store);

    AsyncJobQueueInspectionService.AsyncJobDetails job =
        service.getJob("assetlocalize", id.value());

    assertThat(job.id()).isEqualTo(id.value());
    assertThat(job.status()).isEqualTo("failed");
    assertThat(job.lastError()).isEqualTo("bad data");
    assertThat(job.jobData()).isEqualTo(payload);
    assertThatThrownBy(() -> service.getJob("other", id.value()))
        .isInstanceOf(AsyncJobQueueInspectionService.AsyncJobNotFoundException.class);
    assertGetCounter("succeeded", 1);
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.get")
                .tag("queueName", "other")
                .tag("result", "notFound")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void countJobsByStatusReturnsStableStatusSetWithoutPayloads() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    store.enqueue("assetlocalize", "{\"step\":\"queued\"}", Instant.now().plusSeconds(60));
    failedJob(store, "assetlocalize", "{\"step\":\"failed\"}", "boom");
    AsyncJobQueueInspectionService service = inspectionService(store);

    List<AsyncJobQueueInspectionService.AsyncJobStatusCountSummary> counts =
        service.countJobsByStatus("assetlocalize");

    assertThat(counts)
        .extracting(
            AsyncJobQueueInspectionService.AsyncJobStatusCountSummary::status,
            AsyncJobQueueInspectionService.AsyncJobStatusCountSummary::count)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("queued", 1L),
            org.assertj.core.groups.Tuple.tuple("running", 0L),
            org.assertj.core.groups.Tuple.tuple("done", 0L),
            org.assertj.core.groups.Tuple.tuple("failed", 1L));
    assertCountCounter("succeeded", 1);
  }

  @Test
  public void countJobsByStatusRecordsStoreFailures() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public List<AsyncJobStatusCount> countByStatus(String queueName) {
                throw new IllegalStateException("database unavailable");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.countJobsByStatus("assetlocalize"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertCountCounter("failed", 1);
  }

  @Test
  public void readyStatusReturnsReadyBacklogWithoutPayloads() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    store.enqueue("assetlocalize", "{\"step\":\"ready\"}", Instant.now().minusSeconds(3));
    store.enqueue("assetlocalize", "{\"step\":\"delayed\"}", Instant.now().plusSeconds(60));
    AsyncJobQueueInspectionService service = inspectionService(store);

    AsyncJobQueueInspectionService.AsyncJobReadyStatusSummary readyStatus =
        service.readyStatus("assetlocalize");

    assertThat(readyStatus.queueName()).isEqualTo("assetlocalize");
    assertThat(readyStatus.count()).isEqualTo(1);
    assertThat(readyStatus.oldestAvailableAt()).isBeforeOrEqualTo(readyStatus.observedAt());
    assertThat(readyStatus.oldestAgeMs()).isGreaterThanOrEqualTo(0);
    assertReadyStatusCounter("succeeded", 1);
  }

  @Test
  public void readyStatusRecordsStoreFailures() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public AsyncJobReadyStatus readyStatus(String queueName) {
                throw new IllegalStateException("database unavailable");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.readyStatus("assetlocalize"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertReadyStatusCounter("failed", 1);
  }

  @Test
  public void expiredLeaseStatusReturnsExpiredRunningBacklogWithoutPayloads() {
    Instant observedAt = Instant.parse("2026-05-23T00:00:03Z");
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public AsyncJobExpiredLeaseStatus expiredLeaseStatus(String queueName) {
                return new AsyncJobExpiredLeaseStatus(
                    2, Instant.parse("2026-05-23T00:00:00Z"), observedAt);
              }
            },
            noOpCoordinator(),
            meterRegistry);

    AsyncJobQueueInspectionService.AsyncJobExpiredLeaseStatusSummary expiredLeaseStatus =
        service.expiredLeaseStatus("assetlocalize");

    assertThat(expiredLeaseStatus.queueName()).isEqualTo("assetlocalize");
    assertThat(expiredLeaseStatus.count()).isEqualTo(2);
    assertThat(expiredLeaseStatus.oldestLeaseUntil())
        .isEqualTo(Instant.parse("2026-05-23T00:00:00Z"));
    assertThat(expiredLeaseStatus.observedAt()).isEqualTo(observedAt);
    assertThat(expiredLeaseStatus.oldestAgeMs()).isEqualTo(3000);
    assertExpiredLeaseStatusCounter("succeeded", 1);
  }

  @Test
  public void expiredLeaseStatusRecordsStoreFailures() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public AsyncJobExpiredLeaseStatus expiredLeaseStatus(String queueName) {
                throw new IllegalStateException("database unavailable");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.expiredLeaseStatus("assetlocalize"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertExpiredLeaseStatusCounter("failed", 1);
  }

  @Test
  public void findJobsRecordsInvalidInputAndStoreFailures() {
    AsyncJobQueueInspectionService invalidInputService =
        inspectionService(new InMemoryAsyncJobStore());

    assertThatThrownBy(() -> invalidInputService.findJobs("assetlocalize", "broken", 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> invalidInputService.findJobs("assetlocalize", "failed", 1001))
        .isInstanceOf(IllegalArgumentException.class);
    assertFindCounter("invalid", "invalidStatus", 1);
    assertFindCounter("failed", "invalidLimit", 1);

    AsyncJobQueueInspectionService failingStoreService =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public List<AsyncJobRecord> findByStatus(
                  String queueName, AsyncJobStatus status, int limit) {
                throw new IllegalStateException("database unavailable");
              }
            },
            noOpCoordinator(),
            meterRegistry);
    assertThatThrownBy(() -> failingStoreService.findJobs("assetlocalize", "failed", 10))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertFindCounter("failed", "failed", 1);
  }

  @Test
  public void findJobsRecordsNonFatalStoreErrors() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public List<AsyncJobRecord> findByStatus(
                  String queueName, AsyncJobStatus status, int limit) {
                throw new NonFatalTestError("store invariant");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.findJobs("assetlocalize", "failed", 10))
        .isInstanceOf(NonFatalTestError.class)
        .hasMessageContaining("store invariant");
    assertFindCounter("failed", "failed", 1);
  }

  @Test
  public void getJobRecordsStoreFailures() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
                throw new IllegalStateException("database unavailable");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.getJob("assetlocalize", "1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertGetCounter("failed", 1);
  }

  @Test
  public void getJobRecordsNonFatalStoreErrors() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
                throw new NonFatalTestError("lookup invariant");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.getJob("assetlocalize", "1"))
        .isInstanceOf(NonFatalTestError.class)
        .hasMessageContaining("lookup invariant");
    assertGetCounter("failed", 1);
  }

  @Test
  public void getJobPropagatesFatalJvmErrorsWithoutFailureCounter() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
                throw new FatalTestError("fatal");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.getJob("assetlocalize", "1"))
        .isInstanceOf(FatalTestError.class)
        .hasMessageContaining("fatal");
    assertNoGetCounter("failed");
  }

  @Test
  public void getJobRecordsInvalidIds() {
    AsyncJobQueueInspectionService service = inspectionService(new InMemoryAsyncJobStore());

    assertThatThrownBy(() -> service.getJob("assetlocalize", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must not be blank");
    assertThatThrownBy(() -> service.getJob("assetlocalize", "abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertThatThrownBy(() -> service.getJob("assetlocalize", "+1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertThatThrownBy(() -> service.getJob("assetlocalize", "01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertThatThrownBy(() -> service.getJob("assetlocalize", "-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertGetCounter("invalidId", 5);
  }

  @Test
  public void requeueFailedJobResetsAttemptsAndPreservesLastError() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobId id = failedJob(store, "assetlocalize", "{\"step\":\"failed\"}", "boom");
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    AsyncJobQueueInspectionService service = inspectionService(store, coordinator);
    Instant beforeReplay = Instant.now().minusMillis(1);

    AsyncJobQueueInspectionService.AsyncJobDetails replayed =
        service.requeueFailedJob("assetlocalize", id.value(), "{\"step\":\"fixed\"}");

    verify(coordinator).triggerPollNow("assetlocalize");
    assertThat(replayed.status()).isEqualTo("queued");
    assertThat(replayed.attemptCount()).isZero();
    assertThat(replayed.availableAt()).isBetween(beforeReplay, Instant.now().plusSeconds(1));
    assertThat(replayed.lastError()).isEqualTo("boom");
    assertThat(replayed.jobData()).isEqualTo("{\"step\":\"fixed\"}");
    assertRequeueCounter("succeeded", 1);
    assertNoRequeueWakeupFailureCounter();
    assertNoGetCounter("succeeded");
  }

  @Test
  public void requeueFailedJobRecordsWakeupFailureWithoutFailingReplay() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobId id = failedJob(store, "assetlocalize", "{\"step\":\"failed\"}", "boom");
    AsyncJobQueueCoordinator coordinator = mock(AsyncJobQueueCoordinator.class);
    doThrow(new IllegalStateException("scheduler unavailable"))
        .when(coordinator)
        .triggerPollNow("assetlocalize");
    AsyncJobQueueInspectionService service = inspectionService(store, coordinator);

    AsyncJobQueueInspectionService.AsyncJobDetails replayed =
        service.requeueFailedJob("assetlocalize", id.value(), null);

    assertThat(replayed.status()).isEqualTo("queued");
    assertRequeueCounter("succeeded", 1);
    assertRequeueWakeupFailureCounter(1);
  }

  @Test
  public void requeueFailedJobRejectsMissingAndNonFailedJobs() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobId queuedId =
        store.enqueue("assetlocalize", "{\"step\":\"queued\"}", Instant.now().plusSeconds(60));
    AsyncJobQueueInspectionService service = inspectionService(store);

    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "404", null))
        .isInstanceOf(AsyncJobQueueInspectionService.AsyncJobNotFoundException.class);
    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", queuedId.value(), null))
        .isInstanceOf(AsyncJobQueueInspectionService.AsyncJobNotFailedException.class);
    assertRequeueCounter("notFound", 1);
    assertRequeueCounter("notFailed", 1);
  }

  @Test
  public void requeueFailedJobRecordsInvalidIds() {
    AsyncJobQueueInspectionService service = inspectionService(new InMemoryAsyncJobStore());

    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must not be blank");
    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "abc", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "+1", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "01", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "0", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Async job id must be a canonical positive numeric string");
    assertRequeueCounter("invalidId", 5);
  }

  @Test
  public void requeueFailedJobRecordsStoreFailures() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public boolean requeueFailedNow(String queueName, AsyncJobId id, String jobData) {
                throw new IllegalStateException("database unavailable");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertRequeueCounter("failed", 1);
  }

  @Test
  public void requeueFailedJobRecordsNonFatalStoreErrors() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public boolean requeueFailedNow(String queueName, AsyncJobId id, String jobData) {
                throw new NonFatalTestError("requeue invariant");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "1", null))
        .isInstanceOf(NonFatalTestError.class)
        .hasMessageContaining("requeue invariant");
    assertRequeueCounter("failed", 1);
  }

  @Test
  public void requeueFailedJobRecordsSingleFailureWhenPostReplayLookupFails() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public boolean requeueFailedNow(String queueName, AsyncJobId id, String jobData) {
                return true;
              }

              @Override
              public List<AsyncJobRecord> getByIds(List<AsyncJobId> ids) {
                throw new IllegalStateException("lookup unavailable");
              }
            },
            noOpCoordinator(),
            meterRegistry);

    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lookup unavailable");
    assertRequeueCounter("failed", 1);
    assertNoGetCounter("failed");
    assertThat(
            meterRegistry
                .find("asyncJobQueue.inspection.requeue")
                .tag("queueName", "assetlocalize")
                .tag("result", "succeeded")
                .counter())
        .isNull();
  }

  @Test
  public void validatesStatusAndLimit() {
    AsyncJobQueueInspectionService service = inspectionService(new InMemoryAsyncJobStore());

    assertThat(service.findJobs("assetlocalize", "FAILED", null)).isEmpty();
    assertThatThrownBy(() -> service.findJobs("assetlocalize", " ", 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status must not be blank");
  }

  private AsyncJobQueueInspectionService inspectionService(InMemoryAsyncJobStore store) {
    return inspectionService(store, noOpCoordinator());
  }

  private AsyncJobQueueInspectionService inspectionService(
      InMemoryAsyncJobStore store, AsyncJobQueueCoordinator coordinator) {
    return new AsyncJobQueueInspectionService(store, coordinator, meterRegistry);
  }

  private AsyncJobQueueCoordinator noOpCoordinator() {
    return mock(AsyncJobQueueCoordinator.class);
  }

  private AsyncJobId failedJob(
      InMemoryAsyncJobStore store, String queueName, String payload, String lastError) {
    AsyncJobId id = store.enqueue(queueName, payload, Instant.now().minusSeconds(1));
    AsyncJobRecord job = store.claimNextJobs(queueName, 1, "worker", Duration.ofSeconds(30)).get(0);
    assertThat(store.markFailed(queueName, id, "worker", job.leaseToken(), null, lastError))
        .isTrue();
    return id;
  }

  private void assertRequeueCounter(String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.requeue")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertRequeueWakeupFailureCounter(double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.requeueWakeup.failed")
                .tag("queueName", "assetlocalize")
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertNoRequeueWakeupFailureCounter() {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.inspection.requeueWakeup.failed")
                .tag("queueName", "assetlocalize")
                .counter())
        .isNull();
  }

  private void assertFindCounter(String status, String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.find")
                .tag("queueName", "assetlocalize")
                .tag("status", status)
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertNoGetCounter(String result) {
    assertThat(
            meterRegistry
                .find("asyncJobQueue.inspection.get")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter())
        .isNull();
  }

  private void assertGetCounter(String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.get")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertCountCounter(String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.count")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertReadyStatusCounter(String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.readyStatus")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private void assertExpiredLeaseStatusCounter(String result, double count) {
    assertThat(
            meterRegistry
                .get("asyncJobQueue.inspection.expiredLeaseStatus")
                .tag("queueName", "assetlocalize")
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(count);
  }

  private static class NonFatalTestError extends Error {
    NonFatalTestError(String message) {
      super(message);
    }
  }

  private static class FatalTestError extends VirtualMachineError {
    FatalTestError(String message) {
      super(message);
    }
  }
}
