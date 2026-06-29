package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;

public class TermUsageOptionsContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void matchesSharedCrossRuntimeOptionContract() {
    JsonNode root =
        objectMapper.readTreeUnchecked(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/term_usage_option_contract_fixture.json"));

    assertThat(root.path("schema").asText())
        .isEqualTo("mojito-mf2-inflection/term-usage-option-contract/v0");
    assertThat(sorted(TermUsageOptions.supportedOptions())).isEqualTo(textArray(root, "options"));
    assertThat(sorted(TermUsageOptions.supportedArticles())).isEqualTo(textArray(root, "articles"));
    assertThat(sorted(TermUsageOptions.supportedCases())).isEqualTo(textArray(root, "cases"));
    assertThat(sorted(TermUsageOptions.supportedDefiniteness()))
        .isEqualTo(textArray(root, "definiteness"));
    assertThat(sorted(TermUsageOptions.supportedNumbers())).isEqualTo(textArray(root, "numbers"));
    assertThat(sorted(TermUsageOptions.supportedPrepositions()))
        .isEqualTo(textArray(root, "prepositions"));

    JsonNode combinations = root.path("prepositionArticleCombinations");
    assertSupportedCombinations(null, textArray(combinations, "articleOnly"));
    for (String preposition : textArray(root, "prepositions")) {
      assertSupportedCombinations(preposition, textArray(combinations, preposition));
    }
  }

  private void assertSupportedCombinations(String preposition, List<String> supportedArticles) {
    for (String article : TermUsageOptions.supportedArticles()) {
      assertThat(TermUsageOptions.isPortuguesePrepositionArticleCombination(preposition, article))
          .as("%s + %s", preposition, article)
          .isEqualTo(supportedArticles.contains(article));
    }
  }

  private List<String> textArray(JsonNode root, String field) {
    JsonNode node = root.path(field);
    assertThat(node.isArray()).as("Expected array field: %s", field).isTrue();
    List<String> values = new ArrayList<>();
    for (JsonNode value : node) {
      assertThat(value.isTextual()).as("Expected text value in field: %s", field).isTrue();
      values.add(value.asText());
    }
    return values;
  }

  private List<String> sorted(Collection<String> values) {
    return values.stream().sorted().toList();
  }

  private String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      if (inputStream == null) {
        throw new IllegalArgumentException("Missing test resource: " + path);
      }
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
