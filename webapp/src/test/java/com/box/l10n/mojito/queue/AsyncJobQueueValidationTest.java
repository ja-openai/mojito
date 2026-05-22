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

  @Test
  public void truncateLastErrorBoundsPersistedErrorSummary() {
    String longError = "x".repeat(AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH + 1);

    assertThat(AsyncJobQueueValidation.truncateLastError(null)).isNull();
    assertThat(AsyncJobQueueValidation.truncateLastError("short")).isEqualTo("short");
    assertThat(AsyncJobQueueValidation.truncateLastError(longError))
        .hasSize(AsyncJobQueueValidation.LAST_ERROR_MAX_LENGTH);
  }

  @Test
  public void validateTerminalStatusRejectsNonTerminalStatuses() {
    assertThat(AsyncJobQueueValidation.validateTerminalStatus(AsyncJobStatus.DONE))
        .isEqualTo(AsyncJobStatus.DONE);
    assertThat(AsyncJobQueueValidation.validateTerminalStatus(AsyncJobStatus.FAILED))
        .isEqualTo(AsyncJobStatus.FAILED);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> AsyncJobQueueValidation.validateTerminalStatus(AsyncJobStatus.QUEUED));

    assertThat(exception).hasMessageContaining("status must be terminal");
  }

  @Test
  public void validateStoreQueryLimitRejectsExcessiveLimit() {
    assertThat(AsyncJobQueueValidation.validateStoreQueryLimit("limit", 1)).isEqualTo(1);
    assertThat(
            AsyncJobQueueValidation.validateStoreQueryLimit(
                "limit", AsyncJobQueueValidation.STORE_QUERY_LIMIT_MAX))
        .isEqualTo(AsyncJobQueueValidation.STORE_QUERY_LIMIT_MAX);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> AsyncJobQueueValidation.validateStoreQueryLimit("limit", 0)))
        .hasMessageContaining("limit must be > 0");
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    AsyncJobQueueValidation.validateStoreQueryLimit(
                        "limit", AsyncJobQueueValidation.STORE_QUERY_LIMIT_MAX + 1)))
        .hasMessageContaining("limit must be <=");
  }
}
