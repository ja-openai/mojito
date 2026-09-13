package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Shared exact-identity contract, including databases with case-insensitive collations. */
final class AsyncJobStoreIdentityContract {

  private static final Duration LEASE = Duration.ofMinutes(1);

  private AsyncJobStoreIdentityContract() {}

  static void assertQueueIsolation(AsyncJobStore store) {
    String queue = "Identity-" + UUID.randomUUID();
    String otherQueue = queue.toLowerCase(Locale.ROOT);
    AsyncJobId otherId = store.enqueueNow(otherQueue, "other");
    AsyncJobId id = store.enqueueNow(queue, "selected");
    AsyncJobRecord beforeOther = store.getByIds(List.of(otherId)).get(0);
    AsyncJobRecord beforeSelected = store.getByIds(List.of(id)).get(0);

    assertThat(store.countByStatus(queue))
        .containsExactly(new AsyncJobStatusCount(AsyncJobStatus.QUEUED, 1));
    assertThat(store.readyStatus(queue).count()).isEqualTo(1);
    assertThat(store.readyStatus(queue).oldestAvailableAt())
        .isEqualTo(beforeSelected.availableAt());
    assertThat(store.findByStatus(queue, AsyncJobStatus.QUEUED, 10))
        .extracting(AsyncJobRecord::id)
        .containsExactly(id);
    AsyncJobRecord claimed = store.claimNextJobs(queue, 1, "Worker", LEASE).get(0);
    assertThat(claimed.id()).isEqualTo(id);
    assertThat(store.getByIds(List.of(otherId))).containsExactly(beforeOther);
    assertRejectedOwner(store, claimed, otherQueue, claimed.workerId(), claimed.leaseToken());
    assertThat(store.markDone(queue, id, claimed.workerId(), claimed.leaseToken(), null)).isTrue();
    assertThat(store.claimNextJobs(otherQueue, 1, "Worker", LEASE))
        .extracting(AsyncJobRecord::id)
        .containsExactly(otherId);
  }

  static void assertMaintenanceIsolation(AsyncJobStore store) {
    for (AsyncJobStatus status : List.of(AsyncJobStatus.FAILED, AsyncJobStatus.DONE)) {
      assertMaintenanceIsolation(store, status);
    }
  }

  private static void assertMaintenanceIsolation(AsyncJobStore store, AsyncJobStatus status) {
    String queue = "Maintenance-" + UUID.randomUUID();
    String otherQueue = queue.toLowerCase(Locale.ROOT);
    AsyncJobRecord other = terminalJob(store, otherQueue, status);
    AsyncJobRecord selected = terminalJob(store, queue, status);

    if (status == AsyncJobStatus.FAILED) {
      assertThat(store.requeueFailed(otherQueue, selected.id(), Instant.now(), "wrong")).isFalse();
      assertThat(store.requeueFailedNow(otherQueue, selected.id(), "wrong")).isFalse();
      assertThat(store.getByIds(List.of(selected.id()))).containsExactly(selected);
      assertThat(store.requeueFailedNow(queue, selected.id(), "replayed")).isTrue();
      AsyncJobRecord replayed = store.claimNextJobs(queue, 1, "Worker", LEASE).get(0);
      assertThat(replayed.id()).isEqualTo(selected.id());
      assertThat(
              store.markFailed(
                  queue, replayed.id(), "Worker", replayed.leaseToken(), null, "error"))
          .isTrue();
    }

    assertThat(store.deleteTerminalJobs(queue, status, Instant.now().plusSeconds(60), 1))
        .isEqualTo(1);
    assertThat(store.getByIds(List.of(selected.id(), other.id()))).containsExactly(other);
    assertThat(store.deleteTerminalJobsOlderThan(queue, status, Duration.ofMillis(1), 1)).isZero();
    assertThat(store.getByIds(List.of(other.id()))).containsExactly(other);
  }

