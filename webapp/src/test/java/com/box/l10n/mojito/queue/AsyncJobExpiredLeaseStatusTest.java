package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.Test;

public class AsyncJobExpiredLeaseStatusTest {

  @Test
  public void rejectsInvalidCountsAndTimestampCombinations() {
    Instant now = Instant.parse("2026-05-23T00:00:00Z");

    assertThatThrownBy(() -> new AsyncJobExpiredLeaseStatus(-1, null, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AsyncJobExpiredLeaseStatus(0, now, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AsyncJobExpiredLeaseStatus(1, null, now))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new AsyncJobExpiredLeaseStatus(1, now.plusMillis(1), now))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
