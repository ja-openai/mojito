package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.Instant;
import org.junit.Test;

public class AsyncJobRecordTest {

  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  public void acceptsValidRunningAndTerminalRecords() {
    AsyncJobRecord running =
        new AsyncJobRecord(
            new AsyncJobId("1"),
            "assetlocalize",
            AsyncJobStatus.RUNNING,
            NOW.minusSeconds(1),
            NOW.plusSeconds(30),
            "worker-a",
            "lease-token",
            "{}",
            1,
            null,
            NOW.minusSeconds(10),
            NOW,
            false);
    AsyncJobRecord done =
        new AsyncJobRecord(
            new AsyncJobId("2"),
            "assetlocalize",
            AsyncJobStatus.DONE,
            NOW.minusSeconds(1),
            null,
            null,
            null,
            "{}",
            1,
            null,
            NOW.minusSeconds(10),
            NOW,
            false);

    assertThat(running.status()).isEqualTo(AsyncJobStatus.RUNNING);
    assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
  }

  @Test
  public void rejectsRunningRecordsWithoutCompleteLeaseOwner() {
    assertThrows(
        NullPointerException.class,
        () -> record(AsyncJobStatus.RUNNING, null, "worker-a", "lease-token", 1));
    IllegalArgumentException blankWorker =
        assertThrows(
            IllegalArgumentException.class,
            () -> record(AsyncJobStatus.RUNNING, NOW.plusSeconds(30), " ", "lease-token", 1));
    IllegalArgumentException blankToken =
        assertThrows(
            IllegalArgumentException.class,
            () -> record(AsyncJobStatus.RUNNING, NOW.plusSeconds(30), "worker-a", " ", 1));

    assertThat(blankWorker).hasMessageContaining("workerId must not be blank");
    assertThat(blankToken).hasMessageContaining("leaseToken must not be blank");
  }

  @Test
  public void rejectsTerminalRecordsWithLeaseOwner() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> record(AsyncJobStatus.FAILED, NOW.plusSeconds(30), "worker-a", "lease-token", 1));

    assertThat(exception).hasMessageContaining("only running async jobs can have a lease owner");
  }

  @Test
  public void rejectsNegativeAttemptCount() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> record(AsyncJobStatus.DONE, null, null, null, -1));

    assertThat(exception).hasMessageContaining("attemptCount must be >= 0");
  }

  private AsyncJobRecord record(
      AsyncJobStatus status,
      Instant leaseUntil,
      String workerId,
      String leaseToken,
      int attemptCount) {
    return new AsyncJobRecord(
        new AsyncJobId("1"),
        "assetlocalize",
        status,
        NOW.minusSeconds(1),
        leaseUntil,
        workerId,
        leaseToken,
        "{}",
        attemptCount,
        null,
        NOW.minusSeconds(10),
        NOW,
        false);
  }
}
