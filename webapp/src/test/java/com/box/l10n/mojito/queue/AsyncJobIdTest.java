package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AsyncJobIdTest {

  @Test
  public void acceptsPositiveNumericIds() {
    assertThat(new AsyncJobId("1").value()).isEqualTo("1");
    assertThat(new AsyncJobId(String.valueOf(Long.MAX_VALUE)).value())
        .isEqualTo(String.valueOf(Long.MAX_VALUE));
  }

  @Test
  public void rejectsBlankNonNumericAndNonPositiveIds() {
    assertInvalidId(" ");
    assertInvalidId("abc");
    assertInvalidId("1.5");
    assertInvalidId("0");
    assertInvalidId("-1");
    assertInvalidId("9223372036854775808");
  }

  private void assertInvalidId(String value) {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> new AsyncJobId(value));
    assertThat(exception).hasMessageContaining("Async job id");
  }
}
