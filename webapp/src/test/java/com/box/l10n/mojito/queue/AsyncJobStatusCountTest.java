package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AsyncJobStatusCountTest {

  @Test
  public void acceptsNonNegativeCounts() {
    AsyncJobStatusCount queued = new AsyncJobStatusCount(AsyncJobStatus.QUEUED, 0);
    AsyncJobStatusCount running = new AsyncJobStatusCount(AsyncJobStatus.RUNNING, 12);

    assertThat(queued.count()).isZero();
    assertThat(running.count()).isEqualTo(12);
  }

  @Test
  public void rejectsNullStatusAndNegativeCounts() {
    assertThrows(NullPointerException.class, () -> new AsyncJobStatusCount(null, 1));
    IllegalArgumentException negativeCount =
        assertThrows(
            IllegalArgumentException.class,
            () -> new AsyncJobStatusCount(AsyncJobStatus.FAILED, -1));

    assertThat(negativeCount).hasMessageContaining("count must be >= 0");
  }
}
