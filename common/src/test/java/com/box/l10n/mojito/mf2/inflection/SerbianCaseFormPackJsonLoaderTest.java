package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.SerbianCaseFormPackJsonLoader.SerbianCaseFormPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class SerbianCaseFormPackJsonLoaderTest {

  private final SerbianCaseFormPackJsonLoader loader = new SerbianCaseFormPackJsonLoader();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new SerbianCaseFormPackJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("objectMapper");
  }

  @Test
  public void rejectsNullJson() {
    assertThatThrownBy(() -> loader.load((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("json");
  }

  @Test
  public void loadsCaseFormPackAndConvertsToCompiledTermPack() {
    SerbianCaseFormPack pack = loader.load(serbianCaseFormPackJson());

    assertThat(pack.locale()).isEqualTo("sr");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.summary().exportedTerms()).isEqualTo(1);
    assertThat(pack.findTerm("sr.case.ma\u010dka")).isPresent();
    assertThat(pack.findTerm("sr.case.ma\u010dka").orElseThrow().forms()).hasSize(4);

    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack.toCompiledTermPack());
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "1")))
        .isEqualTo("Obrisano je ma\u010dku.");
  }

  @Test
  public void loadedCaseFormPackCollectionsAreImmutable() {
    SerbianCaseFormPack pack = loader.load(serbianCaseFormPackJson());

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
  public void loadsPythonGeneratedFixtureAndRendersSerbianCases() {
    SerbianCaseFormPack pack =
        loader.load(
            readResource("com/box/l10n/mojito/mf2/inflection/sr_case_form_pack_fixture.json"));

    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("12d5c2e0becdf55bbdd33b14fd816d5f5b78c1f8a60bb0596914be3a34a6f282");
    assertThat(pack.summary().candidateTerms()).isEqualTo(159);
    assertThat(pack.summary().exportedTerms()).isEqualTo(12);
    assertThat(pack.summary().formRows()).isEqualTo(105);
    assertThat(pack.summary().skippedTerms())
        .containsEntry("not-nominative", 755)
        .containsEntry("unsupported-part-of-speech", 110)
        .containsEntry("missing-or-ambiguous-gender", 6);
    assertThat(pack.summary().candidateTerms() + sumValues(pack.summary().skippedTerms()))
        .isEqualTo(1140);
    assertThat(pack.findTerm("sr.case.ma\u010dka")).isPresent();

    CompiledTermPack compiledPack = pack.toCompiledTermPack();
    assertThat(compiledPack.locale()).isEqualTo("sr");
    assertThat(compiledPack.provenance().sourceLabels())
        .containsExactlyElementsOf(pack.provenance().sourceLabels());
    assertThat(compiledPack.provenance().sources().get(0).sha256())
        .isEqualTo("12d5c2e0becdf55bbdd33b14fd816d5f5b78c1f8a60bb0596914be3a34a6f282");
    assertThat(compiledPack.sizeEstimates().binaryLowerBoundBytes().stringPoolBytes())
        .isEqualTo(1399);
    assertThat(compiledPack.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(240);
    assertThat(compiledPack.sizeEstimates().binaryLowerBoundBytes().formRowBytes()).isEqualTo(1260);
    assertThat(compiledPack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(2899);

    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(compiledPack);
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "1")))
        .isEqualTo("Obrisano je ma\u010dku.");
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "2")))
        .isEqualTo("Obrisano je ma\u010dke.");
    assertThat(
            renderer.renderMessage(
                "Daj {$item :term case=dative}.", Map.of("item", "sr.case.ma\u010dka"), Map.of()))
        .isEqualTo("Daj ma\u010dki.");
  }

  @Test
  public void rejectsUnsupportedFormKey() {
    String json =
        serbianCaseFormPackJson().replace("\"accusative.singular\"", "\"ablative.singular\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Serbian case-form key");
  }

  @Test
  public void rejectsNonIntegralStringIndex() {
    String json = serbianCaseFormPackJson().replaceFirst("\"id\": 0", "\"id\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: id");
  }

  @Test
  public void rejectsNonIntegralSummaryInt() {
    String json = serbianCaseFormPackJson().replace("\"formRows\": 4", "\"formRows\": 4.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: formRows");
  }

  @Test
  public void rejectsUnsupportedSkippedTermReason() {
    String json = serbianCaseFormPackJson().replace("\"not-nominative\"", "\"ambiguous-variant\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Serbian skippedTerms key");
  }

  @Test
  public void rejectsNonIntegralSkippedTermCount() {
    String json =
        serbianCaseFormPackJson().replace("\"not-nominative\": 0", "\"not-nominative\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-negative Serbian count: skippedTerms");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json = serbianCaseFormPackJson().replace("\"byteSize\": 81741", "\"byteSize\": 81741.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsSummaryMismatch() {
    String json = serbianCaseFormPackJson().replace("\"exportedTerms\": 1", "\"exportedTerms\": 2");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exported term count does not match");
  }

  @Test
  public void rejectsProvenanceSourceLabelCountMismatch() {
    String json =
        serbianCaseFormPackJson()
            .replace("\"sourceLabels\": [", "\"sourceLabels\": [\n              \"extra-source\",");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source label count");
  }

  @Test
  public void rejectsIncoherentStringPoolByteEstimate() {
    String json =
        serbianCaseFormPackJson().replace("\"stringPoolBytes\": 122", "\"stringPoolBytes\": 123");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("string-pool byte estimate");
  }

  @Test
  public void rejectsIncoherentBinaryLowerBoundEstimate() {
    String json =
        serbianCaseFormPackJson()
            .replace("\"binaryLowerBoundBytes\": 190", "\"binaryLowerBoundBytes\": 191");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binary lower-bound estimate");
  }

  @Test
  public void rejectsJsonByteCountMismatch() {
    String json = serbianCaseFormPackJson().replace("\"jsonBytes\": 1909", "\"jsonBytes\": 1910");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON byte count");
  }

  @Test
  public void rejectsMissingNominativeSingularForm() {
    String json =
        serbianCaseFormPackJson().replace("\"nominative.singular\"", "\"vocative.singular\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing nominative.singular");
  }

  private String serbianCaseFormPackJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/sr-case-form-sample-pack/v0",
          "locale": "sr",
          "description": "test fixture",
          "provenance": {
            "license": "Unicode-3.0",
            "generator": "dev-docs/experiments/mf2-inflection/sr_case_pack_report.py",
            "sourceLabels": [
              "inflection/resources/org/unicode/inflection/dictionary/dictionary_sr.lst",
              "inflection/resources/org/unicode/inflection/dictionary/inflectional_sr.xml"
            ],
            "sources": [
              {
                "path": "dictionary_sr.lst",
                "byteSize": 81741,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "gitLfsPointer": false
              },
              {
                "path": "inflectional_sr.xml",
                "byteSize": 122329,
                "sha256": "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                "gitLfsPointer": false
              }
            ]
          },
          "strings": [
            "sr.case.ma\u010dka",
            "ma\u010dka",
            "ma\u010d",
            "accusative.singular",
            "ma\u010dku",
            "accusative.plural",
            "ma\u010dke",
            "dative.singular",
            "ma\u010dki",
            "nominative.singular"
          ],
          "terms": [
            {
              "id": 0,
              "text": 1,
              "partOfSpeech": "noun",
              "gender": "feminine",
              "number": "singular",
              "inflectionPattern": "42",
              "stem": 2,
              "forms": [
                {
                  "key": 3,
                  "value": 4,
                  "kind": "literal"
                },
                {
                  "key": 5,
                  "value": 6,
                  "kind": "literal"
                },
                {
                  "key": 7,
                  "value": 8,
                  "kind": "literal"
                },
                {
                  "key": 9,
                  "value": 1,
                  "kind": "literal"
                }
              ]
            }
          ],
          "summary": {
            "candidateTerms": 1,
            "exportedTerms": 1,
            "skippedTerms": {
              "not-nominative": 0
            },
            "strings": 10,
            "formRows": 4,
            "stringPoolBytes": 122,
            "jsonBytes": 1909,
            "binaryLowerBoundBytes": 190
          }
        }
        """;
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

  private int sumValues(Map<String, Integer> values) {
    return values.values().stream().mapToInt(Integer::intValue).sum();
  }
}
