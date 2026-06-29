package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.FrenchNounMetadataAnalyzer.AnalysisSource;
import com.box.l10n.mojito.mf2.inflection.FrenchNounMetadataAnalyzer.AnalysisStatus;
import com.box.l10n.mojito.mf2.inflection.FrenchNounMetadataPackJsonLoader.FrenchNounMetadataPack;
import org.junit.Test;

public class FrenchNounMetadataAnalyzerTest {

  private final FrenchNounMetadataPack pack =
      new FrenchNounMetadataPackJsonLoader().load(frenchNounMetadataPackJson());
  private final FrenchNounMetadataAnalyzer analyzer = new FrenchNounMetadataAnalyzer(pack);

  @Test
  public void resolvesExactDictionaryRowsAfterNormalization() {
    FrenchNounMetadataAnalyzer.Analysis analysis = analyzer.analyze("  \u00c9COLE  ");

    assertThat(analysis.status()).isEqualTo(AnalysisStatus.EXACT);
    assertThat(analysis.source()).isEqualTo(AnalysisSource.DICTIONARY);
    assertThat(analysis.normalizedSurface()).isEqualTo("\u00e9cole");
    assertThat(analysis.matchedRowOptional()).isPresent();
    assertThat(analysis.morphology()).isPresent();
    assertThat(analysis.morphology().orElseThrow().gender()).isEqualTo("feminine");
    assertThat(analysis.gender()).contains("feminine");
    assertThat(analysis.confidence()).isEmpty();
    assertThat(analysis.morphology().orElseThrow().startsWithVowelSound()).isTrue();
  }

  @Test
  public void normalizesSmartApostrophesLikeDictionaryParser() {
    FrenchNounMetadataAnalyzer.Analysis analysis = analyzer.analyze("Presqu\u2019ile");

    assertThat(analysis.status()).isEqualTo(AnalysisStatus.EXACT);
    assertThat(analysis.normalizedSurface()).isEqualTo("presqu'ile");
    assertThat(analysis.morphology().orElseThrow().gender()).isEqualTo("feminine");
  }

  @Test
  public void returnsUnknownForMissingOrBlankSurfaces() {
    FrenchNounMetadataAnalyzer.Analysis missing = analyzer.analyze("chaise");
    FrenchNounMetadataAnalyzer.Analysis blank = analyzer.analyze("  ");

    assertThat(missing.status()).isEqualTo(AnalysisStatus.UNKNOWN);
    assertThat(missing.source()).isEqualTo(AnalysisSource.NONE);
    assertThat(missing.matchedRowOptional()).isEmpty();
    assertThat(missing.morphology()).isEmpty();
    assertThat(missing.gender()).isEmpty();
    assertThat(blank.status()).isEqualTo(AnalysisStatus.UNKNOWN);
    assertThat(blank.normalizedSurface()).isEmpty();
  }

  @Test
  public void returnsAmbiguousForDictionaryAmbiguitiesBeforeHeuristics() {
    FrenchNounMetadataAnalyzer analyzerWithFallback =
        new FrenchNounMetadataAnalyzer(
            pack,
            new FrenchGenderSuffixRulePackJsonLoader().load(frenchGenderSuffixRulePackJson()));

    FrenchNounMetadataAnalyzer.Analysis analysis = analyzerWithFallback.analyze("Livre");

    assertThat(analysis.status()).isEqualTo(AnalysisStatus.AMBIGUOUS);
    assertThat(analysis.source()).isEqualTo(AnalysisSource.DICTIONARY);
    assertThat(analysis.ambiguityRowOptional()).isPresent();
    assertThat(analysis.ambiguityRowOptional().orElseThrow().analyses())
        .extracting("gender")
        .containsExactly("feminine", "masculine");
    assertThat(analysis.gender()).isEmpty();
    assertThat(analysis.confidence()).isEmpty();
  }

