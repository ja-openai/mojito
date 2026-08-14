package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RegexTermUsageExtractorTest {

  private final RegexTermUsageExtractor extractor = new RegexTermUsageExtractor();

  @Test
  public void extractsTermUsagesInSourceOrderWithStableSpans() {
    String message =
        "Deleted {$item :term article=definite count=$count} from {$place :term role=\"source\"}.";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(2);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options()).containsExactlyEntriesOf(orderedOptions());
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term article=definite count=$count}");

    assertThat(usages.get(1).argument()).isEqualTo("place");
    assertThat(usages.get(1).options()).containsExactlyEntriesOf(Map.of("role", "source"));
    assertThat(message.substring(usages.get(1).start(), usages.get(1).end()))
        .isEqualTo("{$place :term role=\"source\"}");
  }

  @Test
  public void spansUseJavaUtf16CodeUnitOffsets() {
    String message = "\uD83D\uDE42 {$item :term article=definite}";

    TermUsageExtractor.TermUsage usage = extractor.extract(message).get(0);

    assertThat(usage.span()).isEqualTo(new SourceSpan(3, message.length()));
    assertThat(message.substring(usage.start(), usage.end()))
        .isEqualTo("{$item :term article=definite}");
  }

  @Test
  public void scansQuotedOptionValuesWithWhitespaceAndBraces() {
    String message = "Deleted {$item :term note=\"source {role}\" article=definite}.";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options())
        .containsExactly(entry("note", "source {role}"), entry("article", "definite"));
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term note=\"source {role}\" article=definite}");
  }

  @Test
  public void rejectsNullMessage() {
    assertThatThrownBy(() -> extractor.extract(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("message");
  }

  @Test
  public void termUsageRejectsInvalidParserOutput() {
    assertThatThrownBy(() -> new TermUsageExtractor.TermUsage("", Map.of(), 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("argument is required");
    assertThatThrownBy(() -> new TermUsageExtractor.TermUsage("item", Map.of(), 3, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid source span");
  }

  @Test
  public void rejectsDuplicateOptions() {
    assertThatThrownBy(() -> extractor.extract("{$item :term article=definite article=indefinite}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate term option: article");
  }

  @Test
  public void rejectsBareOptions() {
    assertThatThrownBy(() -> extractor.extract("{$item :term article}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Term option must use key=value syntax: article");
  }

  @Test
  public void rejectsBlankOptionValues() {
    assertThatThrownBy(() -> extractor.extract("{$item :term article=}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Term option value must not be blank: article");
  }

  @Test
  public void rejectsInvalidOptionKeys() {
    assertThatThrownBy(() -> extractor.extract("{$item :term @article=definite}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid term option key: @article");
  }

  @Test
  public void rejectsUnbalancedQuotedOptionValues() {
    assertThatThrownBy(() -> extractor.extract("{$item :term article=\"definite}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unbalanced quoted value for term option: article");
  }

  @Test
  public void rejectsSingleCharacterQuotedOptionValues() {
    assertThatThrownBy(() -> extractor.extract("{$item :term article=\"}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unbalanced quoted value for term option: article");
  }

  private Map<String, String> orderedOptions() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("article", "definite");
    options.put("count", "$count");
    return options;
  }
}
