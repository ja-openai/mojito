package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class InMemoryAsyncJobStoreTest {

  private final InMemoryAsyncJobStore inMemoryAsyncJobStore = new InMemoryAsyncJobStore();

  @Test
  public void claimNextJobsFiltersByAvailabilityAndOrder() {
    Instant now = Instant.now();
    AsyncJobId firstReadyId =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"name\":\"first\"}", now.minusSeconds(2));
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"name\":\"later\"}", now.plusSeconds(30));
    AsyncJobId secondReadyId =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"name\":\"second\"}", now.minusSeconds(1));

    List<AsyncJobRecord> claimed =
        inMemoryAsyncJobStore.claimNextJobs("assetlocalize", 10, "worker-a", Duration.ofSeconds(5));

    assertThat(claimed).hasSize(2);
    assertThat(claimed.get(0).id()).isEqualTo(firstReadyId);
    assertThat(claimed.get(1).id()).isEqualTo(secondReadyId);
    assertThat(claimed).allMatch(job -> job.status() == AsyncJobStatus.RUNNING);
    assertThat(claimed).allMatch(job -> "worker-a".equals(job.workerId()));
    assertThat(claimed).allMatch(job -> job.leaseToken() != null && !job.leaseToken().isBlank());
  }

  @Test
  public void leaseExpiryAllowsReclaim() throws Exception {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{}", Instant.now().minusSeconds(1));

    AsyncJobRecord firstClaim =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofMillis(50))
            .get(0);

    assertThat(
            inMemoryAsyncJobStore.claimNextJobs(
                "assetlocalize", 1, "worker-b", Duration.ofSeconds(1)))
        .isEmpty();

    Thread.sleep(80);

    AsyncJobRecord reclaimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-b", Duration.ofSeconds(1))
            .get(0);

    assertThat(reclaimed.id()).isEqualTo(id);
    assertThat(reclaimed.workerId()).isEqualTo("worker-b");
    assertThat(reclaimed.leaseToken()).isNotEqualTo(firstClaim.leaseToken());
  }

  @Test
  public void operationsAreFencedByLeaseOwnerAndToken() {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"v\":1}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);

    assertThat(
            inMemoryAsyncJobStore.heartbeat(
                "assetlocalize", id, "worker-a", "wrong-token", Duration.ofSeconds(5)))
        .isFalse();
    assertThat(
            inMemoryAsyncJobStore.markDone(
                "assetlocalize", id, "worker-b", claimed.leaseToken(), "{\"v\":2}"))
        .isFalse();
    assertThat(
            inMemoryAsyncJobStore.requeue(
                "assetlocalize",
                id,
                "worker-b",
                claimed.leaseToken(),
                Instant.now().plusSeconds(1),
                "{\"v\":2}",
                null))
        .isFalse();

    assertThat(
            inMemoryAsyncJobStore.heartbeat(
                "assetlocalize", id, "worker-a", claimed.leaseToken(), Duration.ofSeconds(5)))
        .isTrue();
  }

  @Test
  public void expiredLeaseOwnerCanNoLongerUpdateBeforeReclaim() throws Exception {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{\"v\":1}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofMillis(50))
            .get(0);

    Thread.sleep(80);

    assertThat(
            inMemoryAsyncJobStore.heartbeat(
                "assetlocalize", id, "worker-a", claimed.leaseToken(), Duration.ofSeconds(5)))
        .isFalse();
    assertThat(
            inMemoryAsyncJobStore.markDone(
                "assetlocalize", id, "worker-a", claimed.leaseToken(), "{\"v\":2}"))
        .isFalse();
    assertThat(
            inMemoryAsyncJobStore.requeue(
                "assetlocalize",
                id,
                "worker-a",
                claimed.leaseToken(),
                Instant.now().plusSeconds(1),
                "{\"v\":2}",
                null))
        .isFalse();
  }

  @Test
  public void requeueAndMarkDoneCanUpdateJobData() throws Exception {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);

    Instant nextAvailableAt = Instant.now().plusMillis(80);
    assertThat(
            inMemoryAsyncJobStore.requeue(
                "assetlocalize",
                id,
                "worker-a",
                claimed.leaseToken(),
                nextAvailableAt,
                "{\"step\":\"requeued\"}",
                "temporary failure"))
        .isTrue();

    assertThat(
            inMemoryAsyncJobStore.claimNextJobs(
                "assetlocalize", 1, "worker-a", Duration.ofSeconds(1)))
        .isEmpty();
    Thread.sleep(100);

    AsyncJobRecord reclaimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);
    assertThat(reclaimed.jobData()).isEqualTo("{\"step\":\"requeued\"}");
    assertThat(reclaimed.attemptCount()).isEqualTo(2);
    assertThat(reclaimed.lastError()).isEqualTo("temporary failure");

    assertThat(
            inMemoryAsyncJobStore.markDone(
                "assetlocalize", id, "worker-a", reclaimed.leaseToken(), "{\"step\":\"done\"}"))
        .isTrue();

    AsyncJobRecord done = inMemoryAsyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(done.jobData()).isEqualTo("{\"step\":\"done\"}");
    assertThat(done.workerId()).isNull();
    assertThat(done.leaseToken()).isNull();
    assertThat(done.leaseUntil()).isNull();
    assertThat(done.lastError()).isNull();
  }

  @Test
  public void markFailedPersistsTerminalStateAndError() {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);

    assertThat(
            inMemoryAsyncJobStore.markFailed(
                "assetlocalize",
                id,
                "worker-a",
                claimed.leaseToken(),
                "{\"step\":\"failed\"}",
                "boom"))
        .isTrue();

    AsyncJobRecord failed = inMemoryAsyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(failed.status()).isEqualTo(AsyncJobStatus.FAILED);
    assertThat(failed.jobData()).isEqualTo("{\"step\":\"failed\"}");
    assertThat(failed.attemptCount()).isEqualTo(1);
    assertThat(failed.lastError()).isEqualTo("boom");
    assertThat(failed.workerId()).isNull();
    assertThat(failed.leaseToken()).isNull();
    assertThat(failed.leaseUntil()).isNull();
  }

  @Test
  public void countByStatusReflectsQueueStateTransitions() {
    AsyncJobId queuedId =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"name\":\"queued\"}", Instant.now().plusSeconds(30));
    AsyncJobId runningAndDoneId =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"name\":\"done\"}", Instant.now().minusSeconds(1));

    AsyncJobRecord running =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);
    assertThat(running.id()).isEqualTo(runningAndDoneId);

    assertThat(
            inMemoryAsyncJobStore.markDone(
                "assetlocalize", running.id(), "worker-a", running.leaseToken(), null))
        .isTrue();

    List<AsyncJobStatusCount> statusCounts = inMemoryAsyncJobStore.countByStatus("assetlocalize");

    assertThat(statusCounts)
        .containsExactlyInAnyOrder(
            new AsyncJobStatusCount(AsyncJobStatus.QUEUED, 1),
            new AsyncJobStatusCount(AsyncJobStatus.DONE, 1));

    AsyncJobRecord queued = inMemoryAsyncJobStore.getByIds(List.of(queuedId)).get(0);
    assertThat(queued.status()).isEqualTo(AsyncJobStatus.QUEUED);
  }
}