  @Test
  public void usesSuffixRuleFallbackForDictionaryMisses() {
    FrenchNounMetadataAnalyzer analyzerWithFallback =
        new FrenchNounMetadataAnalyzer(
            pack,
            new FrenchGenderSuffixRulePackJsonLoader().load(frenchGenderSuffixRulePackJson()));

    FrenchNounMetadataAnalyzer.Analysis analysis = analyzerWithFallback.analyze("Modernisation");

    assertThat(analysis.status()).isEqualTo(AnalysisStatus.HEURISTIC);
    assertThat(analysis.source()).isEqualTo(AnalysisSource.SUFFIX_RULE);
    assertThat(analysis.normalizedSurface()).isEqualTo("modernisation");
    assertThat(analysis.matchedRowOptional()).isEmpty();
    assertThat(analysis.morphology()).isEmpty();
    assertThat(analysis.gender()).contains("feminine");
    assertThat(analysis.confidence()).contains(1.0);
  }

  @Test
  public void dictionaryMetadataWinsOverSuffixRuleFallback() {
    FrenchNounMetadataAnalyzer analyzerWithFallback =
        new FrenchNounMetadataAnalyzer(
            pack,
            new FrenchGenderSuffixRulePackJsonLoader().load(frenchGenderSuffixRulePackJson()));

    FrenchNounMetadataAnalyzer.Analysis analysis = analyzerWithFallback.analyze("abaca");

    assertThat(analysis.status()).isEqualTo(AnalysisStatus.EXACT);
    assertThat(analysis.source()).isEqualTo(AnalysisSource.DICTIONARY);
    assertThat(analysis.gender()).contains("masculine");
    assertThat(analysis.confidence()).isEmpty();
  }

  @Test
  public void rejectsNullSurface() {
    assertThatThrownBy(() -> analyzer.analyze(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("surface");
  }

  private String frenchNounMetadataPackJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0",
          "locale": "fr",
          "strings": ["abaca", "\u00e9cole", "presqu'ile", "livre"],
          "rows": [
            {
              "surface": 0,
              "featureBits": 5,
              "gender": "masculine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": "29"
            },
            {
              "surface": 1,
              "featureBits": 22,
              "gender": "feminine",
              "number": "singular",
              "elides": true,
              "inflectionPattern": "b1"
            },
            {
              "surface": 2,
              "featureBits": 6,
              "gender": "feminine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": null
            }
          ],
          "ambiguousRows": [
            {
              "surface": 3,
              "reasons": ["multiple-genders"],
              "analyses": [
                {
                  "gender": "feminine",
                  "number": "singular",
                  "elides": false,
                  "inflectionPattern": null
                },
                {
                  "gender": "masculine",
                  "number": "singular",
                  "elides": false,
                  "inflectionPattern": null
                }
              ]
            }
          ],
          "summary": {
            "rows": 3,
            "ambiguousRows": 1,
            "strings": 4,
            "stringPoolBytes": 34,
            "jsonBytes": 650,
            "binaryLowerBoundBytes": 58
          }
        }
        """;
  }

  private String frenchGenderSuffixRulePackJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/fr-gender-suffix-rule-pack/v0",
          "locale": "fr",
          "rules": [
            {
              "suffix": "isation",
              "gender": "feminine",
              "confidence": 1.0,
              "support": 86
            },
            {
              "suffix": "aca",
              "gender": "feminine",
              "confidence": 0.9,
              "support": 20
            },
            {
              "suffix": "ivre",
              "gender": "masculine",
              "confidence": 0.95,
              "support": 42
            }
          ],
          "summary": {
            "trainingSurfaces": 16590,
            "rules": 370,
            "exportedRules": 3,
            "suffixOnlyAccuracy": 0.7907,
            "suffixRuleBytes": 3117,
            "exportedSuffixRuleBytes": 20,
            "maxSuffixLen": 8,
            "minSupport": 20,
            "minConfidence": 0.88
          }
        }
        """;
  }
}
