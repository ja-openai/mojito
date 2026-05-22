package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

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
    assertThat(claimed).allMatch(job -> !job.leaseReclaimed());
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
    assertThat(reclaimed.leaseReclaimed()).isTrue();
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
  public void storeTransitionsTruncateLastError() {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);
    String longError = "x".repeat(AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH + 1);

    assertThat(
            inMemoryAsyncJobStore.requeue(
                "assetlocalize",
                id,
                "worker-a",
                claimed.leaseToken(),
                Instant.now().minusSeconds(1),
                null,
                longError))
        .isTrue();

    AsyncJobRecord requeued = inMemoryAsyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(requeued.lastError()).hasSize(AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH);

    AsyncJobRecord reclaimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);
    assertThat(
            inMemoryAsyncJobStore.markFailed(
                "assetlocalize", id, "worker-a", reclaimed.leaseToken(), null, longError))
        .isTrue();

    AsyncJobRecord failed = inMemoryAsyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(failed.lastError()).hasSize(AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH);
  }

  @Test
  public void findByStatusAndRequeueFailedSupportOperatorReplay() {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue(
            "assetlocalize", "{\"step\":\"new\"}", Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore
            .claimNextJobs("assetlocalize", 1, "worker-a", Duration.ofSeconds(5))
            .get(0);
    assertThat(
            inMemoryAsyncJobStore.markFailed(
                "assetlocalize", id, "worker-a", claimed.leaseToken(), null, "boom"))
        .isTrue();

    assertThat(inMemoryAsyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.FAILED, 10))
        .extracting(AsyncJobRecord::id)
        .containsExactly(id);
    assertThat(
            inMemoryAsyncJobStore.requeueFailed(
                "assetlocalize", id, Instant.now().plusMillis(50), "{\"step\":\"operator-fixed\"}"))
        .isTrue();

    AsyncJobRecord replayed = inMemoryAsyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(replayed.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(replayed.jobData()).isEqualTo("{\"step\":\"operator-fixed\"}");
    assertThat(replayed.attemptCount()).isEqualTo(0);
    assertThat(replayed.lastError()).isEqualTo("boom");
    assertThat(replayed.workerId()).isNull();
    assertThat(replayed.leaseToken()).isNull();
    assertThat(replayed.leaseUntil()).isNull();
    assertThat(inMemoryAsyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.FAILED, 10))
        .isEmpty();
  }

  @Test
  public void requeueFailedIgnoresNonFailedJobs() {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue("assetlocalize", "{}", Instant.now().minusSeconds(1));

    assertThat(inMemoryAsyncJobStore.requeueFailed("assetlocalize", id, Instant.now(), "{\"v\":2}"))
        .isFalse();

    AsyncJobRecord queued = inMemoryAsyncJobStore.getByIds(List.of(id)).get(0);
    assertThat(queued.status()).isEqualTo(AsyncJobStatus.QUEUED);
    assertThat(queued.jobData()).isEqualTo("{}");
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

  @Test
  public void deleteTerminalJobsDeletesOnlyBoundedMatchingTerminalRows() {
    completeJob("assetlocalize", "{\"name\":\"done-1\"}");
    completeJob("assetlocalize", "{\"name\":\"done-2\"}");
    failJob("assetlocalize", "{\"name\":\"failed\"}");
    inMemoryAsyncJobStore.enqueue(
        "assetlocalize", "{\"name\":\"queued\"}", Instant.now().plusSeconds(30));
    completeJob("stats", "{\"name\":\"other-queue\"}");

    Instant cutoffAfterCreatedRows = Instant.now().plusSeconds(1);

    assertThat(
            inMemoryAsyncJobStore.deleteTerminalJobs(
                "assetlocalize", AsyncJobStatus.DONE, cutoffAfterCreatedRows, 1))
        .isEqualTo(1);

    assertThat(inMemoryAsyncJobStore.countByStatus("assetlocalize"))
        .containsExactlyInAnyOrder(
            new AsyncJobStatusCount(AsyncJobStatus.DONE, 1),
            new AsyncJobStatusCount(AsyncJobStatus.FAILED, 1),
            new AsyncJobStatusCount(AsyncJobStatus.QUEUED, 1));
    assertThat(inMemoryAsyncJobStore.countByStatus("stats"))
        .containsExactly(new AsyncJobStatusCount(AsyncJobStatus.DONE, 1));

    assertThat(
            inMemoryAsyncJobStore.deleteTerminalJobs(
                "assetlocalize", AsyncJobStatus.FAILED, cutoffAfterCreatedRows, 10))
        .isEqualTo(1);
    assertThat(inMemoryAsyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.FAILED, 10))
        .isEmpty();
  }

  @Test
  public void deleteTerminalJobsRejectsNonTerminalStatusAndHonorsLimit() {
    completeJob("assetlocalize", "{\"name\":\"done\"}");

    assertThat(
            inMemoryAsyncJobStore.deleteTerminalJobs(
                "assetlocalize", AsyncJobStatus.DONE, Instant.now().plusSeconds(1), 0))
        .isZero();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inMemoryAsyncJobStore.deleteTerminalJobs(
                "assetlocalize", AsyncJobStatus.QUEUED, Instant.now().plusSeconds(1), 10));

    assertThat(inMemoryAsyncJobStore.findByStatus("assetlocalize", AsyncJobStatus.DONE, 10))
        .hasSize(1);
  }

  @Test
  public void rejectsQueueNamesOutsideSchemaBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> inMemoryAsyncJobStore.enqueue(" ", "{}", Instant.now()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inMemoryAsyncJobStore.enqueue(
                "x".repeat(AsyncJobQueueValidation.QUEUE_NAME_MAX_LENGTH + 1),
                "{}",
                Instant.now()));
  }

  @Test
  public void rejectsWorkerIdsOutsideSchemaBounds() {
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{}", Instant.now().minusSeconds(1));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inMemoryAsyncJobStore.claimNextJobs(
                "assetlocalize",
                1,
                "x".repeat(AsyncJobQueueValidation.WORKER_ID_MAX_LENGTH + 1),
                Duration.ofSeconds(1)));
  }

  @Test
  public void rejectsLeaseDurationThatOverflowsInstantRange() {
    inMemoryAsyncJobStore.enqueue("assetlocalize", "{}", Instant.now().minusSeconds(1));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                inMemoryAsyncJobStore.claimNextJobs(
                    "assetlocalize", 1, "worker-a", Duration.ofSeconds(Long.MAX_VALUE)));

    assertThat(exception).hasMessageContaining("leaseUntil");
  }

  private AsyncJobId completeJob(String queueName, String jobData) {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue(queueName, jobData, Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore.claimNextJobs(queueName, 1, "worker-a", Duration.ofSeconds(5)).get(0);
    assertThat(
            inMemoryAsyncJobStore.markDone(queueName, id, "worker-a", claimed.leaseToken(), null))
        .isTrue();
    return id;
  }

  private AsyncJobId failJob(String queueName, String jobData) {
    AsyncJobId id =
        inMemoryAsyncJobStore.enqueue(queueName, jobData, Instant.now().minusSeconds(1));
    AsyncJobRecord claimed =
        inMemoryAsyncJobStore.claimNextJobs(queueName, 1, "worker-a", Duration.ofSeconds(5)).get(0);
    assertThat(
            inMemoryAsyncJobStore.markFailed(
                queueName, id, "worker-a", claimed.leaseToken(), null, "boom"))
        .isTrue();
    return id;
  }
}
