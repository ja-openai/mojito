package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.Instant;
import org.junit.Test;

public class AsyncJobHandlerResultTest {

  @Test
  public void factoriesCreateValidResults() {
    Instant availableAt = Instant.parse("2030-01-01T00:00:00Z");

    assertThat(AsyncJobHandlerResult.done().action()).isEqualTo(AsyncJobHandlerResult.Action.DONE);
    assertThat(AsyncJobHandlerResult.done("{\"done\":true}").jobData())
        .isEqualTo("{\"done\":true}");
    assertThat(AsyncJobHandlerResult.requeue(availableAt).availableAt()).isEqualTo(availableAt);
    assertThat(AsyncJobHandlerResult.requeue(null, "{\"retry\":true}").jobData())
        .isEqualTo("{\"retry\":true}");
  }

  @Test
  public void rejectsMissingAction() {
    assertThrows(NullPointerException.class, () -> new AsyncJobHandlerResult(null, null, null));
  }

  @Test
  public void rejectsDoneResultsWithAvailableAt() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AsyncJobHandlerResult(
                    AsyncJobHandlerResult.Action.DONE,
                    Instant.parse("2030-01-01T00:00:00Z"),
                    null));

    assertThat(exception).hasMessageContaining("availableAt must be null");
  }

  @Test
  public void rejectsRequeueAvailableAtOutsideDatabaseTimestampBounds() {
    IllegalArgumentException tooEarly =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobHandlerResult.requeue(
                    AsyncJobQueueValidation.DATABASE_TIMESTAMP_MIN.minusNanos(1)));
    IllegalArgumentException tooLate =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AsyncJobHandlerResult.requeue(
                    AsyncJobQueueValidation.DATABASE_TIMESTAMP_MAX.plusNanos(1)));

    assertThat(tooEarly).hasMessageContaining("availableAt must be between");
    assertThat(tooLate).hasMessageContaining("availableAt must be between");
  }
}
