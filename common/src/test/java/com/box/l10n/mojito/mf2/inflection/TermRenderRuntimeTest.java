package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.Test;

public class TermRenderRuntimeTest {

  @Test
  public void rendersPatternVariables() {
    assertThat(
            TermRenderRuntime.renderPattern(
                "Owner {$owner}: {$item}", Map.of("owner", "my", "item", "book")))
        .isEqualTo("Owner my: book");
  }

  @Test
  public void rejectsMissingPatternVariables() {
    assertThatThrownBy(() -> TermRenderRuntime.renderPattern("Owner {$owner}", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing pattern variable: owner");
  }

  @Test
  public void derivesNumberFromCountReference() {
    assertThat(TermRenderRuntime.numberFromCountReference(null, Map.of())).isEqualTo("singular");
    assertThat(TermRenderRuntime.numberFromCountReference("$count", Map.of("count", "1")))
        .isEqualTo("singular");
    assertThat(TermRenderRuntime.numberFromCountReference("$count", Map.of("count", "2")))
        .isEqualTo("plural");
  }

  @Test
  public void rejectsMissingAndInvalidCounts() {
    assertThatThrownBy(() -> TermRenderRuntime.numberFromCountReference("$count", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing count variable: count");
    assertThatThrownBy(
            () -> TermRenderRuntime.numberFromCountReference("$count", Map.of("count", "many")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Count variable must be numeric");
  }

  @Test
  public void supportsCustomMissingVariableMessages() {
    assertThatThrownBy(
            () ->
                TermRenderRuntime.numberFromVariable(
                    "itemCount", Map.of(), "Missing Hindi pronoun agreeWithCount variable: "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing Hindi pronoun agreeWithCount variable: itemCount");
  }
}
