package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.SerbianCasePackReportJsonLoader.SerbianCasePackReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SerbianCasePackReportJsonLoaderTest {

  private final SerbianCasePackReportJsonLoader loader = new SerbianCasePackReportJsonLoader();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new SerbianCasePackReportJsonLoader(null))
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
  public void loadsCasePackReportAndExposesSamples() {
    SerbianCasePackReport report = loader.load(serbianCasePackReportJson());

    assertThat(report.schema()).isEqualTo("mojito-mf2-inflection/sr-case-pack-report/v0");
    assertThat(report.locale()).isEqualTo("sr");
    assertThat(report.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(report.sources()).hasSize(2);
    assertThat(report.sources().get(0).sha256())
        .isEqualTo("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    assertThat(report.sources().get(0).gitLfsPointer()).isFalse();
    assertThat(report.counts().supportedEntries()).isEqualTo(2);
    assertThat(report.features().grammaticalCase()).containsEntry("nominative", 1);
    assertThat(report.features().patternSlotCases()).containsEntry("accusative", 1);
    assertThat(report.reviewPolicy().runtimeExport()).isEqualTo("closed-world-case-forms");
    assertThat(report.reviewPolicy().automaticExportTerms()).isEqualTo(1);
    assertThat(report.reviewPolicy().reviewRequiredSurfaces()).isEqualTo(1);
    assertThat(report.reviewPolicy().blockedDictionaryEntries()).isEqualTo(1);
    assertThat(report.reviewPolicy().blockedReasons()).containsEntry("not-nominative", 1);
    assertThat(report.sizeEstimates().simpleCasePackBytes()).isEqualTo(39);
    assertThat(report.samples().nominativeSingularCandidates())
        .extracting("surface")
        .containsExactly("Srbija");
    assertThat(report.samples().ambiguousSurfaces().get(0).reasons())
        .containsExactly("multiple-cases");
    assertThat(report.samples().ambiguousSurfaces().get(0).entries().get(0).cases())
        .containsExactly("nominative", "vocative");
  }

  @Test
  public void loadedReportCollectionsAreImmutable() {
    SerbianCasePackReport report = loader.load(serbianCasePackReportJson());

    assertThatThrownBy(() -> report.sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.provenance().sourceLabels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.features().grammaticalCase().put("dative", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.reviewPolicy().blockedReasons().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.samples().ambiguousSurfaces().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.samples().ambiguousSurfaces().get(0).entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.samples().nominativeSingularCandidates().get(0).cases().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void loadsPythonGeneratedFixture() {
    SerbianCasePackReport report =
        loader.load(
            readResource("com/box/l10n/mojito/mf2/inflection/sr_case_pack_report_fixture.json"));

    assertThat(report.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(report.provenance().sourceLabels()).hasSize(2);
    assertThat(report.sources()).hasSize(2);
    assertThat(report.sources().get(0).sha256())
        .isEqualTo("12d5c2e0becdf55bbdd33b14fd816d5f5b78c1f8a60bb0596914be3a34a6f282");
    assertThat(report.counts().dictionaryEntries()).isEqualTo(1140);
    assertThat(report.counts().supportedEntries()).isEqualTo(1030);
    assertThat(report.counts().missingInflectionPatterns()).isZero();
    assertThat(report.features().grammaticalCase()).containsEntry("vocative", 263);
    assertThat(report.features().gender()).containsEntry("neuter", 90);
    assertThat(report.reviewPolicy().runtimeExport()).isEqualTo("closed-world-case-forms");
    assertThat(report.reviewPolicy().automaticExportTerms()).isEqualTo(159);
    assertThat(report.reviewPolicy().reviewRequiredSurfaces()).isEqualTo(558);
    assertThat(report.reviewPolicy().blockedDictionaryEntries()).isEqualTo(981);
    assertThat(report.reviewPolicy().blockedReasons()).containsEntry("not-nominative", 755);
    assertThat(report.sizeEstimates().simpleCasePackBytes()).isEqualTo(29468);
    assertThat(report.samples().nominativeSingularCandidates())
        .extracting("surface")
        .contains("Srbija", "izuzetak");
    assertThat(report.samples().ambiguousSurfaces()).hasSize(4);
  }

  @Test
  public void rejectsUnexpectedLocale() {
    String json = serbianCasePackReportJson().replace("\"locale\": \"sr\"", "\"locale\": \"fr\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected locale sr");
  }

  @Test
  public void rejectsUnsupportedCaseValue() {
    String json = serbianCasePackReportJson().replace("\"nominative\": 1", "\"ablative\": 1");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Serbian case value");
  }

  @Test
  public void rejectsNonIntegralCount() {
    String json =
        serbianCasePackReportJson()
            .replace("\"dictionaryEntries\": 2", "\"dictionaryEntries\": 2.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: dictionaryEntries");
  }

  @Test
  public void rejectsNonIntegralFeatureCount() {
    String json =
        serbianCasePackReportJson().replaceFirst("\"nominative\": 1", "\"nominative\": 1.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: case.nominative");
  }

  @Test
  public void rejectsNonIntegralSampleLine() {
    String json = serbianCasePackReportJson().replace("\"line\": 1", "\"line\": 1.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: line");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    String json = serbianCasePackReportJson().replace("\"byteSize\": 81741", "\"byteSize\": 0.5");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsIncoherentCounts() {
    String json =
        serbianCasePackReportJson().replace("\"supportedEntries\": 2", "\"supportedEntries\": 3");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Supported entries exceed dictionary entries");
  }

  @Test
  public void rejectsIncoherentSizeEstimate() {
    String json =
        serbianCasePackReportJson()
            .replace("\"simpleCasePackBytes\": 39", "\"simpleCasePackBytes\": 40");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Simple case-pack byte estimate does not match parts");
  }

  @Test
  public void rejectsIncoherentReviewPolicy() {
    String json =
        serbianCasePackReportJson()
            .replace("\"automaticExportTerms\": 1", "\"automaticExportTerms\": 2");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("review policy must cover every dictionary entry");
  }

  @Test
  public void rejectsProvenanceSourceMismatch() {
    String json =
        serbianCasePackReportJson()
            .replace(
                """
                    "sourceLabels": [
                      "inflection/resources/org/unicode/inflection/dictionary/dictionary_sr.lst",
                      "inflection/resources/org/unicode/inflection/dictionary/inflectional_sr.xml"
                    ]
                """,
                """
                    "sourceLabels": [
                      "inflection/resources/org/unicode/inflection/dictionary/dictionary_sr.lst"
                    ]
                """);

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source label count");
  }

  private String serbianCasePackReportJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/sr-case-pack-report/v0",
          "locale": "sr",
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
          ],
          "provenance": {
            "license": "Unicode-3.0",
            "generator": "dev-docs/experiments/mf2-inflection/sr_case_pack_report.py",
            "sourceLabels": [
              "inflection/resources/org/unicode/inflection/dictionary/dictionary_sr.lst",
              "inflection/resources/org/unicode/inflection/dictionary/inflectional_sr.xml"
            ]
          },
          "counts": {
            "dictionaryEntries": 2,
            "supportedEntries": 2,
            "uniqueSupportedSurfaces": 2,
            "skippedLines": 0,
            "ambiguousSupportedSurfaces": 1,
            "nominativeSingularCandidates": 1,
            "dictionaryInflectionPatterns": 1,
            "missingInflectionPatterns": 0,
            "inflectionalPatterns": 1,
            "nounInflectionalPatterns": 1,
            "usedInflectionalPatterns": 1
          },
          "features": {
            "case": {
              "nominative": 1,
              "vocative": 1
            },
            "gender": {
              "feminine": 1,
              "masculine": 1
            },
            "number": {
              "plural": 1,
              "singular": 1
            },
            "animacy": {
              "<missing>": 1,
              "inanimate": 1
            },
            "partOfSpeech": {
              "noun": 2
            },
            "patternSlotCases": {
              "accusative": 1,
              "nominative": 1
            },
            "patternSlotGenders": {
              "feminine": 1,
              "masculine": 1
            },
            "patternSlotNumbers": {
              "plural": 1,
              "singular": 1
            },
            "ambiguityReasons": {
              "multiple-cases": 1
            }
          },
          "reviewPolicy": {
            "runtimeExport": "closed-world-case-forms",
            "automaticExportTerms": 1,
            "reviewRequiredSurfaces": 1,
            "blockedDictionaryEntries": 1,
            "reviewRequiredReasons": {
              "multiple-cases": 1
            },
            "blockedReasons": {
              "not-nominative": 1
            }
          },
          "sizeEstimates": {
            "surfaceStringPoolBytes": 10,
            "patternTemplateStringPoolBytes": 5,
            "exactSurfaceRowBytes": 20,
            "patternSlotRows": 1,
            "patternSlotRowBytes": 4,
            "simpleCasePackBytes": 39
          },
          "samples": {
            "nominativeSingularCandidates": [
              {
                "surface": "Srbija",
                "line": 1,
                "partOfSpeech": ["noun"],
                "case": ["nominative"],
                "gender": ["feminine"],
                "number": ["singular"],
                "animacy": [],
                "inflections": ["1f"]
              }
            ],
            "ambiguousSurfaces": [
              {
                "surface": "izuzeci",
                "reasons": ["multiple-cases"],
                "entries": [
                  {
                    "surface": "izuzeci",
                    "line": 7,
                    "partOfSpeech": ["noun"],
                    "case": ["nominative", "vocative"],
                    "gender": ["masculine"],
                    "number": ["plural"],
                    "animacy": ["inanimate"],
                    "inflections": ["35"]
                  }
                ]
              }
            ],
            "missingInflectionPatterns": []
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
}
