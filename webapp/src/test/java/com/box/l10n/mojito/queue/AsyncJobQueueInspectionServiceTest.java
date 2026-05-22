package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  }

  @Test
  public void requeueFailedJobResetsAttemptsAndPreservesLastError() {
    InMemoryAsyncJobStore store = new InMemoryAsyncJobStore();
    AsyncJobId id = failedJob(store, "assetlocalize", "{\"step\":\"failed\"}", "boom");
    AsyncJobQueueInspectionService service = inspectionService(store);
    Instant beforeReplay = Instant.now().minusMillis(1);

    AsyncJobQueueInspectionService.AsyncJobDetails replayed =
        service.requeueFailedJob("assetlocalize", id.value(), "{\"step\":\"fixed\"}");

    assertThat(replayed.status()).isEqualTo("queued");
    assertThat(replayed.attemptCount()).isZero();
    assertThat(replayed.availableAt()).isBetween(beforeReplay, Instant.now().plusSeconds(1));
    assertThat(replayed.lastError()).isEqualTo("boom");
    assertThat(replayed.jobData()).isEqualTo("{\"step\":\"fixed\"}");
    assertRequeueCounter("succeeded", 1);
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
  public void requeueFailedJobRecordsStoreFailures() {
    AsyncJobQueueInspectionService service =
        new AsyncJobQueueInspectionService(
            new InMemoryAsyncJobStore() {
              @Override
              public boolean requeueFailedNow(String queueName, AsyncJobId id, String jobData) {
                throw new IllegalStateException("database unavailable");
              }
            },
            meterRegistry);

    assertThatThrownBy(() -> service.requeueFailedJob("assetlocalize", "1", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("database unavailable");
    assertRequeueCounter("failed", 1);
  }

  @Test
  public void validatesStatusAndLimit() {
    AsyncJobQueueInspectionService service = inspectionService(new InMemoryAsyncJobStore());

    assertThat(service.findJobs("assetlocalize", "FAILED", null)).isEmpty();
    assertThatThrownBy(() -> service.findJobs("assetlocalize", "broken", 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status must be one of");
    assertThatThrownBy(() -> service.findJobs("assetlocalize", "failed", 1001))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit must be <= 1000");
  }

  private AsyncJobQueueInspectionService inspectionService(InMemoryAsyncJobStore store) {
    return new AsyncJobQueueInspectionService(store, meterRegistry);
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
}
