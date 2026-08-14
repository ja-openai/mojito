package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class IcuMessage2TermUsageExtractorTest {

  private final IcuMessage2TermUsageExtractor extractor = new IcuMessage2TermUsageExtractor();

  @Test
  public void extractsTermUsagesWithIcuSemanticsAndRegexSpans() {
    String message =
        "Deleted {$item :term article=definite count=$count} from {$place :term role=source}.";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(2);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options()).containsExactlyEntriesOf(itemOptions());
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term article=definite count=$count}");
    assertThat(usages.get(1).argument()).isEqualTo("place");
    assertThat(usages.get(1).options()).containsExactlyEntriesOf(Map.of("role", "source"));
  }

  @Test
  public void matchesRegexExtractorForSupportedMessageShapes() {
    RegexTermUsageExtractor regexExtractor = new RegexTermUsageExtractor();
    for (String message : supportedMessageShapes()) {
      assertThat(extractor.extract(message)).isEqualTo(regexExtractor.extract(message));
    }
  }

  @Test
  public void rejectsNullMessage() {
    assertThatThrownBy(() -> extractor.extract(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("message");
  }

  @Test
  public void returnsIcuOptionsInDeterministicNameOrder() {
    String message = "Deleted {$item :term count=$count article=definite}.";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).options().keySet()).containsExactly("article", "count");
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term count=$count article=definite}");
  }

  @Test
  public void attachesScannerSpanToIcuPipeLiteralOption() {
    String message = "Deleted {$item :term role=|source place| article=definite}.";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).options().keySet()).containsExactly("article", "role");
    assertThat(usages.get(0).options()).containsEntry("role", "source place");
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term role=|source place| article=definite}");
  }

  @Test
  public void ignoresNonTermFunctions() {
    assertThat(extractor.extract("Hello {$name :string}.")).isEmpty();
  }

  @Test
  public void extractsTermUsageFromInputDeclaration() {
    String message = ".input {$item :term article=definite}\n{{Deleted {$item}.}}";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options()).containsExactlyEntriesOf(Map.of("article", "definite"));
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term article=definite}");
  }

  @Test
  public void extractsTermUsageFromLocalDeclaration() {
    String message = ".local $term = {$item :term article=definite}\n{{Deleted {$term}.}}";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options()).containsExactlyEntriesOf(Map.of("article", "definite"));
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term article=definite}");
  }

  @Test
  public void extractsTermUsageFromSelectMessageInputDeclaration() {
    String message =
        ".input {$item :term article=definite}\n"
            + ".input {$count :number}\n"
            + ".match $count\n"
            + "1 {{Deleted {$item}.}}\n"
            + "* {{Deleted {$item}.}}";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options()).containsExactlyEntriesOf(Map.of("article", "definite"));
    assertThat(message.substring(usages.get(0).start(), usages.get(0).end()))
        .isEqualTo("{$item :term article=definite}");
  }

  @Test
  public void extractsTermUsagesFromSelectMessageVariants() {
    String message =
        ".input {$count :number}\n"
            + ".match $count\n"
            + "1 {{Deleted {$item :term article=definite count=$count}.}}\n"
            + "* {{Deleted {$item :term article=definite count=$count}.}}";

    List<TermUsageExtractor.TermUsage> usages = extractor.extract(message);

    assertThat(usages).hasSize(2);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options()).containsExactlyEntriesOf(itemOptions());
    assertThat(usages.get(1).argument()).isEqualTo("item");
    assertThat(usages.get(1).options()).containsExactlyEntriesOf(itemOptions());
  }

  @Test
  public void rejectsInvalidMf2BeforeSpanMatching() {
    assertThatThrownBy(() -> extractor.extract("Hello {$name :term"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid MF2 message");
  }

  @Test
  public void rejectsSpanSideTableCountMismatch() {
    IcuMessage2TermUsageExtractor mismatchedExtractor =
        new IcuMessage2TermUsageExtractor(message -> List.of());

    assertThatThrownBy(() -> mismatchedExtractor.extract("Deleted {$item :term article=definite}."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ICU term usage count 1 does not match span side-table count 0");
  }

  @Test
  public void rejectsSpanSideTableSemanticMismatch() {
    IcuMessage2TermUsageExtractor mismatchedExtractor =
        new IcuMessage2TermUsageExtractor(
            message ->
                List.of(
                    new TermUsageExtractor.TermUsage(
                        "place", Map.of("article", "definite"), 8, 39)));

    assertThatThrownBy(() -> mismatchedExtractor.extract("Deleted {$item :term article=definite}."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ICU term usage does not match span side-table usage at index 0")
        .hasMessageContaining("semantic=SemanticTermUsage[argument=item")
        .hasMessageContaining("span=TermUsage[argument=place");
  }

  private Map<String, String> itemOptions() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("article", "definite");
    options.put("count", "$count");
    return options;
  }

  private List<String> supportedMessageShapes() {
    return List.of(
        "Deleted {$item :term article=definite count=$count}.",
        ".input {$item :term article=definite}\n{{Deleted {$item}.}}",
        ".local $term = {$item :term article=definite}\n{{Deleted {$term}.}}",
        ".input {$count :number}\n"
            + ".match $count\n"
            + "1 {{Deleted {$item :term article=definite count=$count}.}}\n"
            + "* {{Deleted {$item :term article=definite count=$count}.}}");
  }
}
