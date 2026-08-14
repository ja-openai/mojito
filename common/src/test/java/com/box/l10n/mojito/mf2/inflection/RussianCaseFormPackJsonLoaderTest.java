package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.RussianCaseFormPackJsonLoader.RussianCaseFormPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class RussianCaseFormPackJsonLoaderTest {

  private final RussianCaseFormPackJsonLoader loader = new RussianCaseFormPackJsonLoader();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new RussianCaseFormPackJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("objectMapper");
  }

  @Test
  public void loadsGeneratedFixtureAndConvertsToCompiledTermPack() {
    RussianCaseFormPack pack = loader.load(russianCaseFormPackFixtureJson());

    assertThat(pack.schema()).isEqualTo("mojito-mf2-inflection/ru-case-form-sample-pack/v0");
    assertThat(pack.locale()).isEqualTo("ru");
    assertThat(pack.description()).contains("unambiguous");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels())
        .containsExactly(
            "inflection/resources/org/unicode/inflection/dictionary/dictionary_ru.lst",
            "inflection/resources/org/unicode/inflection/dictionary/inflectional_ru.xml");
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("0cec5900694f7a7e1361ab95b3e47bca9114079b6ab8b48531c355eb8862bfeb");
    assertThat(pack.provenance().sources().get(1).sha256())
        .isEqualTo("8fade258e582840840daa29bd6971a61a090feb33268b303582c10bd4b08b00a");

    assertThat(pack.summary().candidateTerms()).isEqualTo(68169);
    assertThat(pack.summary().exportedTerms()).isEqualTo(3);
    assertThat(pack.summary().formRows()).isEqualTo(36);
    assertThat(pack.summary().strings()).isEqualTo(44);
    assertThat(pack.summary().stringPoolBytes()).isEqualTo(792);
    assertThat(pack.summary().jsonBytes()).isEqualTo(6756);
    assertThat(pack.summary().binaryLowerBoundBytes()).isEqualTo(1284);
    assertThat(pack.summary().skippedTerms())
        .containsEntry("conflicting-form-key", 26995)
        .containsEntry("duplicate-term-id", 2)
        .containsEntry("not-nominative", 702128)
        .containsEntry("unsupported-pattern-part-of-speech", 3);

    assertThat(pack.findTerm("ru.case.кошка")).isPresent();
    assertThat(pack.findTerm("ru.case.кошка").orElseThrow().animacy()).isEqualTo("animate");
    assertThat(pack.findTerm("ru.case.кошка").orElseThrow().forms()).hasSize(12);
    assertThat(pack.findTerm("ru.case.ресторан")).isPresent();
    assertThat(pack.findTerm("ru.case.аббатство")).isPresent();

    CompiledTermPack compiledPack = pack.toCompiledTermPack();
    assertThat(compiledPack.locale()).isEqualTo("ru");
    assertThat(compiledPack.provenance().sourceLabels()).hasSize(2);
    assertThat(compiledPack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(1284);

    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(compiledPack);
    assertThat(
            renderer.renderMessage(
                "Удалено {$item :term case=accusative count=$count}.",
                Map.of("item", "ru.case.кошка"),
                Map.of("count", "1")))
        .isEqualTo("Удалено кошку.");
    assertThat(
            renderer.renderMessage(
                "Удалено {$item :term case=accusative count=$count}.",
                Map.of("item", "ru.case.кошка"),
                Map.of("count", "2")))
        .isEqualTo("Удалено кошек.");
    assertThat(
            renderer.renderMessage(
                "Нет {$item :term case=genitive count=$count}.",
                Map.of("item", "ru.case.ресторан"),
                Map.of("count", "2")))
        .isEqualTo("Нет ресторанов.");
    assertThat(
            renderer.renderMessage(
                "В {$item :term case=prepositional count=$count}.",
                Map.of("item", "ru.case.аббатство"),
                Map.of("count", "1")))
        .isEqualTo("В аббатстве.");
  }

  @Test
  public void loadedCaseFormPackCollectionsAreImmutable() {
    RussianCaseFormPack pack = loader.load(russianCaseFormPackFixtureJson());

    assertThatThrownBy(() -> pack.strings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.terms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.terms().get(0).forms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.provenance().sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.summary().skippedTerms().put("not-nominative", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsUnsupportedFormKey() {
    String json =
        russianCaseFormPackFixtureJson()
            .replace("\"prepositional.singular\"", "\"ablative.singular\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Russian case-form key");
  }

  @Test
  public void rejectsPatternRowsForRussianV0() {
    String json =
        russianCaseFormPackFixtureJson()
            .replaceFirst("\"kind\": \"literal\"", "\"kind\": \"pattern\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be literal");
  }

  @Test
  public void rejectsUnsupportedSkippedTermReason() {
    String json =
        russianCaseFormPackFixtureJson().replace("\"duplicate-term-id\"", "\"ambiguous-variant\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Russian skippedTerms key");
  }

  @Test
  public void rejectsNonIntegralStringIndex() {
    String json = russianCaseFormPackFixtureJson().replaceFirst("\"id\": 0", "\"id\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: id");
  }

  @Test
  public void rejectsNonIntegralSummaryCount() {
    String json =
        russianCaseFormPackFixtureJson().replace("\"formRows\": 36", "\"formRows\": 36.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: formRows");
  }

  @Test
  public void rejectsNonIntegralSkippedTermCount() {
    String json =
        russianCaseFormPackFixtureJson()
            .replace("\"duplicate-term-id\": 2", "\"duplicate-term-id\": 2.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-negative Russian count: skippedTerms");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json =
        russianCaseFormPackFixtureJson().replace("\"byteSize\": 76320564", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsSummaryMismatch() {
    String json =
        russianCaseFormPackFixtureJson().replace("\"exportedTerms\": 3", "\"exportedTerms\": 4");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exported term count does not match");
  }

  @Test
  public void rejectsIncoherentBinaryEstimate() {
    String json =
        russianCaseFormPackFixtureJson()
            .replace("\"binaryLowerBoundBytes\": 1284", "\"binaryLowerBoundBytes\": 1285");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binary lower-bound estimate");
  }

  @Test
  public void rejectsJsonByteCountMismatch() {
    String json =
        russianCaseFormPackFixtureJson().replace("\"jsonBytes\": 6756", "\"jsonBytes\": 6757");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON byte count");
  }

  private String russianCaseFormPackFixtureJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/ru_case_form_pack_fixture.json");
  }

  private String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