  static void assertExpiredQueueIsolation(AsyncJobStore store) throws InterruptedException {
    String queue = "Expiry-" + UUID.randomUUID();
    String otherQueue = queue.toLowerCase(Locale.ROOT);
    AsyncJobId otherId = store.enqueueNow(otherQueue, "other");
    store.claimNextJobs(otherQueue, 1, "Worker", Duration.ofMillis(1));
    awaitExpiredLease(store, otherQueue);
    AsyncJobRecord beforeOther = store.getByIds(List.of(otherId)).get(0);
    assertThat(store.expiredLeaseStatus(queue).count()).isZero();
    assertThat(store.claimNextJobs(queue, 1, "Worker", LEASE)).isEmpty();

    AsyncJobId id = store.enqueueNow(queue, "selected");
    store.claimNextJobs(queue, 1, "Worker", Duration.ofMillis(1));
    awaitExpiredLease(store, queue);
    assertThat(store.expiredLeaseStatus(queue).oldestLeaseUntil())
        .isEqualTo(store.getByIds(List.of(id)).get(0).leaseUntil());
    assertThat(store.claimNextJobs(queue, 1, "Worker", LEASE))
        .extracting(AsyncJobRecord::id)
        .containsExactly(id);
    assertThat(store.getByIds(List.of(otherId))).containsExactly(beforeOther);
    assertThat(store.claimNextJobs(otherQueue, 1, "Worker", LEASE))
        .extracting(AsyncJobRecord::id)
        .containsExactly(otherId);
  }

  private static void awaitExpiredLease(AsyncJobStore store, String queue)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (store.expiredLeaseStatus(queue).count() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(store.expiredLeaseStatus(queue).count()).isEqualTo(1);
  }

  static void assertLeaseOwnerIsolation(AsyncJobStore store) {
    String queue = "Owner-" + UUID.randomUUID();
    String worker = "Worker-\u00e9";
    AsyncJobId id = store.enqueueNow(queue, "selected");
    AsyncJobRecord claimed = store.claimNextJobs(queue, 1, worker, LEASE).get(0);
    for (String otherWorker :
        List.of("worker-\u00e9", worker + " ", "Worker-e", "Worker-e\u0301")) {
      assertRejectedOwner(store, claimed, queue, otherWorker, claimed.leaseToken());
    }
    assertRejectedOwner(store, claimed, queue, worker, claimed.leaseToken() + " ");
    String upperToken = claimed.leaseToken().toUpperCase(Locale.ROOT);
    if (!upperToken.equals(claimed.leaseToken())) {
      assertRejectedOwner(store, claimed, queue, worker, upperToken);
    }
    assertThat(store.heartbeat(queue, id, worker, claimed.leaseToken(), LEASE)).isTrue();
    assertThat(store.markDone(queue, id, worker, claimed.leaseToken(), "done")).isTrue();
  }

  private static AsyncJobRecord terminalJob(
      AsyncJobStore store, String queue, AsyncJobStatus status) {
    AsyncJobId id = store.enqueueNow(queue, "input");
    AsyncJobRecord claim = store.claimNextJobs(queue, 1, "Worker", LEASE).get(0);
    assertThat(
            status == AsyncJobStatus.FAILED
                ? store.markFailed(queue, id, "Worker", claim.leaseToken(), null, "error")
                : store.markDone(queue, id, "Worker", claim.leaseToken(), null))
        .isTrue();
    return store.getByIds(List.of(id)).get(0);
  }

  private static void assertRejectedOwner(
      AsyncJobStore store, AsyncJobRecord claimed, String queue, String worker, String token) {
    AsyncJobRecord before = store.getByIds(List.of(claimed.id())).get(0);
    assertThat(store.heartbeat(queue, claimed.id(), worker, token, LEASE))
        .as("heartbeat identity")
        .isFalse();
    assertThat(store.markDone(queue, claimed.id(), worker, token, "wrong"))
        .as("done identity")
        .isFalse();
    assertThat(store.requeue(queue, claimed.id(), worker, token, Instant.now(), "wrong", "error"))
        .as("requeue identity")
        .isFalse();
    assertThat(
            store.requeueAfter(queue, claimed.id(), worker, token, Duration.ZERO, "wrong", "error"))
        .as("relative requeue identity")
        .isFalse();
    assertThat(store.markFailed(queue, claimed.id(), worker, token, "wrong", "error"))
        .as("failed identity")
        .isFalse();
    assertThat(store.getByIds(List.of(claimed.id()))).containsExactly(before);
  }
}
