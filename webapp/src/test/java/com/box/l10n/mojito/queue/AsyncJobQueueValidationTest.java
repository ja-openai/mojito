package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

public class AsyncJobQueueValidationTest {

  @Test
  public void plusDurationRejectsInstantOverflowWithFieldContext() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.plusDuration(
                    "leaseUntil", Instant.now(), Duration.ofSeconds(Long.MAX_VALUE)));

    assertThat(exception).hasMessageContaining("leaseUntil");
  }

  @Test
  public void validateDatabaseTimestampRejectsPortableMysqlDatetimeBounds() {
    IllegalArgumentException tooEarly =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.validateDatabaseTimestamp(
                    "availableAt", AsyncJobQueueValidation.DATABASE_TIMESTAMP_MIN.minusNanos(1)));
    IllegalArgumentException tooLate =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.validateDatabaseTimestamp(
                    "availableAt", AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX.plusNanos(1)));

    assertThat(tooEarly).hasMessageContaining("availableAt must be between");
    assertThat(tooLate).hasMessageContaining("availableAt must be between");
  }

  @Test
  public void plusDurationWithinDatabaseTimestampRangeRejectsUnstorableResult() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobQueueValidation.plusDurationWithinDatabaseTimestampRange(
                    "availableAt",
                    AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX,
                    Duration.ofMillis(1)));

    assertThat(exception).hasMessageContaining("availableAt must be between");
  }
}
